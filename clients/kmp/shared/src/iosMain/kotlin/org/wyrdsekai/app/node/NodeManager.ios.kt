@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.engine.between.NatsBetweenClient
import org.wyrdsekai.app.engine.transit.RelayTunnelHolder
import org.wyrdsekai.app.engine.persistence.IosEventJournal
import org.wyrdsekai.app.engine.persistence.IosVitalityStore
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.inference.RemoteAuthType
import org.wyrdsekai.app.network.HouseholdTrustStore
import org.wyrdsekai.app.network.parseWsHostPort
import org.wyrdsekai.app.network.pinRelayFromInviteFingerprints
import org.wyrdsekai.app.platform.AppProps
import org.wyrdsekai.app.state.TokenStore
import platform.Foundation.*

/**
 * iOS NodeManager — runs a phone-class node with extracted engines.
 * Boots Nexus + Terminal rooms, spawns Wyrd companion, connects to inference.
 */
actual class NodeManager actual constructor(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow("stopped")
    actual val state: StateFlow<String> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    actual val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    actual val isAvailable: Boolean = true
    actual val port: Int = 7070

    private var _phoneNode: PhoneNode? = null
    actual val phoneNode: PhoneNode? get() = _phoneNode
    actual val modelStatus: kotlinx.coroutines.flow.StateFlow<String> = MutableStateFlow("idle")
    actual val modelProgress: kotlinx.coroutines.flow.StateFlow<Float> = MutableStateFlow(0f)
    actual val modelStatusText: kotlinx.coroutines.flow.StateFlow<String?> = MutableStateFlow(null)
    private var journal: IosEventJournal? = null
    private var vitalityStore: IosVitalityStore? = null
    private var relayNats: NatsBetweenClient? = null

    actual fun start() {
        _state.value = "starting"
        scope.launch {
            try {
                val dataDir = getDataDirectory()
                val j = IosEventJournal(dataDir)
                journal = j
                val vs = IosVitalityStore(dataDir)
                vitalityStore = vs

                // Inference endpoint — the Welcome wizard's API-key path persists
                // the provider's base URL here via AppProps; default to LAN
                // llama-server.
                val inferenceUrl = AppProps.get("wyrdsekai.inference.url") ?: "http://localhost:8080"

                // Cloud-API auth — mirror NodeManager.android: the wizard's
                // "I have an API key" path persists provider/key via AppProps.
                // Plumb them onto the InferenceClient so the cloud HTTP call sends
                // the right header + model. anthropic → x-api-key, openai/
                // openrouter → Bearer. Without this iOS shipped a plain localhost
                // client and the companion could never reach the cloud.
                val inferenceClient = InferenceClient()
                val apiProvider = AppProps.get("wyrdsekai.api.provider")
                val apiKey = AppProps.get("wyrdsekai.api.key")
                if (apiProvider != null && apiKey != null) {
                    val authType = when (apiProvider) {
                        "anthropic" -> RemoteAuthType.X_API_KEY
                        else -> RemoteAuthType.BEARER
                    }
                    inferenceClient.setRemoteAuth(authType, apiKey)
                    // Cloud /v1/chat/completions 400s without `model`. Send the
                    // provider's default; an explicit override wins if set.
                    val apiModel = AppProps.get("wyrdsekai.api.model")
                        ?: when (apiProvider) {
                            "anthropic" -> "claude-sonnet-4-6"
                            "openrouter" -> "anthropic/claude-sonnet-4"
                            "openai" -> "gpt-4o"
                            else -> null
                        }
                    inferenceClient.setRemoteModel(apiModel)
                    println("WyrdNode: Cloud-API auth wired: provider=$apiProvider authType=$authType model=$apiModel")
                }

                val node = PhoneNode(
                    journal = j,
                    vitalityStore = vs,
                    inferenceClient = inferenceClient,
                    inferenceBaseUrl = inferenceUrl,
                    scope = scope,
                )
                _phoneNode = node
                node.start()

                // Relay leg: pin-before-connect in the
                // same coroutine — the #1229 lesson — then hold the NATS
                // connection so the phone appears as relay_phone even before
                // a zone node answers discovery.
                scope.launch { setupRelayLeg() }

                // Wait for node to reach RUNNING state
                node.state.collect { nodeState ->
                    when (nodeState) {
                        PhoneNode.State.RUNNING -> {
                            _state.value = "running"
                            return@collect
                        }
                        PhoneNode.State.ERROR -> {
                            _state.value = "error"
                            _errorMessage.value = node.error.value
                            return@collect
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message
                _state.value = "error"
            }
        }
    }

    /**
     * Connect the standalone NATS relay leg from invite-provisioned props
     * (set by WyrdApp's invite branch and cold-start restore). An explicit
     * wyrdsekai.nats.url wins; without it the leg is skipped — iOS has no
     * server-url derivation path.
     */
    private suspend fun setupRelayLeg() {
        try {
            val rawUrl = AppProps.get("wyrdsekai.nats.url") ?: run {
                println("WyrdNode: NATS setup skipped — no nats url configured")
                return
            }
            val wsUrl =
                if (rawUrl.startsWith("nats://")) rawUrl.replace("nats://", "ws://") else rawUrl
            val natsUser = AppProps.get("wyrdsekai.nats.user") ?: "relay_phone"
            val natsPass = AppProps.get("wyrdsekai.nats.pass") ?: run {
                println("WyrdNode: NATS setup skipped — no nats credentials")
                return
            }
            println("WyrdNode: NATS setup wss=$wsUrl")

            // Pin the relay cert from the persisted invite fingerprints
            // BEFORE connecting, so the wss handshake can never race the pin.
            val fps = AppProps.get("wyrdsekai.relay.fps")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                .orEmpty()
            if (fps.isNotEmpty()) {
                parseWsHostPort(wsUrl)?.let { (host, port) ->
                    if (HouseholdTrustStore.get(host) == null) {
                        val pinned = pinRelayFromInviteFingerprints(host, port, fps)
                        println("WyrdNode: NATS pre-connect pin for $host: ${if (pinned) "OK" else "FAILED"}")
                    }
                }
            }

            val nc = NatsBetweenClient(scope)
            nc.setCredentials(natsUser, natsPass)
            nc.autoReconnect = true
            nc.connectWithRetry(wsUrl)
            relayNats = nc
            // NOTE: RelayTunnelHolder.set(nc) is published LAST (after zone +
            // mcp.login token are saved) — WyrdApp builds the tunnel the instant
            // the holder flips and reads loadZoneId()/loadAuthToken() THEN. If we
            // published here, the tunnel would open token-less before login
            // finished and the zone's loopback /ws rejects it (4002 Auth required,
            // #1268). The raw `nc` leg already holds the phone on the relay as
            // relay_phone without the holder.
            println("WyrdNode: NATS connected to $wsUrl as $natsUser")

            // to tunnel a FULL session into the real zone
            // (not just hold a raw leg) WyrdApp needs (1) the zone id and (2) a
            // session token. Mirror NodeManager.android.setupRelayLeg: resolve the
            // zone (cached, else discover with retry — the responder can lag the
            // leg) and `mcp.login` as the saved account, so the tunnel auths the
            // zone's loopback /ws as that user and lands in their Study instead of
            // falling back to the on-device offline node. Without this iOS only
            // ever showed the local mirror (#1268).
            val tokenStore = TokenStore()
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            fun field(reply: String?, key: String): String? = try {
                reply?.let { json.parseToJsonElement(it).jsonObject[key]?.jsonPrimitive?.contentOrNull }
            } catch (_: Exception) {
                null
            }
            fun replyOk(reply: String?): Boolean =
                field(reply, "ok")?.equals("true", ignoreCase = true) == true

            val cachedZone = tokenStore.loadZoneId()?.takeIf {
                it.isNotBlank() && it != "_unknown" && it != "home" && it != "unspecified"
            }
            var resolvedZone: String? = cachedZone
            if (cachedZone != null) {
                println("WyrdNode: NATS zone=$cachedZone (cached — discovery skipped)")
            } else {
                repeat(3) { attempt ->
                    if (resolvedZone != null) return@repeat
                    val reply = nc.request("wyrd.discover.zone", "{}", timeoutMs = 5_000L)
                    val z = field(reply, "zoneId")
                    if (!z.isNullOrBlank() && z != "home") {
                        resolvedZone = z
                        println("WyrdNode: NATS zone discovered → $z (attempt ${attempt + 1})")
                    } else {
                        println(
                            "WyrdNode: NATS zone discovery → " +
                                "${reply ?: "no responder"} (attempt ${attempt + 1})",
                        )
                    }
                }
            }

            val zone = resolvedZone
            if (zone == null) {
                println("WyrdNode: NATS no zone resolved — holding relay leg; terminal stays on offline node")
                return
            }

            // mcp.login as the saved account → session token the tunnel hands the
            // zone's /ws?token=. Anonymous (no creds) would land in the Nexus.
            val mcpUser = tokenStore.loadMcpUsername() ?: AppProps.get("wyrdsekai.mcp.username")
            val mcpPass = tokenStore.loadMcpPassword() ?: AppProps.get("wyrdsekai.mcp.password")
            if (!mcpUser.isNullOrBlank() && !mcpPass.isNullOrBlank()) {
                val loginPayload = buildJsonObject {
                    put("username", mcpUser)
                    put("password", mcpPass)
                }.toString()
                val loginReply = nc.request("wyrd.zone.$zone.mcp.login", loginPayload, timeoutMs = 8_000L)
                if (replyOk(loginReply)) {
                    val token = field(loginReply, "token")
                    if (!token.isNullOrBlank()) {
                        tokenStore.saveAuthToken(token)
                        println("WyrdNode: relay mcp.login OK as $mcpUser — tunnel lands in zone $zone")
                    } else {
                        println("WyrdNode: relay mcp.login reply missing token — tunnel would be anonymous")
                    }
                } else {
                    println("WyrdNode: relay mcp.login failed: ${field(loginReply, "error") ?: "no reply"}")
                }
            } else {
                println("WyrdNode: no saved account creds — tunnel would be anonymous (Nexus)")
            }

            // Publish zone + tunnel holder LAST — only now are both the zone id
            // and (for the authed path) the session token persisted, so WyrdApp's
            // tunnel build reads a complete (zone, token) pair and the zone's
            // loopback /ws accepts the session instead of rejecting it 4002 (#1268).
            tokenStore.saveZoneId(zone)
            RelayTunnelHolder.set(nc)
            // Wired-but-dead audit: iOS never attached the Between client to the
            // node, so presence/study-sync never came up on iOS at all.
            _phoneNode?.attachBetweenClient(nc)
            println("WyrdNode: relay tunnel holder published for zone $zone")
        } catch (e: Exception) {
            println("WyrdNode: NATS setup failed: ${e.message} — staying local-only")
        }
    }

    actual fun stop() {
        _phoneNode?.stop()
        _phoneNode = null
        journal = null
        vitalityStore = null
        RelayTunnelHolder.clear()
        scope.launch { relayNats?.disconnect() }
        relayNats = null
        _state.value = "stopped"
    }

    fun getPhoneNode(): PhoneNode? = _phoneNode

    private fun getDataDirectory(): String {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        )
        val docDir = paths.firstOrNull() as? String ?: "/tmp"
        return "$docDir/wyrdsekai"
    }
}
