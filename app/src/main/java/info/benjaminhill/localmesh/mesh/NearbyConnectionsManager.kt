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
import info.benjaminhill.localmesh.logic.ConnectionManager
import info.benjaminhill.localmesh.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * The Android-specific implementation of the [ConnectionManager] interface.
 *
 * ## What it does
 * - Acts as the "hands" of the network, wrapping the Google Play Services Nearby Connections API.
 * - Translates commands from the `TopologyOptimizer` (e.g., `connectTo`, `disconnectFrom`) into
 *   actual hardware operations (Wi-Fi/Bluetooth).
 * - Manages the low-level details of advertising, discovery, and connection lifecycle.
 * - Receives all raw `Payload` objects from peers. It immediately emits `BYTES` payloads to the
 *   `incomingPayloads` flow for consumption by other services (like `TopologyOptimizer` and
 *   `BridgeService`) and uses a callback for handling `STREAM` payloads (for file transfers).
 *
 * ## What it doesn't do
 * - It has no knowledge of the mesh topology or optimization strategy. It is a stateless transport layer.
 * - It does not parse the content of `BYTES` payloads; it just passes them on.
 *
 * ## Comparison to other classes
 * - **[TopologyOptimizer]:** This class is the "hands", while `TopologyOptimizer` is the "brains".
 * - **[BridgeService]:** This class is the workhorse for P2P communication, while `BridgeService` acts
 *   as the central orchestrator.
 */
class NearbyConnectionsManager(
    private val context: Context,
    private val endpointName: String,
    private val logger: AppLogger,
    private val payloadReceivedCallback: (endpointId: String, payload: Payload) -> Unit,
    override val maxConnections: Int,
) : ConnectionManager {

    override val connectedPeers = MutableStateFlow(emptySet<String>())
    override val incomingPayloads = MutableSharedFlow<Pair<String, ByteArray>>()
    override val discoveredEndpoints = MutableSharedFlow<String>()

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val serviceId = "info.benjaminhill.localmesh.v1"
    private val retryCounts = mutableMapOf<String, Int>()
    private val seenMessageIds = ConcurrentHashMap<UUID, Long>()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var isDiscoveryModeActive = false
    private var discoveryModeJob: Job? = null


    override fun start() {
        logger.log("NearbyConnectionsManager.start()")
        scope.launch {
            startAdvertising()
            startDiscovery()
        }
    }

    override fun stop() {
        logger.log("NearbyConnectionsManager.stop()")
        connectionsClient.stopAllEndpoints()
        scope.cancel()
        connectedPeers.value = emptySet()
        retryCounts.clear()
        seenMessageIds.clear()
    }

    fun sendPayload(endpointIds: List<String>, payload: Payload) =
        logger.runCatchingWithLogging {
            logger.log("NearbyConnectionsManager.sendPayload() to ${endpointIds.size} endpoints.")
            connectionsClient.sendPayload(endpointIds, payload)
                .addOnFailureListener { e ->
                    logger.e("Failed to send payload ${payload.id}", e)
                }
        }

    override fun connectTo(endpointId: String) {
        requestConnection(endpointId)
    }

    override fun disconnectFrom(endpointId: String) {
        disconnectFromEndpoint(endpointId)
    }

    override fun sendPayload(endpointIds: List<String>, payload: ByteArray) {
        sendPayload(endpointIds, Payload.fromBytes(payload))
    }

    override fun enterDiscoveryMode() {
        if (isDiscoveryModeActive) {
            logger.log("Already in discovery mode. Ignoring request.")
            return
        }
        logger.log("Entering time-limited island discovery mode for 20 seconds.")
        isDiscoveryModeActive = true

        // Cancel any previous job to be safe
        discoveryModeJob?.cancel()

        discoveryModeJob = scope.launch {
            delay(20_000L)
            if (isDiscoveryModeActive) {
                logger.log("Island discovery timeout reached. Exiting discovery mode.")
                isDiscoveryModeActive = false
            }
        }
    }

    private suspend fun startAdvertising() = logger.runCatchingWithLogging {
        withContext(Dispatchers.IO) {
            // P2P_CLUSTER is a balanced strategy for a mesh network with multiple connections.
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

    private suspend fun startDiscovery() = logger.runCatchingWithLogging {
        withContext(Dispatchers.IO) {
            // P2P_CLUSTER is a balanced strategy for a mesh network with multiple connections.
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
            logger.log("onPayloadReceived: from $endpointId, type: ${payload.type}")
            logger.runCatchingWithLogging {
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val bytes = payload.asBytes()!!
                        logger.log("Emitting ${bytes.size} bytes from $endpointId to incomingPayloads flow.")
                        scope.launch {
                            incomingPayloads.emit(endpointId to bytes)
                        }
                    }

                    else -> {
                        logger.log("Passing non-BYTES payload from $endpointId to callback.")
                        payloadReceivedCallback(endpointId, payload)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            logger.runCatchingWithLogging {
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
                logger.runCatchingWithLogging {
                    logger.log("onConnectionInitiated from ${connectionInfo.endpointName} (id:$endpointId)")
                    if (connectedPeers.value.size < maxConnections) {
                        logger.log("Accepting connection from $endpointId (current connections: ${connectedPeers.value.size})")
                        connectionsClient.acceptConnection(endpointId, payloadCallback)
                            .addOnFailureListener { e ->
                                logger.e("Failed to accept connection from $endpointId", e)
                            }
                    } else {
                        logger.log("Rejecting connection from $endpointId (already at max connections: ${connectedPeers.value.size})")
                        connectionsClient.rejectConnection(endpointId)
                    }
                }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    logger.log("Connected to $endpointId")
                    connectedPeers.value += endpointId
                    retryCounts.remove(endpointId) // Clear on success
                } else {
                    logger.log("Connection to $endpointId failed: ${result.status.statusCode}")
                    scheduleRetry(endpointId, "connection result") {
                        requestConnection(endpointId)
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                logger.log("onDisconnected: $endpointId")
                connectedPeers.value -= endpointId
            }
        }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            logger.log("onEndpointFound: ${discoveredEndpointInfo.endpointName} (id:$endpointId)")

            if (isDiscoveryModeActive) {
                logger.log("In discovery mode, attempting to connect to new endpoint '$endpointId' to merge islands.")
                isDiscoveryModeActive = false // Exit discovery mode immediately upon finding a candidate
                discoveryModeJob?.cancel()
                requestConnection(endpointId)
                return
            }

            scope.launch {
                discoveredEndpoints.emit(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            logger.log("onEndpointLost: $endpointId")
        }
    }

    fun requestConnection(endpointId: String) {
        logger.runCatchingWithLogging {
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
            scope.launch {
                delay(backoffMillis)
                action()
            }
        } else {
            logger.log("Max retries reached for $description on '$key'. Giving up.")
            retryCounts.remove(key)
        }
    }

    fun disconnectFromEndpoint(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

}