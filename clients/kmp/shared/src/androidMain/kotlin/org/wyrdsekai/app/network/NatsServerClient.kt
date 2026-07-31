package org.wyrdsekai.app.network

import io.nats.client.Connection
import io.nats.client.Message
import io.nats.client.Nats
import io.nats.client.Options
import java.nio.charset.StandardCharsets
import java.time.Duration
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
 * Connection: `wss://relay:4443` via jnats (which supports wss:// since 2.16).
 * TLS verification piggy-backs on the OkHttp HouseholdTrust pin already
 * installed for HTTPS — jnats falls back to the platform default SSLContext,
 * which on Android uses the user trust store (where the household CA cert
 * is installed during the existing TOFU flow). For per-port pin granularity
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
    initialMcpToken: String? = null,
    private val requestTimeout: Duration = Duration.ofSeconds(5),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : PhoneRemoteClient, ZoneBankSyncClient, DirectorySearchClient {
    private var nc: Connection? = null
    private var mcpToken: String? = initialMcpToken

    fun getToken(): String? = mcpToken

    /**
     * Open the NATS WebSocket connection. Idempotent — safe to call
     * multiple times. Must succeed before any other method is invoked.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (nc?.status == Connection.Status.CONNECTED) return@withContext
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
            .sslContext(sslCtx)
            .connectionName("wyrd-phone-kmp")
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(2))
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
     */
    private suspend fun request(subj: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val conn = nc ?: return@withContext buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive("Not connected — call connect() first"))
        }
        try {
            val payload = json.encodeToString(JsonObject.serializer(), body).toByteArray(StandardCharsets.UTF_8)
            val msg: Message? = conn.request(subj, payload, requestTimeout)
            if (msg == null) {
                return@withContext buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive("request-failed: timeout"))
                }
            }
            val text = String(msg.data, StandardCharsets.UTF_8)
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("error", JsonPrimitive("request-failed: ${e.message ?: e::class.simpleName}"))
            }
        }
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
            val reply = request("wyrd.discover.zone", buildJsonObject {})
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
     * target zone yet. Sent to the TARGET zone's own subject, so it reaches the
     * zone you discovered if it homes on a relay you hold. Returns the recorded
     * request id, or null on failure.
     */
    suspend fun requestAccess(
        targetZone: String,
        requesterName: String,
        requesterContact: String? = null,
        reason: String? = null,
    ): String? {
        connect()
        val reply = request("wyrd.zone.$targetZone.directory.knock", buildJsonObject {
            put("requesterName", JsonPrimitive(requesterName))
            if (requesterContact != null) put("requesterContact", JsonPrimitive(requesterContact))
            if (reason != null) put("reason", JsonPrimitive(reason))
        })
        if (!replyOk(reply)) return null
        return reply["requestId"]?.jsonPrimitive?.contentOrNull
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
}
