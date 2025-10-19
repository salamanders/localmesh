package info.benjaminhill.localmesh.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Unpacks the assets folder into a Ktor friendly normal folder.
 * Handles storing new files that override the shipped default files, and retrieving the cached files and falling back to the original files if none exist.
 *
 * Note: for Ktor to use "staticFiles", these have to all exist in the same place, we aren't going to "intercept" the request any more.
 */
object AssetManager {

    private const val TAG = "AssetManager"
    const val INCLUDED_ASSET_NAME = "web"
    const val UNPACKED_FILES_DIR = "web"

    const val RESPECT_EXISTING_FILES = false

    /** Copies the defaults to the folder, only if they don't already exist (safe to run every startup, won't clobber distributed files) */
    fun unpack(context: Context) {
        val staticDir = getFilesDir(context)
        Log.d(TAG, "Unpacking assets to ${staticDir.absolutePath}")
        copyAssetDir(context, assetDir = INCLUDED_ASSET_NAME, destDir = staticDir)
    }

    fun getFilesDir(context: Context): File =
        File(context.filesDir, UNPACKED_FILES_DIR)

    fun getFolders(context: Context): List<String> {
        val staticWebDir = getFilesDir(context)
        return staticWebDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: emptyList()
    }

    fun saveFile(context: Context, destinationPath: String, inputStream: InputStream) {
        val destFile = File(getFilesDir(context), destinationPath)
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        Log.d(TAG, "Saved file to ${destFile.absolutePath}")
    }

    fun getRedirectPath(context: Context, path: String): String? {
        if (path.isEmpty()) {
            return null
        }
        val file = File(getFilesDir(context), path)
        return when {
            !file.isDirectory -> null
            File(file, "index.html").exists() -> "$path/index.html"
            else -> {
                Log.d(
                    TAG,
                    "Tried to navigate to a directory without an index.html? ${file.absolutePath}"
                )
                null
            }
        }
    }

    /** Copies the defaults to the folder, only if they don't already exist (safe to run every startup, won't clobber distributed files */
    private fun copyAssetDir(context: Context, assetDir: String, destDir: File) {
        destDir.mkdirs()
        context.assets.list(assetDir)?.forEach { asset ->
            val assetPath = "$assetDir/$asset"
            val destFile = File(destDir, asset)

            val isDir = try {
                context.assets.list(assetPath)?.isNotEmpty() == true
            } catch (_: IOException) {
                false
            }

            when {
                isDir -> copyAssetDir(context, assetPath, destFile)
                destFile.exists() && RESPECT_EXISTING_FILES -> Log.d(
                    TAG,
                    "Skipping $assetPath, already exists"
                )

                else -> {
                    runCatching {
                        Files.copy(
                            context.assets.open(assetPath), destFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        Log.d(TAG, "Copied $assetPath to ${destFile.absolutePath}")
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to copy asset file: $assetPath", e)
                    }
                }
            }
        }
    }
}