package info.benjaminhill.localmesh.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import info.benjaminhill.localmesh.service.LocalHttpServer
import info.benjaminhill.localmesh.service.P2PBridgeAction
import info.benjaminhill.localmesh.service.P2PBridgeEvent
import info.benjaminhill.localmesh.service.ServiceState
import info.benjaminhill.localmesh.ui.theme.LocalMeshTheme
import kotlinx.serialization.json.Json

@Composable
fun MainScreen(onAction: (P2PBridgeAction) -> Unit) {
    var status by remember { mutableStateOf("Inactive") }
    val logs = remember { mutableStateListOf<String>() }
    var currentServiceState by remember { mutableStateOf<ServiceState>(ServiceState.Idle) }

    SystemBroadcastReceiver(P2PBridgeEvent::class.java.name) { intent ->
        val eventJson = intent.getStringExtra("event") ?: return@SystemBroadcastReceiver
        when (val event = Json.decodeFromString<P2PBridgeEvent>(eventJson)) {
            is P2PBridgeEvent.StatusUpdate -> {
                status = "${event.status} - ${event.peerCount} Peers"
                currentServiceState =
                    ServiceState.Running // Assuming StatusUpdate means running
            }

            is P2PBridgeEvent.LogMessage -> {
                logs.add(event.message)
            }
        }
    }

    LocalMeshTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            ControlPanel(
                modifier = Modifier.padding(innerPadding),
                status = status,
                logs = logs,
                currentServiceState = currentServiceState,
                onAction = { action ->
                    if (action is P2PBridgeAction.Start) {
                        status = "Starting..."
                        currentServiceState = ServiceState.Starting
                    } else if (action is P2PBridgeAction.Stop) {
                        status = "Stopping..."
                        currentServiceState = ServiceState.Stopping
                    }
                    onAction(action)
                },
                onLogMessage = logs::add
            )
        }
    }
}


@Composable
fun SystemBroadcastReceiver(
    systemAction: String,
    onSystemEvent: (intent: Intent) -> Unit
) {
    val context = LocalContext.current
    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onSystemEvent(intent)
            }
        }
    }
    DisposableEffect(context, systemAction) {
        val filter = IntentFilter(systemAction)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}

@Composable
fun ControlPanel(
    modifier: Modifier = Modifier,
    status: String,
    logs: List<String>,
    currentServiceState: ServiceState,
    onAction: (P2PBridgeAction) -> Unit = {},
    onLogMessage: (String) -> Unit = {}
) {
    val serverAddress = "http://localhost:" + LocalHttpServer.PORT
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val requestPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.all { it.value }) {
                onLogMessage("Permissions granted, starting service...")
                onAction(P2PBridgeAction.Start)
            } else {
                onLogMessage("Permissions not granted")
            }
        }

        StatusDisplay(status = status)
        Spacer(modifier = Modifier.height(16.dp))
        ServiceButtons(
            isServiceRunning = currentServiceState is ServiceState.Running,
            isServiceStarting = currentServiceState is ServiceState.Starting,
            onRequestStart = {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                        Manifest.permission.NFC,
                    )
                )
            },
            onStopService = { onAction(P2PBridgeAction.Stop) }
        )
        Spacer(modifier = Modifier.height(16.dp))
        ServerAddressDisplay(serverAddress = serverAddress)
        Spacer(modifier = Modifier.height(16.dp))
        LogView(logs = logs)
    }
}

@Composable
fun StatusDisplay(status: String) {
    Text("Status: $status")
}

@Composable
fun ServiceButtons(
    isServiceRunning: Boolean,
    isServiceStarting: Boolean,
    onRequestStart: () -> Unit,
    onStopService: () -> Unit
) {
    Button(onClick = onRequestStart, enabled = !isServiceRunning && !isServiceStarting) {
        Text("Start Service")
    }
    Button(onClick = onStopService, enabled = isServiceRunning) {
        Text("Stop Service")
    }
}

@Composable
fun ServerAddressDisplay(serverAddress: String) {
    Text(serverAddress)
}

@Composable
fun LogView(logs: List<String>) {
    LazyColumn {
        items(logs) { log ->
            Text(log)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ControlPanelPreview() {
    LocalMeshTheme {
        ControlPanel(
            status = "Inactive",
            logs = listOf("Log 1", "Log 2"),
            currentServiceState = ServiceState.Idle
        )
    }
}
