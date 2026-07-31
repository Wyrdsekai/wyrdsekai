package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.wyrdsekai.app.network.CreateAccountResult
import org.wyrdsekai.app.network.NatsServerClient
import org.wyrdsekai.app.network.OpenOutcome
import org.wyrdsekai.app.network.OpenZone
import org.wyrdsekai.app.network.OpenZoneResult
import org.wyrdsekai.app.network.ServersViewModel
import org.wyrdsekai.app.network.ZoneBank
import org.wyrdsekai.app.network.ZonePasswordStore

/**
 * ServersScreen — "Your servers" (/P5), Android parity
 * with the RN ServersScreen. Tap a banked server → auto-attempt the login across
 * its held relays; prompt inline only when a password (or a missing username) is
 * needed.
 *
 * Thin shell over [ServersViewModel]: the screen injects the REAL open effect
 * (OpenZone.openZone, which connects over NATS and syncs the bank) and stashes
 * the connected [NatsServerClient] so the host can route the user into the world
 * — or into "Find a zone", which rides this same connection.
 */
@Composable
fun ServersScreen(
    bank: ZoneBank,
    passwords: ZonePasswordStore,
    scope: CoroutineScope,
    onEnter: (zoneId: String, client: NatsServerClient) -> Unit,
    onFindZone: (client: NatsServerClient) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    // The connected client from the most recent successful open. Held here (not
    // in the ViewModel) so the testable state machine stays platform-free.
    var lastClient by remember { mutableStateOf<NatsServerClient?>(null) }

    // Registration UI state (2026-07-23, phone-first onboarding). Kept at the
    // screen level — a lightweight companion to the login state machine rather
    // than woven into it.
    var registerZone by remember { mutableStateOf<String?>(null) }
    var registerBusy by remember { mutableStateOf(false) }
    var registerError by remember { mutableStateOf<String?>(null) }
    var needsInviteCode by remember { mutableStateOf(false) }
    // When set, show the ONE-TIME recovery key before entering the world.
    var recoveryPrompt by remember { mutableStateOf<Triple<String, String?, String>?>(null) } // zoneId, role, key

    recoveryPrompt?.let { (zid, role, key) ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (role == "steward") "You are the steward of this household" else "Account created") },
            text = {
                Text(
                    "Save your recovery key — it is shown ONCE and is the only way " +
                    "to reset your password:\n\n$key",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val client = lastClient
                    recoveryPrompt = null
                    if (client != null) onEnter(zid, client)
                }, modifier = Modifier.testTag("recovery-ack")) { Text("I've saved it") }
            },
        )
    }

    val vm = remember {
        ServersViewModel(bank) { zoneId, password ->
            when (val res = OpenZone.openZone(
                bank = bank,
                zoneId = zoneId,
                passwords = passwords,
                now = System.currentTimeMillis(),
                explicitPassword = password,
            )) {
                is OpenZoneResult.Ok -> {
                    lastClient = res.client
                    OpenOutcome.Connected(zoneId, res.relayUrl)
                }
                OpenZoneResult.NeedsPassword -> OpenOutcome.NeedsPassword
                is OpenZoneResult.AuthRejected -> OpenOutcome.AuthRejected(res.error)
                is OpenZoneResult.Unreachable -> OpenOutcome.Unreachable(res.error)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("servers-screen"),
    ) {
        Text(
            "Your servers",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag("servers-title"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap a server to sign in. We'll find a relay that reaches it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (bank.zones.isEmpty()) {
            Text(
                "No servers yet. Paste an invite, or find a zone to ask its steward.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("servers-empty"),
            )
        }

        val doCreate: (String, String, String, String?) -> Unit = { zid, user, pw, code ->
            scope.launch {
                registerBusy = true
                registerError = null
                when (val r = OpenZone.createAccount(
                    bank = bank,
                    zoneId = zid,
                    passwords = passwords,
                    now = System.currentTimeMillis(),
                    username = user,
                    password = pw,
                    inviteCode = if (needsInviteCode) code else null,
                )) {
                    is CreateAccountResult.Ok -> {
                        lastClient = r.client
                        registerZone = null
                        needsInviteCode = false
                        if (r.recoveryKey != null) {
                            recoveryPrompt = Triple(zid, r.role, r.recoveryKey!!)
                        } else {
                            onEnter(zid, r.client)
                        }
                    }
                    is CreateAccountResult.RegistrationClosed -> {
                        needsInviteCode = true
                        registerError = r.error
                    }
                    is CreateAccountResult.Rejected -> registerError = r.error
                    is CreateAccountResult.Unreachable -> registerError = r.error
                }
                registerBusy = false
            }
        }

        for (zone in bank.zones) {
            ServerCard(
                displayName = zone.displayName.ifBlank { zone.zoneId },
                zoneId = zone.zoneId,
                busy = vm.busyZone == zone.zoneId || (registerZone == zone.zoneId && registerBusy),
                promptOpen = vm.promptZone == zone.zoneId,
                registerOpen = registerZone == zone.zoneId,
                needsInviteCode = needsInviteCode,
                username = zone.username,
                error = vm.errorByZone[zone.zoneId] ?: (if (registerZone == zone.zoneId) registerError else null),
                onTap = { scope.launch { vm.attempt(zone.zoneId) } },
                onSubmit = { user, pw -> scope.launch { vm.submitPrompt(zone.zoneId, user, pw) } },
                onStartRegister = { registerZone = zone.zoneId; registerError = null },
                onCancelRegister = { registerZone = null; needsInviteCode = false; registerError = null },
                onCreate = { user, pw, code -> doCreate(zone.zoneId, user, pw, code) },
            )
            Spacer(Modifier.height(12.dp))
        }

        // Once a server is signed in, the connection is live: offer to enter that
        // world, or to ride the same connection into directory discovery.
        val connected = vm.connectedZone
        val client = lastClient
        if (connected != null && client != null) {
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth().testTag("servers-connected-$connected")) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Signed in to ${bank.getZone(connected)?.displayName?.ifBlank { connected } ?: connected}.",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onEnter(connected, client) },
                        modifier = Modifier.fillMaxWidth().testTag("servers-enter"),
                    ) { Text("Enter") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onFindZone(client) },
                        modifier = Modifier.fillMaxWidth().testTag("servers-find-zone"),
                    ) { Text("Find a zone…") }
                }
            }
        }

        if (onBack != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack, modifier = Modifier.testTag("servers-back")) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun ServerCard(
    displayName: String,
    zoneId: String,
    busy: Boolean,
    promptOpen: Boolean,
    registerOpen: Boolean,
    needsInviteCode: Boolean,
    username: String,
    error: String?,
    onTap: () -> Unit,
    onSubmit: (username: String?, password: String) -> Unit,
    onStartRegister: () -> Unit,
    onCancelRegister: () -> Unit,
    onCreate: (username: String, password: String, inviteCode: String?) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("servers-card-$zoneId")) {
        Column(Modifier.padding(16.dp)) {
            Text(displayName, style = MaterialTheme.typography.titleMedium)
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("servers-error-$zoneId"),
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                registerOpen -> RegisterPrompt(
                    zoneId = zoneId,
                    displayName = displayName,
                    needsInviteCode = needsInviteCode,
                    busy = busy,
                    onCreate = onCreate,
                    onCancel = onCancelRegister,
                )
                promptOpen -> {
                    InlinePrompt(
                        zoneId = zoneId,
                        needUsername = username.isBlank(),
                        busy = busy,
                        onSubmit = onSubmit,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onStartRegister,
                        modifier = Modifier.testTag("servers-register-link-$zoneId"),
                    ) { Text("New here? Create your account on $displayName") }
                }
                else -> Button(
                    onClick = onTap,
                    enabled = !busy,
                    modifier = Modifier.testTag("servers-open-$zoneId"),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                    else Text("Sign in")
                }
            }
        }
    }
}

@Composable
private fun RegisterPrompt(
    zoneId: String,
    displayName: String,
    needsInviteCode: Boolean,
    busy: Boolean,
    onCreate: (username: String, password: String, inviteCode: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Choose the name and password you'll use on $displayName. On a brand-new " +
            "household, the first account becomes the steward — the keeper of the keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Choose a username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("servers-reg-user-$zoneId"),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text("Choose a password (4+ characters)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("servers-reg-pw-$zoneId"),
        )
        if (needsInviteCode) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Invite code from the steward") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("servers-reg-code-$zoneId"),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onCreate(user.trim(), pw, code.trim().ifBlank { null }) },
            enabled = !busy && user.isNotBlank() && pw.length >= 4 && (!needsInviteCode || code.isNotBlank()),
            modifier = Modifier.fillMaxWidth().testTag("servers-reg-submit-$zoneId"),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
            else Text("Create account")
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag("servers-reg-back-$zoneId"),
        ) { Text("I already have an account — log in") }
    }
}

@Composable
private fun InlinePrompt(
    zoneId: String,
    needUsername: Boolean,
    busy: Boolean,
    onSubmit: (username: String?, password: String) -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        if (needUsername) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Your account name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("servers-username-$zoneId"),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("servers-password-$zoneId"),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = { onSubmit(user.ifBlank { null }, pw) },
                enabled = !busy && pw.isNotBlank(),
                modifier = Modifier.testTag("servers-submit-$zoneId"),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                else Text("Sign in")
            }
        }
    }
}
