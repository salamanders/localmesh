package info.benjaminhill.localmesh.mesh

import android.content.Context
import android.os.PowerManager
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.GlobalExceptionHandler.runCatchingWithLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ServiceHardener(
    private val service: BridgeService,
    private val logger: AppLogger
) {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var scheduler: ScheduledExecutorService
    private val client = HttpClient(CIO)

    private val _lastP2pMessageTime = AtomicLong(0L)
    private val _lastWebViewReportTime = AtomicLong(0L)

    fun start() {
        logger.log("ServiceHardener starting.")
        acquireWakeLock()
        resetTimestamps()

        if (::scheduler.isInitialized && !scheduler.isShutdown) {
            logger.log("Hardener scheduler already running.")
            return
        }
        scheduler = Executors.newSingleThreadScheduledExecutor()
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
        if (::scheduler.isInitialized && !scheduler.isShutdown) {
            scheduler.shutdown()
            runCatchingWithLogging(logger::e) {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            }
        }
        client.close()
        logger.log("Hardener scheduler stopped.")
    }

    fun updateP2pMessageTime() {
        _lastP2pMessageTime.set(System.currentTimeMillis())
    }

    fun updateWebViewReportTime() {
        _lastWebViewReportTime.set(System.currentTimeMillis())
    }

    fun resetTimestamps() {
        val now = System.currentTimeMillis()
        _lastP2pMessageTime.set(now)
        _lastWebViewReportTime.set(now)
    }

    private fun runChecks() {
        logger.log("Running hardener checks...")
        val failureMessages = mutableListOf<String>()

        if (!isWebServerHealthy()) {
            failureMessages.add("Web server is not responsive.")
        }
        if (!isP2pNetworkHealthy()) {
            failureMessages.add("P2P network is unhealthy.")
        }
        if (!isWebViewHealthy()) {
            failureMessages.add("WebView appears frozen.")
        }

        if (failureMessages.isEmpty()) {
            logger.log("Hardener checks passed.")
        } else {
            val reason = failureMessages.joinToString(" ")
            logger.e("Hardener failure detected: $reason. Restarting service.")
            service.restart()
        }
    }

    private fun isWebServerHealthy(): Boolean = runCatchingWithLogging(logger::e) {
        runBlocking {
            val response = client.get("http://127.0.0.1:8099/status")
            val healthy = response.status.value in 200..299 && response.bodyAsText().isNotEmpty()
            if (!healthy) {
                logger.e("Web server health check failed with status ${response.status.value}")
            }
            healthy
        }
    } ?: false

    private fun isP2pNetworkHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val uptime = now - service.serviceStartTime
        val timeSinceLastMessage = now - _lastP2pMessageTime.get()

        if (uptime > FIVE_MINUTES_MS && service.nearbyConnectionsManager.connectedPeerCount == 0) {
            logger.e("P2P Health Fail: No peers connected after ${uptime / 1000}s.")
            return false
        }

        if (service.nearbyConnectionsManager.connectedPeerCount > 0 && timeSinceLastMessage > FIVE_MINUTES_MS) {
            logger.e("P2P Health Fail: No messages for ${timeSinceLastMessage / 1000}s.")
            return false
        }

        return true
    }

    private fun isWebViewHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastWebViewUpdate = now - _lastWebViewReportTime.get()
        if (timeSinceLastWebViewUpdate > WEBVIEW_TIMEOUT_MS) {
            logger.e("WebView Health Fail: No report for ${timeSinceLastWebViewUpdate / 1000}s.")
            return false
        }
        return true
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