package info.benjaminhill.localmesh

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView


/**
 * An activity dedicated to displaying web content in a full-screen WebView.
 *
 * ## What it does
 * - Renders a web page from a given URL, typically pointing to the local Ktor server.
 * - Enables JavaScript and allows camera/mic permissions for rich web applications.
 * - Enables remote debugging for the WebView in debug builds.
 *
 * ## What it doesn't do
 * - It does not contain any application logic itself. It is a generic container for web content.
 */
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
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE

                        // Capture and log web errors to Logcat under our app's tag.
                        // This is crucial for debugging, as WebView errors can otherwise
                        // be difficult to spot in the system-wide log.
                        webViewClient = object : WebViewClient() {
                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                val url = request?.url?.toString() ?: ""
                                val description = error?.description ?: ""
                                Log.e("WebViewActivity", "WebView Error: '$description' on $url")
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
    }

    companion object {
        const val EXTRA_URL = "info.benjaminhill.localmesh.EXTRA_URL"
    }
}