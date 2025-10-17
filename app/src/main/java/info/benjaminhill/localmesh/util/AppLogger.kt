package info.benjaminhill.localmesh.util

import android.util.Log
import info.benjaminhill.localmesh.LogFileWriter

/**
 * A simple logger that writes to both Logcat and a file.
 * This is a simple wrapper to make it easier to pass logging as a dependency.
 */
class AppLogger(
    private val tag: String,
    private val logFileWriter: LogFileWriter
) {
    fun log(message: String) {
        Log.d(tag, message)
        logFileWriter.writeLog(message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            logFileWriter.writeLog("ERROR: $message, ${throwable.message}")
        } else {
            Log.e(tag, message)
            logFileWriter.writeLog("ERROR: $message")
        }
    }
}