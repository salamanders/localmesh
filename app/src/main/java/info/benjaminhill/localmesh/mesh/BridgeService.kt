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
import info.benjaminhill.localmesh.MainActivity
import info.benjaminhill.localmesh.R
import info.benjaminhill.localmesh.logic.HttpRequestWrapper
import info.benjaminhill.localmesh.logic.NetworkMessage
import info.benjaminhill.localmesh.logic.TopologyOptimizer
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.AssetManager
import info.benjaminhill.localmesh.util.LogFileWriter
import info.benjaminhill.localmesh.util.PermissionUtils
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the entire application, acting as the central hub for all components.
 *
 * This class is a foreground service, meaning it will keep the application alive and running
 * even when the UI is not visible. It is responsible for initializing and managing the lifecycle
 * of all major components, including the `TopologyOptimizer`, `NearbyConnectionsManager`,
 * `LocalHttpServer`, and `ServiceHardener`.
 *
 * It has two primary responsibilities for data handling:
 * 1.  It launches a coroutine to collect the `incomingPayloads` flow from the `ConnectionManager`.
 *     For each payload, it deserializes the `NetworkMessage` and the `HttpRequestWrapper` within
 *     it, and dispatches the request to the `LocalHttpServer` for processing.
 * 2.  It provides a callback to the `NearbyConnectionsManager` for handling `STREAM` payloads,
 *     which are used for file transfers.
 */
class BridgeService : Service() {
    var currentState: BridgeState = BridgeState.Idle
        internal set

    internal lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    internal lateinit var localHttpServer: LocalHttpServer
    internal lateinit var serviceHardener: ServiceHardener
    internal lateinit var topologyOptimizer: TopologyOptimizer
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

        logger.runCatchingWithLogging {
            (('A'..'Z') + ('a'..'z') + ('0'..'9')).shuffled().take(5).joinToString("").also {
                endpointName = it
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
                    logger = logger,
                    payloadReceivedCallback = { _, payload ->
                        handleStreamPayload(payload)
                    },
                    maxConnections = TopologyOptimizer.TARGET_CONNECTIONS + 1
                )
            }
            if (!::topologyOptimizer.isInitialized) {
                topologyOptimizer = TopologyOptimizer(
                    connectionManager = nearbyConnectionsManager,
                    log = { msg: String -> logger.log(msg) },
                    endpointName = endpointName
                )
            }
            logger.log("onCreate() finished successfully")
        } ?: run {
            logger.e("FATAL: Service crashed on create.")
            currentState = BridgeState.Error("onCreate failed")
        }
    }

    internal fun handleIncomingData(data: ByteArray) {
        serviceHardener.updateP2pMessageTime()
        logger.runCatchingWithLogging {
            val jsonString = data.toString(Charsets.UTF_8)
            val networkMessage = Json.decodeFromString<NetworkMessage>(jsonString)

            networkMessage.httpRequest?.let { wrapper ->
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
    }

    internal fun handleStreamPayload(payload: Payload) {
        val filename = incomingFilePayloads.remove(payload.id)
        if (filename == null) {
            logger.e("Received stream payload with unknown ID: ${payload.id}")
            return
        }
        logger.runCatchingWithLogging {
            payload.asStream()?.asInputStream()?.use { inputStream ->
                AssetManager.saveFile(applicationContext, filename, inputStream)
            }
            logger.log("File received and saved: $filename")
        }
    }

    // Mechanism for UI to connect and interact with this service.
    inner class BridgeBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    private val binder = BridgeBinder()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.log("BridgeService: onStartCommand() called with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            else -> logger.log("onStartCommand: Unknown action: ${intent?.action}")
        }
        // If the OS kills the service, restart it, but don't re-deliver the last intent.
        return START_STICKY
    }

    fun broadcast(wrapper: HttpRequestWrapper) {
        val networkMessage = NetworkMessage(
            hopCount = 0,
            messageId = UUID.randomUUID().toString(),
            httpRequest = wrapper
        )
        val payload = Json.encodeToString(networkMessage).toByteArray(Charsets.UTF_8)
        nearbyConnectionsManager.sendPayload(
            nearbyConnectionsManager.connectedPeers.value.toList(),
            payload
        )
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
        broadcast(wrapper)
        nearbyConnectionsManager.sendPayload(
            nearbyConnectionsManager.connectedPeers.value.toList(),
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
        // FLAG_IMMUTABLE is a security best practice.
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Web Bridge")
            .setContentText("Running...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        // Required for long-running network tasks on modern Android.
        // The type hints to the OS that this service is for P2P connections.
        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        listenForIncomingData()
        nearbyConnectionsManager.start()
        topologyOptimizer.start()
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

        return PermissionUtils.getDangerousPermissions(this).all { permission ->
            (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED).also {
                if (!it) {
                    logger.e("Permission not granted: $permission")
                }
            }
        }
    }

    private fun stop() {
        logger.log("BridgeService.stop()")
        currentState = BridgeState.Stopping

        serviceHardener.stop()
        topologyOptimizer.stop()
        nearbyConnectionsManager.stop()
        localHttpServer.stop()
        // True to remove the notification, ensuring a clean stop.
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Request that the service be stopped.
        stopSelf()

        currentState = BridgeState.Idle
        logger.log("Service stopped.")
    }

    private fun listenForIncomingData() {
        CoroutineScope(ioDispatcher).launch {
            nearbyConnectionsManager.incomingPayloads.collect { (endpointId, data) ->
                logger.log("Collected ${data.size} bytes from $endpointId from flow.")
                handleIncomingData(data)
            }
        }
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