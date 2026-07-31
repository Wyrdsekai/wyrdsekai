@file:OptIn(ExperimentalForeignApi::class)

package org.wyrdsekai.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyCancel
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * iOS WKWebView host for OpenRouter OAuth — the structural twin of the
 * Android `WebViewClient` actual.
 *
 * SFSafariViewController (the "obvious" iOS choice) can't do this: it runs
 * the page out-of-process and exposes no navigation delegate, so the app
 * could never see the `http://localhost:3000/callback?code=…` redirect that
 * carries the PKCE code. WKWebView does, via
 * `decidePolicyForNavigationAction` — the exact analogue of Android's
 * `shouldOverrideUrlLoading`. We cancel the (unrouted) localhost load and
 * hand the URL up before any network request goes out.
 */
@Composable
actual fun OpenRouterAuthWebView(
    authUrl: String,
    callbackPrefix: String,
    onCallback: (url: String) -> Unit,
    onError: (message: String) -> Unit,
    modifier: Modifier,
) {
    // The delegate must outlive recomposition AND be strongly held — WKWebView
    // keeps only a weak navigationDelegate ref, so a transient would be GC'd
    // mid-flow and interception would silently stop.
    val delegate = remember {
        OpenRouterNavigationDelegate(callbackPrefix, onCallback, onError)
    }
    UIKitView(
        factory = {
            WKWebView(
                frame = CGRectZero.readValue(),
                configuration = WKWebViewConfiguration(),
            ).apply {
                navigationDelegate = delegate
                NSURL.URLWithString(authUrl)?.let {
                    loadRequest(NSURLRequest.requestWithURL(it))
                }
            }
        },
        modifier = modifier,
    )
}

private class OpenRouterNavigationDelegate(
    private val callbackPrefix: String,
    private val onCallback: (String) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    // The callback fires per matching navigation; deliver the code once.
    private var delivered = false

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        if (url != null && url.startsWith(callbackPrefix)) {
            if (!delivered) {
                delivered = true
                onCallback(url)
            }
            decisionHandler(WKNavigationActionPolicyCancel) // localhost has no listener
            return
        }
        decisionHandler(WKNavigationActionPolicyAllow)
    }

    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        // A failed load of the loopback callback is self-inflicted (we point
        // OpenRouter at an unrouted address); decidePolicy already handled it.
        // Anything else is a real navigation failure worth surfacing.
        if (delivered) return
        onError(withError.localizedDescription)
    }
}
