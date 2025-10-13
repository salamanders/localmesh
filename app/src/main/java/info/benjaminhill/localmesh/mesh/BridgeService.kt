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
import info.benjaminhill.localmesh.util.GlobalExceptionHandler.runCatchingWithLogging
import info.benjaminhill.localmesh.util.PermissionUtils
import io.ktor.http.parseUrlEncodedParameters
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
    var currentState: BridgeState = BridgeState.Idle
        internal set

    internal lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    internal lateinit var localHttpServer: LocalHttpServer
    lateinit var endpointName: String
        internal set

    private var wakeLock: PowerManager.WakeLock? = null
    internal lateinit var logFileWriter: LogFileWriter

    internal val incomingFilePayloads = ConcurrentHashMap<Long, String>()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() called")
        runCatchingWithLogging(::logError) {
            endpointName =
                (('A'..'Z') + ('a'..'z') + ('0'..'9')).shuffled().take(5).joinToString("")
            logFileWriter = LogFileWriter(applicationContext)
            if (!::localHttpServer.isInitialized) {
                localHttpServer = LocalHttpServer(this, ::sendLogMessage, ::logError)
            }
            if (!::nearbyConnectionsManager.isInitialized) {
                nearbyConnectionsManager = NearbyConnectionsManager(
                    context = this,
                    endpointName = endpointName,
                    peerCountUpdateCallback = { /* No-op, web UI will poll /status */ },
                    logMessageCallback = ::sendLogMessage,
                    logErrorCallback = ::logError
                ) { _, payload ->
                    when (payload.type) {
                        Payload.Type.BYTES -> handleBytesPayload(payload)
                        Payload.Type.STREAM -> handleStreamPayload(payload)
                        else -> sendLogMessage("Received unsupported payload type: ${payload.type}")
                    }
                }
            }
            Log.i(TAG, "onCreate() finished successfully")
        } ?: run {
            sendLogMessage("FATAL: Service crashed on create.")
            currentState = BridgeState.Error("onCreate failed")
        }
    }

    internal fun handleBytesPayload(payload: Payload) {
        runCatchingWithLogging(::logError) {
            val jsonString = payload.asBytes()!!.toString(Charsets.UTF_8)
            val wrapper = HttpRequestWrapper.fromJson(jsonString)

            // If this is a file broadcast, store the mapping of payloadId to filename
            if (wrapper.path == "/send-file") {
                val params = wrapper.queryParams.parseUrlEncodedParameters()
                val filename = params["filename"]
                val payloadId = params["payloadId"]?.toLongOrNull()
                if (filename != null && payloadId != null) {
                    incomingFilePayloads[payloadId] = filename
                    sendLogMessage("Expecting file '$filename' for payload $payloadId")
                }
            }

            CoroutineScope(ioDispatcher).launch {
                localHttpServer.dispatchRequest(wrapper)
            }
        }
    }

    internal fun handleStreamPayload(payload: Payload) {
        val filename = incomingFilePayloads.remove(payload.id)
        if (filename == null) {
            sendLogMessage("Received stream payload with unknown ID: ${payload.id}")
            return
        }
        runCatchingWithLogging(::logError) {
            val cacheDir = File(applicationContext.cacheDir, "web_cache").also { it.mkdirs() }
            val file = File(cacheDir, filename)
            file.parentFile?.mkdirs()
            payload.asStream()?.asInputStream()?.use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            sendLogMessage("File received and cached: $filename")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand() called with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            else -> Log.w(TAG, "onStartCommand: Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    fun broadcast(jsonString: String) {
        nearbyConnectionsManager.broadcastBytes(jsonString.toByteArray(Charsets.UTF_8))
    }

    fun sendFile(file: File, destinationPath: String) {
        val streamPayload = Payload.fromStream(file.inputStream())
        val wrapper = HttpRequestWrapper(
            method = "POST",
            path = "/send-file",
            queryParams = "filename=$destinationPath&payloadId=${streamPayload.id}",
            body = "",
            sourceNodeId = endpointName
        )
        broadcast(wrapper.toJson())
        nearbyConnectionsManager.sendPayload(
            nearbyConnectionsManager.connectedPeerIds,
            streamPayload
        )
    }

    private fun start() {
        sendLogMessage("BridgeService.start()")
        if (currentState !is BridgeState.Idle) {
            sendLogMessage("Service is not idle, cannot start. Current state: ${currentState::class.java.simpleName}")
            return
        }
        currentState = BridgeState.Starting

        // Start the local HTTP server immediately.
        if (!localHttpServer.start()) {
            sendLogMessage("FATAL: LocalHttpServer failed to start.")
            currentState = BridgeState.Error("HTTP server failed to start.")
            stopSelf()
            return
        }

        if (!checkHardwareAndPermissions()) {
            currentState =
                BridgeState.Error("Hardware or permissions not met.")
            // Do not stop the entire service, the web UI might be useful.
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
        acquireWakeLock()
        currentState = BridgeState.Running
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

        val requiredPermissions = PermissionUtils.getDangerousPermissions(this)
        for (permission in requiredPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                sendLogMessage("Permission not granted: $permission")
                return false
            }
        }
        return true
    }

    private fun stop() {
        sendLogMessage("BridgeService.stop()")
        currentState = BridgeState.Stopping

        nearbyConnectionsManager.stop()
        localHttpServer.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        currentState = BridgeState.Idle
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
    }

    private fun logError(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
            logFileWriter.writeLog("ERROR: $message, ${throwable.message}")
        } else {
            Log.e(TAG, message)
            logFileWriter.writeLog("ERROR: $message")
        }
    }

    companion object {
        const val ACTION_START = "info.benjaminhill.localmesh.action.START"
        const val ACTION_STOP = "info.benjaminhill.localmesh.action.STOP"
        private const val CHANNEL_ID = "P2PBridgeServiceChannel"
        private const val TAG = "BridgeService"
    }
}