package org.wyrdsekai.app.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.wyrdsekai.app.engine.discovery.PhoneInvite
import org.wyrdsekai.app.engine.discovery.SavedHouseholdConfig
import org.wyrdsekai.app.network.AuthClient
import org.wyrdsekai.app.network.WyrdWebSocket
import org.wyrdsekai.app.network.addInviteToBank
import org.wyrdsekai.app.network.parseWsHostPort
import org.wyrdsekai.app.network.pinRelayFromInviteFingerprints
import org.wyrdsekai.app.platform.AppProps
import org.wyrdsekai.app.state.TokenStore

class ConnectionViewModel(
    private val scope: CoroutineScope,
    private val webSocket: WyrdWebSocket,
    private val tokenStore: TokenStore,
    /** Receives the relay config parsed from a pasted wyrdphone:// invite. */
    private val phoneInviteSink: ((SavedHouseholdConfig) -> Unit)? = null,
) {
    private val _serverUrl = MutableStateFlow("localhost:7070")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    /**
     * Set true once a relay-invite ACCOUNT login has persisted its credentials.
     * WyrdApp observes this to switch into local-relay mode, where
     * setupNatsServerClient runs `wyrd.zone.{zone}.mcp.login` as the real
     * account over the relay — instead of auto-registering a throwaway phone
     * account. This is the "surfaced account login over relay" path
     * ( / phone-relay-friendly-login): relay-transport-auth
     * (the invite's nats creds) and account-login compose, rather than the
     * invite short-circuiting login entirely.
     */
    private val _relayLoginReady = MutableStateFlow(false)
    val relayLoginReady: StateFlow<Boolean> = _relayLoginReady.asStateFlow()

    val connectionState = webSocket.connectionState

    init {
        // Try to restore saved credentials and auto-connect
        val savedToken = tokenStore.loadToken()
        val savedUrl = tokenStore.loadServerUrl()
        val savedUsername = tokenStore.loadUsername()
        val savedLocale = tokenStore.loadLocale() ?: "en"

        if (savedToken != null && savedUrl != null && savedUsername != null) {
            _serverUrl.value = savedUrl
            _username.value = savedUsername
            _token.value = savedToken
            webSocket.connect(savedUrl, savedToken, savedLocale)
        } else {
            // Pre-fill fields from saved values even if token is missing
            savedUrl?.let { _serverUrl.value = it }
            savedUsername?.let { _username.value = it }
        }
    }

    fun setServerUrl(url: String) { _serverUrl.value = url }
    fun setUsername(name: String) { _username.value = name }
    fun setPassword(pass: String) { _password.value = pass }

    // relay config parsed from a pasted
    // wyrdphone:// invite. Consumers (HouseholdConnector wiring) collect
    // this to attach the relay leg with the invite's credentials.
    private val _phoneInviteConfig = MutableStateFlow<SavedHouseholdConfig?>(null)
    val phoneInviteConfig: StateFlow<SavedHouseholdConfig?> = _phoneInviteConfig.asStateFlow()

    /**
     * A pasted `wyrd phone invite` URL in the server field configures the
     * relay path instead of attempting an HTTP login. Returns true when
     * the input was an invite (callers skip the login path).
     */
    private fun applyPhoneInvite(url: String): Boolean {
        if (!PhoneInvite.isPhoneInviteUrl(url)) return false
        _error.value = null
        val invite = try {
            PhoneInvite.parse(url)
        } catch (e: IllegalArgumentException) {
            _error.value = e.message ?: "Invalid invite URL"
            return true
        }
        // Tier-1 phone guard: a zone-less invite is unroutable — without a zone the
        // NATS path can't reach the home zone and would SILENTLY fall to local mode.
        // Refuse it here (the mint side now refuses to produce one too) so the user
        // sees a real error instead of a dead local Study.
        if (invite.zoneId.isNullOrBlank()) {
            _error.value = "This invite has no home zone — ask the zone owner for a fresh invite."
            return true
        }
        val config = SavedHouseholdConfig.fromPhoneInvite(
            invite, Clock.System.now().toEpochMilliseconds())
        scope.launch {
            // the invite IS the trust decision: pin
            // the relay's certificate from the fp/ca_fp the steward carried,
            // BEFORE the sink fires, so the first relay connect already
            // validates against the pin (no first-contact TOFU leap).
            val fingerprints = listOfNotNull(config.relayCaFp, config.relayFp)
            val pinned = if (fingerprints.isNotEmpty()) {
                parseWsHostPort(config.natsWsUrl)?.let { (host, port) ->
                    pinRelayFromInviteFingerprints(host, port, fingerprints)
                } ?: false
            } else {
                false
            }
            _phoneInviteConfig.value = config
            phoneInviteSink?.invoke(config)
            _serverUrl.value = config.natsWsUrl
            tokenStore.saveServerUrl(config.natsWsUrl)
            _error.value = when {
                pinned -> "Relay invite accepted — relay verified and pinned, connecting…"
                fingerprints.isNotEmpty() ->
                    "Relay invite accepted — relay not reachable yet, will verify on first connect…"
                else -> "Relay invite accepted — connecting through the relay…"
            }
        }
        return true
    }

    fun login() {
        // A pasted wyrdphone:// invite means "log into my account THROUGH this
        // relay": pin/persist the relay leg AND carry the typed account creds
        // forward as MCP creds, so the local-relay boot logs in as the real
        // account over the relay (vs. the old behaviour where the invite
        // short-circuited login into a relay-attach-only / auto-register path).
        extractInvite(_serverUrl.value)?.let { invite ->
            _serverUrl.value = invite  // strip any pre-filled prefix before the relay path parses it
            loginOverRelay()
            return
        }
        // A bare relay wss:// URL is not HTTP-loggable — fail with guidance
        // instead of a raw network error (the historical dead-end: fetch
        // against wss → an unhelpful failure). Relay creds travel in invites.
        if (isRelayWsUrl(_serverUrl.value)) {
            _error.value = "That's a relay address — relays need an invite. " +
                "On your node, run: wyrd phone invite, then paste the wyrdphone:// link here."
            return
        }
        scope.launch {
            _isLoading.value = true
            _error.value = null
            val client = AuthClient(_serverUrl.value)
            val result = client.login(_username.value, _password.value)
            client.close()

            result.fold(
                onSuccess = { auth ->
                    _token.value = auth.token
                    saveCredentials(auth.token)
                    webSocket.connect(_serverUrl.value, auth.token, currentLocale())
                },
                onFailure = { e ->
                    _error.value = e.message ?: "Login failed"
                },
            )
            _isLoading.value = false
        }
    }

    /**
     * Account login through a held relay. A pasted wyrdphone:// invite carries
     * the relay's transport credentials + zone label; the typed username/
     * password are the user's ACCOUNT on that zone. We pin + persist the relay
     * leg (so the local-relay boot can open the wss connection) AND persist the
     * typed creds as MCP creds, then raise [relayLoginReady] so WyrdApp enters
     * local-relay mode. There, setupNatsServerClient sees saved MCP creds and
     * runs `wyrd.zone.{zone}.mcp.login` over the relay as this account — landing
     * the user in their own Study rather than auto-registering a throwaway phone
     * account. Mirrors the WelcomeScreen onComplete relay branch for the relay
     * persistence; the only addition is carrying the account creds.
     */
    private fun loginOverRelay() {
        val user = _username.value.trim()
        val pass = _password.value
        if (user.isBlank() || pass.isBlank()) {
            _error.value = "Enter your account username and password to log in over the relay"
            return
        }
        val invite = try {
            PhoneInvite.parse(_serverUrl.value)
        } catch (e: IllegalArgumentException) {
            _error.value = e.message ?: "Invalid invite URL"
            return
        }
        _error.value = null
        val relay = invite.relays.first()
        // Tier-1 phone guard: refuse a zone-less invite (see applyPhoneInvite) — it
        // would silently drop the user into a dead local Study.
        if (invite.zoneId.isNullOrBlank()) {
            _error.value = "This invite has no home zone — ask the zone owner for a fresh invite."
            return
        }
        // Persist the relay leg (URL + transport creds + zone) so the cold-start
        // boot reconnects without the invite, and set the AppProps the local
        // boot reads on this same launch.
        tokenStore.saveNatsUrl(relay.wsUrl)
        tokenStore.saveRelayUrl(relay.wsUrl)
        tokenStore.saveNatsUser(relay.natsUser)
        tokenStore.saveNatsPassword(relay.natsPassword)
        AppProps.set("wyrdsekai.nats.url", relay.wsUrl)
        AppProps.set("wyrdsekai.nats.user", relay.natsUser)
        AppProps.set("wyrdsekai.nats.pass", relay.natsPassword)
        invite.zoneId?.let { tokenStore.saveZoneId(it) }
        // The invite IS the trust decision: pin the relay cert from the carried
        // fingerprints before the first connect (NodeManager re-pins from the
        // persisted fps too, so a kill mid-pin loses nothing).
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
        // The user's ACCOUNT creds — setupNatsServerClient reads these and runs
        // mcp.login as this user instead of registerAndLogin.
        tokenStore.saveMcpUsername(user)
        tokenStore.saveMcpPassword(pass)
        tokenStore.saveUsername(user)
        AppProps.set("wyrdsekai.mcp.username", user)
        AppProps.set("wyrdsekai.mcp.password", pass)
        // Accrue this zone into the held "Your servers" bank (parity with the
        // WelcomeScreen invite-paste path).
        runCatching { addInviteToBank(_serverUrl.value) }
        _error.value = "Logging in as $user over the relay…"
        _relayLoginReady.value = true
    }

    fun register() {
        extractInvite(_serverUrl.value)?.let { invite ->
            _serverUrl.value = invite
            if (applyPhoneInvite(invite)) return
        }
        // Same guard as login(): no HTTP against a relay wss URL.
        if (isRelayWsUrl(_serverUrl.value)) {
            _error.value = "That's a relay address — relays need an invite. " +
                "On your node, run: wyrd phone invite, then paste the wyrdphone:// link here."
            return
        }
        scope.launch {
            _isLoading.value = true
            _error.value = null
            val client = AuthClient(_serverUrl.value)
            val result = client.register(_username.value, _password.value, _username.value)
            client.close()

            result.fold(
                onSuccess = { auth ->
                    _token.value = auth.token
                    saveCredentials(auth.token)
                    webSocket.connect(_serverUrl.value, auth.token, currentLocale())
                },
                onFailure = { e ->
                    _error.value = e.message ?: "Registration failed"
                },
            )
            _isLoading.value = false
        }
    }

    fun connectAnonymous() {
        webSocket.connect(_serverUrl.value, null, currentLocale())
    }

    fun connectToLocalNode() {
        _serverUrl.value = "localhost:7070"
        webSocket.connect("localhost:7070", _token.value, currentLocale())
    }

    /** A bare relay NATS-over-WebSocket URL — HTTP auth cannot work against it. */
    private fun isRelayWsUrl(url: String): Boolean =
        url.startsWith("wss://", ignoreCase = true) || url.startsWith("ws://", ignoreCase = true)

    /**
     * A pasted invite often lands AFTER pre-filled text (saved server URL,
     * stray whitespace) — "localhost:7070wyrdphone://…" must still read as an
     * invite, not fall through to an HTTP login against garbage (proven live
     * on the RN client, 2026-07-22). Extract the wyrdphone:// substring
     * wherever it sits.
     */
    private fun extractInvite(raw: String): String? =
        Regex("wyrdphone://\\S+").find(raw)?.value

    private fun currentLocale(): String = tokenStore.loadLocale() ?: "en"

    fun disconnect() {
        webSocket.disconnect()
        _token.value = null
    }

    private fun saveCredentials(token: String) {
        tokenStore.saveToken(token)
        tokenStore.saveServerUrl(_serverUrl.value)
        tokenStore.saveUsername(_username.value)
    }
}
