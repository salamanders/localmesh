package info.benjaminhill.localmesh

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import info.benjaminhill.localmesh.ui.theme.LocalMeshTheme

class WebViewActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        enableEdgeToEdge()
        val url = intent.getStringExtra(EXTRA_URL)
            ?: "http://localhost:${LocalHttpServer.PORT}/index.html"

        setContent {
            LocalMeshTheme {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    companion object {
        const val EXTRA_URL = "info.benjaminhill.localmesh.EXTRA_URL"
    }
}