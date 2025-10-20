package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A simulated, in-memory implementation of the [ConnectionManager] for testing.
 * It uses a singleton [SimulationRegistry] to manage interactions between instances.
 */
class SimulatedConnectionManager(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : ConnectionManager {

    val id: String = UUID.randomUUID().toString()

    override val connectedPeers = MutableStateFlow(emptySet<String>())
    override val incomingPayloads = MutableSharedFlow<Pair<String, ByteArray>>()
    override val discoveredEndpoints = MutableSharedFlow<String>()

    init {
        SimulationRegistry.register(this)
    }

    override fun start() {
        coroutineScope.launch {
            SimulationRegistry.getPeers().forEach { peer ->
                if (peer.id != this@SimulatedConnectionManager.id) {
                    discoveredEndpoints.emit(peer.id)
                }
            }
        }
    }

    override fun stop() {
        SimulationRegistry.unregister(this)
        connectedPeers.value.forEach { disconnectFrom(it) }
        connectedPeers.value = emptySet()
    }

    override fun sendPayload(endpointIds: List<String>, payload: ByteArray) {
        endpointIds.forEach { endpointId ->
            SimulationRegistry.getPeer(endpointId)?.handleIncomingPayload(id, payload)
        }
    }

    override fun connectTo(endpointId: String) {
        SimulationRegistry.getPeer(endpointId)?.handleConnectionRequest(this)
    }

    override fun disconnectFrom(endpointId: String) {
        SimulationRegistry.getPeer(endpointId)?.handleDisconnection(id)
        connectedPeers.value -= endpointId
    }

    internal fun handleIncomingPayload(senderId: String, payload: ByteArray) {
        coroutineScope.launch {
            incomingPayloads.emit(senderId to payload)
        }
    }

    internal fun handleConnectionRequest(requester: SimulatedConnectionManager) {
        // For simulation, we'll just auto-accept. Real logic would check connection limits.
        connectedPeers.value += requester.id
        requester.confirmConnection(id)
    }

    internal fun confirmConnection(peerId: String) {
        connectedPeers.value += peerId
    }

    internal fun handleDisconnection(peerId: String) {
        connectedPeers.value -= peerId
    }

    /** Called by the registry when a new peer joins the simulation. */
    internal fun announcePeer(peerId: String) {
        coroutineScope.launch {
            discoveredEndpoints.emit(peerId)
        }
    }

    override fun enterDiscoveryMode() {
        // Simulate finding all other peers in the network.
        coroutineScope.launch {
            SimulationRegistry.getPeers().forEach { peer ->
                if (peer.id != this@SimulatedConnectionManager.id && peer.id !in connectedPeers.value) {
                    discoveredEndpoints.emit(peer.id)
                }
            }
        }
    }
}

/**
 * A singleton object that acts as the "network" for all [SimulatedConnectionManager] instances.
 */
internal object SimulationRegistry {
    private val peers = ConcurrentHashMap<String, SimulatedConnectionManager>()

    fun register(newPeer: SimulatedConnectionManager) {
        // Announce the new peer to all existing peers.
        peers.values.forEach { existingPeer ->
            existingPeer.announcePeer(newPeer.id)
        }
        // Add the new peer to the registry.
        peers[newPeer.id] = newPeer
    }

    fun unregister(peer: SimulatedConnectionManager) {
        peers.remove(peer.id)
    }

    fun clear() {
        peers.clear()
    }

    fun getPeer(id: String): SimulatedConnectionManager? = peers[id]

    fun getPeers(): Collection<SimulatedConnectionManager> = peers.values
}
