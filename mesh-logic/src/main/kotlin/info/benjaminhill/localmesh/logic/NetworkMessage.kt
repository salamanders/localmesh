package info.benjaminhill.localmesh.logic

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A chunk of a file being transferred over the network.
 */
@Serializable
data class FileChunk(
    /** Unique ID for the entire file transfer. */
    val fileId: String,
    /** Where to save the reassembled file. */
    val destinationPath: String,
    /** The index of this chunk. */
    val chunkIndex: Int,
    /** The total number of chunks for this file. */
    val totalChunks: Int,
    /** The actual data of the chunk. Note: Requires special handling for ByteArray serialization. */
    @Serializable(with = ByteArraySerializer::class)
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileChunk

        if (chunkIndex != other.chunkIndex) return false
        if (fileId != other.fileId) return false
        if (destinationPath != other.destinationPath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkIndex
        result = 31 * result + fileId.hashCode()
        result = 31 * result + destinationPath.hashCode()
        return result
    }
}

/**
 * The standard wrapper for all messages sent across the mesh network.
 * This enables network-wide behaviors like duplicate detection and hop counting.
 */
@Serializable
data class NetworkMessage(
    /** A unique identifier for this message to prevent broadcast loops. */
    val messageId: String = UUID.randomUUID().toString(),
    /** The number of hops this message has taken. */
    val hopCount: Int = 0,
    /** The HTTP request payload, if this is an HTTP message. */
    val httpRequest: HttpRequestWrapper? = null,
    /** The file chunk payload, if this is a file transfer message. */
    val fileChunk: FileChunk? = null,
    /** The peer list of the sending node, for topology gossip. */
    val gossip: Map<String, List<String>>? = null
)
