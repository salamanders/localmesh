package info.benjaminhill.localmesh.logic

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A platform-agnostic interface for managing peer-to-peer connections.
 */
interface ConnectionManager {
    /** The absolute maximum connections before refusing new ones. */
    val maxConnections: Int

    /** A flow that emits the set of currently connected endpoint IDs. */
    val connectedPeers: StateFlow<Set<String>>

    /** A flow that emits incoming data payloads from other peers. */
    val incomingPayloads: SharedFlow<Pair<String, ByteArray>>

    /** A flow that emits discovered endpoints. */
    val discoveredEndpoints: SharedFlow<String>

    /**
     * Starts advertising and discovery.
     * @param payload A small byte array to be broadcast to discovered endpoints.
     */
    fun startDiscovery(payload: ByteArray)

    /**
     * Stops advertising and discovery.
     */
    fun stopDiscovery()

    /**
     * Stops the connection manager, disconnecting from all peers.
     */
    fun stop()

    /**
     * Sends a data payload to a specific list of endpoints.
     * @param endpointIds The list of endpoint IDs to send the payload to.
     * @param payload The data to send.
     */
    fun sendPayload(endpointIds: List<String>, payload: ByteArray)

    /**
     * Initiates a connection to a given endpoint.
     * @param endpointId The ID of the endpoint to connect to.
     */
    fun connectTo(endpointId: String)

    /**
     * Disconnects from a given endpoint.
     * @param endpointId The ID of the endpoint to disconnect from.
     */
    fun disconnectFrom(endpointId: String)

}
