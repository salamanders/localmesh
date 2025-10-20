package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.collections.ArrayDeque

class IslandMergingTest {

    @Before
    fun setup() {
        // Clear the singleton registry to ensure a clean state for each test.
        SimulationRegistry.clear()
    }

    @Test
    fun `test network islands can merge`() = runBlocking {
        val numNodes = 6
        val cliqueSize = 3
        val coroutineScope = CoroutineScope(Dispatchers.Default)

        // Create nodes
        val nodes = (0 until numNodes).map { i ->
            val manager = SimulatedConnectionManager(coroutineScope, startWithDiscovery = false)
            val optimizer = TopologyOptimizer(
                connectionManager = manager,
                log = { println("node$i: ${it.take(120)}") },
                endpointName = "node$i",
                targetConnections = cliqueSize - 1,
                gossipIntervalMs = 500L, // Fast for testing
                initialIslandDiscoveryDelayMs = 500L,
                islandDiscoveryAnalysisIntervalMs = 1000L
            )
            manager to optimizer
        }

        // Start all nodes
        nodes.forEach { (manager, optimizer) ->
            manager.start()
            optimizer.start()
        }

        // Form two cliques
        val island1 = nodes.subList(0, cliqueSize)
        val island2 = nodes.subList(cliqueSize, numNodes)

        formClique(island1)
        formClique(island2)

        // Give them time to connect
        withTimeout(5000L) {
            while (true) {
                val island1Ready = island1.all { (manager, _) -> manager.connectedPeers.value.size == cliqueSize - 1 }
                val island2Ready = island2.all { (manager, _) -> manager.connectedPeers.value.size == cliqueSize - 1 }
                if (island1Ready && island2Ready) {
                    break
                }
                delay(100) // Poll every 100ms
            }
        }

        // Verify two separate cliques
        island1.forEach { (manager, _) ->
            assertEquals(cliqueSize - 1, manager.connectedPeers.value.size)
        }
        island2.forEach { (manager, _) ->
            assertEquals(cliqueSize - 1, manager.connectedPeers.value.size)
        }

        println("Two islands formed. Waiting for auto-merge...")

        // Wait for the optimizer's island discovery logic to trigger and merge the networks
        delay(6000)

        // Verify that all nodes are now part of a single network
        assert(isNetworkConnected(nodes)) { "Network is not fully connected after merge attempt." }

        println("Islands merged successfully.")
    }

    private fun formClique(nodes: List<Pair<SimulatedConnectionManager, TopologyOptimizer>>) {
        nodes.forEach { (managerA, _) ->
            nodes.forEach { (managerB, _) ->
                if (managerA.id != managerB.id) {
                    managerA.connectTo(managerB.id)
                }
            }
        }
    }

    private fun isNetworkConnected(nodes: List<Pair<SimulatedConnectionManager, TopologyOptimizer>>): Boolean {
        if (nodes.isEmpty()) return true

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        val nodeMap = nodes.associate { (manager, _) -> manager.id to manager }

        val startNodeId = nodes.first().first.id
        queue.add(startNodeId)
        visited.add(startNodeId)

        while (queue.isNotEmpty()) {
            val currentNodeId = queue.removeFirst()
            val currentNode = nodeMap[currentNodeId] ?: continue

            currentNode.connectedPeers.value.forEach { peerId ->
                if (peerId !in visited) {
                    visited.add(peerId)
                    queue.add(peerId)
                }
            }
        }
        return visited.size == nodes.size
    }
}
