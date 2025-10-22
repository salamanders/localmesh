package info.benjaminhill.localmesh.display

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import info.benjaminhill.localmesh.LocalHttpServer
import info.benjaminhill.localmesh.mesh.BridgeService
import kotlinx.coroutines.channels.Channel

/**
 * A reusable, single-instance Activity that hosts the primary WebView UI.
 *
 * It is launched by both local user action and remote peer commands (via `onNewIntent`).
 * Its only role is to render web content; all application logic resides in the Ktor server and web frontend.
 * A heartbeat reports WebView health back to the BridgeService.
 */
class DisplayActivity : ComponentActivity() {

    private val pathState = mutableStateOf("index.html")
    private var bridgeService: BridgeService? = null
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val pathChannel = Channel<String>(Channel.UNLIMITED)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BridgeService.BridgeBinder
            bridgeService = binder.getService()
            Log.i(TAG, "BridgeService connected.")
            startWebViewHeartbeat()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            Log.i(TAG, "BridgeService disconnected.")
            stopWebViewHeartbeat()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate with intent: $intent")
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        handleIntent(intent)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContent {
            WebViewScreen(
                url = "http://localhost:${LocalHttpServer.PORT}/${pathState.value}",
                onWebViewReady = { webView ->
                    this@DisplayActivity.webView = webView
                }
            )
            LaunchedEffect(Unit) {
                for (path in pathChannel) {
                    pathState.value = path
                }
            }
        }

        val intent = Intent(this, BridgeService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        pathChannel.close()
        unbindService(serviceConnection)
        stopWebViewHeartbeat()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                Log.i(TAG, "Intent extra: $key = ${bundle.getString(key)}")
            }
        }
        val path = intent?.getStringExtra(EXTRA_PATH) ?: "index.html"
        pathChannel.trySend(path)
    }

    private fun startWebViewHeartbeat() {
        Log.i(TAG, "Starting WebView heartbeat.")
        handler.post(webViewHeartbeatRunnable)
    }

    private fun stopWebViewHeartbeat() {
        Log.i(TAG, "Stopping WebView heartbeat.")
        handler.removeCallbacks(webViewHeartbeatRunnable)
    }

    private val webViewHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (bridgeService == null) {
                Log.w(TAG, "Cannot run WebView heartbeat, service is not connected.")
                return
            }
            webView.evaluateJavascript("\"ok\"") { result ->
                if (result == "\"ok\"") {
                    bridgeService?.serviceHardener?.updateWebViewReportTime()
                    Log.d(TAG, "WebView heartbeat sent.")
                } else {
                    Log.w(
                        TAG,
                        "WebView heartbeat failed: JavaScript evaluation did not return 'ok'."
                    )
                }
            }
            handler.postDelayed(this, WEBVIEW_HEARTBEAT_INTERVAL_MS)
        }
    }

    companion object {
        const val EXTRA_PATH = "info.benjaminhill.localmesh.EXTRA_PATH"
        private const val WEBVIEW_HEARTBEAT_INTERVAL_MS = 60 * 1000L
        private const val TAG = "DisplayActivity"
    }
}