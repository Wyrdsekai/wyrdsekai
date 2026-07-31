package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.wyrdsekai.app.engine.discovery.DiscoveredInference
import org.wyrdsekai.app.engine.discovery.InferenceDiscovery
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.viewmodel.ConnectionViewModel

@Composable
fun ConnectScreen(
    viewModel: ConnectionViewModel,
    onSwitchToLocal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()
    val error by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Network scan state
    var discoveredServers by remember { mutableStateOf<List<DiscoveredInference>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }

    // on iOS there is no system back key to dismiss the soft
    // keyboard, and the form is not tall enough to push the Login button above
    // it: without a scroll the button is permanently occluded (the relay login
    // can never be triggered). Make the column scrollable so the button can rise
    // above the keyboard, and give the password field an IME "Done" action that
    // clears focus (dismisses the keyboard) — both reachable to a real user.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Extend the scroll region by the on-screen keyboard's height so the
            // Login/Register buttons can scroll ABOVE the soft keyboard instead of
            // being trapped behind it (iOS especially — same class as #1240). Without
            // this, verticalScroll alone can't help: content bottoms out at the screen
            // edge and the keyboard overlays the buttons with nowhere to scroll.
            .imePadding()
            .padding(32.dp)
            .testTag("connect-screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = strings.appTitle,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(32.dp))

        // ── Network scan section ────────────────────────────────────────
        OutlinedButton(
            onClick = {
                scanning = true
                scope.launch {
                    discoveredServers = InferenceDiscovery.discover()
                    scanning = false
                    // Auto-select the best server found
                    val best = InferenceDiscovery.bestEndpoint(discoveredServers)
                    if (best != null) {
                        viewModel.setServerUrl(best.url)
                    }
                }
            },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth().testTag("network-scan-button"),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Scanning network...")
            } else {
                Text("Scan Network")
            }
        }

        // Discovered servers list
        for (server in discoveredServers) {
            val isSelected = serverUrl == server.url
            OutlinedCard(
                onClick = { viewModel.setServerUrl(server.url) },
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
                    Text(server.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Manual server URL ───────────────────────────────────────────
        OutlinedTextField(
            value = serverUrl,
            onValueChange = viewModel::setServerUrl,
            label = { Text(strings.serverUrl) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("server-url-input"),
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = viewModel::setUsername,
            label = { Text(strings.username) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("username-input"),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = viewModel::setPassword,
            label = { Text(strings.password) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().testTag("password-input"),
        )

        Spacer(Modifier.height(16.dp))

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("error-text"),
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = viewModel::login,
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.testTag("login-button"),
            ) {
                Text(strings.login)
            }

            OutlinedButton(
                onClick = viewModel::register,
                enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.testTag("register-button"),
            ) {
                Text(strings.register)
            }
        }

        // Deliberately absent (2026-07-29), matching the RN twin:
        //   * "Connect without account" — an auth bypass sitting one tap from a
        //     logged-out person, on a screen they reached by going BACKWARDS.
        //   * "Switch to local companion instead" — the last door into the
        //     legacy FirstRunScreen.
        // WelcomeScreen owns onboarding and ServersHost owns the zone bank.
        // This is a login form for one already-chosen server; keep it that way.

        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
    }
}
