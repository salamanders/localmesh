package info.benjaminhill.localmesh

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import info.benjaminhill.localmesh.display.DisplayActivity
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.util.AssetManager
import info.benjaminhill.localmesh.util.PermissionUtils

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

    // Modern Android API for requesting permissions and handling the user's response.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            Log.i(TAG, "Permissions granted, starting service...")
            startBridgeServiceAndWebView()
        } else {
            Log.e(TAG, "User denied permissions.")
            Toast.makeText(
                this,
                "Permissions are required for LocalMesh to function. Please enable them in settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Copy all assets from the APK to writable storage on first run.
        // This allows Ktor's `staticFiles` to serve them with range support.
        AssetManager.unpack(this)

        // Check for a testing hook flag in the intent. This allows automated scripts
        // to bypass the UI and trigger the service start sequence directly.
        if (intent.getBooleanExtra("auto_start", false)) {
            Log.d(TAG, "auto_start extra found, launching permissions check.")
            // Since permissions should already be granted by the test script (e.g., via `adb install -g`),
            // this will immediately trigger the `startBridgeServiceAndWebView()` call in the callback.
            requestPermissionLauncher.launch(PermissionUtils.getDangerousPermissions(this))
        } else {
            // Standard interactive startup: show the button to the user.
            setContent {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val context = LocalContext.current
                    Button(onClick = {
                        requestPermissionLauncher.launch(
                            PermissionUtils.getDangerousPermissions(
                                context
                            )
                        )
                    }) {
                        Text("Start Service")
                    }
                }
            }
        }
    }

    private fun startBridgeServiceAndWebView() {
        Log.d(TAG, "startBridgeServiceAndWebView() called.")
        // Start the service
        Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
        }.also {
            startService(it)
        }

        // Launch the DisplayActivity to show the main UI. Due to its 'singleTop' launchMode,
        // future P2P display commands will reuse this Activity instance by sending it a new Intent
        // via the onNewIntent() callback.
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
