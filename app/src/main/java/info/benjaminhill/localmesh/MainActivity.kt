package info.benjaminhill.localmesh

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import info.benjaminhill.localmesh.mesh.BridgeService

/**
 * The main entry point for the application.
 *
 * ## What it does
 * - Displays a single "Start Service" button.
 * - Requests all necessary permissions (Bluetooth, Location, etc.).
 * - Once permissions are granted, it starts the [BridgeService].
 * - Finishes itself so the user cannot navigate back to it.
 */
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            Log.i(TAG, "Permissions granted, starting service...")
            startBridgeServiceAndWebView()
        } else {
            Log.e(TAG, "Permissions not granted. Please enable them in settings.")
            // TODO: Show a more user-friendly message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = {
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.NEARBY_WIFI_DEVICES,
                            Manifest.permission.POST_NOTIFICATIONS,
                            Manifest.permission.NFC,
                        )
                    )
                }) {
                    Text("Start Service")
                }
            }
        }
    }

    private fun startBridgeServiceAndWebView() {
        // Start the service
        Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
        }.also {
            startService(it)
        }

        // Launch the display activity
        Intent(this, DisplayActivity::class.java).apply {
            putExtra(DisplayActivity.EXTRA_PATH, "index.html")
        }.also {
            startActivity(it)
        }
        // Close the MainActivity so the user can't navigate back to the start button.
        finish()
    }

    companion object {
        const val TAG: String = "MainActivity"
    }
}
