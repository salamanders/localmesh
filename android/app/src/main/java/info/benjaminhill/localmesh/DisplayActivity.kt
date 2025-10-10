package info.benjaminhill.localmesh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.atan2
import kotlin.math.sqrt

class DisplayActivity : ComponentActivity() {

    private val pathState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContent {
            val path = pathState.value
            if (path == "motion") {
                MotionScreen(context = this)
            } else if (path != null) {
                WebViewScreen(url = "http://localhost:${LocalHttpServer.PORT}/$path")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        pathState.value = intent?.getStringExtra(EXTRA_PATH) ?: "index.html"
    }

    companion object {
        const val EXTRA_PATH = "info.benjaminhill.localmesh.EXTRA_PATH"
    }
}

@Composable
private fun MotionScreen(context: Context) {
    val sensorManager =
        remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val color = remember { mutableStateOf(Color.Black) }
    val magnitudeHistory = remember { mutableListOf<Float>() }
    var minRecentMagnitude = 9.0f
    var maxRecentMagnitude = 15.0f
    val windowSize = 150

    val sensorListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    val hue = (atan2(z, x) * (180f / Math.PI.toFloat()) + 360f) % 360f

                    val magnitude = sqrt(x * x + y * y + z * z)
                    magnitudeHistory.add(magnitude)
                    if (magnitudeHistory.size > windowSize) {
                        magnitudeHistory.removeAt(0)
                    }

                    if (magnitudeHistory.size > 1) {
                        minRecentMagnitude = magnitudeHistory.minOrNull() ?: minRecentMagnitude
                        maxRecentMagnitude = magnitudeHistory.maxOrNull() ?: maxRecentMagnitude
                    }

                    val range = maxRecentMagnitude - minRecentMagnitude
                    val lightness = if (range > 1f) {
                        ((magnitude - minRecentMagnitude) / range).coerceIn(0f, 1f)
                    } else if (magnitude >= maxRecentMagnitude) {
                        1f
                    } else {
                        0f
                    }
                    color.value = Color.hsl(hue, 1f, lightness)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(Unit) {
        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color.value)
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewScreen(url: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        val requestUrl = request?.url?.toString() ?: ""
                        val description = error?.description ?: ""
                        Log.e("DisplayActivity", "WebView Error: '$description' on $requestUrl")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        request.grant(request.resources)
                    }
                }
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
