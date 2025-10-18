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
import info.benjaminhill.localmesh.logic.ConnectionManager
import info.benjaminhill.localmesh.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow

private const val TARGET_CONNECTIONS = 3
private const val MAX_CONNECTIONS = 4

private const val MESSAGE_TYPE_DATA: Byte = 0
private const val MESSAGE_TYPE_GOSSIP_PEER_LIST: Byte = 1

@Serializable
private data class NetworkMessage(
    val type: Byte,
    val hopCount: Byte,
    val messageId: String, // UUID as string
    val payloadContent: String // The HttpRequestWrapper serialized to JSON
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
interface TopologyOptimizerCallback {
    fun onDataPayloadReceived(hopCount: Int, sourceNodeId: String)
    fun onGossipPayloadReceived(endpointId: String, theirPeers: List<String>)
}

class NearbyConnectionsManager(
    private val context: Context,
    private val endpointName: String,
    private val logger: AppLogger,
    private val payloadReceivedCallback: (endpointId: String, payload: Payload) -> Unit,
    private val topologyOptimizerCallback: TopologyOptimizerCallback
) : ConnectionManager {

    override val connectedPeers = MutableStateFlow(emptySet<String>())
    override val incomingPayloads = MutableSharedFlow<Pair<String, ByteArray>>()
    override val discoveredEndpoints = MutableSharedFlow<String>()

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val serviceId = "info.benjaminhill.localmesh.v1"
    private val retryCounts = mutableMapOf<String, Int>()
    private val seenMessageIds = ConcurrentHashMap<UUID, Long>()


    override fun start() {
        logger.log("NearbyConnectionsManager.start()")
        CoroutineScope(Dispatchers.IO).launch {
            startAdvertising()
            startDiscovery()
        }
    }


    fun broadcastPeerList() {
        if (connectedPeers.value.isEmpty()) return
        val peers = connectedPeers.value.toList()
        val data = peers.joinToString(",").toByteArray(Charsets.UTF_8)
        val messageId = UUID.randomUUID()
        val payload = createForwardablePayload(MESSAGE_TYPE_GOSSIP_PEER_LIST, 0, messageId, data)
        logger.log("Gossiping peer list to ${peers.size} peers.")
        sendPayload(peers, payload)
    }


    override fun stop() {
        logger.log("NearbyConnectionsManager.stop()")
        connectionsClient.stopAllEndpoints()
        connectedPeers.value = emptySet()
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
        val payload = createForwardablePayload(MESSAGE_TYPE_DATA, 0, messageId, data)
        logger.log("Broadcasting original data message $messageId to ${connectedPeers.value.size} peers.")
        sendPayload(connectedPeers.value.toList(), payload)
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
     * Creates a payload by serializing a `NetworkMessage` to JSON and then to `ByteArray`.
     */
    private fun createForwardablePayload(
        type: Byte,
        hopCount: Byte,
        messageId: UUID,
        data: ByteArray
    ): Payload {
        val networkMessage = NetworkMessage(
            type = type,
            hopCount = hopCount,
            messageId = messageId.toString(),
            payloadContent = data.toString(Charsets.UTF_8)
        )
        val jsonString = Json.encodeToString(networkMessage)
        return Payload.fromBytes(jsonString.toByteArray(Charsets.UTF_8))
    }

    /**
     * Extracts the `NetworkMessage` from a received `Payload` by deserializing from JSON.
     */
    private fun unpackForwardablePayload(payload: Payload): NetworkMessage {
        val jsonString = payload.asBytes()!!.toString(Charsets.UTF_8)
        return Json.decodeFromString<NetworkMessage>(jsonString)
    }

    override fun connectTo(endpointId: String) {
        requestConnection(endpointId)
    }

    override fun disconnectFrom(endpointId: String) {
        disconnectFromEndpoint(endpointId)
    }

    override fun sendPayload(endpointIds: List<String>, payload: ByteArray) {
        sendPayload(endpointIds, Payload.fromBytes(payload))
    }

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
            logger.log("onPayloadReceived: Received payload from $endpointId, type: ${payload.type}, size: ${payload.asBytes()?.size}")
            logger.runCatchingWithLogging {
                if (payload.type != Payload.Type.BYTES) {
                    logger.log("Received a non-BYTES payload from $endpointId. Processing directly.")
                    payloadReceivedCallback(endpointId, payload)
                    return@runCatchingWithLogging
                }
                val bytes = payload.asBytes()
                if (bytes == null || bytes.isEmpty()) {
                    logger.log("Received an empty BYTES payload from $endpointId. Ignoring.")
                    payloadReceivedCallback(endpointId, payload)
                    return@runCatchingWithLogging
                }
                val jsonString = bytes.toString(Charsets.UTF_8)
                logger.log("Received BYTES payload content from $endpointId: $jsonString")

                val networkMessage = unpackForwardablePayload(payload)

                if (seenMessageIds.containsKey(UUID.fromString(networkMessage.messageId))) {
                    logger.log("Ignoring duplicate message ${networkMessage.messageId} from $endpointId.")
                    return@runCatchingWithLogging
                }
                seenMessageIds[UUID.fromString(networkMessage.messageId)] =
                    System.currentTimeMillis()

                when (networkMessage.type) {
                    MESSAGE_TYPE_DATA -> handleDataPayload(endpointId, networkMessage)
                    MESSAGE_TYPE_GOSSIP_PEER_LIST -> handleGossipPayload(endpointId, networkMessage)
                    else -> logger.log("Received unknown message type: ${networkMessage.type}")
                }
            }
        }

        private fun handleDataPayload(endpointId: String, networkMessage: NetworkMessage) {
            logger.log("Received new data message ${networkMessage.messageId} from $endpointId with hopCount ${networkMessage.hopCount}.")
            val wrapper = HttpRequestWrapper.fromJson(networkMessage.payloadContent)
            topologyOptimizerCallback.onDataPayloadReceived(
                networkMessage.hopCount.toInt(),
                wrapper.sourceNodeId
            )
            CoroutineScope(Dispatchers.IO).launch {
                incomingPayloads.emit(endpointId to networkMessage.payloadContent.toByteArray(Charsets.UTF_8))
            }

            // 1. Forward to all other peers
            val otherPeers = connectedPeers.value.filter { it != endpointId }
            if (otherPeers.isNotEmpty()) {
                val nextHopCount = (networkMessage.hopCount + 1).toByte()
                val forwardedPayload = createForwardablePayload(
                    networkMessage.type,
                    nextHopCount,
                    UUID.fromString(networkMessage.messageId),
                    networkMessage.payloadContent.toByteArray(Charsets.UTF_8)
                )
                forwardPayload(otherPeers, forwardedPayload)
            }

            // 2. Process the data locally by passing it up to the service
        }

        private fun handleGossipPayload(endpointId: String, networkMessage: NetworkMessage) {
            val theirPeers =
                networkMessage.payloadContent.split(",").filter { it.isNotBlank() }
            logger.log("Received gossip from $endpointId with ${theirPeers.size} peers.")
            topologyOptimizerCallback.onGossipPayloadReceived(endpointId, theirPeers)
            // Do not forward gossip messages
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
                    connectedPeers.value += endpointId
                    retryCounts.remove(endpointId) // Clear on success
                } else {
                    logger.log("Connection to $endpointId failed: ${result.status.statusCode}")
                    scheduleRetry(endpointId, "connection result") {
                        requestConnection(endpointId)
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                logger.log("onDisconnected: $endpointId")
                connectedPeers.value -= endpointId
            }
        }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            logger.log("onEndpointFound: ${discoveredEndpointInfo.endpointName} (id:$endpointId)")
            CoroutineScope(Dispatchers.IO).launch {
                discoveredEndpoints.emit(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            logger.log("onEndpointLost: $endpointId")
        }
    }

    fun requestConnection(endpointId: String) {
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

    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

}