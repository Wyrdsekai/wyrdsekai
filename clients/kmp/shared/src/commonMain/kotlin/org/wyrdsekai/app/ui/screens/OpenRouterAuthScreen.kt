package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.wyrdsekai.app.inference.OpenRouterOAuth

/**
 * OpenRouter OAuth PKCE flow with an in-app WebView.
 *
 * Flow:
 *  1. [OpenRouterOAuth.buildAuthUrl] returns (authUrl, PkceState).
 *  2. [OpenRouterAuthWebView] renders authUrl and watches for the
 *     loopback callback. The localhost URL is unreachable but that's
 *     fine — the WebView intercepts the load attempt before any network
 *     call goes out.
 *  3. Parse {@code ?code=...} from the intercepted URL.
 *  4. Call [OpenRouterOAuth.exchangeCode] to swap code for API key.
 *  5. Fire [onApiKey]. Caller persists via TokenStore + configures the
 *     InferenceClient (anthropic/openai/openrouter share the bearer-auth
 *     header path on InferenceClient.setRemoteAuth).
 *
 * Works on Android and iOS (both host a real in-app WebView that intercepts
 * the loopback callback). Desktop falls back to the system browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouterAuthScreen(
    onApiKey: (String) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Build once. `remember` keeps the PkceState alive across recomposition
    // so the exchange call has the matching verifier.
    val authPair = remember { OpenRouterOAuth.buildAuthUrl(OpenRouterOAuth.LOOPBACK_CALLBACK) }
    val authUrl = authPair.first
    val pkce = authPair.second

    var exchanging by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect OpenRouter") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (exchanging) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Exchanging code for API key…")
                    }
                }
                return@Column
            }
            OpenRouterAuthWebView(
                authUrl = authUrl,
                callbackPrefix = OpenRouterOAuth.LOOPBACK_CALLBACK,
                onCallback = { url ->
                    val code = parseCodeFromCallbackUrl(url)
                    if (code.isNullOrEmpty()) {
                        onError("OpenRouter returned no code in callback")
                        return@OpenRouterAuthWebView
                    }
                    exchanging = true
                    scope.launch {
                        val result = OpenRouterOAuth.exchangeCode(code, pkce)
                        if (result.key != null) {
                            onApiKey(result.key)
                        } else {
                            onError(result.error ?: "Token exchange failed")
                            exchanging = false
                        }
                    }
                },
                onError = { msg -> onError(msg) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Extract the {@code code} query param from the localhost callback URL. */
private fun parseCodeFromCallbackUrl(url: String): String? {
    val q = url.substringAfter('?', "")
    if (q.isEmpty()) return null
    for (kv in q.split('&')) {
        val i = kv.indexOf('=')
        if (i > 0 && kv.substring(0, i) == "code") return kv.substring(i + 1)
    }
    return null
}
