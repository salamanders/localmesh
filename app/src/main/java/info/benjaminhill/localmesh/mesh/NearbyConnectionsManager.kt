package info.benjaminhill.localmesh.mesh

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import info.benjaminhill.localmesh.util.AppLogger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow

private const val TARGET_CONNECTIONS = 3
private const val MESSAGE_ID_CACHE_SIZE = 100

/**
 * Manages all peer-to-peer network interactions using the Google Nearby Connections API.
 *
 * ## What it does
 * - Handles device discovery, advertising, and connection management.
 * - Establishes a many-to-many mesh network using the `P2P_CLUSTER` strategy.
 * - Sends and receives `Payload` objects (both `BYTES` and `STREAM`) to and from all connected peers.
 * - Manages connection retries with exponential backoff.
 *
 * ## What it doesn't do
 * - It does not interpret the content of the payloads it sends or receives. It is a transport layer,
 *   passing raw payloads up to the `BridgeService`.
 * - It is not aware of the HTTP server or the application's specific API endpoints.
 *
 * ## Comparison to other classes
 * - **[BridgeService]:** This class is the workhorse for P2P communication, while `BridgeService` acts
 *   as the orchestrator, connecting this manager to the `LocalHttpServer`.
 */
class NearbyConnectionsManager(
    private val context: Context,
    private val endpointName: String,
    private val logger: AppLogger,
    private val payloadReceivedCallback: (endpointId: String, payload: Payload) -> Unit,
) {

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val serviceId = "info.benjaminhill.localmesh.v1"
    private val connectedEndpoints = ConcurrentLinkedQueue<String>()
    private val retryCounts = mutableMapOf<String, Int>()
    private val seenMessageIds = ConcurrentHashMap<UUID, Long>()


    fun start() {
        logger.log("NearbyConnectionsManager.start()")
        CoroutineScope(Dispatchers.IO).launch {
            startAdvertising()
            startDiscovery()
        }
    }

    fun stop() {
        logger.log("NearbyConnectionsManager.stop()")
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        retryCounts.clear()
        seenMessageIds.clear()
    }

    fun sendPayload(endpointIds: List<String>, payload: Payload) =
        logger.runCatchingWithLogging {
            logger.log("NearbyConnectionsManager.sendPayload() to ${endpointIds.size} endpoints.")
            connectionsClient.sendPayload(endpointIds, payload)
                .addOnFailureListener { e ->
                    logger.e("Failed to send payload ${payload.id}", e)
                }
        }

    /**
     * Broadcasts a byte array to all connected endpoints, wrapping it in a forwardable message structure.
     */
    fun broadcastBytes(data: ByteArray) {
        val messageId = UUID.randomUUID()
        val payload = createForwardablePayload(messageId, data)
        logger.log("Broadcasting original message $messageId to ${connectedEndpoints.size} peers.")
        sendPayload(connectedEndpoints.toList(), payload)
    }

    /**
     * Forwards a payload to a list of endpoints.
     * This is used to propagate messages through the mesh.
     */
    private fun forwardPayload(endpointIds: List<String>, payload: Payload) {
        logger.log("Forwarding message to ${endpointIds.size} peers.")
        sendPayload(endpointIds, payload)
    }

    /**
     * Creates a payload with a prepended UUID for tracking and forwarding.
     * Format: [16-byte UUID][original data]
     */
    private fun createForwardablePayload(messageId: UUID, data: ByteArray): Payload {
        val buffer = ByteBuffer.allocate(16 + data.size)
        buffer.putLong(messageId.mostSignificantBits)
        buffer.putLong(messageId.leastSignificantBits)
        buffer.put(data)
        return Payload.fromBytes(buffer.array())
    }

    /**
     * Extracts the UUID and the original data from a forwardable payload.
     */
    private fun unpackForwardablePayload(payload: Payload): Pair<UUID, ByteArray> {
        val buffer = ByteBuffer.wrap(payload.asBytes()!!)
        val mostSigBits = buffer.long
        val leastSigBits = buffer.long
        val messageId = UUID(mostSigBits, leastSigBits)
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return messageId to data
    }

    val connectedPeerCount: Int
        get() = connectedEndpoints.size

    val connectedPeerIds: List<String>
        get() = connectedEndpoints.toList()

    private suspend fun startAdvertising() = logger.runCatchingWithLogging {
        withContext(Dispatchers.IO) {
            val advertisingOptions =
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            connectionsClient.startAdvertising(
                endpointName,
                serviceId,
                connectionLifecycleCallback,
                advertisingOptions
            ).addOnSuccessListener {
                logger.log("Advertising started.")
            }.addOnFailureListener { e ->
                logger.e("Failed to start advertising", e)
            }
        }
    }

    private suspend fun startDiscovery() = logger.runCatchingWithLogging {
        withContext(Dispatchers.IO) {
            val discoveryOptions =
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            connectionsClient.startDiscovery(
                serviceId,
                endpointDiscoveryCallback,
                discoveryOptions
            ).addOnSuccessListener {
                logger.log("Discovery started.")
            }.addOnFailureListener { e ->
                logger.e("Failed to start discovery", e)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            logger.runCatchingWithLogging {
                if (payload.type != Payload.Type.BYTES || payload.asBytes()!!.size <= 16) {
                    logger.log("Received a non-forwardable or empty payload from $endpointId. Processing directly.")
                    payloadReceivedCallback(endpointId, payload)
                    return@runCatchingWithLogging
                }

                val (messageId, data) = unpackForwardablePayload(payload)

                if (seenMessageIds.containsKey(messageId)) {
                    logger.log("Ignoring duplicate message $messageId from $endpointId.")
                    return@runCatchingWithLogging
                }

                logger.log("Received new message $messageId from $endpointId.")
                seenMessageIds[messageId] = System.currentTimeMillis()

                // 1. Forward to all other peers
                val otherPeers = connectedEndpoints.filter { it != endpointId }
                if (otherPeers.isNotEmpty()) {
                    forwardPayload(otherPeers, payload)
                }

                // 2. Process the data locally by passing it up to the service
                payloadReceivedCallback(endpointId, Payload.fromBytes(data))

                // 3. Clean up old message IDs to prevent the cache from growing indefinitely
                cleanupMessageIdCache()
            }
        }

        private fun cleanupMessageIdCache() {
            if (seenMessageIds.size > MESSAGE_ID_CACHE_SIZE) {
                val sortedEntries = seenMessageIds.entries.sortedBy { it.value }
                val toRemove = sortedEntries.take(seenMessageIds.size - MESSAGE_ID_CACHE_SIZE)
                toRemove.forEach { seenMessageIds.remove(it.key) }
                logger.log("Cleaned up ${toRemove.size} old message IDs from cache.")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            logger.runCatchingWithLogging {
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS ->
                        logger.log("SUCCESS: Payload ${update.payloadId} transfer to $endpointId complete.")

                    PayloadTransferUpdate.Status.FAILURE ->
                        logger.e(
                            "FAILURE: Payload ${update.payloadId} transfer to $endpointId failed.",
                            Exception("PayloadTransferUpdate Failure")
                        )

                    PayloadTransferUpdate.Status.CANCELED ->
                        logger.log("CANCELED: Payload ${update.payloadId} transfer to $endpointId was canceled.")

                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        // Ignoring for now to keep logs clean. This is where you'd update a progress bar.
                    }
                }
            }
        }
    }

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                logger.runCatchingWithLogging {
                    logger.log("onConnectionInitiated from ${connectionInfo.endpointName} (id:$endpointId)")
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                        .addOnFailureListener { e ->
                            logger.e("Failed to accept connection from $endpointId", e)
                        }
                }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    logger.log("Connected to $endpointId")
                    connectedEndpoints.add(endpointId)
                    retryCounts.remove(endpointId) // Clear on success
                    // peerCountUpdateCallback(connectedEndpoints.size)
                } else {
                    logger.log("Connection to $endpointId failed: ${result.status.statusCode}")
                    scheduleRetry(endpointId, "connection result") {
                        requestConnection(endpointId)
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                logger.log("onDisconnected: $endpointId")
                connectedEndpoints.remove(endpointId)
                // peerCountUpdateCallback(connectedEndpoints.size)
            }
        }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            logger.log("onEndpointFound: ${discoveredEndpointInfo.endpointName} (id:$endpointId)")
            if (connectedEndpoints.size < TARGET_CONNECTIONS) {
                logger.log("Attempting to connect to $endpointId (current connections: ${connectedEndpoints.size})")
                requestConnection(endpointId)
            } else {
                logger.log("Skipping connection to $endpointId (already at target connections: ${connectedEndpoints.size})")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            logger.log("onEndpointLost: $endpointId")
        }
    }

    private fun requestConnection(endpointId: String) {
        logger.runCatchingWithLogging {
            connectionsClient.requestConnection(
                endpointName,
                endpointId,
                connectionLifecycleCallback
            )
                .addOnSuccessListener {
                    logger.log("Connection request sent to $endpointId.")
                    retryCounts.remove(endpointId) // Clear on success
                }
                .addOnFailureListener { e ->
                    val statusCode = (e as? ApiException)?.statusCode
                    logger.e(
                        "Failed to request connection to $endpointId (code: $statusCode)",
                        e
                    )
                    if (statusCode == ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR) {
                        scheduleRetry(endpointId, "initial request") {
                            requestConnection(endpointId)
                        }
                    }
                }
        }
    }

    private fun scheduleRetry(key: String, description: String, action: suspend () -> Unit) {
        val currentRetries = retryCounts.getOrPut(key) { 0 }
        if (currentRetries < 5) {
            val nextRetry = currentRetries + 1
            retryCounts[key] = nextRetry
            val backoffMillis = (1000 * 2.0.pow(currentRetries)).toLong() + (0..1000).random()
            logger.log("Scheduling retry for $description on '$key' in $backoffMillis ms (attempt $nextRetry)")
            CoroutineScope(Dispatchers.IO).launch {
                delay(backoffMillis)
                action()
            }
        } else {
            logger.log("Max retries reached for $description on '$key'. Giving up.")
            retryCounts.remove(key)
        }
    }
}