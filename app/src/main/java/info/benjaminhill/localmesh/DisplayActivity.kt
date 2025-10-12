package info.benjaminhill.localmesh

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import info.benjaminhill.localmesh.customdisplay.WebViewScreen

class DisplayActivity : ComponentActivity() {

    private val pathState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("DisplayActivity", "onCreate with intent: $intent")
        enableEdgeToEdge()
        handleIntent(intent)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContent {
            WebViewScreen(url = "http://localhost:${LocalHttpServer.PORT}/${pathState.value}")
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                Log.i("DisplayActivity", "Intent extra: $key = ${bundle.get(key)}")
            }
        }
        pathState.value = intent?.getStringExtra(EXTRA_PATH) ?: "index.html"
    }

    companion object {
        const val EXTRA_PATH = "info.benjaminhill.localmesh.EXTRA_PATH"
    }
}

