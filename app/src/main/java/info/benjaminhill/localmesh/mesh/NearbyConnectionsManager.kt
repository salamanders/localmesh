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
private const val MAX_CONNECTIONS = 4
private const val MESSAGE_ID_CACHE_SIZE = 100
private const val GOSSIP_INTERVAL_MS = 30_000L
private const val REWIRING_ANALYSIS_INTERVAL_MS = 60_000L

private const val MESSAGE_TYPE_DATA: Byte = 0
private const val MESSAGE_TYPE_GOSSIP_PEER_LIST: Byte = 1

private data class UnpackedPayload(
    val type: Byte,
    val hopCount: Byte,
    val messageId: UUID,
    val data: ByteArray
)

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
    private val neighborPeerLists = ConcurrentHashMap<String, List<String>>()
    private val distantNodes = ConcurrentHashMap<String, Int>()


    fun start() {
        logger.log("NearbyConnectionsManager.start()")
        CoroutineScope(Dispatchers.IO).launch {
            startAdvertising()
            startDiscovery()
            startGossip()
            startRewiringAnalysis()
        }
    }

    private fun CoroutineScope.startGossip() = launch {
        while (true) {
            delay(GOSSIP_INTERVAL_MS)
            broadcastPeerList()
        }
    }

    private fun broadcastPeerList() {
        if (connectedEndpoints.isEmpty()) return
        val peers = connectedEndpoints.toList()
        val data = peers.joinToString(",").toByteArray(Charsets.UTF_8)
        val messageId = UUID.randomUUID()
        val payload = createForwardablePayload(MESSAGE_TYPE_GOSSIP_PEER_LIST, 0, messageId, data)
        logger.log("Gossiping peer list to ${peers.size} peers.")
        sendPayload(peers, payload)
    }

    private fun CoroutineScope.startRewiringAnalysis() = launch {
        while (true) {
            delay(REWIRING_ANALYSIS_INTERVAL_MS)
            analyzeAndLogRewiringOpportunities()
        }
    }

    fun stop() {
        logger.log("NearbyConnectionsManager.stop()")
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        retryCounts.clear()
        seenMessageIds.clear()
        neighborPeerLists.clear()
        distantNodes.clear()
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
        val payload = createForwardablePayload(MESSAGE_TYPE_DATA, 0, messageId, data)
        logger.log("Broadcasting original data message $messageId to ${connectedEndpoints.size} peers.")
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
     * Creates a payload with a prepended type, hop count, and UUID for tracking and forwarding.
     * Format: [1-byte type][1-byte hopCount][16-byte UUID][original data]
     */
    private fun createForwardablePayload(
        type: Byte,
        hopCount: Byte,
        messageId: UUID,
        data: ByteArray
    ): Payload {
        val buffer = ByteBuffer.allocate(1 + 1 + 16 + data.size)
        buffer.put(type)
        buffer.put(hopCount)
        buffer.putLong(messageId.mostSignificantBits)
        buffer.putLong(messageId.leastSignificantBits)
        buffer.put(data)
        return Payload.fromBytes(buffer.array())
    }

    /**
     * Extracts the type, hop count, UUID, and original data from a forwardable payload.
     */
    private fun unpackForwardablePayload(payload: Payload): UnpackedPayload {
        val buffer = ByteBuffer.wrap(payload.asBytes()!!)
        val type = buffer.get()
        val hopCount = buffer.get()
        val mostSigBits = buffer.long
        val leastSigBits = buffer.long
        val messageId = UUID(mostSigBits, leastSigBits)
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return UnpackedPayload(type, hopCount, messageId, data)
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

    private fun analyzeAndLogRewiringOpportunities() {
        logger.log("Analyzing network for rewiring opportunities.")
        val myPeers = connectedEndpoints.toSet()
        if (myPeers.size < 2) {
            logger.log("Not enough peers to analyze for rewiring.")
            return
        }

        // Find redundant local connections (triangles)
        var redundantPeer: String? = null
        for (peerA in myPeers) {
            val peersOfPeerA = neighborPeerLists[peerA]?.toSet() ?: continue
            for (peerB in myPeers) {
                if (peerA != peerB && peersOfPeerA.contains(peerB)) {
                    redundantPeer = peerB
                    logger.log("Found redundant connection: We are connected to $peerA and $peerB, and they are connected to each other.")
                    break
                }
            }
            if (redundantPeer != null) break
        }

        if (redundantPeer == null) {
            logger.log("No redundant local connections found.")
            return
        }

        // Find the most distant node we know of
        val mostDistantNode = distantNodes.entries
            .filter { !myPeers.contains(it.key) } // Not one of our direct peers
            .maxByOrNull { it.value }

        if (mostDistantNode == null) {
            logger.log("No distant nodes found to rewire to.")
            return
        }

        logger.log("REWIRING OPPORTUNITY: Drop redundant peer $redundantPeer and connect to distant node ${mostDistantNode.key} (hop count: ${mostDistantNode.value})")
        // In the future, this is where the actual disconnection/connection logic would go.
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            logger.runCatchingWithLogging {
                if (payload.type != Payload.Type.BYTES || payload.asBytes()!!.size <= 18) {
                    logger.log("Received a non-forwardable or empty payload from $endpointId. Processing directly.")
                    payloadReceivedCallback(endpointId, payload)
                    return@runCatchingWithLogging
                }

                val unpacked = unpackForwardablePayload(payload)

                if (seenMessageIds.containsKey(unpacked.messageId)) {
                    logger.log("Ignoring duplicate message ${unpacked.messageId} from $endpointId.")
                    return@runCatchingWithLogging
                }
                seenMessageIds[unpacked.messageId] = System.currentTimeMillis()

                when (unpacked.type) {
                    MESSAGE_TYPE_DATA -> handleDataPayload(endpointId, unpacked)
                    MESSAGE_TYPE_GOSSIP_PEER_LIST -> handleGossipPayload(endpointId, unpacked)
                    else -> logger.log("Received unknown message type: ${unpacked.type}")
                }

                cleanupMessageIdCache()
            }
        }

        private fun handleDataPayload(endpointId: String, unpacked: UnpackedPayload) {
            logger.log("Received new data message ${unpacked.messageId} from $endpointId with hopCount ${unpacked.hopCount}.")
            distantNodes[endpointId] = unpacked.hopCount.toInt()

            // 1. Forward to all other peers
            val otherPeers = connectedEndpoints.filter { it != endpointId }
            if (otherPeers.isNotEmpty()) {
                val nextHopCount = (unpacked.hopCount + 1).toByte()
                val forwardedPayload = createForwardablePayload(
                    unpacked.type,
                    nextHopCount,
                    unpacked.messageId,
                    unpacked.data
                )
                forwardPayload(otherPeers, forwardedPayload)
            }

            // 2. Process the data locally by passing it up to the service
            payloadReceivedCallback(endpointId, Payload.fromBytes(unpacked.data))
        }

        private fun handleGossipPayload(endpointId: String, unpacked: UnpackedPayload) {
            val theirPeers = unpacked.data.toString(Charsets.UTF_8).split(",").filter { it.isNotBlank() }
            logger.log("Received gossip from $endpointId with ${theirPeers.size} peers.")
            neighborPeerLists[endpointId] = theirPeers
            // Do not forward gossip messages
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
                    if (connectedEndpoints.size < MAX_CONNECTIONS) {
                        logger.log("Accepting connection from $endpointId (current connections: ${connectedEndpoints.size})")
                        connectionsClient.acceptConnection(endpointId, payloadCallback)
                            .addOnFailureListener { e ->
                                logger.e("Failed to accept connection from $endpointId", e)
                            }
                    } else {
                        logger.log("Rejecting connection from $endpointId (already at max connections: ${connectedEndpoints.size})")
                        connectionsClient.rejectConnection(endpointId)
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
                neighborPeerLists.remove(endpointId)
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