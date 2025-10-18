package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TARGET_CONNECTIONS = 5
private const val GOSSIP_INTERVAL_MS = 30_000L
private const val REWIRING_ANALYSIS_INTERVAL_MS = 60_000L
private const val REWIRING_COOLDOWN_MS = 60_000L
private const val NODE_HOP_COUNT_EXPIRY_MS = 120_000L // 2 minutes
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
 * Orchestrates the self-optimizing mesh network topology.
 *
 * ## What it does
 * - Manages timers for gossip and rewiring analysis.
 * - Maintains cached lists of neighbor peers and distant node hop counts.
 * - Analyzes the network topology to identify redundant connections and distant nodes.
 * - Instructs the [NearbyConnectionsManager] to disconnect from redundant peers and connect to more distant ones to optimize the network.
 * - Implements a cooldown mechanism to prevent rapid topology changes.
 * - Cleans up expired node hop count data to prevent stale information.
 *
 * ## What it doesn't do
 * - It does not directly handle Nearby Connections API calls; it delegates these to [NearbyConnectionsManager].
 * - It does not handle payload sending or receiving; it receives processed data from [NearbyConnectionsManager] via [TopologyOptimizerCallback].
 *
 * ## Comparison to other classes
 * - **[NearbyConnectionsManager]:** This class is the "brains" of the network optimization, while [NearbyConnectionsManager] is the "hands" that execute the connection changes.
 * - **[BridgeService]:** This class is a component managed by [BridgeService] to provide the self-optimizing functionality.
 */
class TopologyOptimizer(
    private val connectionManager: ConnectionManager,
    private val log: (String) -> Unit,
    private val endpointName: String,
) {

    private val neighborPeerLists = ConcurrentHashMap<String, List<String>>()
    private val nodeHopCounts =
        ConcurrentHashMap<String, Pair<Int, Long>>() // endpointId to (hopCount, timestamp)
    private var lastRewireTimestamp = 0L

    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        log("TopologyOptimizer.start()")
        scope.launch {
            listenForDiscoveredEndpoints()
            listenForIncomingPayloads()
            startGossip()
            startRewiringAnalysis()
            cleanupNodeHopCounts()
        }
    }

    private fun CoroutineScope.listenForDiscoveredEndpoints() = launch {
        connectionManager.discoveredEndpoints.collect { endpointId ->
            if (connectionManager.connectedPeers.value.size < TARGET_CONNECTIONS) {
                log("Attempting to connect to discovered endpoint $endpointId")
                connectionManager.connectTo(endpointId)
            }
        }
    }

    private fun CoroutineScope.listenForIncomingPayloads() = launch {
        connectionManager.incomingPayloads.collect { (endpointId, payload) ->
            val jsonString = payload.toString(Charsets.UTF_8)
            val networkMessage = Json.decodeFromString<NetworkMessage>(jsonString)

            when (networkMessage.type) {
                MESSAGE_TYPE_DATA -> {
                    // This is a bit of a hack, but we need to get the source node ID from the payload.
                    // In the real app, this would be part of the HttpRequestWrapper.
                    // For the simulation, we'll just use the endpointId.
                    nodeHopCounts[endpointId] = Pair(networkMessage.hopCount.toInt(), System.currentTimeMillis())
                }
                MESSAGE_TYPE_GOSSIP_PEER_LIST -> {
                    val theirPeers = networkMessage.payloadContent.split(",").filter { it.isNotBlank() }
                    neighborPeerLists[endpointId] = theirPeers
                }
            }
        }
    }

    private fun CoroutineScope.startGossip() = launch {
        while (true) {
            delay(GOSSIP_INTERVAL_MS)
            val peers = connectionManager.connectedPeers.value.toList()
            if (peers.isEmpty()) continue
            val data = peers.joinToString(",").toByteArray(Charsets.UTF_8)
            val messageId = UUID.randomUUID()
            val networkMessage = NetworkMessage(
                type = MESSAGE_TYPE_GOSSIP_PEER_LIST,
                hopCount = 0,
                messageId = messageId.toString(),
                payloadContent = data.toString(Charsets.UTF_8)
            )
            val payload = Json.encodeToString(networkMessage).toByteArray(Charsets.UTF_8)
            connectionManager.sendPayload(peers, payload)
        }
    }

    private fun CoroutineScope.startRewiringAnalysis() = launch {
        while (true) {
            delay(REWIRING_ANALYSIS_INTERVAL_MS)
            analyzeAndPerformRewiring()
        }
    }

    private fun analyzeAndPerformRewiring() {
        log("Analyzing network for rewiring opportunities.")

        if (System.currentTimeMillis() - lastRewireTimestamp < REWIRING_COOLDOWN_MS) {
            log("Skipping rewiring analysis due to cooldown.")
            return
        }

        val myPeers = connectionManager.connectedPeers.value
        if (myPeers.size < 2) {
            log("Not enough peers to analyze for rewiring.")
            return
        }

        // Find redundant local connections (triangles)
        var redundantPeer: String? = null
        for (peerA in myPeers) {
            val peersOfPeerA = neighborPeerLists[peerA]?.toSet() ?: continue
            for (peerB in myPeers) {
                if (peerA != peerB && peersOfPeerA.contains(peerB)) {
                    redundantPeer = peerB
                    log("Found redundant connection: We are connected to $peerA and $peerB, and they are connected to each other.")
                    break
                }
            }
            if (redundantPeer != null) break
        }

        if (redundantPeer == null) {
            log("No redundant local connections found.")
            return
        }

        // Find the most distant node we know of
        val now = System.currentTimeMillis()
        val mostDistantNodeEntry = nodeHopCounts.entries
            .filter { entry ->
                val nodeId = entry.key
                val (_, timestamp) = entry.value
                nodeId != endpointName && !myPeers.contains(nodeId) && (now - timestamp < NODE_HOP_COUNT_EXPIRY_MS)
            }
            .maxByOrNull { it.value.first }

        val mostDistantNodeId = mostDistantNodeEntry?.key

        if (mostDistantNodeId == null) {
            log("No distant nodes found to rewire to.")
            return
        }

        log("PERFORMING REWIRING: Dropping redundant peer $redundantPeer and connecting to distant node $mostDistantNodeId (hop count: ${mostDistantNodeEntry.value.first})")
        connectionManager.disconnectFrom(redundantPeer)
        connectionManager.connectTo(mostDistantNodeId)
        lastRewireTimestamp = System.currentTimeMillis()
    }

    private fun CoroutineScope.cleanupNodeHopCounts() = launch {
        while (true) {
            delay(NODE_HOP_COUNT_EXPIRY_MS / 2) // Clean up twice as often as expiry
            val now = System.currentTimeMillis()
            nodeHopCounts.entries.removeIf { (_, pair) ->
                now - pair.second > NODE_HOP_COUNT_EXPIRY_MS
            }
            log("Cleaned up expired node hop counts. Current size: ${nodeHopCounts.size}")
        }
    }

    fun stop() {
        log("TopologyOptimizer.stop()")
        scope.cancel()
    }
}
