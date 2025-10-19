package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/**
 * The platform-agnostic "brains" of the self-optimizing mesh network.
 *
 * ## What it does
 * - Resides in the pure-Kotlin `mesh-logic` module, independent of the Android framework.
 * - Consumes a [ConnectionManager] implementation to remain platform-agnostic.
 * - Collects `incomingPayloads` from the `ConnectionManager` to analyze network traffic (both
 *   application data and gossip messages).
 * - Manages timers for periodic peer-list gossiping and rewiring analysis.
 * - Maintains a map of the known network topology, including node hop counts and neighbor lists.
 * - Contains the core heuristic to identify redundant local connections and distant nodes, issuing
 *   commands to the `ConnectionManager` (`connectTo`, `disconnectFrom`) to optimize the network
 *   into a "small-world" topology.
 *
 * ## What it doesn't do
 * - It does not directly handle any platform-specific networking APIs.
 * - It does not know whether it is running in a simulation or on a real Android device.
 *
 * ## Comparison to other classes
 * - **[ConnectionManager]:** This class is the "brains", while the `ConnectionManager` is the
 *   abstract "hands" that this class directs.
 * - **[BridgeService]:** This class is a component that is instantiated and managed by the
 *   `BridgeService` on Android.
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
                    nodeHopCounts[endpointId] =
                        Pair(networkMessage.hopCount.toInt(), System.currentTimeMillis())
                }

                MESSAGE_TYPE_GOSSIP_PEER_LIST -> {
                    val theirPeers =
                        networkMessage.payloadContent.split(",").filter { it.isNotBlank() }
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
