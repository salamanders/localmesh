package info.benjaminhill.localmesh.mesh

import android.content.Context
import android.os.PowerManager
import info.benjaminhill.localmesh.util.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * A watchdog that monitors the health of the application and restarts it if it becomes unresponsive.
 * This class is responsible for acquiring a wakelock to keep the device awake, and for scheduling
 * periodic checks of the web server, P2P network, and WebView. If any of these components
 * are found to be unhealthy, the service is restarted. This class does not handle any UI,
 * and it does not directly handle any networking.
 */
class ServiceHardener(
    private val service: BridgeService,
    private val logger: AppLogger
) {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var scheduler: ScheduledExecutorService
    private val client = HttpClient(CIO)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val lastP2pMessageTime = AtomicLong(0L)
    private val lastWebViewReportTime = AtomicLong(0L)

    fun start() {
        logger.log("ServiceHardener starting.")
        acquireWakeLock()
        resetTimestamps()

        synchronized(this) {
            if (::scheduler.isInitialized && !scheduler.isShutdown) {
                logger.log("Hardener scheduler already running.")
                return
            }
            scheduler = Executors.newSingleThreadScheduledExecutor()
        }
        val initialDelay = Random.nextLong(60, 180)
        scheduler.scheduleWithFixedDelay(
            this::runChecks,
            initialDelay,
            CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
        logger.log("Hardener scheduler started with initial delay of $initialDelay seconds.")
    }

    fun stop() {
        logger.log("ServiceHardener stopping.")
        releaseWakeLock()
        synchronized(this) {
            if (::scheduler.isInitialized && !scheduler.isShutdown) {
                scheduler.shutdown()
                scope.cancel()
                logger.runCatchingWithLogging {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow()
                    }
                }
            }
        }
        client.close()
        logger.log("Hardener scheduler stopped.")
    }

    fun updateP2pMessageTime() {
        lastP2pMessageTime.set(System.currentTimeMillis())
    }

    fun updateWebViewReportTime() {
        lastWebViewReportTime.set(System.currentTimeMillis())
    }

    fun resetTimestamps() {
        val now = System.currentTimeMillis()
        lastP2pMessageTime.set(now)
        lastWebViewReportTime.set(now)
    }

    private fun runChecks() {
        logger.log("Running hardener checks...")
        scope.launch {
            val failureMessages = mutableListOf<String>()
            if (!isWebServerHealthy()) failureMessages.add("Web server is not responsive.")
            if (!isP2pNetworkHealthy()) failureMessages.add("P2P network is unhealthy.")
            if (!isWebViewHealthy()) failureMessages.add("WebView appears frozen.")

            if (failureMessages.isEmpty()) {
                logger.log("Hardener checks passed.")
            } else {
                val reason = failureMessages.joinToString(" ")
                logger.e("Hardener failure detected: $reason. Restarting service.")
                service.restart()
            }
        }
    }

    private suspend fun isWebServerHealthy(): Boolean = logger.runCatchingWithLogging {
        val response = client.get("http://127.0.0.1:8099/status")
        val healthy = response.status.value in 200..299 && response.bodyAsText().isNotEmpty()
        if (!healthy) {
            logger.e("Web server health check failed with status ${response.status.value}")
        }
        healthy
    } ?: false

    private fun isP2pNetworkHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val uptime = now - service.serviceStartTime
        val timeSinceLastMessage = now - lastP2pMessageTime.get()

        if (uptime > FIVE_MINUTES_MS && service.nearbyConnectionsManager.connectedPeers.value.isEmpty()) {
            logger.e("P2P Health Fail: No peers connected after ${uptime / 1000}s.")
            return false
        }

        if (service.nearbyConnectionsManager.connectedPeers.value.isNotEmpty() && timeSinceLastMessage > FIVE_MINUTES_MS) {
            logger.e("P2P Health Fail: No messages for ${timeSinceLastMessage / 1000}s.")
            return false
        }

        return true
    }

    private fun isWebViewHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastWebViewUpdate = now - lastWebViewReportTime.get()
        return (timeSinceLastWebViewUpdate <= WEBVIEW_TIMEOUT_MS).also {
            if (!it) {
                logger.e("WebView Health Fail: No report for ${timeSinceLastWebViewUpdate / 1000}s.")
            }
        }
    }

    private fun acquireWakeLock() {
        releaseWakeLock() // Ensure no existing lock is held
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalMesh::WakeLock").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
        logger.log("Wakelock acquired.")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            logger.log("Wakelock released.")
        }
        wakeLock = null
    }

    companion object {
        private const val CHECK_INTERVAL_MINUTES = 5L
        private const val FIVE_MINUTES_MS = CHECK_INTERVAL_MINUTES * 60 * 1000L
        private const val WEBVIEW_TIMEOUT_MS = 2 * 60 * 1000L
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}