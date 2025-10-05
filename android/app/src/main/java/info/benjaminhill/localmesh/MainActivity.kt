package info.benjaminhill.localmesh

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import info.benjaminhill.localmesh.mesh.P2PBridgeAction
import info.benjaminhill.localmesh.mesh.P2PBridgeService
import info.benjaminhill.localmesh.ui.MainScreen
import java.io.IOException

class MainActivity : ComponentActivity() {
    private fun startP2PBridgeService(action: P2PBridgeAction) {
        Log.i(TAG, "MainActivity.startP2PBridgeService() with ${action::class.java.simpleName}")
        val intent = Intent(this, P2PBridgeService::class.java).apply {
            this.action = action::class.java.name
            if (action is P2PBridgeAction.ShareFolder) {
                putExtra(P2PBridgeService.EXTRA_FOLDER_NAME, action.folderName)
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

    private fun getAssetFolders(): List<String> {
        val assetManager = assets
        val path = "web"
        return try {
            assetManager.list(path)
                ?.filter {
                    // Check if the item is a directory by trying to list its contents.
                    // An empty list or null indicates it's not a directory we are interested in.
                    try {
                        assetManager.list("$path/$it")?.isNotEmpty() == true
                    } catch (e: IOException) {
                        false
                    }
                }
                ?: emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Error listing asset folders", e)
            emptyList()
        }
    }


    companion object {
        const val TAG: String = "MainActivity"
    }
}