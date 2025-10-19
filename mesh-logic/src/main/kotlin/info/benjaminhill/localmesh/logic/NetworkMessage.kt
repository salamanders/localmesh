package info.benjaminhill.localmesh.logic

import kotlinx.serialization.Serializable

/**
 * The standard wrapper for all messages sent across the mesh network.
 * This enables network-wide behaviors like duplicate detection and hop counting.
 */
@Serializable
data class NetworkMessage(
    /** Type of message, used to determine how to parse the payloadContent. */
    val type: Byte,
    /** The number of hops this message has taken. */
    val hopCount: Byte,
    /** A unique identifier for this message to prevent broadcast loops. */
    val messageId: String, // UUID as string
    /** The actual content of the message (e.g., a serialized HttpRequestWrapper or a peer list). */
    val payloadContent: String
)
