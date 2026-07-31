package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI

/**
 * Desktop / JVM fallback.
 *
 * Compose Multiplatform on the JVM has no embedded WebView, so we can't host
 * the OpenRouter authorization page in-app the way Android does. Instead we
 * open [authUrl] in the user's system browser via [Desktop.browse] and present
 * a small form where they paste the redirect URL they land on (which begins
 * with [callbackPrefix] and carries the {@code code}/{@code state} params).
 * Pasting the URL invokes [onCallback] exactly as the embedded WebView would,
 * so the downstream callback parsing is identical across platforms.
 */
@Composable
actual fun OpenRouterAuthWebView(
    authUrl: String,
    callbackPrefix: String,
    onCallback: (url: String) -> Unit,
    onError: (message: String) -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(authUrl) {
        try {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(authUrl))
            } else {
                onError(
                    "Couldn't open a browser automatically. Copy this URL into your browser:\n$authUrl",
                )
            }
        } catch (t: Throwable) {
            onError("Couldn't open the OpenRouter authorization page: ${t.message}\n\nURL: $authUrl")
        }
    }

    var pasted by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "We opened OpenRouter in your browser. After you authorize, your browser " +
                "will redirect to a localhost address that won't load — that's expected.\n\n" +
                "Copy the full address from the browser's URL bar and paste it below.",
        )
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text("Redirect URL (starts with $callbackPrefix)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val url = pasted.text.trim()
                when {
                    url.isEmpty() -> onError("Paste the redirect URL from your browser first.")
                    !url.startsWith(callbackPrefix) ->
                        onError("That doesn't look right — the URL should start with $callbackPrefix.")
                    else -> onCallback(url)
                }
            },
            enabled = pasted.text.isNotBlank(),
        ) {
            Text("Continue")
        }
    }
}
