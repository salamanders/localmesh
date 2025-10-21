package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// How often to check for opportunities to improve the network topology.
private const val REWIRING_ANALYSIS_INTERVAL_MS = 1 * 60 * 1_000L

// How often to check for opportunities to merge network islands.
private const val ISLAND_DISCOVERY_ANALYSIS_INTERVAL_MS = 5 * 60 * 1_000L

// How long to wait after a rewiring before attempting another.
private const val REWIRING_COOLDOWN_MS = 1 * 60 * 1_000L

// How long to remember a node's hop count before it's considered stale.
private const val NODE_HOP_COUNT_EXPIRY_MS = 2 * 60 * 1_000L

/**
 * The platform-agnostic "brains" of the self-optimizing mesh network.
 *
 * ## What it does
 * - Resides in the pure-Kotlin `mesh-logic` module, independent of the Android framework.
 * - Consumes a [ConnectionManager] implementation to remain platform-agnostic.
 * - Passively collects hop counts from all incoming `NetworkMessage` traffic to build a mental map of the network topology.
 * - Periodically analyzes this map to find and execute topology improvements.
 * - Contains the core heuristic to identify redundant local connections and distant nodes, issuing
 *   commands to the `ConnectionManager` (`connectTo`, `disconnectFrom`) to optimize the network
 *   into a "small-world" topology.
 * - Contains logic to periodically drop a connection to search for and merge with isolated network "islands".
 *
 * ## What it doesn't do
 * - It does not actively gossip or create its own network traffic. It is a passive observer.
 * - It does not directly handle any platform-specific networking APIs.
 *
 * ## Comparison to other classes
 * - **[ConnectionManager]:** This class is the "brains", while the `ConnectionManager` is the
 *   abstract "hands" that this class directs.
 */
class TopologyOptimizer(
    private val connectionManager: ConnectionManager,
    private val log: (String) -> Unit,
    private val endpointName: String,
    private val targetConnections: Int = TARGET_CONNECTIONS,
    private val initialIslandDiscoveryDelayMs: Long = 30_000L,
    private val islandDiscoveryAnalysisIntervalMs: Long = ISLAND_DISCOVERY_ANALYSIS_INTERVAL_MS,
) {
    companion object {
        const val TARGET_CONNECTIONS = 3
    }

    private val nodeHopCounts =
        ConcurrentHashMap<String, Pair<Int, Long>>() // endpointId to (hopCount, timestamp)
    private var lastRewireTimestamp = 0L

    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        log("TopologyOptimizer.start()")
        scope.launch {
            listenForDiscoveredEndpoints()
            listenForIncomingPayloads()
            startRewiringAnalysis()
            startIslandDiscoveryAnalysis()
            cleanupNodeHopCounts()
        }
    }

    private fun CoroutineScope.startIslandDiscoveryAnalysis() = launch {
        delay(initialIslandDiscoveryDelayMs)
        while (true) {
            delay(islandDiscoveryAnalysisIntervalMs)
            analyzeAndPerformIslandDiscovery()
        }
    }

    private fun analyzeAndPerformIslandDiscovery() {
        log("Analyzing network for potential islands.")
        val myPeers = connectionManager.connectedPeers.value
        if (myPeers.size < targetConnections) {
            log("Not enough connections to justify island discovery. Skipping.")
            return
        }

        // For simplicity, we'll just drop a random peer to see if we can find a new island.
        val peerToDrop = myPeers.randomOrNull()
        if (peerToDrop == null) {
            log("No peer to drop for island discovery.")
            return
        }

        log("Initiating island discovery: Dropping peer '$peerToDrop' to search for new islands.")
        connectionManager.disconnectFrom(peerToDrop)
        connectionManager.enterDiscoveryMode()
    }

    private fun CoroutineScope.listenForDiscoveredEndpoints() = launch {
        connectionManager.discoveredEndpoints.collect { endpointId ->
            if (connectionManager.connectedPeers.value.size < targetConnections) {
                log("Attempting to connect to discovered endpoint $endpointId")
                connectionManager.connectTo(endpointId)
            }
        }
    }

    private fun CoroutineScope.listenForIncomingPayloads() = launch {
        connectionManager.incomingPayloads.collect { (_, payload) ->
            val jsonString = payload.toString(Charsets.UTF_8)
            val networkMessage = Json.decodeFromString<NetworkMessage>(jsonString)
            networkMessage.httpRequest?.let {
                nodeHopCounts[it.sourceNodeId] =
                    Pair(networkMessage.hopCount, System.currentTimeMillis())
            }
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
        if (myPeers.isEmpty()) {
            log("No peers to rewire.")
            return
        }

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

        // Drop a random peer to make space for a better connection.
        val peerToDrop = myPeers.random()
        log("PERFORMING REWIRING: Dropping peer $peerToDrop and connecting to distant node $mostDistantNodeId (hop count: ${mostDistantNodeEntry.value.first})")
        connectionManager.disconnectFrom(peerToDrop)
        connectionManager.connectTo(mostDistantNodeId)
        lastRewireTimestamp = System.currentTimeMillis()
    }

    private fun CoroutineScope.cleanupNodeHopCounts() = launch {
        while (true) {
            delay(NODE_HOP_COUNT_EXPIRY_MS / 2)
            val now = System.currentTimeMillis()
            nodeHopCounts.entries.removeIf { (_, pair) ->
                now - pair.second > NODE_HOP_COUNT_EXPIRY_MS
            }
        }
    }

    fun stop() {
        log("TopologyOptimizer.stop()")
        scope.cancel()
    }
}
