package info.benjaminhill.localmesh

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles writing log messages to a persistent file.
 * This class creates and appends logs to a file named "app_log.txt"
 * located in a "logs" subdirectory of the app's internal files directory.
 * Each log entry is timestamped.
 *
 * @param context The application context, used to access the file system.
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