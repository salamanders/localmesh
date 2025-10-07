package info.benjaminhill.localmesh

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import info.benjaminhill.localmesh.mesh.BridgeAction
import info.benjaminhill.localmesh.mesh.BridgeService
import info.benjaminhill.localmesh.ui.MainScreen
import java.io.IOException

/**
 * The main entry point of the application's UI.
 *
 * ## What it does
 * - Displays the main control screen of the app using Jetpack Compose (`MainScreen`).
 * - Handles user interactions from the UI, such as starting and stopping the `BridgeService`.
 * - Translates UI events into `BridgeAction` intents to be sent to the `BridgeService`.
 *
 * ## What it doesn't do
 * - It does not contain any P2P or web server logic. It is purely for UI and user interaction.
 * - It does not directly manage the state of the service; it only sends commands and observes
 *   state changes via `AppStateHolder`.
 */
class MainActivity : ComponentActivity() {
    private fun startP2PBridgeService(action: BridgeAction) {
        Log.i(TAG, "MainActivity.startP2PBridgeService() with ${action::class.java.simpleName}")
        val intent = Intent(this, BridgeService::class.java).apply {
            this.action = action::class.java.name
            if (action is BridgeAction.BroadcastCommand) {
                putExtra(BridgeService.EXTRA_COMMAND, action.command)
                putExtra(BridgeService.EXTRA_PAYLOAD, action.payload)
            }
        }
        startService(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity.onCreate()")
        enableEdgeToEdge()
        val assetFolders = getAssetFolders()
        setContent {
            MainScreen(
                assetFolders = assetFolders,
                onAction = ::startP2PBridgeService
            )
        }
    }

    private fun isAssetDir(path: String): Boolean {
        return try {
            assets.list(path)?.isNotEmpty() == true
        } catch (_: IOException) {
            false
        }
    }

    private fun getAssetFolders(): List<String> {
        val path = "web"
        return try {
            assets.list(path)?.filter { isAssetDir("$path/$it") } ?: emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Error listing asset folders", e)
            emptyList()
        }
    }


    companion object {
        const val TAG: String = "MainActivity"
    }
}