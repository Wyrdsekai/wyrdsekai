package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.ui.QrScannerPane
import org.wyrdsekai.app.ui.qrScanningSupported
import org.wyrdsekai.app.ui.enableTestTagsAsResourceId

/**
 * Welcome / onboarding screen — shown on first launch only.
 *
 * 2026-07-22 redesign (, parity with RN WelcomeScreen):
 * the first question is WHERE THE COMPANION LIVES, not what infrastructure
 * the user has.
 *
 *   ⌂ On my home machines → Mode 1 (remote terminal). One input takes an
 *     invite OR a LAN address; the caller picks the transport (invite →
 *     zone bank → servers surface, plain address → direct remote login).
 *     No "Use my server" / "Log in to my account" ambiguity.
 *
 *   ◎ On this phone → standalone mini-zone (Modes 2/3): cloud API key,
 *     on-device model, or (advanced, preserved capability) a home server
 *     used purely as the inference endpoint.
 */
@Composable
fun WelcomeScreen(
    /**
     * [onDeviceModel] means the user explicitly chose to run the model on this
     * device. That choice IS the experimental opt-in — it is the one path with
     * nothing else to think with, so the phone must be allowed to try. Every
     * other path (home zone, cloud key, server endpoint) leaves it false and
     * lands on mode 1 or 2.
     */
    onComplete: (
        serverUrl: String?,
        apiProvider: String?,
        apiKey: String?,
        onDeviceModel: Boolean,
    ) -> Unit,
    /** Home-zone path: invite or server address. Returns an error string to
     * display, or null after the caller has navigated. */
    onHomeZone: ((input: String) -> String?)? = null,
    onMyServers: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Steps: "live-where" | "home-connect" | "standalone-think" | "api-key"
    var step by remember { mutableStateOf("live-where") }
    var homeInput by remember { mutableStateOf("") }
    var homeError by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var showServerInference by remember { mutableStateOf(false) }
    var apiProvider by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        QrScannerPane(
            onResult = { scanned ->
                homeInput = scanned
                homeError = ""
                showScanner = false
            },
            onDismiss = { showScanner = false },
        )
        return
    }

    Scaffold(modifier = modifier.enableTestTagsAsResourceId()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Reserve space for the soft keyboard so the scroll region ends
                // above it — the primary button stays reachable by scrolling
                // (iOS otherwise hides it behind the keyboard).
                .imePadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                "Wyrdsekai",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.testTag("welcome-title"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Your personal Study — a private space for\nyour thoughts, notes, and a companion\nwho learns your patterns.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            when (step) {
                // Step: where does your companion live?
                "live-where" -> {
                    Text(
                        "Where does your companion live?",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedCard(
                        onClick = { step = "home-connect" },
                        modifier = Modifier.fillMaxWidth().testTag("welcome-home-zone"),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("⌂  On my home machines", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "I have a zone — connect this phone to it.\nThe phone becomes a window to the companion\nwho lives there.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedCard(
                        onClick = { step = "standalone-think" },
                        modifier = Modifier.fillMaxWidth().testTag("welcome-standalone-mode"),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("◎  On this phone", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "A standalone companion, born here.\nNo home zone needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // reach the held "Your servers" address
                    // book: zones added from invites + synced across your devices.
                    if (onMyServers != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { onMyServers() },
                            modifier = Modifier.fillMaxWidth().testTag("welcome-my-servers"),
                        ) {
                            Text("My servers")
                        }
                    }
                }

                // Step: connect to the home zone (invite OR LAN address).
                "home-connect" -> {
                    Text(
                        "Connect to your zone",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Paste an invite, or type your server's address\nif you're on the same network.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = homeInput,
                        onValueChange = { homeInput = it; homeError = "" },
                        label = { Text("Invite or server address") },
                        placeholder = { Text("wyrdphone://…  or  http://192.168.1.x:7070") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("welcome-home-input"),
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (homeInput.isNotBlank() && onHomeZone != null) {
                                homeError = onHomeZone(homeInput.trim()) ?: ""
                            }
                        },
                        enabled = homeInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("welcome-connect"),
                    ) {
                        Text("Connect")
                    }

                    if (qrScanningSupported) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showScanner = true },
                            modifier = Modifier.fillMaxWidth().testTag("welcome-scan-invite"),
                        ) {
                            Text("Scan invite QR")
                        }
                    }

                    if (homeError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            homeError,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("welcome-home-error"),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Don't have an invite? On your node, run:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "wyrd phone invite",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { homeError = ""; step = "live-where" }) {
                        Text("Back")
                    }
                }

                // Step: standalone — how should your companion think?
                "standalone-think" -> {
                    Text(
                        "How should your companion think?",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "A standalone companion needs a way to reason.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedButton(
                        onClick = { step = "api-key" },
                        modifier = Modifier.fillMaxWidth().testTag("welcome-use-api"),
                    ) {
                        Text("Cloud API key")
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { onComplete(null, null, null, true) },
                        modifier = Modifier.fillMaxWidth().testTag("welcome-standalone"),
                    ) {
                        Text("On-device model (works offline) \u00b7 EXPERIMENTAL")
                    }

                    // Preserved capability: a home server used purely as the
                    // inference endpoint for the phone-local companion (NOT a
                    // home-zone login).
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showServerInference = !showServerInference },
                        modifier = Modifier.fillMaxWidth().testTag("welcome-server-inference-toggle"),
                    ) {
                        Text(if (showServerInference) "Hide" else "Advanced: my server provides inference")
                    }

                    if (showServerInference) {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("Server address") },
                            placeholder = { Text("http://192.168.1.x:7070") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("welcome-server-url"),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onComplete(serverUrl.ifBlank { null }, null, null, false) },
                            enabled = serverUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("welcome-use-server"),
                        ) {
                            Text("Use my server for thinking")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { step = "live-where" }) {
                        Text("Back")
                    }
                }

                // Step: API key provider selection + key input
                "api-key" -> {
                    Text(
                        "Which service?",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val providers = listOf(
                        "openrouter" to "OpenRouter (recommended)\nAccess Claude, GPT, Llama — one account",
                        "anthropic" to "Anthropic (Claude)\nconsole.anthropic.com/settings/keys",
                        "openai" to "OpenAI\nplatform.openai.com/api-keys",
                        "custom" to "Custom endpoint\nAny OpenAI-compatible API",
                    )

                    for ((id, label) in providers) {
                        val selected = apiProvider == id
                        OutlinedButton(
                            onClick = { apiProvider = id },
                            modifier = Modifier.fillMaxWidth().testTag("welcome-provider-$id"),
                            colors = if (selected) ButtonDefaults.filledTonalButtonColors()
                            else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(label, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (apiProvider.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        if (apiProvider == "custom") {
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("API base URL") },
                                placeholder = { Text("http://localhost:8080") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Shared by the Continue button and the keyboard's Done
                        // action. The latter matters on iOS, where the soft
                        // keyboard covers the Continue button with no dismiss
                        // affordance — pressing Done submits without reaching it.
                        val submitApiKey = {
                            if (apiKey.isNotBlank()) {
                                onComplete(
                                    serverUrl.ifBlank { null },
                                    apiProvider,
                                    apiKey.ifBlank { null },
                                    false,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API key") },
                            placeholder = { Text("sk-...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitApiKey() }),
                            modifier = Modifier.fillMaxWidth().testTag("welcome-api-key"),
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = submitApiKey,
                            enabled = apiKey.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("welcome-api-done"),
                        ) {
                            Text("Continue")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { step = "standalone-think" }) {
                        Text("Back")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
