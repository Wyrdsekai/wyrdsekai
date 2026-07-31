package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.wyrdsekai.app.network.AuthClient
import org.wyrdsekai.app.network.ServerStatus
import org.wyrdsekai.app.state.TokenStore
import org.wyrdsekai.app.platform.AppProps

/**
 * Login/register screen shown after pairing and before BirthScreen.
 *
 * Queries GET /api/auth/status on mount to determine mode:
 * - First user (no accounts): shows "Create Account" form only.
 * - Existing server: shows tabbed "Login" / "Create Account" modes.
 *
 * On success, saves auth token, userId, and role to [TokenStore],
 * links the device token, then calls [onLoggedIn].
 */
@Composable
fun LoginScreen(
    serverUrl: String,
    deviceToken: String,
    tokenStore: TokenStore,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverStatus by remember { mutableStateOf<ServerStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var statusError by remember { mutableStateOf<String?>(null) }

    // 0 = login, 1 = register
    var selectedTab by remember { mutableStateOf(0) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val authClient = remember { AuthClient(serverUrl) }

    // Query server status on mount
    LaunchedEffect(Unit) {
        loading = true
        statusError = null
        val result = authClient.checkStatus()
        result.onSuccess { status ->
            serverStatus = status
            // If no users exist, default to register tab
            if (!status.has_users) {
                selectedTab = 1
            }
        }.onFailure {
            statusError = "Could not reach server at $serverUrl"
        }
        loading = false
    }

    // Clean up client on dispose
    DisposableEffect(Unit) {
        onDispose { authClient.close() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Scroll + imePadding so the submit button rises above the soft keyboard
            // instead of being trapped behind it (iOS especially — same as #1240).
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(32.dp)
            .testTag("login-screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Account",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(8.dp))

        when {
            loading -> {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Checking server...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            statusError != null -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = statusError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            serverStatus != null -> {
                val isFirstUser = !serverStatus!!.has_users

                if (isFirstUser) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Welcome. You are the first user -- create your account to become steward.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    // Tab row for login / register
                    Spacer(Modifier.height(16.dp))
                    PrimaryTabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                errorMessage = null
                            },
                            text = { Text("Login") },
                            modifier = Modifier.testTag("login-tab"),
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                errorMessage = null
                            },
                            text = { Text("Create Account") },
                            modifier = Modifier.testTag("register-tab"),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Username field
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("login-username-input"),
                )

                Spacer(Modifier.height(12.dp))

                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("login-password-input"),
                )

                // Confirm password -- shown for register mode or first user
                if (selectedTab == 1 || isFirstUser) {
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            errorMessage = null
                        },
                        label = { Text("Confirm Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("login-confirm-password-input"),
                        isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Error message
                errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("login-error"),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Submit button
                val isRegister = selectedTab == 1 || isFirstUser
                val canSubmit = username.isNotBlank() && password.isNotBlank() &&
                    (!isRegister || (confirmPassword.isNotEmpty() && confirmPassword == password)) &&
                    !submitting

                Button(
                    onClick = {
                        submitting = true
                        errorMessage = null
                        scope.launch {
                            try {
                                if (isRegister) {
                                    if (password != confirmPassword) {
                                        errorMessage = "Passwords do not match."
                                        submitting = false
                                        return@launch
                                    }
                                    val result = authClient.register(
                                        username = username.trim(),
                                        password = password,
                                        displayName = username.trim(),
                                    )
                                    result.onSuccess { auth ->
                                        completeLogin(auth, tokenStore, authClient, deviceToken, onLoggedIn)
                                    }.onFailure { e ->
                                        errorMessage = parseError(e, "Registration failed. Username may be taken.")
                                    }
                                } else {
                                    val result = authClient.login(
                                        username = username.trim(),
                                        password = password,
                                    )
                                    result.onSuccess { auth ->
                                        completeLogin(auth, tokenStore, authClient, deviceToken, onLoggedIn)
                                    }.onFailure { e ->
                                        errorMessage = parseError(e, "Login failed. Check username and password.")
                                    }
                                }
                            } catch (e: Exception) {
                                errorMessage = "Connection error: ${e.message}"
                            }
                            submitting = false
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().testTag("login-submit-button"),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRegister) "Creating..." else "Logging in...")
                    } else {
                        Text(if (isRegister) "Create Account" else "Login")
                    }
                }
            }
        }
    }
}

/**
 * After successful login/register: save credentials, link device, set system props, proceed.
 */
private suspend fun completeLogin(
    auth: org.wyrdsekai.app.network.AuthResponse,
    tokenStore: TokenStore,
    authClient: AuthClient,
    deviceToken: String,
    onLoggedIn: () -> Unit,
) {
    tokenStore.saveAuthToken(auth.token)
    tokenStore.saveUserId(auth.user_id)
    tokenStore.saveUserRole(auth.role)

    // Link device token to this user account
    if (deviceToken.isNotBlank()) {
        authClient.linkDevice(auth.token, deviceToken)
        // Non-fatal if link fails -- the user is still authenticated
    }

    // Set system properties for downstream components
    AppProps.set("wyrdsekai.auth.token", auth.token)
    AppProps.set("wyrdsekai.user.id", auth.user_id)
    AppProps.set("wyrdsekai.user.role", auth.role)

    onLoggedIn()
}

private fun parseError(e: Throwable, fallback: String): String {
    val msg = e.message ?: return fallback
    // Check for specific server error responses (HTTP 409 = duplicate)
    return if (msg.contains("409") || msg.contains("already exists", ignoreCase = true)) {
        "Username already taken."
    } else if (msg.contains("401") || msg.contains("invalid credentials", ignoreCase = true)) {
        "Invalid username or password."
    } else {
        // Show actual error for debugging
        "$fallback ($msg)"
    }
}
