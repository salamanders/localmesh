package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// How often to share peer lists with neighbors.
private const val GOSSIP_INTERVAL_MS = 30 * 1_000L

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
 */
class TopologyOptimizer(
    private val connectionManager: ConnectionManager,
    private val log: (String) -> Unit,
    private val endpointName: String,
    private val targetConnections: Int = TARGET_CONNECTIONS,
    private val gossipIntervalMs: Long = GOSSIP_INTERVAL_MS,
    private val initialIslandDiscoveryDelayMs: Long = 30_000L,
    private val islandDiscoveryAnalysisIntervalMs: Long = ISLAND_DISCOVERY_ANALYSIS_INTERVAL_MS,
) {
    companion object {
        // The number of connections to proactively seek.
        // Per P2P_DOCS.md, the practical limit for stable connections is 3-4.
        const val TARGET_CONNECTIONS = 3
    }

    private val neighborPeerLists = ConcurrentHashMap<String, List<String>>()
    private val nodeHopCounts =
        ConcurrentHashMap<String, Pair<Int, Long>>() // endpointId to (hopCount, timestamp)
    private var lastRewireTimestamp = 0L

    private val scope = CoroutineScope(Dispatchers.IO)
    private val connectionMutex = Mutex()

    fun start() {
        log("TopologyOptimizer.start()")
        scope.launch {
            listenForDiscoveredEndpoints()
            listenForConnectionChanges()
            listenForIncomingPayloads()
            startGossip()
            startRewiringAnalysis()
            startIslandDiscoveryAnalysis()
            cleanupNodeHopCounts()
        }
    }

    private fun CoroutineScope.listenForConnectionChanges() = launch {
        connectionManager.connectedPeers.collect { currentPeers ->
            // Remove any peers from our maps that we are no longer connected to.
            val removedPeers = neighborPeerLists.keys.filter { it !in currentPeers }
            if (removedPeers.isNotEmpty()) {
                log("Peers disconnected: $removedPeers. Cleaning up maps.")
                removedPeers.forEach {
                    neighborPeerLists.remove(it)
                    nodeHopCounts.remove(it)
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun CoroutineScope.startGossip() = launch {
        while (true) {
            delay(gossipIntervalMs)
            val myPeers = connectionManager.connectedPeers.value.toList()
            if (myPeers.isNotEmpty()) {
                val gossipMessage = NetworkMessage(
                    gossip = mapOf(endpointName to myPeers)
                )
                val payload = Cbor.encodeToByteArray(gossipMessage)
                connectionManager.sendPayload(myPeers, payload)
                log("Gossiped peer list to ${myPeers.size} peers.")
            }
        }
    }

    private fun CoroutineScope.startIslandDiscoveryAnalysis() = launch {
        // Don't start island discovery immediately, let the network settle.
        delay(initialIslandDiscoveryDelayMs)
        while (true) {
            delay(islandDiscoveryAnalysisIntervalMs)
            analyzeAndPerformIslandDiscovery()
        }
    }

    private suspend fun analyzeAndPerformIslandDiscovery() = connectionMutex.withLock {
        log("Analyzing network for potential islands.")

        val myPeers = connectionManager.connectedPeers.value
        if (myPeers.size < targetConnections) {
            log("Not enough connections to justify island discovery. Skipping.")
            return@withLock
        }

        val redundantPeer = findRedundantPeer()
        if (redundantPeer == null) {
            log("No redundant peer found to drop for island discovery. Skipping.")
            return@withLock
        }

        log("Initiating island discovery: Dropping redundant peer '$redundantPeer' to search for new islands.")
        connectionManager.disconnectFrom(redundantPeer)
        connectionManager.enterDiscoveryMode()
    }

    private fun CoroutineScope.listenForDiscoveredEndpoints() = launch {
        connectionManager.discoveredEndpoints.collect { endpointId ->
            connectionMutex.withLock {
                if (connectionManager.connectedPeers.value.size <= targetConnections) {
                    log("Attempting to connect to discovered endpoint $endpointId")
                    connectionManager.connectTo(endpointId)
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun CoroutineScope.listenForIncomingPayloads() = launch {
        connectionManager.incomingPayloads.collect { (endpointId, payload) ->
            try {
                val networkMessage = Cbor.decodeFromByteArray<NetworkMessage>(payload)

                // Update hop counts from any message that has a source
                networkMessage.httpRequest?.let {
                    nodeHopCounts[it.sourceNodeId] =
                        Pair(networkMessage.hopCount, System.currentTimeMillis())
                }

                // Process gossip payload
                networkMessage.gossip?.let { gossip ->
                    gossip.forEach { (nodeId, peers) ->
                        neighborPeerLists[nodeId] = peers
                        log("Received gossip from $nodeId, knows ${peers.size} peers.")
                    }
                }
            } catch (e: Exception) {
                log("Error decoding network message from $endpointId: ${e.message}")
            }
        }
    }

    private fun CoroutineScope.startRewiringAnalysis() = launch {
        while (true) {
            delay(REWIRING_ANALYSIS_INTERVAL_MS)
            analyzeAndPerformRewiring()
        }
    }


    private suspend fun analyzeAndPerformRewiring() = connectionMutex.withLock {
        log("Analyzing network for rewiring opportunities.")

        if (System.currentTimeMillis() - lastRewireTimestamp < REWIRING_COOLDOWN_MS) {
            log("Skipping rewiring analysis due to cooldown.")
            return@withLock
        }

        val redundantPeer = findRedundantPeer()
        if (redundantPeer == null) {
            log("No redundant local connections found for rewiring.")
            return@withLock
        }

        val myPeers = connectionManager.connectedPeers.value
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
            return@withLock
        }

        log("PERFORMING REWIRING: Dropping redundant peer $redundantPeer and connecting to distant node $mostDistantNodeId (hop count: ${mostDistantNodeEntry.value.first})")
        connectionManager.disconnectFrom(redundantPeer)
        connectionManager.connectTo(mostDistantNodeId)
        lastRewireTimestamp = System.currentTimeMillis()
    }

    private fun findRedundantPeer(): String? {
        val myPeers = connectionManager.connectedPeers.value
        if (myPeers.size < 2) {
            return null
        }
        for (peerA in myPeers) {
            val peersOfPeerA = neighborPeerLists[peerA]?.toSet() ?: continue
            for (peerB in myPeers) {
                if (peerA != peerB && peersOfPeerA.contains(peerB)) {
                    log("Found redundant connection: We are connected to $peerA and $peerB, and they are connected to each other.")
                    return peerB
                }
            }
        }
        return null
    }


    private fun CoroutineScope.cleanupNodeHopCounts() = launch {
        while (true) {
            delay(NODE_HOP_COUNT_EXPIRY_MS / 2) // Clean up twice as often as expiry
            val now = System.currentTimeMillis()
            // To avoid ConcurrentModificationException, we identify expired keys first,
            // then remove them in a separate step.
            val expiredKeys = nodeHopCounts.entries
                .filter { (_, pair) -> now - pair.second > NODE_HOP_COUNT_EXPIRY_MS }
                .map { it.key }

            if (expiredKeys.isNotEmpty()) {
                log("Cleaning up expired node hop counts for: $expiredKeys")
                expiredKeys.forEach { nodeHopCounts.remove(it) }
                log("Cleaned up expired node hop counts. Current size: ${nodeHopCounts.size}")
            }
        }
    }

    fun stop() {
        log("TopologyOptimizer.stop()")
        scope.cancel()
    }
}
