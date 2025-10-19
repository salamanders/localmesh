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
            val optimizer = TopologyOptimizer(manager, { println(it) }, "node$i")
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

        println("Two islands formed. Waiting for merge...")

        // Now, we need a mechanism to bridge the islands.
        // In a real scenario, this happens through discovery.
        // Let's simulate one node from island1 discovering a node from island2
        val nodeA = island1.first().first
        val nodeB = island2.first().first

        coroutineScope.launch {
            nodeA.discoveredEndpoints.emit(nodeB.id)
        }

        // Wait for the optimizer to connect them and for the networks to merge
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
