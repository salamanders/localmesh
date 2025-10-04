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

class MainActivity : ComponentActivity() {
    private fun startP2PBridgeService(action: P2PBridgeAction) {
        Log.i(TAG, "MainActivity.startP2PBridgeService()")
        val intent = Intent(this, P2PBridgeService::class.java).apply {
            this.action = action::class.java.name
        }
        startService(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity.onCreate()")
        enableEdgeToEdge()
        setContent {
            MainScreen(onAction = ::startP2PBridgeService)
        }
    }

    companion object {
        const val TAG: String = "MainActivity"
    }
}