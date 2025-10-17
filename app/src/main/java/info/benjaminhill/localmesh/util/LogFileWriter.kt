package info.benjaminhill.localmesh.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A simple utility for writing timestamped log messages to a persistent file.
 *
 * ## What it does
 * - Appends log messages to a file named `app_log.txt` in the app's internal storage.
 * - Creates the log file and directory if they don't exist.
 * - Prefixes each log message with a `yyyy-MM-dd HH:mm:ss.SSS` timestamp.
 *
 * ## What it doesn't do
 * - It does not handle log rotation or log level filtering. It is a simple append-only logger.
 * - It does not display logs in the UI; that is handled by `AppStateHolder`.
 */
class LogFileWriter(private val context: Context) {

    private val logFile: File by lazy {
        val logDir = File(context.filesDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        File(logDir, "app_log.txt")
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Appends a message to the log file with a current timestamp.
     * If writing to the file fails, it prints the stack trace to the system log.
     *
     * @param message The log message to write.
     */
    fun writeLog(message: String) {
        try {
            FileWriter(logFile, true).use { writer ->
                writer.append("${dateFormat.format(Date())} - $message\n")
            }
        } catch (e: IOException) {
            // Log to system log if file writing fails
            e.printStackTrace()
        }
    }
}