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
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.LogFileWriter
import info.benjaminhill.localmesh.MainActivity
import info.benjaminhill.localmesh.R
import info.benjaminhill.localmesh.ui.AppStateHolder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val incomingFilePayloads = ConcurrentHashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() called")
        try {
            logFileWriter = LogFileWriter(applicationContext)
            endpointName = UUID.randomUUID().toString().substring(0, 4)
            nearbyConnectionsManager = NearbyConnectionsManager(this, endpointName, {
                AppStateHolder.statusText.value = "Running - $it Peers"
            }, ::sendLogMessage) { endpointId, payload ->
                try {
                    when (payload.type) {
                        com.google.android.gms.nearby.connection.Payload.Type.BYTES -> {
                            val message = Json.decodeFromString<Message>(payload.asBytes()!!.toString(Charsets.UTF_8))
                            if (receivedMessages.size >= MAX_QUEUE_SIZE) {
                                receivedMessages.poll()
                            }
                            receivedMessages.add(message)
                            sendLogMessage("Received message: $message")

                            if (message.payload.startsWith("display ")) {
                                val urlPath = message.payload.substringAfter("display ").trim()
                                if (urlPath.isNotBlank()) {
                                    val safeUrlPath = if (urlPath.startsWith("/")) urlPath else "/$urlPath"
                                    val fullUrl = "http://localhost:${LocalHttpServer.PORT}$safeUrlPath"
                                    sendLogMessage("Received display command, opening $fullUrl")
                                    val intent = Intent(applicationContext, info.benjaminhill.localmesh.WebViewActivity::class.java).apply {
                                        putExtra(info.benjaminhill.localmesh.WebViewActivity.EXTRA_URL, fullUrl)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                }
                            } else if (message.payload.startsWith("content ")) {
                                val parts = message.payload.split(Regex("\\s+"), 3)
                                if (parts.size == 3 && parts[0] == "content") {
                                    val filename = parts[1].substringAfterLast('/').substringAfterLast('\\')
                                    val fileContent = parts[2]
                                    if (filename.isNotBlank()) {
                                        try {
                                            val cacheDir = File(applicationContext.cacheDir, "web_cache")
                                            if (!cacheDir.exists()) {
                                                cacheDir.mkdirs()
                                            }
                                            val file = File(cacheDir, filename)
                                            file.writeText(fileContent)
                                            sendLogMessage("Cached file updated: $filename")
                                        } catch (e: Exception) {
                                            sendLogMessage("Error caching file '$filename': ${e.message}")
                                        }
                                    }
                                }
                            } else if (message.payload.startsWith("file_info ")) {
                                val parts = message.payload.split(Regex("\\s+"), 3)
                                if (parts.size == 3 && parts[0] == "file_info") {
                                    val filename = parts[1].substringAfterLast('/').substringAfterLast('\\')
                                    val payloadId = parts[2].toLong()
                                    incomingFilePayloads[payloadId] = filename
                                    sendLogMessage("Received file info: $filename (payloadId: $payloadId)")
                                }
                            }
                        }
                        com.google.android.gms.nearby.connection.Payload.Type.STREAM -> {
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

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand() called with action: ${intent?.action}")
        when (intent?.action) {
            P2PBridgeAction.Start::class.java.name -> start()
            P2PBridgeAction.Stop::class.java.name -> stop()
            P2PBridgeAction.ShareFolder::class.java.name -> {
                intent.getStringExtra(EXTRA_FOLDER_NAME)?.let { folderName ->
                    sendMessage("display $folderName")
                    val newUrl = "http://localhost:${LocalHttpServer.PORT}/$folderName"
                    AppStateHolder.serverUrl.value = newUrl
                    sendLogMessage("Broadcasting display command for folder: $folderName and updated local URL to $newUrl")
                } ?: sendLogMessage("ShareFolder action received with no folderName")
            }

            else -> Log.w(TAG, "onStartCommand: Unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    fun getReceivedMessages(): List<Message> {
        val messages = receivedMessages.toList()
        receivedMessages.clear()
        return messages
    }

    fun sendMessage(message: String) {
        val messageObject = Message(
            from = endpointName,
            sequence = System.currentTimeMillis(),
            timestamp = System.currentTimeMillis(),
            payload = message
        )
        val payload =
            Json.encodeToString(Message.serializer(), messageObject).toByteArray(Charsets.UTF_8)
        nearbyConnectionsManager.broadcastBytes(payload)
    }

    fun sendFile(file: File) {
        val payloadId = com.google.android.gms.nearby.connection.Payload.fromStream(file.inputStream()).id
        val metadata = Json.encodeToString(
            Message.serializer(),
            Message(
                from = endpointName,
                sequence = System.currentTimeMillis(),
                timestamp = System.currentTimeMillis(),
                payload = "file_info ${file.name} $payloadId"
            )
        ).toByteArray(Charsets.UTF_8)

        nearbyConnectionsManager.sendPayload(nearbyConnectionsManager.getConnectedPeerIds(), com.google.android.gms.nearby.connection.Payload.fromBytes(metadata))
        nearbyConnectionsManager.sendPayload(nearbyConnectionsManager.getConnectedPeerIds(), com.google.android.gms.nearby.connection.Payload.fromStream(file.inputStream()))
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

    private fun sendLogMessage(message: String) {
        Log.d(TAG, message)
        logFileWriter.writeLog(message)
        AppStateHolder.addLog(message)
    }

    companion object {
        const val EXTRA_FOLDER_NAME = "extra_folder_name"
        private const val CHANNEL_ID = "P2PBridgeServiceChannel"
        private const val TAG = "P2PBridgeService"
        private const val MAX_QUEUE_SIZE = 1_000
    }
}