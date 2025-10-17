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
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.connection.Payload
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.LogFileWriter
import info.benjaminhill.localmesh.MainActivity
import info.benjaminhill.localmesh.R
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.AssetManager
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
 * - Manages the lifecycle of the `NearbyConnectionsManager`, the `LocalHttpServer`, and the `ServiceHardener`.
 * - Acts as a bridge, forwarding messages between the network layer (`NearbyConnectionsManager`) and
 *   the application logic layer (`LocalHttpServer`).
 * - Handles incoming `BridgeAction` intents from the UI to start and stop the service.
 *
 * ## What it doesn't do
 * - It does not contain the application's core API logic; that resides in `LocalHttpServer`.
 * - It does not directly manage peer connections; that is delegated to `NearbyConnectionsManager`.
 * - It does not contain the hardening logic; that is delegated to `ServiceHardener`.
 *
 * ## Comparison to other classes
 * - **[LocalHttpServer]:** `BridgeService` manages the lifecycle of the server, but the server itself
 *   contains all the Ktor routing and application logic.
 * - **[NearbyConnectionsManager]:** `BridgeService` owns and directs the `NearbyConnectionsManager`,
 *   telling it when to start and stop and receiving payloads from it.
 * - **[ServiceHardener]:** `BridgeService` owns and directs the `ServiceHardener`, which manages
 *  the service's lifecycle and restarts it if it becomes unhealthy.
 */
class BridgeService : Service() {
    var currentState: BridgeState = BridgeState.Idle
        internal set

    internal lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    internal lateinit var localHttpServer: LocalHttpServer
    internal lateinit var serviceHardener: ServiceHardener
    private lateinit var logger: AppLogger

    lateinit var endpointName: String
        internal set

    @Volatile
    var serviceStartTime = 0L

    internal val incomingFilePayloads = ConcurrentHashMap<Long, String>()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BridgeService.onCreate() called")

        logger = AppLogger(TAG, LogFileWriter(applicationContext))

        runCatchingWithLogging(logger::e) {
            endpointName =
                (('A'..'Z') + ('a'..'z') + ('0'..'9')).shuffled().take(5).joinToString("").also {
                    logger.log("This node is now named: $it")
                }

            if (!::serviceHardener.isInitialized) {
                serviceHardener = ServiceHardener(this, logger)
            }
            if (!::localHttpServer.isInitialized) {
                localHttpServer = LocalHttpServer(this, logger)
            }
            if (!::nearbyConnectionsManager.isInitialized) {
                nearbyConnectionsManager = NearbyConnectionsManager(
                    context = this,
                    endpointName = endpointName,
                    logger = logger
                ) { _, payload ->
                    when (payload.type) {
                        Payload.Type.BYTES -> handleBytesPayload(payload)
                        Payload.Type.STREAM -> handleStreamPayload(payload)
                        else -> logger.e("Received unsupported payload type: ${payload.type}")
                    }
                }
            }
            logger.log("onCreate() finished successfully")
        } ?: run {
            logger.e("FATAL: Service crashed on create.")
            currentState = BridgeState.Error("onCreate failed")
        }
    }

    internal fun handleBytesPayload(payload: Payload) {
        serviceHardener.updateP2pMessageTime()
        runCatchingWithLogging(logger::e) {
            val jsonString = payload.asBytes()!!.toString(Charsets.UTF_8)
            val wrapper = HttpRequestWrapper.fromJson(jsonString)

            if (wrapper.path == "/send-file") {
                val params = wrapper.queryParams.parseUrlEncodedParameters()
                val filename = params["filename"]
                val payloadId = params["payloadId"]?.toLongOrNull()
                if (filename != null && payloadId != null) {
                    incomingFilePayloads[payloadId] = filename
                    logger.log("Expecting file '$filename' for payload $payloadId")
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
            logger.e("Received stream payload with unknown ID: ${payload.id}")
            return
        }
        runCatchingWithLogging(logger::e) {
            payload.asStream()?.asInputStream()?.use { inputStream ->
                AssetManager.saveFile(applicationContext, filename, inputStream)
            }
            logger.log("File received and saved: $filename")
        }
    }

    inner class BridgeBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    private val binder = BridgeBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.log("onStartCommand() called with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            else -> logger.log("onStartCommand: Unknown action: ${intent?.action}")
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
        logger.log("BridgeService.start()")
        if (currentState !is BridgeState.Idle) {
            logger.log("Service is not idle, cannot start. Current state: ${currentState::class.java.simpleName}")
            return
        }
        currentState = BridgeState.Starting
        serviceStartTime = System.currentTimeMillis()

        if (!localHttpServer.start()) {
            logger.e("FATAL: LocalHttpServer failed to start.")
            currentState = BridgeState.Error("HTTP server failed to start.")
            stopSelf()
            return
        }

        if (!checkHardwareAndPermissions()) {
            currentState = BridgeState.Error("Hardware or permissions not met.")
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
        serviceHardener.start()
        currentState = BridgeState.Running
        logger.log("Service started and running.")
    }

    private fun checkHardwareAndPermissions(): Boolean {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter == null || !bluetoothManager.adapter.isEnabled) {
            logger.e("Bluetooth is not enabled")
            return false
        }

        val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            logger.e("Wi-Fi is not enabled")
            return false
        }

        val requiredPermissions = PermissionUtils.getDangerousPermissions(this)
        for (permission in requiredPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                logger.e("Permission not granted: $permission")
                return false
            }
        }
        return true
    }

    private fun stop() {
        logger.log("BridgeService.stop()")
        currentState = BridgeState.Stopping

        serviceHardener.stop()
        nearbyConnectionsManager.stop()
        localHttpServer.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        currentState = BridgeState.Idle
        logger.log("Service stopped.")
    }

    fun restart() {
        logger.log("Restarting BridgeService...")
        stop()
        start()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "P2P Bridge Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    companion object {
        const val ACTION_START = "info.benjaminhill.localmesh.action.START"
        const val ACTION_STOP = "info.benjaminhill.localmesh.action.STOP"
        private const val CHANNEL_ID = "P2PBridgeServiceChannel"
        private const val TAG = "BridgeService"
    }
}