package info.benjaminhill.localmesh.logic

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class FileChunk(
    val fileId: String,      // Unique ID for the entire file transfer
    val destinationPath: String, // Where to save the reassembled file
    val chunkIndex: Int,
    val totalChunks: Int,
    @Serializable(with = ByteArraySerializer::class)
    val data: ByteArray      // Note: Requires special handling for ByteArray serialization
)

@Serializable
data class NetworkMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val hopCount: Int = 0,
    val httpRequest: HttpRequestWrapper? = null,
    val fileChunk: FileChunk? = null
)
