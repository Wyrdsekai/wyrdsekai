package org.wyrdsekai.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.wyrdsekai.app.i18n.ProvideUiStrings
import org.wyrdsekai.app.inference.InferenceRouter
import org.wyrdsekai.app.inference.LlamaServerManager
import org.wyrdsekai.app.inference.ModelManager
import org.wyrdsekai.app.network.WyrdWebSocket
import org.wyrdsekai.app.state.TokenStore
import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.ui.screens.BirthScreen
import org.wyrdsekai.app.ui.screens.ConnectScreen
import org.wyrdsekai.app.ui.screens.FirstRunScreen
import org.wyrdsekai.app.ui.screens.WelcomeScreen
import org.wyrdsekai.app.ui.screens.LocalRoomScreen
import org.wyrdsekai.app.ui.screens.zoneBankSurfaceSupported
import org.wyrdsekai.app.ui.screens.LoginScreen
import org.wyrdsekai.app.ui.screens.RoomScreen
import org.wyrdsekai.app.ui.screens.ServersHost
import org.wyrdsekai.app.network.addInviteToBank
import org.wyrdsekai.app.ui.theme.WyrdTheme
import org.wyrdsekai.app.viewmodel.ConnectionViewModel
import org.wyrdsekai.app.viewmodel.HouseholdViewModel
import org.wyrdsekai.app.viewmodel.InferenceViewModel
import org.wyrdsekai.app.viewmodel.NodeViewModel
import org.wyrdsekai.app.viewmodel.RoomViewModel
import org.wyrdsekai.app.platform.AppProps
import org.wyrdsekai.app.engine.discovery.PhoneInvite
import org.wyrdsekai.app.engine.transit.RelayTunnelHolder
import org.wyrdsekai.app.engine.transit.RelayTunnelServerConnection
import org.wyrdsekai.app.network.parseWsHostPort
import org.wyrdsekai.app.network.pinRelayFromInviteFingerprints

@Composable
fun WyrdApp(scope: CoroutineScope) {
    val tokenStore = remember { TokenStore() }
    val webSocket = remember { WyrdWebSocket(scope) }
    val connectionVM = remember { ConnectionViewModel(scope, webSocket, tokenStore) }
    val roomVM = remember { RoomViewModel(scope, webSocket) }
    val nodeVM = remember { NodeViewModel(scope) }
    val llamaServerManager = remember { LlamaServerManager(scope) }
    val modelManager = remember { ModelManager() }
    val inferenceRouter = remember { InferenceRouter(llamaServerManager) }
    val inferenceVM = remember { InferenceViewModel(scope, llamaServerManager, modelManager, inferenceRouter) }
    val householdVM = remember { HouseholdViewModel(scope) }

    val connectionState by webSocket.connectionState.collectAsState()
    val serverUrl by connectionVM.serverUrl.collectAsState()
    val username by connectionVM.username.collectAsState()
    val nodeState by nodeVM.nodeState.collectAsState()

    // Persisted app mode: null (first run), "local", or "remote"
    var appMode by remember { mutableStateOf(tokenStore.loadMode()) }

    // Track whether we're in local node mode (PhoneNode running directly)
    var localNodeActive by remember { mutableStateOf(false) }

    // BirthScreen completion state
    var birthComplete by remember { mutableStateOf(false) }
    var initialGreeting by remember { mutableStateOf<String?>(null) }
    var readyPhoneNode by remember { mutableStateOf<PhoneNode?>(null) }

    // Model download progress for FirstRunScreen wizard
    var modelDownloadProgress by remember { mutableStateOf(0f) }

    // Locale state — drives ProvideUiStrings recomposition
    var locale by remember { mutableStateOf(tokenStore.loadLocale() ?: "en") }

    // Login gate: after pairing, before BirthScreen
    var loggedIn by remember {
        mutableStateOf(tokenStore.loadAuthToken() != null && tokenStore.loadUserId() != null)
    }

    // Remote mode gate: don't show RoomScreen until room has loaded
    var remoteReady by remember { mutableStateOf(false) }

    // Relay account-login bridge: ConnectScreen's login over a pasted
    // wyrdphone:// invite persists the account creds as MCP creds + the relay
    // leg, then raises relayLoginReady. We switch into local-relay mode, where
    // setupNatsServerClient runs `wyrd.zone.{zone}.mcp.login` over the relay as
    // the real account (surfaced account login over relay, vs auto-register).
    val relayLoginReady by connectionVM.relayLoginReady.collectAsState()
    LaunchedEffect(relayLoginReady) {
        if (relayLoginReady) {
            tokenStore.saveMode("local")
            appMode = "local"
        }
    }

    // Set system properties for companion name and inference URL when in local mode
    // (BirthScreen handles the actual node start)
    LaunchedEffect(appMode) {
        println("WyrdApp: LaunchedEffect appMode=$appMode localNodeActive=$localNodeActive nodeState=$nodeState isAvailable=${nodeVM.isAvailable}")
        if (appMode == "local") {
            val companionName = tokenStore.loadCompanionName() ?: "Wyrd"
            AppProps.set("wyrdsekai.companion.name", companionName)
            val inferenceUrl = tokenStore.loadInferenceUrl()
            if (inferenceUrl != null) {
                AppProps.set("wyrdsekai.inference.url", inferenceUrl)
            }
            val homeName = tokenStore.loadHomeName()
            if (homeName != null) {
                AppProps.set("wyrdsekai.home.name", homeName)
            }
            // Load pairing credentials + server URL into system properties.
            // serverUrl is set independently of pairingToken — local-mode-with-
            // server-routing (the public-relay use case) doesn't require a
            // pairing token, and gating server-url on pairing-token-presence
            // means ServerClient.probe never fires for relay-only deployments.
            val pairingToken = tokenStore.loadPairingToken()
            val pairedServerUrl = tokenStore.loadServerUrl()
            if (pairedServerUrl != null) {
                AppProps.set("wyrdsekai.server.url", pairedServerUrl)
            }
            if (pairingToken != null) {
                AppProps.set("wyrdsekai.pairing.token", pairingToken)
            }
            val natsUrl = tokenStore.loadNatsUrl()
            if (natsUrl != null) {
                AppProps.set("wyrdsekai.nats.url", natsUrl)
            }
            // Phone NATS credentials from a wyrdphone:// invite — without
            // these the relay leg falls back to defaults and never
            // authenticates on a randomized-credential relay.
            tokenStore.loadNatsUser()?.let { AppProps.set("wyrdsekai.nats.user", it) }
            tokenStore.loadNatsPassword()?.let { AppProps.set("wyrdsekai.nats.pass", it) }
            // Invite fingerprints — NodeManager pins the relay cert from these
            // before opening the NATS leg, so trust survives app restarts even
            // if the onboarding-time pin never completed.
            tokenStore.loadRelayFingerprints()?.let { AppProps.set("wyrdsekai.relay.fps", it) }
            // Set auth system properties if already logged in
            val authToken = tokenStore.loadAuthToken()
            val userId = tokenStore.loadUserId()
            val userRole = tokenStore.loadUserRole()
            if (authToken != null) AppProps.set("wyrdsekai.auth.token", authToken)
            if (userId != null) AppProps.set("wyrdsekai.user.id", userId)
            if (userRole != null) AppProps.set("wyrdsekai.user.role", userRole)
            // MCP creds — required for ServerClient login on every cold start.
            // The auth_token alone isn't enough: server restarts invalidate it,
            // and ServerClient.login() exchanges username+password for a fresh
            // session token. Without these the cold-start probe falls through
            // to registerAndLogin which fails when openRegistration=false.
            val mcpUsername = tokenStore.loadMcpUsername()
            val mcpPassword = tokenStore.loadMcpPassword()
            if (mcpUsername != null) AppProps.set("wyrdsekai.mcp.username", mcpUsername)
            if (mcpPassword != null) AppProps.set("wyrdsekai.mcp.password", mcpPassword)
        }
    }

    // Send saved locale to server whenever connection becomes established
    LaunchedEffect(connectionState) {
        if (connectionState == WyrdWebSocket.ConnectionState.CONNECTED) {
            roomVM.setPreference("locale", tokenStore.loadLocale() ?: "en")
            // Wait for room to load before showing RoomScreen
            roomVM.roomName.first { it.isNotEmpty() && it != "?" }
            remoteReady = true
        } else if (connectionState == WyrdWebSocket.ConnectionState.DISCONNECTED) {
            remoteReady = false
        }
    }

    // Track node state — when it transitions away from running, clear localNodeActive
    LaunchedEffect(nodeState) {
        if (nodeState != "running" && localNodeActive && appMode == "local") {
            // Node stopped unexpectedly — keep localNodeActive true so we show loading state
        } else if (nodeState != "running" && localNodeActive) {
            localNodeActive = false
        }
    }

    WyrdTheme {
        Box(Modifier.fillMaxSize()) {
        ProvideUiStrings(locale) {
            when {
                // First run — show welcome/onboarding screen
                appMode == null -> {
                    WelcomeScreen(
                        onComplete = { serverUrl, apiProvider, apiKey, onDeviceModel ->
                            // Picking "on-device model" IS the experimental
                            // opt-in: it is the one path with nothing else to
                            // think with. Persisted before saveMode so the
                            // first mode resolution already sees it.
                            if (onDeviceModel) tokenStore.saveOnDeviceModelOptIn(true)
                            tokenStore.saveMode("local")
                            val name = tokenStore.loadCompanionName() ?: "Wyrd"
                            tokenStore.saveCompanionName(name)
                            AppProps.set("wyrdsekai.companion.name", name)

                            // Save server URL if provided. A pasted/scanned
                            // wyrdphone:// invite configures the relay path
                            // instead: persist the relay
                            // URL + phone NATS credentials and pin the relay
                            // from the invite's fingerprints — the invite IS
                            // the trust decision.
                            if (serverUrl != null && PhoneInvite.isPhoneInviteUrl(serverUrl)) {
                                runCatching { PhoneInvite.parse(serverUrl) }.onSuccess { invite ->
                                    val relay = invite.relays.first()
                                    tokenStore.saveNatsUrl(relay.wsUrl)
                                    tokenStore.saveRelayUrl(relay.wsUrl)
                                    tokenStore.saveNatsUser(relay.natsUser)
                                    tokenStore.saveNatsPassword(relay.natsPassword)
                                    AppProps.set("wyrdsekai.nats.url", relay.wsUrl)
                                    AppProps.set("wyrdsekai.nats.user", relay.natsUser)
                                    AppProps.set("wyrdsekai.nats.pass", relay.natsPassword)
                                    invite.zoneId?.let { tokenStore.saveZoneId(it) }
                                    val fingerprints = listOfNotNull(relay.caFp, relay.fp)
                                    if (fingerprints.isNotEmpty()) {
                                        // Persist the fps: the invite IS the trust
                                        // decision, and NodeManager re-pins from
                                        // wyrdsekai.relay.fps before every NATS
                                        // connect — so a kill between this launch
                                        // and the pin completing loses nothing.
                                        val joined = fingerprints.joinToString(",")
                                        tokenStore.saveRelayFingerprints(joined)
                                        AppProps.set("wyrdsekai.relay.fps", joined)
                                        scope.launch {
                                            parseWsHostPort(relay.wsUrl)?.let { (host, port) ->
                                                pinRelayFromInviteFingerprints(host, port, fingerprints)
                                            }
                                        }
                                    }
                                }
                                // also accrue this zone into
                                // the held "Your servers" bank so it's reachable
                                // later from the servers surface (and syncs across
                                // devices). The local-mode boot below is unchanged.
                                addInviteToBank(serverUrl)
                            } else if (serverUrl != null) {
                                tokenStore.saveServerUrl(serverUrl)
                                AppProps.set("wyrdsekai.server.url", serverUrl)
                            }

                            // Save API key if provided
                            if (apiProvider != null && apiKey != null) {
                                AppProps.set("wyrdsekai.api.provider", apiProvider)
                                AppProps.set("wyrdsekai.api.key", apiKey)
                                // Set inference URL based on provider
                                val inferenceUrl = when (apiProvider) {
                                    "anthropic" -> "https://api.anthropic.com"
                                    "openai" -> "https://api.openai.com"
                                    "openrouter" -> "https://openrouter.ai/api"
                                    else -> serverUrl ?: "http://localhost:8080"
                                }
                                tokenStore.saveInferenceUrl(inferenceUrl)
                                AppProps.set("wyrdsekai.inference.url", inferenceUrl)
                            }

                            appMode = "local"
                        },
                        onHomeZone = { input ->
                            // Home-zone path (Mode 1) — ONE input, the app picks
                            // the door (parity with RN WelcomeScreenWrapper):
                            //   invite → relay config + zone bank → servers
                            //     surface, where one tap runs the REAL relay
                            //     login (mcp.login over NATS — never HTTP).
                            //   bare wss:// relay URL → rejected with guidance
                            //     (relay creds travel in invites).
                            //   plain LAN/host URL → remote mode, account login
                            //     on ConnectScreen pre-pointed at it.
                            // Returns an error string to show, or null after
                            // navigating.
                            when {
                                PhoneInvite.isPhoneInviteUrl(input) -> {
                                    val parsed = runCatching { PhoneInvite.parse(input) }
                                    val relay = parsed.getOrNull()?.relays?.firstOrNull()
                                    if (relay == null) {
                                        "That invite could not be read — ask your node for a fresh one (wyrd phone invite)."
                                    } else {
                                        // The invite IS the trust decision: persist
                                        // the relay leg + pin its fingerprints, same
                                        // as the onComplete invite branch.
                                        tokenStore.saveNatsUrl(relay.wsUrl)
                                        tokenStore.saveRelayUrl(relay.wsUrl)
                                        tokenStore.saveNatsUser(relay.natsUser)
                                        tokenStore.saveNatsPassword(relay.natsPassword)
                                        AppProps.set("wyrdsekai.nats.url", relay.wsUrl)
                                        AppProps.set("wyrdsekai.nats.user", relay.natsUser)
                                        AppProps.set("wyrdsekai.nats.pass", relay.natsPassword)
                                        parsed.getOrNull()?.zoneId?.let { tokenStore.saveZoneId(it) }
                                        val fingerprints = listOfNotNull(relay.caFp, relay.fp)
                                        if (fingerprints.isNotEmpty()) {
                                            val joined = fingerprints.joinToString(",")
                                            tokenStore.saveRelayFingerprints(joined)
                                            AppProps.set("wyrdsekai.relay.fps", joined)
                                            scope.launch {
                                                parseWsHostPort(relay.wsUrl)?.let { (host, port) ->
                                                    pinRelayFromInviteFingerprints(host, port, fingerprints)
                                                }
                                            }
                                        }
                                        addInviteToBank(input)
                                        tokenStore.saveMode("local")
                                        // Servers surface only where it's REAL
                                        // (androidMain). On iOS/desktop the actual
                                        // is a placeholder — boot the proven
                                        // local-relay path directly instead
                                        // (identical to the pre-redesign flow).
                                        appMode = if (zoneBankSurfaceSupported) "servers" else "local"
                                        null
                                    }
                                }
                                input.startsWith("wss://", ignoreCase = true)
                                        || input.startsWith("ws://", ignoreCase = true) ->
                                    "That looks like a relay address. Relays need an invite — on your node, run: wyrd phone invite"
                                else -> {
                                    tokenStore.saveServerUrl(input)
                                    tokenStore.saveMode("remote")
                                    AppProps.set("wyrdsekai.server.url", input)
                                    connectionVM.setServerUrl(input)
                                    appMode = "remote"
                                    null
                                }
                            }
                        },
                        // open the held "Your servers" bank
                        // (androidMain only; placeholder platforms get no button).
                        onMyServers = if (zoneBankSurfaceSupported) {
                            { appMode = "servers" }
                        } else null,
                    )
                }

                // "Your servers" + "Find a zone" surface.
                // Sign in to a banked zone → persists its relay creds → flips to
                // local mode (the proven relay-leg boot) and enters the world.
                appMode == "servers" -> {
                    ServersHost(
                        scope = scope,
                        // Return to wherever the user actually was (My zones is now
                        // reachable from a running local session, not just first
                        // run) — falling back to Welcome only when never onboarded.
                        onExit = {
                            appMode = tokenStore.loadMode()?.takeIf { it.isNotBlank() }
                        },
                        onEnterLocal = { appMode = "local" },
                    )
                }

                // Legacy: FirstRunScreen kept for household pairing flow
                appMode == "setup" -> {
                    FirstRunScreen(
                        onLocalMode = { companionName, inferenceUrl ->
                            tokenStore.saveMode("local")
                            tokenStore.saveCompanionName(companionName)
                            AppProps.set("wyrdsekai.companion.name", companionName)
                            if (!inferenceUrl.isNullOrBlank()) {
                                tokenStore.saveInferenceUrl(inferenceUrl)
                                AppProps.set("wyrdsekai.inference.url", inferenceUrl)
                            }
                            appMode = "local"
                        },
                        onLocalModeWithAnswers = { companionName, answers, inferenceUrl ->
                            tokenStore.saveMode("local")
                            tokenStore.saveCompanionName(companionName)
                            AppProps.set("wyrdsekai.companion.name", companionName)
                            if (!inferenceUrl.isNullOrBlank()) {
                                tokenStore.saveInferenceUrl(inferenceUrl)
                                AppProps.set("wyrdsekai.inference.url", inferenceUrl)
                            }
                            appMode = "local"
                        },
                        onRemoteMode = {
                            tokenStore.saveMode("remote")
                            appMode = "remote"
                        },
                        onStartModelDownload = {
                            scope.launch {
                                try {
                                    val preferredModel = "qwen3.5-2b-q4"
                                    val modelPath = modelManager.getModelPath(preferredModel)
                                    if (modelPath == null) {
                                        modelManager.downloadModel(preferredModel) { progress ->
                                            modelDownloadProgress = progress
                                        }
                                    }
                                    modelDownloadProgress = 1f
                                } catch (_: Exception) {
                                    // Download failure is non-fatal -- user can still proceed
                                }
                            }
                        },
                        modelDownloadProgress = modelDownloadProgress,
                        tokenStore = tokenStore,
                    )
                }

                // Local node mode — Study boots immediately, node starts in background
                appMode == "local" && nodeVM.isAvailable -> {
                    val modelStatusTextVal by nodeVM.nodeManager.modelStatusText.collectAsState()
                    val modelProgressVal by nodeVM.nodeManager.modelProgress.collectAsState()

                    // Start node in background on first composition. Keyed on
                    // localNodeActive (not Unit) so stopping the node WITHOUT
                    // leaving "local" mode — switch-to-standalone from a home
                    // zone, log out — restarts it; with key=Unit the effect never
                    // re-fired and the screen hung on the Study skeleton until
                    // app restart (2026-07-25).
                    LaunchedEffect(localNodeActive) {
                        if (!localNodeActive) {
                            nodeVM.startNode()
                            // Wait for node to reach running state
                            nodeVM.nodeState.first { it == "running" || it == "error" }
                            if (nodeVM.nodeState.value == "running") {
                                readyPhoneNode = nodeVM.nodeManager.phoneNode
                                localNodeActive = true
                            }
                        }
                    }

                    if (readyPhoneNode != null) {
                        // if a relay BetweenClient is up
                        // (set by NodeManager in relay-login mode) and we know
                        // the zone, tunnel a FULL session to the real zone over
                        // the relay. Otherwise null → the screen drives the
                        // offline local node. The session token auths the zone's
                        // loopback /ws.
                        val tunnelBetween by RelayTunnelHolder.between.collectAsState()
                        val tunnelConn = remember(tunnelBetween, readyPhoneNode) {
                            val bc = tunnelBetween
                            val zone = tokenStore.loadZoneId()
                            if (bc != null && bc.isConnected && !zone.isNullOrBlank()) {
                                RelayTunnelServerConnection(bc, zone, tokenStore.loadAuthToken())
                                    .also { it.open() }
                            } else null
                        }
                        // End the LIVE relay session, not just the stored leg.
                        // disconnectHomeZone() alone left the tunnel session and the
                        // relay NATS connection running until app restart (the RN
                        // Settings switch documents+fixes the same bug) — so
                        // "standalone" wasn't really standalone (2026-07-25).
                        val teardownRelaySession: () -> Unit = {
                            tunnelConn?.close()
                            val bc = RelayTunnelHolder.get()
                            RelayTunnelHolder.clear()
                            if (bc != null) scope.launch { runCatching { bc.disconnect() } }
                        }
                        LocalRoomScreen(
                            phoneNode = readyPhoneNode!!,
                            scope = scope,
                            remoteConnection = tunnelConn,
                            onStop = {
                                nodeVM.stopNode()
                                localNodeActive = false
                                readyPhoneNode = null
                            },
                            onSwitchMode = {
                                teardownRelaySession()
                                nodeVM.stopNode()
                                localNodeActive = false
                                readyPhoneNode = null
                                // If we're on a home zone, disconnect from it → run
                                // local-only (the local Study is kept as the last-synced
                                // mirror; the ZONE BANK keeps the zone + relay creds, so
                                // returning is one tap on My zones — switching never
                                // burns the invite). Otherwise re-onboard from scratch.
                                if (tokenStore.loadRelayUrl() != null) {
                                    tokenStore.disconnectHomeZone()
                                    appMode = "local"
                                } else {
                                    // Re-onboard through the CURRENT door, never the
                                    // legacy one. This used to set appMode = "setup",
                                    // which mounts FirstRunScreen — and FirstRunScreen's
                                    // "connect to household" mounts ConnectScreen, the
                                    // pre-Welcome UI with "Connect without account" on
                                    // it. After a logout the relay URL is already
                                    // cleared, so this branch was the normal one: log
                                    // out → Your zones → Back → Settings → "Connect to a
                                    // server instead" walked backwards into a UI we
                                    // replaced (reported 2026-07-29).
                                    tokenStore.saveMode("")
                                    appMode = if (zoneBankSurfaceSupported) "servers" else null
                                }
                            },
                            hasHomeZone = tokenStore.loadRelayUrl() != null,
                            // Log out — end the zone session (tunnel + relay connection
                            // + session token) but KEEP the zone in the bank; lands on
                            // My zones for a one-tap way back in. RN Settings parity.
                            onLogout = if (zoneBankSurfaceSupported) {
                                {
                                    teardownRelaySession()
                                    nodeVM.stopNode()
                                    localNodeActive = false
                                    readyPhoneNode = null
                                    tokenStore.disconnectHomeZone()
                                    appMode = "servers"
                                }
                            } else null,
                            // My zones — the zone-bank surface; pick a zone, one-tap
                            // relay login, or add another invite. Only offered where
                            // the surface is real (androidMain) — iOS/desktop actuals
                            // are placeholders and get no dead button.
                            onMyServers = if (zoneBankSurfaceSupported) {
                                {
                                    nodeVM.stopNode()
                                    localNodeActive = false
                                    readyPhoneNode = null
                                    appMode = "servers"
                                }
                            } else null,
                            modelStatusText = modelStatusTextVal,
                            modelProgress = modelProgressVal,
                            onInferenceUrlChanged = { url -> tokenStore.saveInferenceUrl(url) },
                        )
                    } else {
                        // Node is starting — show the Study room skeleton
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    modelStatusTextVal ?: "Opening your Study...",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }

                // Local node mode — node not available on this platform
                appMode == "local" -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Local node not available on this platform.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Remote mode — connected AND room ready
                appMode == "remote" && remoteReady && (
                    connectionState == WyrdWebSocket.ConnectionState.CONNECTED ||
                    connectionState == WyrdWebSocket.ConnectionState.RECONNECTING
                ) -> {
                    RoomScreen(
                        viewModel = roomVM,
                        onDisconnect = {
                            connectionVM.disconnect()
                            tokenStore.clear()
                        },
                        serverUrl = serverUrl,
                        username = username,
                        onLogout = {
                            connectionVM.disconnect()
                            tokenStore.clear()
                        },
                        nodeViewModel = nodeVM,
                        inferenceViewModel = inferenceVM,
                        householdViewModel = householdVM,
                        tokenStore = tokenStore,
                        webSocket = webSocket,
                        onLocaleChanged = { locale = it },
                    )
                }

                // Remote mode — connected but room not ready yet
                appMode == "remote" && (
                    connectionState == WyrdWebSocket.ConnectionState.CONNECTED ||
                    connectionState == WyrdWebSocket.ConnectionState.RECONNECTING
                ) -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Entering the world...", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                // Remote mode with no live session.
                //
                // This used to mount ConnectScreen — the pre-Welcome login UI, with
                // "Connect without account" and "Switch to local companion instead"
                // on it. It was the last door into the legacy screens: any time a
                // remote session ended without appMode being reset, the app fell
                // through to here and showed a UI we had replaced.
                //
                // Send people to the zone bank (or Welcome, where the bank surface
                // is a placeholder) instead. ConnectScreen and FirstRunScreen now
                // have no reachable entry point from normal use.
                else -> {
                    LaunchedEffect(Unit) {
                        appMode = if (zoneBankSurfaceSupported) "servers" else null
                    }
                }
            }
        }
        } // Box
    }
}
