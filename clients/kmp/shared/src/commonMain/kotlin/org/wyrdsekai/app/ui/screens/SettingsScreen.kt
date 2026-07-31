package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.network.WyrdWebSocket
import org.wyrdsekai.app.state.TokenStore
import org.wyrdsekai.app.viewmodel.HouseholdViewModel
import org.wyrdsekai.app.viewmodel.InferenceViewModel
import org.wyrdsekai.app.viewmodel.NodeViewModel
import org.wyrdsekai.app.viewmodel.RoomViewModel

private val API_PROVIDERS = listOf(
    "openai" to "OpenAI",
    "anthropic" to "Anthropic",
    "openrouter" to "OpenRouter",
    "custom" to "Custom",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    roomViewModel: RoomViewModel,
    serverUrl: String,
    username: String,
    onLogout: () -> Unit,
    onClose: () -> Unit,
    nodeViewModel: NodeViewModel? = null,
    inferenceViewModel: InferenceViewModel? = null,
    onManageModels: () -> Unit = {},
    householdViewModel: HouseholdViewModel? = null,
    onManageHousehold: () -> Unit = {},
    tokenStore: TokenStore? = null,
    webSocket: WyrdWebSocket? = null,
    onLocaleChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val languages = listOf("en" to "English", "es" to "Espa\u00f1ol", "ja" to "\u65e5\u672c\u8a9e")
    var selectedLang by remember { mutableStateOf(tokenStore?.loadLocale() ?: "en") }

    // Connection state
    val connectionState = webSocket?.connectionState?.collectAsState()

    // Inference / API key state
    var selectedProvider by remember { mutableStateOf(tokenStore?.loadApiProvider() ?: "openai") }
    var apiKey by remember { mutableStateOf(tokenStore?.loadApiKey() ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiBaseUrl by remember { mutableStateOf(tokenStore?.loadApiBaseUrl() ?: "") }

    // Companion state
    var companionName by remember { mutableStateOf(tokenStore?.loadCompanionName() ?: "") }

    // Advanced state
    var inferenceUrlOverride by remember { mutableStateOf(tokenStore?.loadInferenceUrl() ?: "") }
    var debugMode by remember { mutableStateOf(tokenStore?.loadDebugMode() ?: false) }

    // OpenRouter PKCE dialog state. `showOpenRouterAuth` opens the
    // full-screen WebView; the result (key or error) lands back via the
    // OpenRouterAuthScreen callbacks below.
    var showOpenRouterAuth by remember { mutableStateOf(false) }
    var openRouterAuthError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.settings) },
                actions = {
                    TextButton(onClick = onClose) {
                        Text(strings.close)
                    }
                },
            )
        },
        modifier = modifier.testTag("settings-screen"),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------------------------------------------------------------
            // CONNECTION section
            // ---------------------------------------------------------------
            SectionHeader(strings.connection)

            OutlinedTextField(
                value = serverUrl,
                onValueChange = {},
                label = { Text(strings.serverUrl) },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Connection status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isConnected = connectionState?.value?.name == "CONNECTED"
                val dotColor = if (isConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                Surface(
                    shape = RoundedCornerShape(50),
                    color = dotColor,
                    modifier = Modifier.size(10.dp),
                ) {}
                Text(
                    text = "${strings.connectionStatus}: ${
                        if (isConnected) strings.connected else strings.disconnected
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = {},
                label = { Text(strings.username) },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------------
            // INFERENCE section
            // ---------------------------------------------------------------
            SectionHeader(strings.inference)

            // Provider selector
            Text(
                text = strings.apiProvider,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                API_PROVIDERS.forEach { (code, label) ->
                    val isSelected = selectedProvider == code
                    OutlinedButton(
                        onClick = {
                            selectedProvider = code
                            tokenStore?.saveApiProvider(code)
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("provider-$code"),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // API key field
            OutlinedTextField(
                value = apiKey,
                onValueChange = { newKey ->
                    apiKey = newKey
                    tokenStore?.saveApiKey(newKey)
                },
                label = { Text(strings.apiKey) },
                placeholder = { Text(strings.apiKeyPlaceholder) },
                singleLine = true,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(if (apiKeyVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("api-key-field"),
            )

            // OpenRouter PKCE — replaces paste-key with a browser-based
            // sign-in. The token lands back in `apiKey` automatically.
            if (selectedProvider == "openrouter") {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showOpenRouterAuth = true },
                    modifier = Modifier.fillMaxWidth().testTag("openrouter-connect-button"),
                ) {
                    Text("Sign in with OpenRouter")
                }
                openRouterAuthError?.let { msg ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Base URL for custom provider
            if (selectedProvider == "custom") {
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { newUrl ->
                        apiBaseUrl = newUrl
                        tokenStore?.saveApiBaseUrl(newUrl)
                    },
                    label = { Text(strings.apiBaseUrl) },
                    placeholder = { Text(strings.apiBaseUrlPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("api-base-url-field"),
                )
            }

            // Local inference model info (only when available)
            if (inferenceViewModel != null && inferenceViewModel.isAvailable) {
                val activeModelId by inferenceViewModel.activeModelId.collectAsState()
                val inferenceServerState by inferenceViewModel.serverState.collectAsState()

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strings.activeModel)
                    Text(
                        text = activeModelId ?: strings.none,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strings.backend)
                    Text(
                        text = inferenceViewModel.getActiveBackend(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = strings.statusTemplate.replace("%s", inferenceServerState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = onManageModels,
                    modifier = Modifier.fillMaxWidth().testTag("manage-models-button"),
                ) {
                    Text(strings.manageModels)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------------
            // COMPANION section
            // ---------------------------------------------------------------
            SectionHeader(strings.companion)

            OutlinedTextField(
                value = companionName,
                onValueChange = { newName ->
                    companionName = newName
                    tokenStore?.saveCompanionName(newName)
                },
                label = { Text(strings.companionName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("companion-name-field"),
            )

            OutlinedButton(
                onClick = { /* TODO: soul seed import flow */ },
                modifier = Modifier.fillMaxWidth().testTag("soul-seed-import-button"),
            ) {
                Text(strings.soulSeedImport)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------------
            // LANGUAGE section
            // ---------------------------------------------------------------
            SectionHeader(strings.language)

            // RTL support: when Arabic/Hebrew locales are added,
            // wrap root with CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)
            // See I18nStrings.isRtl()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                languages.forEach { (code, name) ->
                    val isSelected = selectedLang == code
                    OutlinedButton(
                        onClick = {
                            selectedLang = code
                            tokenStore?.saveLocale(code)
                            webSocket?.setLocale(code)
                            roomViewModel.setPreference("locale", code)
                            onLocaleChanged(code)
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.primary,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Text(name)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------------
            // ADVANCED section
            // ---------------------------------------------------------------
            SectionHeader(strings.advanced)

            // Local Node toggle (only when available)
            if (nodeViewModel != null && nodeViewModel.isAvailable) {
                val currentNodeState by nodeViewModel.nodeState.collectAsState()
                val nodeError by nodeViewModel.errorMessage.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strings.runLocalNode)
                    Switch(
                        checked = currentNodeState == "running" || currentNodeState == "starting",
                        onCheckedChange = { nodeViewModel.toggleNode() },
                    )
                }

                Text(
                    text = strings.statusTemplate.replace("%s", currentNodeState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (currentNodeState == "error" && nodeError != null) {
                    Text(
                        text = nodeError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (currentNodeState == "running" || currentNodeState == "starting") {
                    OutlinedButton(
                        onClick = { nodeViewModel.stopNode() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().testTag("stop-node-button"),
                    ) {
                        Text(strings.stopNode)
                    }
                }

                Spacer(Modifier.height(4.dp))
            }

            // Inference URL override
            OutlinedTextField(
                value = inferenceUrlOverride,
                onValueChange = { newUrl ->
                    inferenceUrlOverride = newUrl
                    tokenStore?.saveInferenceUrl(newUrl)
                },
                label = { Text(strings.inferenceUrlOverride) },
                placeholder = { Text("http://198.51.100.10:11434") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("inference-url-override-field"),
            )

            // Debug mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.debugMode)
                Switch(
                    checked = debugMode,
                    onCheckedChange = { enabled ->
                        debugMode = enabled
                        tokenStore?.saveDebugMode(enabled)
                    },
                    modifier = Modifier.testTag("debug-mode-toggle"),
                )
            }

            // Household section (only when available)
            if (householdViewModel != null && householdViewModel.isAvailable) {
                val hhState by householdViewModel.connectivityState.collectAsState()

                Spacer(Modifier.height(4.dp))

                Text(
                    text = strings.statusTemplate.replace("%s", hhState.name.lowercase()
                        .replace('_', ' ')
                        .replaceFirstChar { it.uppercaseChar() }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = onManageHousehold,
                    modifier = Modifier.fillMaxWidth().testTag("manage-household-button"),
                ) {
                    Text("Manage Household")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Logout
            Spacer(Modifier.weight(1f))

            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().testTag("logout-button"),
            ) {
                Text(strings.logout)
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // OpenRouter OAuth WebView, presented as a full-screen Dialog so the
    // WebView gets real estate without us having to plumb new state through
    // WyrdApp.kt. Android = android.webkit.WebView, iOS = WKWebView; both
    // intercept the loopback PKCE callback in-process.
    if (showOpenRouterAuth) {
        Dialog(
            onDismissRequest = { showOpenRouterAuth = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            OpenRouterAuthScreen(
                onApiKey = { key ->
                    apiKey = key
                    apiKeyVisible = false
                    openRouterAuthError = null
                    tokenStore?.saveApiKey(key)
                    if (selectedProvider != "openrouter") {
                        selectedProvider = "openrouter"
                        tokenStore?.saveApiProvider("openrouter")
                    }
                    showOpenRouterAuth = false
                },
                onCancel = { showOpenRouterAuth = false },
                onError = { msg ->
                    openRouterAuthError = msg
                    showOpenRouterAuth = false
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}
