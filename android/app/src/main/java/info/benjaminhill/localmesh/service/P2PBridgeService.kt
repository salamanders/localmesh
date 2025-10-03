package info.benjaminhill.localmesh.service


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
import info.benjaminhill.localmesh.MainActivity
import info.benjaminhill.localmesh.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class P2PBridgeService : Service() {

    @Serializable
    data class Message(
        val from: String,
        val sequence: Long,
        val timestamp: Long,
        val payload: String
    )

    private lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var localHttpServer: LocalHttpServer
    private lateinit var endpointName: String
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var logFileWriter: LogFileWriter

    private val receivedMessages = ConcurrentLinkedQueue<Message>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() called")
        try {
            logFileWriter = LogFileWriter(applicationContext)
            Log.i(TAG, "onCreate: LogFileWriter initialized")
            endpointName =
                UUID.randomUUID().toString().substring(0, 4) // Generate a short random ID
            Log.i(TAG, "onCreate: endpointName created: $endpointName")
            nearbyConnectionsManager = NearbyConnectionsManager(this, endpointName, {
                sendP2PBridgeEvent(P2PBridgeEvent.StatusUpdate("Running", it))
            }, ::sendLogMessage) { _, payload ->
                try {
                    val message = Json.decodeFromString<Message>(payload.toString(Charsets.UTF_8))
                    if (receivedMessages.size >= MAX_QUEUE_SIZE) {
                        receivedMessages.poll() // remove the oldest message
                    }
                    receivedMessages.add(message)
                    sendLogMessage("Received message: $message")
                } catch (e: Exception) {
                    sendLogMessage("Failed to parse message: ${e.message}")
                }
            }
            Log.i(TAG, "onCreate: NearbyConnectionsManager initialized")
            localHttpServer = LocalHttpServer(this, ::sendLogMessage)
            Log.i(TAG, "onCreate: LocalHttpServer initialized")
            Log.i(TAG, "onCreate() finished successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: CRASHED: ${e.message}", e)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.i(TAG, "onBind() called, returning null.")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand() called with action: ${intent?.action}")
        when (intent?.action) {
            P2PBridgeAction.Start::class.java.name -> start()
            P2PBridgeAction.Stop::class.java.name -> stop()
            else -> Log.w(TAG, "onStartCommand: Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }


    private var currentState: ServiceState = ServiceState.Idle

    fun getReceivedMessages(): List<Message> {
        val messages = receivedMessages.toList()
        receivedMessages.clear()
        return messages
    }

    fun sendMessage(message: String) {
        val messageObject = Message(
            from = endpointName,
            sequence = System.currentTimeMillis(), // Basic sequence, can be improved
            timestamp = System.currentTimeMillis(),
            payload = message
        )
        val payload =
            Json.encodeToString(Message.serializer(), messageObject).toByteArray(Charsets.UTF_8)
        nearbyConnectionsManager.sendPayload(payload)
    }

    fun getConnectedPeerCount(): Int = nearbyConnectionsManager.getConnectedPeerCount()

    fun getConnectedPeerIds(): List<String> = nearbyConnectionsManager.getConnectedPeerIds()

    fun getEndpointName(): String = endpointName

    private fun start() {
        Log.i(TAG, "start() called")
        sendLogMessage("P2PBridgeService.start()")
        if (currentState !is ServiceState.Idle) {
            sendLogMessage("Service is not idle, cannot start. Current state: ${currentState::class.java.simpleName}")
            return
        }
        currentState = ServiceState.Starting
        sendP2PBridgeEvent(P2PBridgeEvent.StatusUpdate("Starting...", 0))

        if (!checkHardwareAndPermissions()) {
            currentState = ServiceState.Error("Hardware or permissions not met.")
            sendP2PBridgeEvent(P2PBridgeEvent.StatusUpdate("Error", 0))
            stopSelf()
            return
        }

        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Web Bridge")
            .setContentText("Running...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        Log.i(TAG, "start: Starting foreground service.")
        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        nearbyConnectionsManager.start()
        localHttpServer.start()
        acquireWakeLock()
        currentState = ServiceState.Running
        sendLogMessage("Service started and running.")
        sendP2PBridgeEvent(P2PBridgeEvent.StatusUpdate("Running", 0))
        Log.i(TAG, "start() finished.")
    }

    private fun checkHardwareAndPermissions(): Boolean {
        Log.d(TAG, "checkHardwareAndPermissions() called")
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
        Log.i(TAG, "stop() called")
        sendLogMessage("P2PBridgeService.stop()")
        if (currentState !is ServiceState.Running && currentState !is ServiceState.Error) {
            sendLogMessage("Service is not running or in error state, cannot stop. Current state: ${currentState::class.java.simpleName}")
            return
        }
        currentState = ServiceState.Stopping
        sendP2PBridgeEvent(P2PBridgeEvent.StatusUpdate("Stopping...", 0))

        nearbyConnectionsManager.stop()
        localHttpServer.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        currentState = ServiceState.Idle
        sendLogMessage("Service stopped.")
        sendP2PBridgeEvent(P2PBridgeEvent.StatusUpdate("Inactive", 0))
        Log.i(TAG, "stop() finished.")
    }

    private fun acquireWakeLock() {
        Log.d(TAG, "acquireWakeLock() called")
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalMesh::WakeLock").apply {
                acquire(4 * 60 * 60 * 1000L /*4 hours*/)
            }
        }
    }

    private fun releaseWakeLock() {
        Log.d(TAG, "releaseWakeLock() called")
        wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        Log.d(TAG, "createNotificationChannel() called")
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "P2P Bridge Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }


    private fun sendP2PBridgeEvent(event: P2PBridgeEvent) {
        Log.d(TAG, "sendP2PBridgeEvent() called with event: $event")
        val intent = Intent(P2PBridgeEvent::class.java.name).apply {
            putExtra("event", Json.encodeToString(P2PBridgeEvent.serializer(), event))
        }
        sendBroadcast(intent)
    }

    private fun sendLogMessage(message: String) {
        Log.d(TAG, "sendLogMessage() called with message: $message")
        logFileWriter.writeLog(message)
        sendP2PBridgeEvent(P2PBridgeEvent.LogMessage(message))
    }

    companion object {
        private const val CHANNEL_ID = "P2PBridgeServiceChannel"
        private const val TAG = "P2PBridgeService"
        private const val MAX_QUEUE_SIZE = 1_000
    }
}
