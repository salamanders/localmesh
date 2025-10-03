package info.benjaminhill.localmesh.service

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

class NearbyConnectionsManager(
    private val context: Context,
    private val endpointName: String,
    private val peerCountUpdateCallback: (Int) -> Unit,
    private val logMessageCallback: (String) -> Unit,
    private val payloadReceivedCallback: (endpointId: String, payload: ByteArray) -> Unit
) {

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val serviceId = "info.benjaminhill.localmesh.v1"
    private val connectedEndpoints = ConcurrentLinkedQueue<String>()

    fun start() {
        logMessageCallback("NearbyConnectionsManager.start()")
        CoroutineScope(Dispatchers.IO).launch {
            startAdvertising()
            startDiscovery()
        }
    }

    fun stop() {
        logMessageCallback("NearbyConnectionsManager.stop()")
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
    }

    fun sendPayload(payload: ByteArray) {
        try {
            logMessageCallback("NearbyConnectionsManager.sendPayload()")
            connectionsClient.sendPayload(connectedEndpoints.toList(), Payload.fromBytes(payload))
                .addOnSuccessListener {
                    logMessageCallback("Payload sent successfully.")
                }
                .addOnFailureListener { e ->
                    logMessageCallback("Failed to send payload: ${e.message}")
                }
        } catch (e: Exception) {
            logMessageCallback("Exception in sendPayload: ${e.message}")
        }
    }

    fun getConnectedPeerCount(): Int {
        return connectedEndpoints.size
    }

    fun getConnectedPeerIds(): List<String> {
        return connectedEndpoints.toList()
    }

    private suspend fun startAdvertising() {
        try {
            logMessageCallback("NearbyConnectionsManager.startAdvertising()")
            withContext(Dispatchers.IO) {
                val advertisingOptions =
                    AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
                connectionsClient.startAdvertising(
                    endpointName,
                    serviceId,
                    connectionLifecycleCallback,
                    advertisingOptions
                ).addOnSuccessListener {
                    logMessageCallback("Advertising started.")
                }.addOnFailureListener { e ->
                    logMessageCallback("Failed to start advertising: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logMessageCallback("Exception in startAdvertising: ${e.message}")
        }
    }

    private suspend fun startDiscovery() {
        try {
            logMessageCallback("NearbyConnectionsManager.startDiscovery()")
            withContext(Dispatchers.IO) {
                val discoveryOptions =
                    DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
                connectionsClient.startDiscovery(
                    serviceId,
                    endpointDiscoveryCallback,
                    discoveryOptions
                )
                    .addOnSuccessListener {
                        logMessageCallback("Discovery started.")
                    }
                    .addOnFailureListener { e ->
                        logMessageCallback("Failed to start discovery: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            logMessageCallback("Exception in startDiscovery: ${e.message}")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            logMessageCallback("onPayloadReceived: endpointId=$endpointId, payload=$payload")
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let {
                    payloadReceivedCallback(endpointId, it)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            logMessageCallback("onPayloadTransferUpdate: endpointId=$endpointId, update=$update")
        }
    }

    private val retryCounts = mutableMapOf<String, Int>()

    private val connectionLifecycleCallback: ConnectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                logMessageCallback("onConnectionInitiated: endpointId=$endpointId, connectionInfo=$connectionInfo")
                try {
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                        .addOnSuccessListener {
                            logMessageCallback("Accepted connection from $endpointId.")
                        }
                        .addOnFailureListener { e ->
                            logMessageCallback("Failed to accept connection from $endpointId: ${e.message}")
                        }
                } catch (e: Exception) {
                    logMessageCallback("Exception in onConnectionInitiated: ${e.message}")
                }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                logMessageCallback("onConnectionResult: endpointId=$endpointId, result=$result")
                if (result.status.isSuccess) {
                    connectedEndpoints.add(endpointId)
                    logMessageCallback("Connected to $endpointId")
                    peerCountUpdateCallback(connectedEndpoints.size)
                    retryCounts.remove(endpointId) // Clear retry count on successful connection
                } else {
                    logMessageCallback("Connection to $endpointId failed: ${result.status.statusCode}")
                    val retryCount = retryCounts.getOrPut(endpointId) { 0 }
                    if (retryCount < 5) { // Max 5 retries
                        val backoffMillis = (1000 * (retryCount + 1) * (1..3).random()).toLong()
                        logMessageCallback("Retrying connection to $endpointId in $backoffMillis ms (attempt ${retryCount + 1})")
                        CoroutineScope(Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(backoffMillis)
                            connectionsClient.requestConnection(
                                endpointName,
                                endpointId,
                                connectionLifecycleCallback
                            )
                        }
                        retryCounts[endpointId] = retryCount + 1
                    } else {
                        logMessageCallback("Max retries reached for $endpointId. Giving up.")
                        retryCounts.remove(endpointId)
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                logMessageCallback("onDisconnected: endpointId=$endpointId")
                connectedEndpoints.remove(endpointId)
                peerCountUpdateCallback(connectedEndpoints.size)
            }
        }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            logMessageCallback("onEndpointFound: endpointId=$endpointId, discoveredEndpointInfo=$discoveredEndpointInfo")
            try {
                connectionsClient.requestConnection(
                    endpointName,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnSuccessListener {
                    logMessageCallback("Requested connection to $endpointId.")
                }.addOnFailureListener { e ->
                    logMessageCallback("Failed to request connection to $endpointId: ${e.message}")
                }
            } catch (e: Exception) {
                logMessageCallback("Exception in onEndpointFound: ${e.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            logMessageCallback("onEndpointLost: endpointId=$endpointId")
        }
    }
}
