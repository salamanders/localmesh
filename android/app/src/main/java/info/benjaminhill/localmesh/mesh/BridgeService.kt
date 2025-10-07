package info.benjaminhill.localmesh.mesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.connection.Payload
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.LogFileWriter
import info.benjaminhill.localmesh.MainActivity
import info.benjaminhill.localmesh.R
import info.benjaminhill.localmesh.ui.AppStateHolder
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The central Android [Service] for the LocalMesh application, acting as the "P2P Web Bridge" middleware.
 *
 * This foreground service orchestrates the entire peer-to-peer communication and local HTTP server
 * functionality. It runs in the background, ensuring continuous operation even when the app's UI
 * is not active.
 *
 * ## What it does
 * - **Lifecycle Management:** Starts and stops the [NearbyConnectionsManager] and [LocalHttpServer].
 * - **State Management:** Manages the overall state of the service using [ServiceState] and updates
 *   the UI via [AppStateHolder].
 * - **P2P Communication:** Receives incoming [com.google.android.gms.nearby.connection.Payload]s from [NearbyConnectionsManager], deserializes
 *   them into [P2PMessage] objects, and dispatches them to its internal [CommandRouter]. It also provides
 *   methods to send messages and files to other peers.
 * - **Local HTTP Server Integration:** Provides the [LocalHttpServer] with the necessary context
 *   and methods to interact with the P2P network (e.g., sending messages from the web UI).
 * - **UI Interaction:** Responds to [P2PBridgeAction]s sent from the [info.benjaminhill.localmesh.MainActivity]
 *   to control its behavior.
 * - **System Integration:** Handles foreground service notifications, acquires wake locks to prevent
 *   the OS from killing the service, and performs hardware/permission checks.
 * - **Logging:** Uses [LogFileWriter] to persist logs and updates [AppStateHolder] for UI display.
 *
 * ## What it doesn't do
 * - It does not directly implement the UI; that's handled by [info.benjaminhill.localmesh.MainActivity]
 *   and its Compose UI.
 * - It does not directly manage the Nearby Connections API; that's delegated to [NearbyConnectionsManager].
 * - It does not directly serve web assets or handle HTTP requests; that's delegated to [LocalHttpServer].
 * - It does not interpret the meaning of commands from other peers; that's delegated to its [CommandRouter].
 *
 * ## Comparison to other classes
 * - **[NearbyConnectionsManager]:** The service *owns and uses* the manager to handle the low-level P2P
 *   networking. The manager is a component of the service.
 * - **[LocalHttpServer]:** The service *owns and uses* the HTTP server to expose functionality to the local
 *   web browser. The server is a component of the service.
 * - **[CommandRouter]:** The service *owns and uses* the router to dispatch incoming commands.
 * - **[P2PBridgeAction]:** These are the *inputs* (commands) from the local UI that the service processes.
 * - **[AppStateHolder]:** The service *writes* its state and logs to the `AppStateHolder`, which
 *   the UI then *reads*.
 * - **[ServiceState]:** This sealed class defines the possible states that `P2PBridgeService` can be in.
 */
class P2PBridgeService : Service() {

    private lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var localHttpServer: LocalHttpServer
    private lateinit var commandRouter: CommandRouter
    private lateinit var endpointName: String
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var logFileWriter: LogFileWriter

    private val receivedP2PMessages = ConcurrentLinkedQueue<P2PMessage>()
    internal val incomingFilePayloads = ConcurrentHashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() called")
        try {
            endpointName =
                (('A'..'Z') + ('a'..'z') + ('0'..'9')).shuffled().take(4).joinToString("")
            logFileWriter = LogFileWriter(applicationContext)
            commandRouter = CommandRouter()
            nearbyConnectionsManager = NearbyConnectionsManager(
                context = this,
                endpointName = endpointName,
                peerCountUpdateCallback = {
                    AppStateHolder.statusText.value = "Running - $it Peers"
                },
                logMessageCallback = ::sendLogMessage
            ) { _, payload ->
                try {
                    when (payload.type) {
                        Payload.Type.BYTES -> handleBytesPayload(payload)
                        Payload.Type.STREAM -> handleStreamPayload(payload)
                        else -> sendLogMessage("Received unsupported payload type: ${payload.type}")
                    }
                } catch (e: Exception) {
                    sendLogMessage("Failed to process payload: ${e.message}")
                }
            }
            localHttpServer = LocalHttpServer(this, ::sendLogMessage)
            Log.i(TAG, "onCreate() finished successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: CRASHED: ${e.message}", e)
            sendLogMessage("FATAL: Service crashed on create: ${e.message}")
            AppStateHolder.currentState.value = ServiceState.Error("onCreate failed")
        }
    }

    private fun handleBytesPayload(payload: Payload) {
        val p2PMessage = Json.decodeFromString<P2PMessage>(
            payload.asBytes()!!.toString(Charsets.UTF_8)
        )
        if (receivedP2PMessages.size >= MAX_QUEUE_SIZE) {
            receivedP2PMessages.poll()
        }
        receivedP2PMessages.add(p2PMessage)
        sendLogMessage("Received: $p2PMessage")
        commandRouter.route(p2PMessage, this)
    }

    private fun handleStreamPayload(payload: Payload) {
        val filename = incomingFilePayloads.remove(payload.id)
        if (filename != null) {
            try {
                val cacheDir = File(applicationContext.cacheDir, "web_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val file = File(cacheDir, filename)
                payload.asStream()?.asInputStream()?.use { inputStream ->
                    file.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                sendLogMessage("File received and cached: $filename")
            } catch (e: Exception) {
                sendLogMessage("Error receiving file '$filename': ${e.message}")
            }
        } else {
            sendLogMessage("Received stream payload with unknown ID: ${payload.id}")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand() called with action: ${intent?.action}")
        when (intent?.action) {
            P2PBridgeAction.Start::class.java.name -> start()
            P2PBridgeAction.Stop::class.java.name -> stop()
            P2PBridgeAction.BroadcastCommand::class.java.name -> {
                val command = intent.getStringExtra(EXTRA_COMMAND)
                val payload = intent.getStringExtra(EXTRA_PAYLOAD)
                if (command != null && payload != null) {
                    sendMessage(command = command, payload = payload)
                    sendLogMessage("Broadcasting command: '$command' with payload: '$payload'")
                } else {
                    sendLogMessage("BroadcastCommand action received with null command or payload")
                }
            }

            else -> Log.w(TAG, "onStartCommand: Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    fun getReceivedMessages(): List<P2PMessage> {
        val messages = receivedP2PMessages.toList()
        receivedP2PMessages.clear()
        return messages
    }

    fun sendMessage(command: String, metadata: String = "", payload: String = "") {
        val p2PMessageObject = P2PMessage(
            from = endpointName,
            sequence = System.currentTimeMillis(),
            timestamp = System.currentTimeMillis(),
            command = command,
            metadata = metadata,
            payload = payload
        )
        val payloadBytes =
            Json.encodeToString(P2PMessage.serializer(), p2PMessageObject).toByteArray(Charsets.UTF_8)
        nearbyConnectionsManager.broadcastBytes(payloadBytes)
    }

    fun sendFile(file: File) {
        val streamPayload = Payload.fromStream(file.inputStream())
        sendMessage(
            command = "file_stream",
            metadata = "${file.name} ${streamPayload.id}"
        )
        nearbyConnectionsManager.sendPayload(
            nearbyConnectionsManager.getConnectedPeerIds(),
            streamPayload
        )
    }

    fun getConnectedPeerCount(): Int = nearbyConnectionsManager.getConnectedPeerCount()

    fun getConnectedPeerIds(): List<String> = nearbyConnectionsManager.getConnectedPeerIds()

    fun getEndpointName(): String = endpointName

    private fun start() {
        sendLogMessage("P2PBridgeService.start()")
        if (AppStateHolder.currentState.value !is ServiceState.Idle) {
            sendLogMessage("Service is not idle, cannot start. Current state: ${AppStateHolder.currentState.value::class.java.simpleName}")
            return
        }
        AppStateHolder.currentState.value = ServiceState.Starting
        AppStateHolder.statusText.value = "Starting..."

        if (!checkHardwareAndPermissions()) {
            AppStateHolder.currentState.value =
                ServiceState.Error("Hardware or permissions not met.")
            AppStateHolder.statusText.value = "Error: Check permissions"
            stopSelf()
            return
        }

        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Web Bridge")
            .setContentText("Running...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        nearbyConnectionsManager.start()
        if (localHttpServer.start()) {
            AppStateHolder.serverUrl.value = "http://localhost:${LocalHttpServer.PORT}/test"
        } else {
            sendLogMessage("FATAL: LocalHttpServer failed to start.")
            AppStateHolder.currentState.value = ServiceState.Error("HTTP server failed to start.")
            AppStateHolder.statusText.value = "Error: HTTP server failed"
            stopSelf()
            return
        }
        acquireWakeLock()
        AppStateHolder.currentState.value = ServiceState.Running
        AppStateHolder.statusText.value = "Running - 0 Peers"
        sendLogMessage("Service started and running.")
    }

    private fun checkHardwareAndPermissions(): Boolean {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter == null || !bluetoothManager.adapter.isEnabled) {
            sendLogMessage("Bluetooth is not enabled")
            return false
        }

        val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            sendLogMessage("Wi-Fi is not enabled")
            return false
        }

        val requiredPermissions = arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        return requiredPermissions.all { permission ->
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                true
            } else {
                sendLogMessage("Permission not granted: $permission")
                false
            }
        }
    }

    private fun stop() {
        sendLogMessage("P2PBridgeService.stop()")
        AppStateHolder.currentState.value = ServiceState.Stopping
        AppStateHolder.statusText.value = "Stopping..."
        AppStateHolder.serverUrl.value = null

        nearbyConnectionsManager.stop()
        localHttpServer.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        AppStateHolder.currentState.value = ServiceState.Idle
        AppStateHolder.statusText.value = "Inactive"
        sendLogMessage("Service stopped.")
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalMesh::WakeLock").apply {
                acquire(4 * 60 * 60 * 1000L /*4 hours*/)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "P2P Bridge Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    fun sendLogMessage(message: String) {
        Log.d(TAG, message)
        logFileWriter.writeLog(message)
        AppStateHolder.addLog(message)
    }

    companion object {
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_PAYLOAD = "extra_payload"
        private const val CHANNEL_ID = "P2PBridgeServiceChannel"
        private const val TAG = "P2PBridgeService"
        private const val MAX_QUEUE_SIZE = 1_000
    }
}