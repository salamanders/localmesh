package info.benjaminhill.localmesh.mesh

import info.benjaminhill.localmesh.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val GOSSIP_INTERVAL_MS = 30_000L
private const val REWIRING_ANALYSIS_INTERVAL_MS = 60_000L
private const val REWIRING_COOLDOWN_MS = 60_000L
private const val NODE_HOP_COUNT_EXPIRY_MS = 120_000L // 2 minutes

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
    private val connectionsManager: NearbyConnectionsManager,
    private val logger: AppLogger,
    private val endpointName: String,
) : TopologyOptimizerCallback {

    private val neighborPeerLists = ConcurrentHashMap<String, List<String>>()
    private val nodeHopCounts =
        ConcurrentHashMap<String, Pair<Int, Long>>() // endpointId to (hopCount, timestamp)
    private var lastRewireTimestamp = 0L

    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        logger.log("TopologyOptimizer.start()")
        scope.launch {
            startGossip()
            startRewiringAnalysis()
            cleanupNodeHopCounts()
        }
    }

    override fun onDataPayloadReceived(hopCount: Int, sourceNodeId: String) {
        nodeHopCounts[sourceNodeId] = Pair(hopCount, System.currentTimeMillis())
    }

    override fun onGossipPayloadReceived(endpointId: String, theirPeers: List<String>) {
        neighborPeerLists[endpointId] = theirPeers
    }

    private fun CoroutineScope.startGossip() = launch {
        while (true) {
            delay(GOSSIP_INTERVAL_MS)
            connectionsManager.broadcastPeerList()
        }
    }

    private fun CoroutineScope.startRewiringAnalysis() = launch {
        while (true) {
            delay(REWIRING_ANALYSIS_INTERVAL_MS)
            analyzeAndPerformRewiring()
        }
    }

    private fun analyzeAndPerformRewiring() {
        logger.log("Analyzing network for rewiring opportunities.")

        if (System.currentTimeMillis() - lastRewireTimestamp < REWIRING_COOLDOWN_MS) {
            logger.log("Skipping rewiring analysis due to cooldown.")
            return
        }

        val myPeers = connectionsManager.connectedPeerIds.toSet()
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
            logger.log("No distant nodes found to rewire to.")
            return
        }

        logger.log("PERFORMING REWIRING: Dropping redundant peer $redundantPeer and connecting to distant node $mostDistantNodeId (hop count: ${mostDistantNodeEntry.value.first})")
        connectionsManager.disconnectFromEndpoint(redundantPeer)
        connectionsManager.requestConnection(mostDistantNodeId)
        lastRewireTimestamp = System.currentTimeMillis()
    }

    private fun CoroutineScope.cleanupNodeHopCounts() = launch {
        while (true) {
            delay(NODE_HOP_COUNT_EXPIRY_MS / 2) // Clean up twice as often as expiry
            val now = System.currentTimeMillis()
            nodeHopCounts.entries.removeIf { (_, pair) ->
                now - pair.second > NODE_HOP_COUNT_EXPIRY_MS
            }
            logger.log("Cleaned up expired node hop counts. Current size: ${nodeHopCounts.size}")
        }
    }

    fun stop() {
        logger.log("TopologyOptimizer.stop()")
        scope.cancel()
    }
}
