package info.benjaminhill.localmesh.mesh

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import info.benjaminhill.localmesh.util.AppLogger
import info.benjaminhill.localmesh.util.GlobalExceptionHandler.runCatchingWithLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow

/**
 * Manages all peer-to-peer network interactions using the Google Nearby Connections API.
 *
 * ## What it does
 * - Handles device discovery, advertising, and connection management.
 * - Establishes a many-to-many mesh network using the `P2P_CLUSTER` strategy.
 * - Sends and receives `Payload` objects (both `BYTES` and `STREAM`) to and from all connected peers.
 * - Manages connection retries with exponential backoff.
 *
 * ## What it doesn't do
 * - It does not interpret the content of the payloads it sends or receives. It is a transport layer,
 *   passing raw payloads up to the `BridgeService`.
 * - It is not aware of the HTTP server or the application's specific API endpoints.
 *
 * ## Comparison to other classes
 * - **[BridgeService]:** This class is the workhorse for P2P communication, while `BridgeService` acts
 *   as the orchestrator, connecting this manager to the `LocalHttpServer`.
 */
class NearbyConnectionsManager(
    private val context: Context,
    private val endpointName: String,
    private val logger: AppLogger,
    private val payloadReceivedCallback: (endpointId: String, payload: Payload) -> Unit,
) {

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val serviceId = "info.benjaminhill.localmesh.v1"
    private val connectedEndpoints = ConcurrentLinkedQueue<String>()
    private val retryCounts = mutableMapOf<String, Int>()

    fun start() {
        logger.log("NearbyConnectionsManager.start()")
        CoroutineScope(Dispatchers.IO).launch {
            startAdvertising()
            startDiscovery()
        }
    }

    fun stop() {
        logger.log("NearbyConnectionsManager.stop()")
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        retryCounts.clear()
    }

    fun sendPayload(endpointIds: List<String>, payload: Payload) =
        runCatchingWithLogging(logger::e) {
            logger.log("NearbyConnectionsManager.sendPayload() to ${endpointIds.size} endpoints.")
            connectionsClient.sendPayload(endpointIds, payload)
                .addOnFailureListener { e ->
                    logger.e("Failed to send payload ${payload.id}", e)
                }
        }

    fun broadcastBytes(payload: ByteArray) {
        sendPayload(connectedEndpoints.toList(), Payload.fromBytes(payload))
    }

    val connectedPeerCount: Int
        get() = connectedEndpoints.size

    val connectedPeerIds: List<String>
        get() = connectedEndpoints.toList()

    private suspend fun startAdvertising() = runCatchingWithLogging(logger::e) {
        withContext(Dispatchers.IO) {
            val advertisingOptions =
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            connectionsClient.startAdvertising(
                endpointName,
                serviceId,
                connectionLifecycleCallback,
                advertisingOptions
            ).addOnSuccessListener {
                logger.log("Advertising started.")
            }.addOnFailureListener { e ->
                logger.e("Failed to start advertising", e)
            }
        }
    }

    private suspend fun startDiscovery() = runCatchingWithLogging(logger::e) {
        withContext(Dispatchers.IO) {
            val discoveryOptions =
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            connectionsClient.startDiscovery(
                serviceId,
                endpointDiscoveryCallback,
                discoveryOptions
            ).addOnSuccessListener {
                logger.log("Discovery started.")
            }.addOnFailureListener { e ->
                logger.e("Failed to start discovery", e)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            runCatchingWithLogging(logger::e) {
                payloadReceivedCallback(endpointId, payload)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            runCatchingWithLogging(logger::e) {
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS ->
                        logger.log("SUCCESS: Payload ${update.payloadId} transfer to $endpointId complete.")

                    PayloadTransferUpdate.Status.FAILURE ->
                        logger.e(
                            "FAILURE: Payload ${update.payloadId} transfer to $endpointId failed.",
                            Exception("PayloadTransferUpdate Failure")
                        )

                    PayloadTransferUpdate.Status.CANCELED ->
                        logger.log("CANCELED: Payload ${update.payloadId} transfer to $endpointId was canceled.")

                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        // Ignoring for now to keep logs clean. This is where you'd update a progress bar.
                    }
                }
            }
        }
    }

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                runCatchingWithLogging(logger::e) {
                    logger.log("onConnectionInitiated from ${connectionInfo.endpointName} (id:$endpointId)")
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                        .addOnFailureListener { e ->
                            logger.e("Failed to accept connection from $endpointId", e)
                        }
                }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    logger.log("Connected to $endpointId")
                    connectedEndpoints.add(endpointId)
                    retryCounts.remove(endpointId) // Clear on success
                    // peerCountUpdateCallback(connectedEndpoints.size)
                } else {
                    logger.log("Connection to $endpointId failed: ${result.status.statusCode}")
                    scheduleRetry(endpointId, "connection result") {
                        requestConnection(endpointId)
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                logger.log("onDisconnected: $endpointId")
                connectedEndpoints.remove(endpointId)
                // peerCountUpdateCallback(connectedEndpoints.size)
            }
        }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            logger.log("onEndpointFound: ${discoveredEndpointInfo.endpointName} (id:$endpointId)")
            requestConnection(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            logger.log("onEndpointLost: $endpointId")
        }
    }

    private fun requestConnection(endpointId: String) {
        runCatchingWithLogging(logger::e) {
            connectionsClient.requestConnection(
                endpointName,
                endpointId,
                connectionLifecycleCallback
            )
                .addOnSuccessListener {
                    logger.log("Connection request sent to $endpointId.")
                    retryCounts.remove(endpointId) // Clear on success
                }
                .addOnFailureListener { e ->
                    val statusCode = (e as? ApiException)?.statusCode
                    logger.e(
                        "Failed to request connection to $endpointId (code: $statusCode)",
                        e
                    )
                    if (statusCode == ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR) {
                        scheduleRetry(endpointId, "initial request") {
                            requestConnection(endpointId)
                        }
                    }
                }
        }
    }

    private fun scheduleRetry(key: String, description: String, action: suspend () -> Unit) {
        val currentRetries = retryCounts.getOrPut(key) { 0 }
        if (currentRetries < 5) {
            val nextRetry = currentRetries + 1
            retryCounts[key] = nextRetry
            val backoffMillis = (1000 * 2.0.pow(currentRetries)).toLong() + (0..1000).random()
            logger.log("Scheduling retry for $description on '$key' in $backoffMillis ms (attempt $nextRetry)")
            CoroutineScope(Dispatchers.IO).launch {
                delay(backoffMillis)
                action()
            }
        } else {
            logger.log("Max retries reached for $description on '$key'. Giving up.")
            retryCounts.remove(key)
        }
    }
}