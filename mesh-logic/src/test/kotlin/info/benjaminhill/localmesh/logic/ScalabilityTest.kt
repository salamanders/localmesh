package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScalabilityTest {

    @Before
    fun setup() {
        // Clear the singleton registry to ensure a clean state for each test.
        SimulationRegistry.clear()
    }

    @Test
    fun `test network scalability with 27 nodes`() = runBlocking {
        val numNodes = 27
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        // Create and start nodes
        val nodes = (0 until numNodes).map { i ->
            val manager = SimulatedConnectionManager(coroutineScope)
            val optimizer =
                TopologyOptimizer(
                    connectionManager = manager,
                    log = { println("node$i: ${it.take(120)}") },
                    endpointName = manager.id,
                    targetConnections = 3,
                    gossipIntervalMs = 1000L // Fast for testing
                )
            manager.start()
            optimizer.start()
            // Stagger startups to prevent race conditions forming stable, disconnected cliques
            delay((10L..100L).random())
            manager
        }

        println("Starting scalability test with $numNodes nodes...")

        // Allow time for discovery and connection, with retries for stability
        var isolatedNodes = emptyList<SimulatedConnectionManager>()
        for (i in 1..5) {
            delay(5000)
            isolatedNodes = nodes.filter { it.connectedPeers.value.isEmpty() }
            if (isolatedNodes.isEmpty()) {
                break
            }
            println("Retry $i: Found ${isolatedNodes.size} isolated nodes. Waiting longer...")
        }

        // Verification
        val adjacencyList = nodes.associate { it.id to it.connectedPeers.value }
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        // Start BFS from the first node
        val startNodeId = nodes.first().id
        queue.add(startNodeId)
        visited.add(startNodeId)

        while (queue.isNotEmpty()) {
            val currentNodeId = queue.removeFirst()
            adjacencyList[currentNodeId]?.forEach { neighborId ->
                if (neighborId !in visited) {
                    visited.add(neighborId)
                    queue.add(neighborId)
                }
            }
        }

        println("Network analysis complete. ${visited.size} of ${nodes.size} nodes are in the main component.")

        // Assert that no node is completely isolated.
        assertTrue(
            "Found ${isolatedNodes.size} isolated nodes: ${isolatedNodes.joinToString { it.id }}",
            isolatedNodes.isEmpty()
        )
    }
}
