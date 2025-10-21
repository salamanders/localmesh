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
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.MainActivity
import info.benjaminhill.localmesh.R
import info.benjaminhill.localmesh.logic.FileChunk
import info.benjaminhill.localmesh.logic.HttpRequestWrapper
import info.benjaminhill.localmesh.logic.NetworkMessage
import info.benjaminhill.localmesh.logic.TopologyOptimizer
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.LogFileWriter
import info.benjaminhill.localmesh.util.PermissionUtils
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val CHUNK_SIZE = 16 * 1024 // 16KB

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
    internal lateinit var fileReassemblyManager: FileReassemblyManager
    private lateinit var logger: AppLogger

    lateinit var endpointName: String
        internal set

    @Volatile
    var serviceStartTime = 0L

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val serviceScope by lazy { CoroutineScope(ioDispatcher + SupervisorJob()) }
    private val seenMessageIds = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BridgeService.onCreate() called")
        logger = AppLogger(TAG, LogFileWriter(applicationContext))
        logger.runCatchingWithLogging {
            endpointName = (('A'..'Z') + ('a'..'z') + ('0'..'9')).shuffled().take(5).joinToString("")
            logger.log("This node is now named: $endpointName")

            serviceHardener = ServiceHardener(this, logger)
            localHttpServer = LocalHttpServer(this, logger)
            fileReassemblyManager = FileReassemblyManager(applicationContext, logger)
            nearbyConnectionsManager = NearbyConnectionsManager(
                context = this,
                endpointName = endpointName,
                logger = logger,
                maxConnections = TopologyOptimizer.TARGET_CONNECTIONS + 1
            )
            topologyOptimizer = TopologyOptimizer(
                connectionManager = nearbyConnectionsManager,
                log = { msg: String -> logger.log(msg) },
                endpointName = endpointName
            )
            logger.log("onCreate() finished successfully")
        } ?: run {
            logger.e("FATAL: Service crashed on create.")
            currentState = BridgeState.Error("onCreate failed")
        }
    }

    internal fun handleIncomingData(fromEndpointId: String, data: ByteArray) {
        serviceHardener.updateP2pMessageTime()
        logger.runCatchingWithLogging {
            val networkMessage = Json.decodeFromString<NetworkMessage>(data.toString(Charsets.UTF_8))
            if (seenMessageIds.containsKey(networkMessage.messageId)) {
                return@runCatchingWithLogging
            }
            seenMessageIds[networkMessage.messageId] = System.currentTimeMillis()

            serviceScope.launch {
                networkMessage.httpRequest?.let { localHttpServer.dispatchRequest(it) }
                networkMessage.fileChunk?.let { fileReassemblyManager.handleFileChunk(it) }
            }

            val forwardPeers = nearbyConnectionsManager.connectedPeers.value.filter { it != fromEndpointId }
            if (forwardPeers.isNotEmpty()) {
                val forwardedMessage = networkMessage.copy(hopCount = networkMessage.hopCount + 1)
                val payload = Json.encodeToString(forwardedMessage).toByteArray(Charsets.UTF_8)
                nearbyConnectionsManager.sendPayload(forwardPeers, payload)
            }
        }
    }

    inner class BridgeBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    private val binder = BridgeBinder()
    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    fun broadcast(wrapper: HttpRequestWrapper) {
        val networkMessage = NetworkMessage(httpRequest = wrapper)
        val payload = Json.encodeToString(networkMessage).toByteArray(Charsets.UTF_8)
        nearbyConnectionsManager.sendPayload(
            nearbyConnectionsManager.connectedPeers.value.toList(),
            payload
        )
    }

    fun sendFile(file: File, destinationPath: String) {
        serviceScope.launch {
            logger.runCatchingWithLogging {
                val fileId = UUID.randomUUID().toString()
                val fileBytes = file.readBytes()
                val totalChunks = (fileBytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE

                for (i in 0 until totalChunks) {
                    val start = i * CHUNK_SIZE
                    val end = minOf((i + 1) * CHUNK_SIZE, fileBytes.size)
                    val chunkData = fileBytes.sliceArray(start until end)

                    val fileChunk = FileChunk(
                        fileId = fileId,
                        destinationPath = destinationPath,
                        chunkIndex = i,
                        totalChunks = totalChunks,
                        data = chunkData
                    )
                    val networkMessage = NetworkMessage(fileChunk = fileChunk)
                    val payload = Json.encodeToString(networkMessage).toByteArray(Charsets.UTF_8)
                    nearbyConnectionsManager.sendPayload(
                        nearbyConnectionsManager.connectedPeers.value.toList(),
                        payload
                    )
                }
            }
        }
    }

    private fun start() {
        if (currentState !is BridgeState.Idle) return
        currentState = BridgeState.Starting
        serviceStartTime = System.currentTimeMillis()

        if (!localHttpServer.start()) {
            currentState = BridgeState.Error("HTTP server failed to start.")
            stopSelf()
            return
        }
        if (!checkHardwareAndPermissions()) {
            currentState = BridgeState.Error("Hardware or permissions not met.")
            return
        }

        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Web Bridge")
            .setContentText("Running...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        listenForIncomingData()
        fileReassemblyManager.start()
        nearbyConnectionsManager.start()
        topologyOptimizer.start()
        serviceHardener.start()
        currentState = BridgeState.Running
    }

    private fun checkHardwareAndPermissions(): Boolean {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        (getSystemService(WIFI_SERVICE) as WifiManager).isWifiEnabled.let { if (!it) return false }
        return PermissionUtils.getDangerousPermissions(this).all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    internal fun stop() {
        currentState = BridgeState.Stopping
        serviceHardener.stop()
        topologyOptimizer.stop()
        nearbyConnectionsManager.stop()
        fileReassemblyManager.stop()
        localHttpServer.stop()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        currentState = BridgeState.Idle
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    private fun listenForIncomingData() {
        serviceScope.launch {
            nearbyConnectionsManager.incomingPayloads.collect { (endpointId, data) ->
                handleIncomingData(endpointId, data)
            }
        }
    }

    fun restart() {
        stop()
        // A small delay to allow resources to be released.
        runBlocking { delay(500) }
        start()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "P2P Bridge Service Channel", NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "info.benjaminhill.localmesh.action.START"
        const val ACTION_STOP = "info.benjaminhill.localmesh.action.STOP"
        private const val CHANNEL_ID = "P2PBridgeServiceChannel"
        private const val TAG = "BridgeService"
    }
}
