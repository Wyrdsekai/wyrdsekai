package org.wyrdsekai.app.network

import io.nats.client.Connection
import io.nats.client.ErrorListener
import io.nats.client.JetStreamStatusException
import io.nats.client.Message
import io.nats.client.Nats
import io.nats.client.Options
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.crypto.SealedException
import org.wyrdsekai.app.crypto.SealedRequest
import org.wyrdsekai.app.engine.between.NatsBetweenClient
import org.wyrdsekai.app.i18n.currentUiStrings

/**
 * KMP (Android) NATS request/reply client for the wyrdsekai relay's
 * NATS WS-TLS surface.
 *
 * Mirrors [NatsServerClient.ts] in the RN client. Each public method maps
 * to a server-side NATS subject:
 *
 *   login          → wyrd.zone.{zone}.mcp.login
 *   tell           → wyrd.zone.{zone}.mcp.tell
 *   writeJournal   → wyrd.zone.{zone}.study.journal           (write op)
 *   listJournal    → wyrd.zone.{zone}.study.journal           (op = "list")
 *   searchLibrary  → wyrd.zone.{zone}.library.search
 *
 * Connection: `wss://relay:4443` via jnats (which supports wss:// since 2.16),
 * or the home's own bus `wss://host:4223` on the home network, with the
 * per-host invite pin ([HouseholdTrustManager]). Never plain ws:// off the
 * device. Reply inboxes sit under `_INBOX.<NATS username>` (D4): a relay lets
 * each user read only its own.
 *
 * Every zone request is a sealed request ( W3
 * [SealedRequest]) to the home's key [zoneKey] from the pairing invite: the
 * relay sees who asked which zone, not what. Without that key the client
 * refuses instead of sending plaintext. In the clear go only which zone
 * answers here (`wyrd.discover.zone`, never sealed) and, when no key for the
 * zone is known, a knock or a directory search: what a stranger may ask.
 *
 * Android-only. iOS phones use the RN client (clients/rn/.../NatsServerClient.ts).
 * Desktop falls through to the Ktor-based [ServerClient] HTTP path for now.
 *
 * Not yet on NATS server-side (server still serves these over HTTP — pending
 * Phase 4 follow-ups):
 *   - mcp/do (say/emote/...)   (use ServerClient.doCommand)
 */
class NatsServerClient(
    /**
     * Full wss:// URL to the relay's NATS WebSocket+TLS listener, e.g.
     * `wss://relay-node.example.com:4443`. Discovered through the existing
     * relay pairing flow; the household-CA leaf
     * cert must already be installed via probeAndTrust.
     */
    private val relayUrl: String,
    /** Zone ID for subject scoping: `wyrd.zone.{zoneId}.{op}`. Mutable so
     *  [setZoneId] (called after [discoverZone]) can switch the scope. */
    private var zoneId: String,
    /**
     * NATS credentials for this phone's account on the relay. Minted
     * during pairing (Phase 4b TODO: provision a `relay_phone_<userid>`
     * NATS user). For now: caller supplies the user/pass pair.
     */
    private val natsUser: String,
    private val natsPassword: String,
    /** The home's public tunnel key `zk`; null (paired before 0.5.0) refuses every zone request. */
    private val zoneKey: ByteArray?,
    initialMcpToken: String? = null,
    private val requestTimeout: Duration = Duration.ofSeconds(5),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : PhoneRemoteClient, ZoneBankSyncClient, DirectorySearchClient {
    private var nc: Connection? = null
    private var mcpToken: String? = initialMcpToken

    /**
     * Requests waiting on a subject the server may refuse this login to publish
     * on. The refusal arrives as `-ERR 'Permissions Violation for Publish to
     * "<subject>"'` through the error listener, not as a reply, so without this
     * a refused request would only time out.
     */
    private val refusals = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    private fun onServerError(error: String) {
        PUBLISH_REFUSED.find(error)?.groupValues?.get(1)?.let { refusals.remove(it)?.complete(Unit) }
    }

    fun getToken(): String? = mcpToken

    /**
     * Open the NATS WebSocket connection. Idempotent — safe to call
     * multiple times. Must succeed before any other method is invoked.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (nc?.status == Connection.Status.CONNECTED) return@withContext
        if (HomeLink.isPlaintextOffDevice(relayUrl)) throw PlaintextRefusedException(HomeLink.hostOf(relayUrl) ?: relayUrl)
        // jnats has its own TLS stack — it does NOT route through OkHttp's
        // HouseholdTrustManager. On wss:// to a household relay with a leaf
        // cert chained to the household CA (not a public CA), the platform
        // default SSLContext rejects the chain ("Trust anchor for
        // certification path not found"). Wire HouseholdTrustManager into a
        // fresh SSLContext and hand it to Options.Builder so jnats's
        // handshake uses the same per-host pin logic the HTTP path does.
        val systemTm = HouseholdTrustManager.resolveSystemTrustManager()
        val householdTm = HouseholdTrustManager(systemTm)
        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(householdTm), java.security.SecureRandom())
        }
        val opts = Options.Builder()
            .server(relayUrl)
            .userInfo(natsUser, natsPassword)
            .inboxPrefix(NatsBetweenClient.inboxFor(natsUser))
            .sslContext(sslCtx)
            .connectionName("wyrd-phone-kmp")
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(2))
            .errorListener(object : ErrorListener {
                override fun errorOccurred(conn: Connection, error: String) = onServerError(error)
            })
            .build()
        nc = Nats.connect(opts)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        nc?.let { runCatching { it.drain(Duration.ofSeconds(3)).get() } }
        nc = null
    }

    // ── subjects ──

    private fun subject(op: String) = "wyrd.zone.$zoneId.$op"

    /**
     * Send a request to a NATS subject and parse the JSON reply.
     * Always returns a JsonObject — transport failures are shaped as
     * `{ "ok": false, "error": "..." }` so callers use a single path.
     *
     * Sealed to [key] (default: this zone's). With no key, only a subject a
     * stranger may use goes out in the clear; anything else is refused here.
     */
    private suspend fun request(subj: String, body: JsonObject, key: ByteArray? = zoneKey): JsonObject = withContext(Dispatchers.IO) {
        val conn = nc ?: return@withContext failure("Not connected — call connect() first")
        val bodyJson = json.encodeToString(JsonObject.serializer(), body)
        if (key == null && !mayGoInTheClear(subj)) {
            return@withContext failure("zone_key_missing", currentUiStrings().secRepairTunnel)
        }
        val refused = CompletableFuture<Unit>().also { refusals[subj] = it }
        try {
            val sealed = key?.let { SealedRequest.seal(it, subj, bodyJson, System.currentTimeMillis()) }
            val payload = (sealed?.wire ?: bodyJson).toByteArray(StandardCharsets.UTF_8)
            val answer = conn.requestWithTimeout(subj, payload, requestTimeout)
            try {
                CompletableFuture.anyOf(answer, refused).get(requestTimeout.toMillis() + 1_000, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                return@withContext failure("request-failed: timeout")
            } catch (_: Exception) {
                // The reply future failed or was cancelled; told apart below.
            }
            if (refused.isDone) return@withContext failure("not_permitted")
            // jnats cancels the request when the server says nobody listens there (503).
            if (answer.isCancelled) return@withContext failure("no_responders")
            val msg: Message = try {
                answer.get()
            } catch (e: ExecutionException) {
                val cause = e.cause
                return@withContext when {
                    cause is JetStreamStatusException && cause.status?.code == NO_RESPONDERS -> failure("no_responders")
                    cause is TimeoutException || cause is CancellationException -> failure("request-failed: timeout")
                    else -> failure("request-failed: ${cause?.message ?: cause?.let { it::class.simpleName }}")
                }
            } ?: return@withContext failure("request-failed: timeout")
            if (msg.isStatusMessage && msg.status?.code == NO_RESPONDERS) return@withContext failure("no_responders")
            val reply = String(msg.data, StandardCharsets.UTF_8)
            val text = try {
                sealed?.openReply(reply) ?: reply
            } catch (_: SealedException) {
                return@withContext failure("sealed_reply_invalid")
            }
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            failure("request-failed: ${e.message ?: e::class.simpleName}")
        } finally {
            refusals.remove(subj, refused)
        }
    }

    private fun failure(error: String, message: String? = null) = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(error))
        if (message != null) put("message", JsonPrimitive(message))
    }

    // ── pair.device ──

    /**
     * Mint this device's identity through an authenticated session, over
     * NATS — the hermod consent mint for relay-resident phones (mirrors
     * POST /api/pair/device; same registry row, same wyrd_dev_ token).
     */
    suspend fun pairDevice(
        sessionToken: String,
        deviceName: String,
        deviceType: String = "phone",
    ): PairingClient.PairingCredentials? {
        val reply = request(subject("pair.device"), buildJsonObject {
            put("token", JsonPrimitive(sessionToken))
            put("deviceName", JsonPrimitive(deviceName))
            put("deviceType", JsonPrimitive(deviceType))
        })
        if (!replyOk(reply)) return null
        val deviceToken = reply["deviceToken"]?.jsonPrimitive?.contentOrNull ?: return null
        return PairingClient.PairingCredentials(
            token = deviceToken,
            householdId = reply["householdId"]?.jsonPrimitive?.contentOrNull ?: "",
            householdName = reply["householdName"]?.jsonPrimitive?.contentOrNull ?: "",
            serverDid = reply["serverDid"]?.jsonPrimitive?.contentOrNull ?: "",
            natsUrl = reply["natsUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            serverUrl = reply["serverUrl"]?.jsonPrimitive?.contentOrNull ?: "",
            natsUser = reply["nats_user"]?.jsonPrimitive?.contentOrNull,
            natsPass = reply["nats_pass"]?.jsonPrimitive?.contentOrNull,
        )
    }

    // ── wyrd.discover.zone ──

    /**
     * Zone-agnostic discovery. The phone doesn't yet know which zone label
     * to scope its NATS subjects under. It publishes a single request to
     * the global `wyrd.discover.zone` subject and the server replies with
     * its zone id. The phone then calls [setZoneId] before any auth.* /
     * mcp.* / library.* / study.* request.
     */
    suspend fun discoverZone(): String? {
        return try {
            connect()
            // In the clear, as PROTOCOL.md specifies: it carries nothing, and every
            // home on the relay may answer with its zone name.
            val reply = request("wyrd.discover.zone", buildJsonObject {}, key = null)
            if (!replyOk(reply)) null
            else reply["zoneId"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Switch the zone scope this client publishes under. Refuses `"home"` —
     * it's reserved as a furnishing concept.
     */
    fun setZoneId(newZoneId: String) {
        require(newZoneId.isNotBlank() && newZoneId != "home") {
            "Invalid zone id: \"home\" is reserved"
        }
        zoneId = newZoneId
    }

    // ── auth.status ──

    /**
     * Probe whether the relay's zone is reachable + learn registration policy.
     * Replaces the HTTP probe (`/api/auth/status`).
     */
    suspend fun probe(): Pair<Boolean, Boolean>? {
        return try {
            connect()
            val reply = request("wyrd.zone.$zoneId.auth.status", buildJsonObject {})
            if (!replyOk(reply)) null
            else {
                val hasUsers = reply["hasUsers"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val openReg = reply["openRegistration"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                hasUsers to openReg
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Auto-create an anonymous phone account and log in over NATS. Mirrors
     * the HTTP `registerAndLogin` flow. Only valid when the zone reports
     * `openRegistration: true` from [probe].
     */
    suspend fun registerAndLogin(companionName: String): Pair<Pair<String, String>, ServerClient.AuthOk> {
        connect()
        val username = "phone-${companionName.lowercase().replace(Regex("[^a-z0-9]"), "")}-${randomSuffix()}"
        val password = buildString {
            repeat(32) { append((0..15).random().toString(16)) }
        }
        val reply = request("wyrd.zone.$zoneId.auth.register", buildJsonObject {
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
            put("displayName", JsonPrimitive("$companionName's phone"))
        })
        if (!replyOk(reply)) {
            throw IllegalStateException(reply["error"]?.jsonPrimitive?.contentOrNull ?: "register failed")
        }
        val token = reply["token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("register reply missing token")
        mcpToken = token
        val replyUser = reply["username"]?.jsonPrimitive?.contentOrNull ?: username
        return (username to password) to ServerClient.AuthOk(token = token, username = replyUser)
    }

    /**
     * Redeem an invite code and create an account on a closed-registration
     * household. Mirrors POST /api/auth/redeem. Use this when [probe]
     * reports `openRegistration: false`.
     */
    suspend fun redeemInvite(
        code: String,
        companionName: String,
    ): Pair<Pair<String, String>, ServerClient.AuthOk> {
        connect()
        val username = "phone-${companionName.lowercase().replace(Regex("[^a-z0-9]"), "")}-${randomSuffix()}"
        val password = buildString {
            repeat(32) { append((0..15).random().toString(16)) }
        }
        val reply = request("wyrd.zone.$zoneId.auth.redeem", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
            put("displayName", JsonPrimitive("$companionName's phone"))
        })
        if (!replyOk(reply)) {
            throw IllegalStateException(reply["error"]?.jsonPrimitive?.contentOrNull ?: "redeem failed")
        }
        val token = reply["token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("redeem reply missing token")
        mcpToken = token
        val replyUser = reply["username"]?.jsonPrimitive?.contentOrNull ?: username
        return (username to password) to ServerClient.AuthOk(token = token, username = replyUser)
    }

    /**
     * Create a NAMED account over the relay — the user's chosen username and
     * password, not the auto-generated anonymous phone account above. This is
     * the phone-first onboarding path (2026-07-23, parity with RN
     * registerNamed): a fresh household's first registrant becomes the steward
     * and receives a one-time recoveryKey the caller MUST surface (it's the
     * only password-reset credential). Fails with `registration_closed` once
     * the household has a steward — collect an invite code and use
     * [redeemNamed] instead.
     */
    suspend fun registerNamed(
        username: String,
        password: String,
        displayName: String? = null,
    ): NamedAccountResult {
        connect()
        val reply = request("wyrd.zone.$zoneId.auth.register", buildJsonObject {
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
            put("displayName", JsonPrimitive(displayName ?: username))
        })
        return namedAccountFrom(reply, username, "register failed")
    }

    /** Redeem a steward-minted invite code into a NAMED account (closed registration). */
    suspend fun redeemNamed(
        code: String,
        username: String,
        password: String,
        displayName: String? = null,
    ): NamedAccountResult {
        connect()
        val reply = request("wyrd.zone.$zoneId.auth.redeem", buildJsonObject {
            put("code", JsonPrimitive(code))
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
            put("displayName", JsonPrimitive(displayName ?: username))
        })
        return namedAccountFrom(reply, username, "redeem failed")
    }

    data class NamedAccountResult(
        val auth: ServerClient.AuthOk,
        val role: String?,
        val recoveryKey: String?,
    )

    private fun namedAccountFrom(
        reply: JsonObject,
        username: String,
        failMsg: String,
    ): NamedAccountResult {
        if (!replyOk(reply)) {
            throw IllegalStateException(reply["error"]?.jsonPrimitive?.contentOrNull ?: failMsg)
        }
        val token = reply["token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("reply missing token")
        mcpToken = token
        return NamedAccountResult(
            auth = ServerClient.AuthOk(
                token = token,
                username = reply["username"]?.jsonPrimitive?.contentOrNull ?: username,
                userId = reply["userId"]?.jsonPrimitive?.contentOrNull,
            ),
            role = reply["role"]?.jsonPrimitive?.contentOrNull,
            recoveryKey = reply["recoveryKey"]?.jsonPrimitive?.contentOrNull,
        )
    }

    // ── login ──

    /**
     * Log in with username/password. Mirrors POST /api/mcp/login.
     * Caches the token for subsequent calls. Throws on any failure.
     */
    suspend fun login(username: String, password: String): ServerClient.AuthOk {
        connect()
        val reply = request(subject("mcp.login"), buildJsonObject {
            put("username", JsonPrimitive(username))
            put("password", JsonPrimitive(password))
        })
        val ok = reply["ok"]?.jsonPrimitive?.contentOrNull == "true" ||
            reply["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        if (!ok) {
            val err = reply["error"]?.jsonPrimitive?.contentOrNull ?: "login failed"
            throw IllegalStateException(err)
        }
        val token = reply["token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("login reply missing token")
        val replyUser = reply["username"]?.jsonPrimitive?.contentOrNull ?: username
        // The account userId OWNS the Study (stable across the user's devices) —
        // capture it so the phone syncs the Study under the account, not the
        // companion soul DID.
        val replyUserId = reply["userId"]?.jsonPrimitive?.contentOrNull
        mcpToken = token
        return ServerClient.AuthOk(token = token, username = replyUser, userId = replyUserId)
    }

    // ── tell ──

    /**
     * Send a tell to an in-zone or cross-zone target. Cross-zone routing
     * is handled server-side via CrossZoneTellService.
     */
    override suspend fun tell(target: String, message: String): ServerClient.McpResult {
        val token = mcpToken ?: return ServerClient.McpResult(ok = false, error = "Not logged in", status = 401)
        val reply = request(subject("mcp.tell"), buildJsonObject {
            put("token", JsonPrimitive(token))
            put("target", JsonPrimitive(target))
            put("message", JsonPrimitive(message))
        })
        return if (replyOk(reply)) {
            ServerClient.McpResult(ok = true, data = "Delivered to ${reply["target"]?.jsonPrimitive?.contentOrNull ?: target}")
        } else {
            ServerClient.McpResult(
                ok = false,
                error = reply["error"]?.jsonPrimitive?.contentOrNull ?: "tell failed",
                status = reply["_status"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    // ── doCommand shim (PhoneRemoteClient interface) ──

    /**
     * Parse a "say …" free-form Study command and route it to the right
     * NATS subject. LocalRoomScreen sends commands as `say library search
     * <q>` / `say journal <text>` / etc. — the HTTP transport posts them
     * verbatim to /api/mcp/do; the NATS transport needs to map them to
     * typed subjects (library.search, study.journal) by hand.
     *
     * Unrecognised commands return ok=false so the caller can fall back to
     * the local PhoneNode handler.
     */
    override suspend fun doCommand(command: String): ServerClient.McpResult {
        // Strip optional leading `say ` so we accept both forms.
        val body = command.trim().let {
            if (it.startsWith("say ", ignoreCase = true)) it.substring(4).trim() else it
        }
        val lower = body.lowercase()

        // Library search — accept "library search X", "search the library
        // for X", "search library for X", "use library card X", "use
        // library_card X" so the same screen text routes either way.
        val libPrefixes = listOf(
            "library search ", "search the library for ", "search library for ",
            "use library card ", "use library_card ",
        )
        for (pfx in libPrefixes) {
            if (lower.startsWith(pfx)) {
                val query = body.substring(pfx.length).trim()
                return searchLibrary(query)
            }
        }

        // Journal — "journal entry X", "journal private X", "journal X".
        // Search ("journal search X") isn't on the NATS surface yet; defer
        // to fallback.
        if (lower.startsWith("journal entry ")) {
            return writeJournal(body.substring("journal entry ".length).trim(), isPrivate = false)
        }
        if (lower.startsWith("journal private ")) {
            return writeJournal(body.substring("journal private ".length).trim(), isPrivate = true)
        }
        if (lower.startsWith("journal ") && !lower.startsWith("journal search ")) {
            return writeJournal(body.substring("journal ".length).trim(), isPrivate = false)
        }

        return ServerClient.McpResult(
            ok = false,
            error = "unsupported via NATS transport: $body",
        )
    }

    // ── library.search ──

    /**
     * Search the household knowledge library. Returns formatted prose
     * matching ServerClient so callers can swap clients without changing
     * the consumer.
     */
    suspend fun searchLibrary(query: String, limit: Int = 5): ServerClient.McpResult {
        val token = mcpToken ?: return ServerClient.McpResult(ok = false, error = "Not logged in", status = 401)
        val reply = request(subject("library.search"), buildJsonObject {
            put("token", JsonPrimitive(token))
            put("query", JsonPrimitive(query))
            put("limit", JsonPrimitive(limit))
        })
        if (!replyOk(reply)) {
            return ServerClient.McpResult(
                ok = false,
                error = reply["error"]?.jsonPrimitive?.contentOrNull ?: "library search failed",
            )
        }
        val results = reply["results"]?.jsonArray ?: emptyList<Any>()
        if (results.isEmpty()) {
            return ServerClient.McpResult(ok = true, data = "No library results for \"$query\".")
        }
        val lines = StringBuilder("Library results for \"$query\" (${results.size}):")
        for (r in results) {
            val obj = (r as? kotlinx.serialization.json.JsonElement)?.jsonObject ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
                ?: obj["source"]?.jsonPrimitive?.contentOrNull
                ?: "untitled"
            val snippet = (obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["snippet"]?.jsonPrimitive?.contentOrNull
                ?: "").take(180).replace(Regex("\\s+"), " ")
            lines.append("\n  • ").append(title)
            if (snippet.isNotEmpty()) lines.append(" — ").append(snippet).append("…")
        }
        return ServerClient.McpResult(ok = true, data = lines.toString())
    }

    // ── study.journal ──

    /**
     * Write a journal entry. The user DID is derived from the auth token
     * server-side — phones can't forge a different user.
     */
    suspend fun writeJournal(content: String, isPrivate: Boolean = false): ServerClient.McpResult {
        val token = mcpToken ?: return ServerClient.McpResult(ok = false, error = "Not logged in", status = 401)
        val reply = request(subject("study.journal"), buildJsonObject {
            put("token", JsonPrimitive(token))
            put("content", JsonPrimitive(content))
            put("isPrivate", JsonPrimitive(isPrivate))
        })
        if (!replyOk(reply)) {
            return ServerClient.McpResult(
                ok = false,
                error = reply["error"]?.jsonPrimitive?.contentOrNull ?: "journal write failed",
            )
        }
        val id = reply["id"]?.jsonPrimitive?.contentOrNull ?: "ok"
        return ServerClient.McpResult(ok = true, data = "Journal entry saved ($id).")
    }

    /**
     * List recent journal entries (most recent first). Returns the raw
     * array as a JsonObject under `entries`.
     */
    suspend fun listJournal(limit: Int = 20): JsonObject {
        val token = mcpToken ?: return buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive("Not logged in"))
        }
        return request(subject("study.journal"), buildJsonObject {
            put("token", JsonPrimitive(token))
            put("op", JsonPrimitive("list"))
            put("limit", JsonPrimitive(limit))
        })
    }

    // ── directory.knock ── (: request access)

    /**
     * Knock on a discovered zone's door. Token-free — you need no account on the
     * target zone yet. Sent to the TARGET zone's own subject
     * (`wyrd.zone.{targetZone}.directory.knock`, the one subject a relay lets a
     * phone publish for another zone), the reply to this login's own inbox.
     * Sealed to the zone's key when the phone has it, else in the clear (it
     * carries only a name and maybe a contact and reason).
     *
     * Not recorded → a plain reason: an older relay refuses the publish
     * (permissions violation); no home for that zone on this relay answers
     * (no responders, or no answer in time); or the zone's own refusal.
     */
    suspend fun requestAccess(
        targetZone: String,
        requesterName: String,
        requesterContact: String? = null,
        reason: String? = null,
        /** The target zone's key when this phone has one; a knock to a stranger goes in the clear. */
        targetZoneKey: ByteArray? = if (targetZone == zoneId) zoneKey else null,
    ): KnockAnswer {
        connect()
        val reply = request("wyrd.zone.$targetZone.directory.knock", buildJsonObject {
            put("requesterName", JsonPrimitive(requesterName))
            if (requesterContact != null) put("requesterContact", JsonPrimitive(requesterContact))
            if (reason != null) put("reason", JsonPrimitive(reason))
        }, targetZoneKey)
        if (replyOk(reply)) return KnockAnswer(requestId = reply["requestId"]?.jsonPrimitive?.contentOrNull ?: "")
        val error = reply["error"]?.jsonPrimitive?.contentOrNull
        val strings = currentUiStrings()
        return KnockAnswer(requestId = null, message = when {
            error == "not_permitted" -> strings.secKnockRefusedByRelay
            error == "no_responders" || error == "request-failed: timeout" -> strings.secKnockNoAnswer
            else -> reply["message"]?.jsonPrimitive?.contentOrNull
        })
    }

    // ── account.zonebank ── (: cross-device sync)

    /**
     * Pull this account's synced zone bank from its home zone. The account is
     * resolved server-side from the auth token, so a phone only sees its own
     * bank. Returns null on transport/auth failure (the caller treats it as a
     * skipped sync). Secrets never travel through here — only the address book.
     */
    override suspend fun getZoneBank(): ZoneBankFetch? {
        val token = mcpToken ?: return null
        val reply = request(subject("account.zonebank.get"), buildJsonObject {
            put("token", JsonPrimitive(token))
        })
        if (!replyOk(reply)) return null
        val bank = reply["bank"]?.jsonPrimitive?.contentOrNull
        val updatedAt = reply["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        return ZoneBankFetch(bank = bank, updatedAt = updatedAt)
    }

    /**
     * Push the merged zone bank up to the home zone. The client already merged
     * per-entry LWW locally; the server is a dumb last-write blob store. Zone
     * passwords must NOT be in [bankJson] — they stay in per-device storage.
     */
    override suspend fun putZoneBank(bankJson: String, updatedAt: Long): Boolean {
        val token = mcpToken ?: return false
        val reply = request(subject("account.zonebank.put"), buildJsonObject {
            put("token", JsonPrimitive(token))
            put("bank", JsonPrimitive(bankJson))
            put("updatedAt", JsonPrimitive(updatedAt))
        })
        return replyOk(reply)
    }

    // ── directory.search ── (: "Find a zone")

    /**
     * Query the opt-in zone directory. No token — only zones that advertise
     * themselves are returned, and a relay's roster is never enumerated. Returns
     * null on transport failure; an empty list means "no published zones".
     */
    override suspend fun searchDirectory(query: String, limit: Int): List<JsonObject>? {
        connect()
        val reply = request(subject("directory.search"), buildJsonObject {
            put("query", JsonPrimitive(query))
            put("limit", JsonPrimitive(limit))
        })
        if (!replyOk(reply)) return null
        val zones = reply["zones"] ?: return emptyList()
        return try {
            zones.jsonArray.mapNotNull { it as? JsonObject }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── helpers ──

    /**
     * Robust ok-bool check — kotlinx.serialization renders booleans as
     * JsonPrimitive (literal "true"/"false"), not as Kotlin Boolean, so a
     * naive `.booleanOrNull` returns null. Check both shapes.
     */
    private fun replyOk(reply: JsonObject): Boolean {
        val raw = reply["ok"]?.jsonPrimitive?.contentOrNull ?: return false
        return raw.equals("true", ignoreCase = true)
    }

    private fun randomSuffix(len: Int = 8): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(len) { append(chars.random()) } }
    }

    companion object {
        /**
         * What a stranger to a zone may ask without its key, and so the only
         * requests that ever go in the clear: which zone answers here, a knock,
         * and a search of the public directory (the home accepts these unsealed
         * too). None carries a password or a token.
         */
        internal fun mayGoInTheClear(subject: String): Boolean =
            subject == "wyrd.discover.zone" || subject.endsWith(".directory.knock") || subject.endsWith(".directory.search")

        /** The status nats-server sends a request nobody listens for. */
        private const val NO_RESPONDERS = 503

        /** nats-server's refusal of a publish: `Permissions Violation for Publish to "<subject>"`. */
        private val PUBLISH_REFUSED = Regex("""Permissions Violation for Publish to "([^"]+)"""")
    }
}
