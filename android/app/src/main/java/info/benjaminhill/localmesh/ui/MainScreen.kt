package info.benjaminhill.localmesh.ui

import android.Manifest
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.benjaminhill.localmesh.mesh.P2PBridgeAction
import info.benjaminhill.localmesh.mesh.ServiceState
import info.benjaminhill.localmesh.ui.theme.LocalMeshTheme

@Composable
fun MainScreen(onAction: (P2PBridgeAction) -> Unit) {
    val status by AppStateHolder.statusText.collectAsStateWithLifecycle()
    val logs by AppStateHolder.logs.collectAsStateWithLifecycle()
    val serverUrl by AppStateHolder.serverUrl.collectAsStateWithLifecycle()
    val currentServiceState by AppStateHolder.currentState.collectAsStateWithLifecycle()

    LocalMeshTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            ControlPanel(
                modifier = Modifier.padding(innerPadding),
                status = status,
                logs = logs,
                currentServiceState = currentServiceState,
                serverUrl = serverUrl,
                onAction = onAction,
                onLogMessage = { AppStateHolder.addLog(it) }
            )
        }
    }
}

@Composable
fun ControlPanel(
    modifier: Modifier = Modifier,
    status: String,
    logs: List<String>,
    currentServiceState: ServiceState,
    serverUrl: String?,
    onAction: (P2PBridgeAction) -> Unit = {},
    onLogMessage: (String) -> Unit = {}
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

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
        ServerAddressDisplay(serverUrl = serverUrl)
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
fun ServerAddressDisplay(serverUrl: String?) {
    if (serverUrl == null) {
        Text("Server not running.")
        return
    }
    val uriHandler = LocalUriHandler.current
    val annotatedString = remember(serverUrl) {
        AnnotatedString(serverUrl)
    }
    ClickableText(
        text = annotatedString,
        onClick = { uriHandler.openUri(serverUrl) },
        style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.primary)
    )
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
            currentServiceState = ServiceState.Idle,
            serverUrl = "http://localhost:8099/test"
        )
    }
}