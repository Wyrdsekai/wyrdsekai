package org.wyrdsekai.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android WebView host for OpenRouter OAuth.
 *
 * The PKCE callback URL is {@code http://localhost:3000/callback?code=...&state=...}.
 * OpenRouter only permits https:443, https:3000, or http://localhost:3000 as
 * callback URLs — none of which we actually serve. We don't need to: the
 * WebView's {@link WebViewClient#shouldOverrideUrlLoading} fires *before* the
 * network request goes out, so we extract the {@code ?code=} param and never
 * let the (doomed) localhost request actually hit the network.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun OpenRouterAuthWebView(
    authUrl: String,
    callbackPrefix: String,
    onCallback: (url: String) -> Unit,
    onError: (message: String) -> Unit,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith(callbackPrefix)) {
                            onCallback(url)
                            return true // abort load — localhost has no listener
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (url != null && url.startsWith(callbackPrefix)) {
                            // Fallback for old WebView versions where shouldOverrideUrlLoading
                            // doesn't fire on first navigation (rare on Android 7+).
                            onCallback(url)
                            view?.stopLoading()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?, request: WebResourceRequest?, error: WebResourceError?,
                    ) {
                        val url = request?.url?.toString()
                        // Localhost-callback failures aren't real errors — we caused them by
                        // pointing OR at an unrouted address. shouldOverrideUrlLoading
                        // should have caught these first; if we still got here, swallow.
                        if (url != null && url.startsWith(callbackPrefix)) return
                        onError(error?.description?.toString() ?: "WebView load failed")
                    }
                }
                loadUrl(authUrl)
            }
        },
        update = { /* authUrl never changes after first render */ },
    )
}
