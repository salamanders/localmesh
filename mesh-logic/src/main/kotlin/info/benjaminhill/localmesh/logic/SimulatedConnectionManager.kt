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
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    override val maxConnections: Int = 5,
    private val startWithDiscovery: Boolean = true
) : ConnectionManager {

    val id: String = UUID.randomUUID().toString()

    override val connectedPeers = MutableStateFlow(emptySet<String>())
    override val incomingPayloads = MutableSharedFlow<Pair<String, ByteArray>>()
    override val discoveredEndpoints = MutableSharedFlow<String>()

    var advertisingPayload: ByteArray = ByteArray(0)

    init {
        SimulationRegistry.register(this)
    }

    override fun startDiscovery(payload: ByteArray) {
        this.advertisingPayload = payload
        // Simulate discovering all existing peers in the network.
        coroutineScope.launch {
            SimulationRegistry.getPeers().forEach { peer ->
                if (peer.id != this@SimulatedConnectionManager.id) {
                    // In a real scenario, the discovery logic would filter based on the payload.
                    // Here, we emit all peers and let the consuming logic (e.g., BridgeService) decide.
                    this.announcePeer(peer.id)
                }
            }
        }
    }

    override fun stopDiscovery() {
        // In this simulation, discovery is a one-shot event, so this is a no-op.
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
        if (connectedPeers.value.size < maxConnections) {
            connectedPeers.value += requester.id
            requester.confirmConnection(id)
        }
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
