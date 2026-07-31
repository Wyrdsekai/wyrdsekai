package org.wyrdsekai.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific OAuth WebView host.
 *
 * Renders the OpenRouter authorization URL in an in-app browser surface
 * and watches the navigation stream for the loopback callback. When a
 * URL starting with [callbackPrefix] is loaded, the WebView aborts the
 * load (the localhost endpoint doesn't actually exist on-device) and
 * invokes [onCallback] with the raw URL so the caller can parse out
 * {@code code} and {@code state} query params.
 *
 * On Android this wraps a [android.webkit.WebView] inside an
 * `AndroidView`; on iOS a `WKWebView` inside a `UIKitView` (its
 * `decidePolicyForNavigationAction` is the analogue of Android's
 * `shouldOverrideUrlLoading`). Desktop has no in-app browser, so it opens
 * the system browser and the user pastes the resulting key.
 */
@Composable
expect fun OpenRouterAuthWebView(
    authUrl: String,
    callbackPrefix: String,
    onCallback: (url: String) -> Unit,
    onError: (message: String) -> Unit,
    modifier: Modifier = Modifier,
)
