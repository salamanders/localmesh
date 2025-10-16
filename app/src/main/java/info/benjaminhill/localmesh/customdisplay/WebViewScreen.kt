package info.benjaminhill.localmesh.customdisplay

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A self-contained, full-screen Composable for displaying a web page.
 * This is a Jetpack Compose UI component, not a complete Android Activity.
 * It wraps a WebView to render web content passed via the [url] parameter.
 *
 * Auto-grants all permissions, logs all errors.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String, onWebViewReady: (WebView) -> Unit = {}) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                onWebViewReady(this)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.displayZoomControls = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Check if the magic function exists, and if so, call it.
                        val script = "typeof autoStartInWebView === 'function'"
                        view?.evaluateJavascript(script) { result ->
                            if ("true" == result) {
                                Log.i(
                                    "WebViewScreen",
                                    "Found autoStartInWebView in $url, executing."
                                )
                                view.evaluateJavascript("autoStartInWebView();", null)
                            } else {
                                Log.i("WebViewScreen", "No autoStartInWebView in $url, skipping.")
                            }
                        }
                    }

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
                        Log.i(
                            "WebViewScreen",
                            "Granting permission for ${request.resources.joinToString()}"
                        )
                        request.grant(request.resources)
                    }
                }
            }
        },
        update = {
            Log.i("WebViewScreen", "Updating URL to: $url")
            it.loadUrl(url)
        },
        modifier = Modifier.fillMaxSize()
    )
}
