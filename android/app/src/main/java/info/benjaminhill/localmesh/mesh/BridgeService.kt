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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The central foreground service that orchestrates the entire P2P bridge.
 *
 * ## What it does
 * - Manages the lifecycle of the `NearbyConnectionsManager` and the `LocalHttpServer`.
 * - Acts as a bridge, forwarding messages between the network layer (`NearbyConnectionsManager`) and
 *   the application logic layer (`LocalHttpServer`).
 * - Handles incoming `BridgeAction` intents from the UI to start and stop the service.
 * - Manages the foreground service notification and a wake lock to ensure the app stays active.
 *
 * ## What it doesn't do
 * - It does not contain the application's core API logic; that resides in `LocalHttpServer`.
 * - It does not directly manage peer connections; that is delegated to `NearbyConnectionsManager`.
 *
 * ## Comparison to other classes
 * - **[LocalHttpServer]:** `BridgeService` manages the lifecycle of the server, but the server itself
 *   contains all the Ktor routing and application logic.
 * - **[NearbyConnectionsManager]:** `BridgeService` owns and directs the `NearbyConnectionsManager`,
 *   telling it when to start and stop and receiving payloads from it.
 */
class BridgeService : Service() {

    internal lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    internal lateinit var localHttpServer: LocalHttpServer
    private lateinit var endpointName: String
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var logFileWriter: LogFileWriter

    internal val incomingFilePayloads = ConcurrentHashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() called")
        try {
            endpointName =
                (('A'..'Z') + ('a'..'z') + ('0'..'9')).shuffled().take(4).joinToString("")
            logFileWriter = LogFileWriter(applicationContext)
            if (!::localHttpServer.isInitialized) {
                localHttpServer = LocalHttpServer(this, ::sendLogMessage)
            }
            if (!::nearbyConnectionsManager.isInitialized) {
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
            }
            Log.i(TAG, "onCreate() finished successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: CRASHED: ${e.message}", e)
            sendLogMessage("FATAL: Service crashed on create: ${e.message}")
            AppStateHolder.currentState.value = BridgeState.Error("onCreate failed")
        }
    }

    internal fun handleBytesPayload(payload: Payload) {
        val jsonString = payload.asBytes()!!.toString(Charsets.UTF_8)
        val wrapper = HttpRequestWrapper.fromJson(jsonString)
        CoroutineScope(Dispatchers.IO).launch {
            localHttpServer.dispatchRequest(wrapper)
        }
    }

    internal fun handleStreamPayload(payload: Payload) {
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
            BridgeAction.Start::class.java.name -> start()
            BridgeAction.Stop::class.java.name -> stop()
            else -> Log.w(TAG, "onStartCommand: Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    fun broadcast(jsonString: String) {
        nearbyConnectionsManager.broadcastBytes(jsonString.toByteArray(Charsets.UTF_8))
    }

    fun sendFile(file: File) {
        val streamPayload = Payload.fromStream(file.inputStream())
        val wrapper = HttpRequestWrapper(
            method = "POST",
            path = "/file-received",
            params = "filename=${file.name}&payloadId=${streamPayload.id}",
            sourceNodeId = endpointName
        )
        broadcast(wrapper.toJson())
        nearbyConnectionsManager.sendPayload(
            nearbyConnectionsManager.getConnectedPeerIds(),
            streamPayload
        )
    }

    fun getConnectedPeerCount(): Int = nearbyConnectionsManager.getConnectedPeerCount()

    fun getConnectedPeerIds(): List<String> = nearbyConnectionsManager.getConnectedPeerIds()

    fun getEndpointName(): String = endpointName

    private fun start() {
        sendLogMessage("BridgeService.start()")
        if (AppStateHolder.currentState.value !is BridgeState.Idle) {
            sendLogMessage("Service is not idle, cannot start. Current state: ${AppStateHolder.currentState.value::class.java.simpleName}")
            return
        }
        AppStateHolder.currentState.value = BridgeState.Starting
        AppStateHolder.statusText.value = "Starting..."

        if (!checkHardwareAndPermissions()) {
            AppStateHolder.currentState.value =
                BridgeState.Error("Hardware or permissions not met.")
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
            AppStateHolder.serverUrl.value = "http://localhost:${LocalHttpServer.PORT}"
        } else {
            sendLogMessage("FATAL: LocalHttpServer failed to start.")
            AppStateHolder.currentState.value = BridgeState.Error("HTTP server failed to start.")
            AppStateHolder.statusText.value = "Error: HTTP server failed"
            stopSelf()
            return
        }
        acquireWakeLock()
        AppStateHolder.currentState.value = BridgeState.Running
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
        sendLogMessage("BridgeService.stop()")
        AppStateHolder.currentState.value = BridgeState.Stopping
        AppStateHolder.statusText.value = "Stopping..."
        AppStateHolder.serverUrl.value = null

        nearbyConnectionsManager.stop()
        localHttpServer.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        AppStateHolder.currentState.value = BridgeState.Idle
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
        private const val TAG = "BridgeService"
    }
}