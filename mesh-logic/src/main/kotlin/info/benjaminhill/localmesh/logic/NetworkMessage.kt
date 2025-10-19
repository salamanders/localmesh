package info.benjaminhill.localmesh.logic

import kotlinx.serialization.Serializable

/**
 * The standard wrapper for all messages sent across the mesh network.
 * This enables network-wide behaviors like duplicate detection and hop counting.
 */
@Serializable
data class NetworkMessage(
    /** The number of hops this message has taken. */
    val hopCount: Byte,
    /** A unique identifier for this message to prevent broadcast loops. */
    val messageId: String, // UUID as string
    /** The HTTP request payload, if this is an HTTP message. */
    val httpRequest: HttpRequestWrapper? = null,
    /** The gossip payload, if this is a gossip message. */
    val gossip: Gossip? = null
)

@Serializable
data class Gossip(
    val peerList: List<String>
)