package info.benjaminhill.localmesh.mesh

import android.content.Context
import info.benjaminhill.localmesh.logic.FileChunk
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.AssetManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the reassembly of file chunks received over the network.
 *
 * This class is responsible for:
 * - Storing incoming file chunks in memory.
 * - Tracking the progress of each file transfer.
 * - Reassembling the chunks into a complete file once all have been received.
 * - Saving the reassembled file to its final destination using `AssetManager`.
 * - Periodically cleaning up incomplete file transfers to prevent memory leaks.
 */
class FileReassemblyManager(
    private val context: Context,
    private val logger: AppLogger
) {
    /** fileId -> (chunkIndex -> chunkData) */
    private val fileChunks = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    /** fileId -> metadata (totalChunks, destinationPath, lastUpdatedTimestamp) */
    private val fileMetadata = ConcurrentHashMap<String, FileMetadata>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cleanupJob: Job? = null

    fun start() {
        logger.log("FileReassemblyManager started.")
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupExpiredFiles()
            }
        }
    }

    fun stop() {
        logger.log("FileReassemblyManager stopped.")
        cleanupJob?.cancel()
        scope.cancel()
        fileChunks.clear()
        fileMetadata.clear()
    }

    /**
     * Processes an incoming file chunk.
     * If the chunk completes a file, the file is reassembled and saved.
     */
    suspend fun handleFileChunk(chunk: FileChunk) {
        // Store metadata and update timestamp on first chunk
        val metadata = fileMetadata.computeIfAbsent(chunk.fileId) {
            FileMetadata(chunk.totalChunks, chunk.destinationPath, System.currentTimeMillis())
        }
        metadata.lastUpdatedTimestamp = System.currentTimeMillis()

        // Store the chunk data
        val chunksForFile = fileChunks.computeIfAbsent(chunk.fileId) { ConcurrentHashMap() }
        chunksForFile[chunk.chunkIndex] = chunk.data

        // Check if all chunks have been received
        if (chunksForFile.size == metadata.totalChunks) {
            logger.log("All ${metadata.totalChunks} chunks received for file ${chunk.fileId}. Reassembling...")
            reassembleAndSave(chunk.fileId, metadata.destinationPath, chunksForFile)
        }
    }

    private suspend fun reassembleAndSave(
        fileId: String,
        destinationPath: String,
        chunks: Map<Int, ByteArray>
    ) {
        // Remove from tracking before starting the expensive reassembly process
        fileChunks.remove(fileId)
        fileMetadata.remove(fileId)

        withContext(Dispatchers.IO) {
            logger.runCatchingWithLogging {
                val outputStream = ByteArrayOutputStream()
                // Ensure chunks are sorted by index before writing to the stream
                chunks.toSortedMap().values.forEach { chunkData ->
                    outputStream.write(chunkData)
                }
                val fileData = outputStream.toByteArray()
                AssetManager.saveFile(context, destinationPath, fileData.inputStream())
                logger.log("Successfully reassembled and saved file: $destinationPath")
            }
        }
    }

    private fun cleanupExpiredFiles() {
        val now = System.currentTimeMillis()
        val expiredFileIds = fileMetadata.filter { (_, metadata) ->
            now - metadata.lastUpdatedTimestamp > FILE_TIMEOUT_MS
        }.keys

        if (expiredFileIds.isNotEmpty()) {
            logger.log("Cleaning up ${expiredFileIds.size} expired file transfers.")
            for (fileId in expiredFileIds) {
                fileChunks.remove(fileId)
                fileMetadata.remove(fileId)
            }
        }
    }

    private data class FileMetadata(
        val totalChunks: Int,
        val destinationPath: String,
        var lastUpdatedTimestamp: Long
    )

    companion object {
        private const val FILE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        private const val CLEANUP_INTERVAL_MS = 60 * 1000L // 1 minute
    }
}
