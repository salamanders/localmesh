package info.benjaminhill.localmesh.mesh

import info.benjaminhill.localmesh.util.GlobalExceptionHandler.runCatchingWithLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class HeartbeatManager(
    private val service: BridgeService,
) {
    private lateinit var scheduler: ScheduledExecutorService
    private val client = HttpClient(CIO)

    fun start() {
        if (::scheduler.isInitialized && !scheduler.isShutdown) {
            service.sendLogMessage("HeartbeatManager already running.")
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
        service.sendLogMessage("HeartbeatManager started with initial delay of $initialDelay seconds.")
    }

    fun stop() {
        if (::scheduler.isInitialized && !scheduler.isShutdown) {
            scheduler.shutdown()
            runCatchingWithLogging(service::logError) {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            }
        }
        client.close()
        service.sendLogMessage("HeartbeatManager stopped.")
    }

    private fun runChecks() {
        service.sendLogMessage("Running heartbeat checks...")
        // Run all checks and collect failure messages
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
            service.sendLogMessage("Heartbeat checks passed.")
        } else {
            val reason = failureMessages.joinToString(" ")
            service.sendLogMessage("Heartbeat failure detected: $reason. Restarting service.")
            service.restart()
        }
    }

    private fun isWebServerHealthy(): Boolean = runCatchingWithLogging(service::logError) {
        runBlocking {
            val response = client.get("http://127.0.0.1:8099/status")
            val healthy = response.status.value in 200..299 && response.bodyAsText().isNotEmpty()
            if (!healthy) {
                service.sendLogMessage("Web server health check failed with status ${response.status.value}")
            }
            healthy
        }
    } ?: false

    private fun isP2pNetworkHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val uptime = now - service.serviceStartTime
        val timeSinceLastMessage = now - service.lastP2pMessageTime

        // After 5 minutes of uptime, we must have at least one peer.
        if (uptime > FIVE_MINUTES_MS && service.nearbyConnectionsManager.connectedPeerCount == 0) {
            service.sendLogMessage("P2P Health Fail: No peers connected after ${uptime / 1000}s.")
            return false
        }

        // If we have peers, we must have received a message in the last 5 minutes.
        // This doesn't apply if we are the only one on the network.
        if (service.nearbyConnectionsManager.connectedPeerCount > 0 && timeSinceLastMessage > FIVE_MINUTES_MS) {
            service.sendLogMessage("P2P Health Fail: No messages for ${timeSinceLastMessage / 1000}s.")
            return false
        }

        return true
    }

    private fun isWebViewHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastWebViewUpdate = now - service.lastWebViewReportTime
        // If it's been over 2 minutes since the last webview report, something is wrong.
        // The check is every minute, so this gives it a buffer.
        if (timeSinceLastWebViewUpdate > WEBVIEW_TIMEOUT_MS) {
            service.sendLogMessage("WebView Health Fail: No report for ${timeSinceLastWebViewUpdate / 1000}s.")
            return false
        }
        return true
    }

    companion object {
        private const val CHECK_INTERVAL_MINUTES = 5L
        private const val FIVE_MINUTES_MS = CHECK_INTERVAL_MINUTES * 60 * 1000L
        private const val WEBVIEW_TIMEOUT_MS = 2 * 60 * 1000L
    }
}