package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
            val manager = SimulatedConnectionManager(coroutineScope)
            val optimizer = TopologyOptimizer(
                connectionManager = manager,
                log = { println("node$i: ${it.take(120)}") },
                endpointName = "node$i",
                islandDiscoveryAnalysisIntervalMs = 2000L, // Fast for testing
                targetConnections = cliqueSize - 1,
                gossipIntervalMs = 1000L // Fast for testing
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
        delay(1000)

        // Verify two separate cliques
        island1.forEach { (manager, _) ->
            assertEquals(cliqueSize - 1, manager.connectedPeers.value.size)
        }
        island2.forEach { (manager, _) ->
            assertEquals(cliqueSize - 1, manager.connectedPeers.value.size)
        }

        println("Two islands formed. Waiting for auto-merge...")

        // Wait for the optimizer's island discovery logic to trigger and merge the networks
        delay(5000)

        // Verify that all nodes are now part of a single network
        // A simple check is that each node has at least `cliqueSize-1` connections,
        // and at least one node has more.
        var totalConnections = 0
        nodes.forEach { (manager, _) ->
            totalConnections += manager.connectedPeers.value.size
        }

        // In a merged 6-node network, we expect more than (2*2 + 2*2) = 8 connections
        assert(totalConnections > (cliqueSize - 1) * numNodes)

        println("Islands merged successfully. Total connections: $totalConnections")
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
}
