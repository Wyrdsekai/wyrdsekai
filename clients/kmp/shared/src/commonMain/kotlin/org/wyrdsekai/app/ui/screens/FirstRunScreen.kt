package org.wyrdsekai.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.wyrdsekai.app.engine.discovery.DiscoveredInference
import org.wyrdsekai.app.engine.discovery.InferenceDiscovery
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.network.PairingClient
import org.wyrdsekai.app.state.TokenStore

/**
 * First-run setup wizard -- step-by-step instead of a single scrollable form.
 *
 * Steps (local mode):
 *   1. Mode selection -- "My companion lives here" vs "Connect to household"
 *   2. Name your companion
 *   3. Find household server (network scan + manual URL)
 *   4. Pair with server (6-digit code entry)
 *   5. (Invisible) Triggers onLocalMode callback and transitions to BirthScreen
 *
 * Remote mode: Step 1 immediately calls onRemoteMode.
 *
 * Model download starts in the background as soon as the user picks local mode
 * (step 1), running through steps 2-4 so it may be done by step 5.
 */
@Composable
fun FirstRunScreen(
    onLocalMode: (companionName: String, inferenceUrl: String?) -> Unit,
    onLocalModeWithAnswers: ((companionName: String, answers: Map<String, String>, inferenceUrl: String?) -> Unit)? = null,
    onRemoteMode: () -> Unit,
    onStartModelDownload: (() -> Unit)? = null,
    modelDownloadProgress: Float = 0f,
    tokenStore: TokenStore? = null,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(1) }
    var companionName by remember { mutableStateOf("Wyrd") }
    var inferenceUrl by remember { mutableStateOf("") }
    var selectedServerUrl by remember { mutableStateOf("") }
    var discoveredEndpoints by remember { mutableStateOf<List<DiscoveredInference>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize().testTag("first-run-screen")) {
        // Use AnimatedVisibility per step for clean transitions
        AnimatedVisibility(
            visible = step == 1,
            enter = fadeIn(),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it }),
        ) {
            WizardStep1_ModeSelection(
                onLocal = {
                    onStartModelDownload?.invoke()
                    step = 2
                },
                onRemote = onRemoteMode,
            )
        }

        AnimatedVisibility(
            visible = step == 2,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it }),
        ) {
            WizardStep2_CompanionName(
                companionName = companionName,
                onNameChanged = { companionName = it },
                onNext = { step = 3 },
                onBack = { step = 1 },
                downloadProgress = modelDownloadProgress,
            )
        }

        AnimatedVisibility(
            visible = step == 3,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it }),
        ) {
            WizardStep3_FindServer(
                inferenceUrl = inferenceUrl,
                onUrlChanged = { inferenceUrl = it },
                discoveredEndpoints = discoveredEndpoints,
                scanning = scanning,
                onScan = {
                    scanning = true
                    scope.launch {
                        discoveredEndpoints = InferenceDiscovery.discover()
                        scanning = false
                        val best = InferenceDiscovery.bestEndpoint(discoveredEndpoints)
                        if (best != null) inferenceUrl = best.url
                    }
                },
                onNext = { url ->
                    selectedServerUrl = url
                    step = 4
                },
                onSkip = {
                    onLocalMode(companionName.trim().ifEmpty { "Wyrd" }, null)
                },
                onBack = { step = 2 },
                downloadProgress = modelDownloadProgress,
            )
        }

        AnimatedVisibility(
            visible = step == 4,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut(),
        ) {
            WizardStep4_PairWithServer(
                serverUrl = selectedServerUrl,
                companionName = companionName.trim().ifEmpty { "Wyrd" },
                tokenStore = tokenStore,
                onPaired = { serverUrl ->
                    onLocalMode(companionName.trim().ifEmpty { "Wyrd" }, serverUrl)
                },
                onBack = { step = 3 },
                downloadProgress = modelDownloadProgress,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1 -- Mode Selection
// ---------------------------------------------------------------------------

@Composable
private fun WizardStep1_ModeSelection(
    onLocal: () -> Unit,
    onRemote: () -> Unit,
) {
    val strings = LocalUiStrings.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = strings.appTitle,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = strings.firstRunWelcome,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))

        // "My companion lives here" card
        OutlinedCard(
            onClick = onLocal,
            modifier = Modifier.fillMaxWidth().testTag("local-mode-card"),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = strings.firstRunLocalTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.firstRunLocalDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // "Connect to household" card
        OutlinedCard(
            onClick = onRemote,
            modifier = Modifier.fillMaxWidth().testTag("remote-mode-card"),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = strings.firstRunRemoteTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.firstRunRemoteDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2 -- Name Your Companion
// ---------------------------------------------------------------------------

@Composable
private fun WizardStep2_CompanionName(
    companionName: String,
    onNameChanged: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    downloadProgress: Float,
) {
    val strings = LocalUiStrings.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scroll + imePadding so the API-key Continue button rises above the soft
            // keyboard instead of being trapped behind it (#1240).
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(32.dp)
            .testTag("wizard-step-2"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = strings.firstRunCompanionNameLabel,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = companionName,
            onValueChange = onNameChanged,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("companion-name-input"),
        )

        Spacer(Modifier.height(32.dp))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Button(
                onClick = onNext,
                enabled = companionName.isNotBlank(),
                modifier = Modifier.testTag("next-button"),
            ) {
                Text("Next")
            }
        }

        // Download progress indicator at bottom
        if (downloadProgress > 0f && downloadProgress < 1f) {
            Spacer(Modifier.height(32.dp))
            DownloadProgressBar(progress = downloadProgress)
        }
    }
}

// ---------------------------------------------------------------------------
// Step 3 -- Find Server (network scan + manual URL)
// ---------------------------------------------------------------------------

@Composable
private fun WizardStep3_FindServer(
    inferenceUrl: String,
    onUrlChanged: (String) -> Unit,
    discoveredEndpoints: List<DiscoveredInference>,
    scanning: Boolean,
    onScan: () -> Unit,
    onNext: (serverUrl: String) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    downloadProgress: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("wizard-step-3"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Do you have a household server on your network?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "A household server lets your companion think more deeply.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        // Scan button
        Button(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth().testTag("household-scan-button"),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Scanning...")
            } else {
                Text("Scan Network")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Discovered endpoints
        for (endpoint in discoveredEndpoints) {
            val isSelected = inferenceUrl == endpoint.url
            OutlinedCard(
                onClick = { onUrlChanged(endpoint.url) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                ),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(endpoint.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        endpoint.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Manual URL entry
        OutlinedTextField(
            value = inferenceUrl,
            onValueChange = onUrlChanged,
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.x:7070") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("inference-url-input"),
        )

        Spacer(Modifier.height(24.dp))

        // Navigation buttons: Back / Skip / Next
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSkip, modifier = Modifier.testTag("skip-button")) {
                    Text("Skip")
                }
                Button(
                    onClick = { onNext(inferenceUrl.trim()) },
                    enabled = inferenceUrl.isNotBlank(),
                ) {
                    Text("Next")
                }
            }
        }

        // Download progress indicator at bottom
        if (downloadProgress > 0f && downloadProgress < 1f) {
            Spacer(Modifier.height(32.dp))
            DownloadProgressBar(progress = downloadProgress)
        }
    }
}

// ---------------------------------------------------------------------------
// Step 4 -- Pair with Server (6-digit code entry)
// ---------------------------------------------------------------------------

@Composable
private fun WizardStep4_PairWithServer(
    serverUrl: String,
    companionName: String,
    tokenStore: TokenStore?,
    onPaired: (serverUrl: String) -> Unit,
    onBack: () -> Unit,
    downloadProgress: Float,
) {
    var pairingCode by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var challengeId by remember { mutableStateOf<String?>(null) }
    var requesting by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Request pairing challenge when this step appears — generates the code on the server
    LaunchedEffect(Unit) {
        requesting = true
        val challenge = PairingClient.requestPairing(
            serverUrl = serverUrl,
            deviceName = companionName,
            deviceType = "phone",
        )
        if (challenge != null) {
            challengeId = challenge.challengeId
        } else {
            errorMessage = "Could not reach server at $serverUrl"
        }
        requesting = false
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("wizard-step-4"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pair with Server",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        if (requesting) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text("Requesting pairing code from server...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                text = "A pairing code has been sent to your server.\nIt will appear on any connected device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Connect to your server first if you haven't:\n" +
                    "• telnet ${serverUrl.removePrefix("http://").substringBefore(":")} 7071\n" +
                    "• Browser: $serverUrl\n" +
                    "• CLI: wyrdsekai pair-code",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Check your server's console, web UI at $serverUrl, or ask your companion on another device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        // 6-digit code input
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { value ->
                // Only allow digits, max 6
                val filtered = value.filter { it.isDigit() }.take(6)
                pairingCode = filtered
            },
            label = { Text("Pairing Code") },
            placeholder = { Text("000000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("pairing-code-input"),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                textAlign = TextAlign.Center,
                letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing * 2,
            ),
            isError = errorMessage != null,
            supportingText = if (errorMessage != null) {
                { Text(errorMessage!!, color = MaterialTheme.colorScheme.error) }
            } else null,
        )

        Spacer(Modifier.height(24.dp))

        // Navigation: Back / Verify
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack, enabled = !verifying) {
                Text("Back")
            }

            Button(
                onClick = {
                    verifying = true
                    errorMessage = null
                    scope.launch {
                        val currentChallengeId = challengeId
                        if (currentChallengeId == null) {
                            errorMessage = "No pairing session. Go back and try again."
                            verifying = false
                            return@launch
                        }
                        val credentials = PairingClient.verifyCode(
                            serverUrl = serverUrl,
                            challengeId = currentChallengeId,
                            code = pairingCode,
                        )
                        if (credentials == null) {
                            errorMessage = "Invalid code. Check the code and try again."
                            verifying = false
                            return@launch
                        }
                        // Save pairing credentials
                        tokenStore?.savePairingToken(credentials.token)
                        tokenStore?.saveHouseholdId(credentials.householdId)
                        tokenStore?.saveHouseholdName(credentials.householdName)
                        tokenStore?.saveServerDid(credentials.serverDid)
                        tokenStore?.saveNatsUrl(credentials.natsUrl)
                        tokenStore?.saveServerUrl(credentials.serverUrl)
                        credentials.relayUrl?.let { tokenStore?.saveRelayUrl(it) }
                        credentials.relayToken?.let { tokenStore?.saveRelayToken(it) }
                        verifying = false
                        onPaired(credentials.serverUrl)
                    }
                },
                enabled = pairingCode.length == 6 && !verifying && !requesting && challengeId != null,
                modifier = Modifier.testTag("verify-button"),
            ) {
                if (verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Verifying...")
                } else {
                    Text("Verify")
                }
            }
        }

        // Download progress indicator at bottom
        if (downloadProgress > 0f && downloadProgress < 1f) {
            Spacer(Modifier.height(32.dp))
            DownloadProgressBar(progress = downloadProgress)
        }
    }
}

// ---------------------------------------------------------------------------
// Shared download progress bar
// ---------------------------------------------------------------------------

@Composable
private fun DownloadProgressBar(progress: Float) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Downloading model... ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
