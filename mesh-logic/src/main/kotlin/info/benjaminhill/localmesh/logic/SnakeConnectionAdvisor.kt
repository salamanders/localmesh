package info.benjaminhill.localmesh.logic

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the state and connection logic for a linear "snake" topology.
 *
 * This class is a simple state manager, not a coroutine-based service. It helps an orchestrator
 * (like `BridgeService`) make decisions about when to discover and connect to peers based on

 * a strict rule: a node can have a maximum of two connections.
 *
 * Its primary responsibilities are:
 * - Tracking the current number of active connections.
 * - Deciding whether the device should be actively discovering new peers.
 * - Preventing connections that would form a loop in the snake by maintaining a short-lived
 *   cache of node IDs that are already part of the snake.
 * - Creating and processing gossip messages to share the snake's topology with other nodes.
 */
class SnakeConnectionAdvisor(
    private val ownEndpointId: String,
    private val log: (String) -> Unit,
) {
    private var connectionCount = 0

    // Cache of node IDs known to be in the current snake.
    // Maps endpointId to the timestamp of when it was last seen.
    private val snakeNodeCache = ConcurrentHashMap<String, Long>()

    companion object {
        // How long to cache snake node IDs from gossip messages before they are considered stale.
        private const val SNAKE_NODE_CACHE_TTL_MS = 1 * 60 * 1000L
    }

    /**
     * Updates the advisor's internal state with the current number of connections.
     */
    fun setConnectionCount(count: Int) {
        log("Connection count updated from $connectionCount to $count")
        connectionCount = count
    }

    /**
     * Determines if the device should be in discovery mode.
     * Returns true if the device is an "endpoint" (1 connection) or "isolated" (0 connections).
     */
    fun shouldBeDiscovering(): Boolean = connectionCount < 2

    /**
     * Decides if a connection to a given peer is allowed.
     *
     * A connection is allowed only if:
     * 1. The device is not already at its max connection limit (2).
     * 2. The peer is not already part of the current snake (to prevent loops).
     *
     * @param peerId The endpoint ID of the potential peer.
     * @return `true` if a connection is permitted, `false` otherwise.
     */
    fun canConnectToPeer(peerId: String): Boolean {
        if (connectionCount >= 2) {
            log("CANNOT connect to $peerId: Already at max connections ($connectionCount).")
            return false
        }
        cleanupExpiredSnakeNodes()
        if (snakeNodeCache.containsKey(peerId)) {
            log("CANNOT connect to $peerId: Peer is already in the snake cache.")
            return false
        }
        log("CAN connect to $peerId: Connection count is $connectionCount and peer is not in snake cache.")
        return true
    }

    /**
     * Creates a gossip message containing all known node IDs in the snake.
     * This includes the device's own ID.
     */
    fun createGossipMessage(): NetworkMessage {
        cleanupExpiredSnakeNodes()
        val snakeNodes = snakeNodeCache.keys.toMutableSet()
        snakeNodes.add(ownEndpointId)
        return NetworkMessage(
            gossip = mapOf("snake" to snakeNodes.toList())
        )
    }

    /**
     * Processes an incoming gossip message, updating the internal cache of snake node IDs.
     */
    fun handleGossipMessage(message: NetworkMessage) {
        val receivedSnakeNodes = message.gossip?.get("snake") ?: return
        log("Received snake gossip with ${receivedSnakeNodes.size} nodes.")
        val now = System.currentTimeMillis()
        receivedSnakeNodes.forEach { nodeId ->
            // Update the timestamp for each node received in the gossip.
            snakeNodeCache[nodeId] = now
        }
    }

    /**
     * Removes stale node IDs from the cache.
     */
    private fun cleanupExpiredSnakeNodes() {
        val now = System.currentTimeMillis()
        val expiredNodes = snakeNodeCache.entries
            .filter { (_, timestamp) -> now - timestamp > SNAKE_NODE_CACHE_TTL_MS }
            .map { it.key }

        if (expiredNodes.isNotEmpty()) {
            log("Removing ${expiredNodes.size} expired nodes from snake cache: $expiredNodes")
            expiredNodes.forEach { snakeNodeCache.remove(it) }
        }
    }
}
