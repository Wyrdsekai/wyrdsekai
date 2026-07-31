package org.wyrdsekai.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import org.wyrdsekai.app.network.NatsServerClient
import org.wyrdsekai.app.network.TokenStoreZonePasswordStore
import org.wyrdsekai.app.network.ZoneBankStore
import org.wyrdsekai.app.state.TokenStore

/**
 * Android [ServersHost]: loads the bank, renders ServersScreen, and sub-navigates
 * to FindZoneScreen once a connection is live (discovery rides that NATS leg).
 *
 * On "Enter", it writes the winning zone's relay credentials + zoneId into the
 * TokenStore — the same keys the live-proven local-mode boot reads — then flips
 * to local mode via [onEnterLocal]. The Servers connect verifies the password and
 * syncs the bank (§4); local mode re-opens the leg and renders the world.
 */
actual val zoneBankSurfaceSupported: Boolean = true

@Composable
actual fun ServersHost(
    scope: CoroutineScope,
    onExit: () -> Unit,
    onEnterLocal: () -> Unit,
) {
    val tokens = remember { TokenStore() }
    val bank = remember { ZoneBankStore(tokens).load() }
    val passwords = remember { TokenStoreZonePasswordStore(tokens) }

    var sub by remember { mutableStateOf("list") }
    var dirClient by remember { mutableStateOf<NatsServerClient?>(null) }

    when (sub) {
        "find" -> {
            val client = dirClient
            if (client != null) {
                FindZoneScreen(
                    client = client,
                    bank = bank,
                    scope = scope,
                    requesterName = {
                        bank.homeZone()?.username?.takeIf { it.isNotBlank() }
                            ?: bank.zones.firstOrNull()?.username?.takeIf { it.isNotBlank() }
                            ?: "a wyrdsekai user"
                    },
                    onBack = { sub = "list" },
                )
            } else {
                // Lost the connection mid-nav — fall back to the list.
                sub = "list"
            }
        }
        else -> {
            ServersScreen(
                bank = bank,
                passwords = passwords,
                scope = scope,
                onEnter = { zoneId, _ ->
                    val relay = bank.relaysForZone(zoneId).firstOrNull()
                    if (relay != null) {
                        tokens.saveNatsUrl(relay.wsUrl)
                        tokens.saveRelayUrl(relay.wsUrl)
                        tokens.saveNatsUser(relay.natsUser)
                        tokens.saveNatsPassword(relay.natsPass)
                        relay.caFp?.let { tokens.saveRelayFingerprints(it) }
                    }
                    tokens.saveZoneId(zoneId)
                    // Mode 1 (relay remote terminal): bridge THIS zone's account
                    // credentials — username from the bank, password from the
                    // per-device store — into the generic mcp creds the local-node
                    // bootstrap reads (NodeManager.loadMcpUsername/Password). Without
                    // it the bootstrap finds no generic creds and falls through to
                    // registerAndLogin, authenticating the relay tunnel as a fresh
                    // ANON account instead of the user — so the phone lands in a
                    // stranger's Study, not their own. openZone already proved these
                    // creds against the zone over the relay.
                    bank.getZone(zoneId)?.username
                        ?.takeIf { it.isNotBlank() }
                        ?.let { tokens.saveMcpUsername(it) }
                    passwords.getPassword(zoneId)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { tokens.saveMcpPassword(it) }
                    tokens.saveMode("local")
                    onEnterLocal()
                },
                onFindZone = { client ->
                    dirClient = client
                    sub = "find"
                },
                onBack = onExit,
            )
        }
    }
}
