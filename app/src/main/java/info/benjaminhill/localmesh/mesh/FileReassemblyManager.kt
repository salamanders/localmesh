package info.benjaminhill.localmesh.mesh

import android.content.Context
import info.benjaminhill.localmesh.logic.FileChunk
import info.benjaminhill.localmesh.util.AssetManager
import info.benjaminhill.localmesh.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the reassembly of file chunks received over the network.
 *
 * This class is responsible for the following:
 * - Storing incoming file chunks in memory.
 * - Reassembling the complete file once all chunks for a given file ID have been received.
 * - Saving the reassembled file to the device's storage using the `AssetManager`.
 * - A timeout mechanism to discard incomplete files after a certain period (5 minutes) to prevent memory leaks.
 */
class FileReassemblyManager(
    private val context: Context,
    private val logger: AppLogger
) {

    /**
     * Stores the chunks for each file being transferred.
     * The outer map's key is the `fileId`.
     * The inner map's key is the `chunkIndex`, and the value is the `FileChunk` object.
     */
    private val fileChunks = ConcurrentHashMap<String, ConcurrentHashMap<Int, FileChunk>>()

    /**
     * Stores the last time a chunk was received for a given file ID.
     * Used for the timeout mechanism.
     */
    private val fileLastReceived = ConcurrentHashMap<String, Long>()

    /**
     * The CoroutineScope for managing the timeout mechanism.
     */
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                // Check for timed-out files every minute.
                delay(60_000)
                val now = System.currentTimeMillis()
                fileLastReceived.forEach { (fileId, lastReceived) ->
                    if (now - lastReceived > TIMEOUT_MS) {
                        fileChunks.remove(fileId)
                        fileLastReceived.remove(fileId)
                        logger.log("Timed out and discarded incomplete file: $fileId")
                    }
                }
            }
        }
    }

    /**
     * Adds a file chunk to the reassembly buffer.
     * If the chunk is the last one needed to complete the file, it reassembles the file
     * and saves it to storage.
     *
     * @param chunk The `FileChunk` to add.
     */
    fun addChunk(chunk: FileChunk) {
        fileLastReceived[chunk.fileId] = System.currentTimeMillis()
        val chunks = fileChunks.getOrPut(chunk.fileId) { ConcurrentHashMap() }
        chunks[chunk.chunkIndex] = chunk

        if (chunks.size == chunk.totalChunks) {
            reassembleAndSave(chunk.fileId, chunks)
        }
    }

    /**
     * Reassembles the file from its chunks and saves it to storage.
     *
     * @param fileId The ID of the file to reassemble.
     * @param chunks The map of chunks for the file.
     */
    private fun reassembleAndSave(fileId: String, chunks: Map<Int, FileChunk>) {
        // Ensure the file is not already being reassembled.
        fileChunks.remove(fileId)
        fileLastReceived.remove(fileId)

        val destinationPath = chunks[0]?.destinationPath
        if (destinationPath == null) {
            logger.e("Cannot reassemble file with no destination path: $fileId")
            return
        }

        logger.log("Reassembling file '$destinationPath' ($fileId)")
        val sortedChunks = chunks.toSortedMap().values
        val combinedBytes = sortedChunks.map { it.data }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        AssetManager.saveFile(context, destinationPath, combinedBytes.inputStream())
        logger.log("Successfully reassembled and saved '$destinationPath'")
    }

    companion object {
        private const val TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
}
