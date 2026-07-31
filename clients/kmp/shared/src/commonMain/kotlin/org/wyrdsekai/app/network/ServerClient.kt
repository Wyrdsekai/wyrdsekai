package org.wyrdsekai.app.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin REST client for the wyrdsekai server's MCP API. Mirrors the RN
 * ServerClient: probe / registerAndLogin / login / tell / doCommand.
 *
 * Auth is via `Authorization: Bearer <mcpToken>`. The MCP session token
 * is obtained from `/api/mcp/login` and cached in the instance.
 */
class ServerClient(
    val baseUrl: String,
    // Default uses the household-trust HTTP client so HTTPS handshakes to a
    // LAN relay with a household-CA leaf cert succeed after TOFU pinning.
    // Tests inject a stub client.
    private val httpClient: HttpClient = createHouseholdHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : PhoneRemoteClient {
    private var mcpToken: String? = null

    fun getToken(): String? = mcpToken

    /** Status response shape from `/api/auth/status`. */
    data class ServerStatus(val hasUsers: Boolean, val openRegistration: Boolean)

    /** Result wrapper for MCP calls — `ok=true` ⇒ `data` carries the response. */
    data class McpResult(val ok: Boolean, val data: String? = null, val error: String? = null, val status: Int = 0)

    /** Auth response from /api/mcp/login. */
    data class AuthOk(val token: String, val username: String, val userId: String? = null)

    /** Generated phone credentials, persisted by the caller. */
    data class PhoneCreds(val username: String, val password: String)

    /**
     * Probe a URL to detect whether it hosts a wyrdsekai server. Returns
     * status on success, null otherwise. Safe to call repeatedly.
     */
    suspend fun probe(): ServerStatus? {
        return try {
            val resp = httpClient.get("$baseUrl/api/auth/status")
            if (resp.status != HttpStatusCode.OK) return null
            val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            ServerStatus(
                hasUsers = obj["hasUsers"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                openRegistration = obj["openRegistration"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Auto-create an anonymous phone account and log in. Returns the
     * generated credentials so the caller can persist them for re-use.
     */
    suspend fun registerAndLogin(companionName: String): Pair<PhoneCreds, AuthOk> {
        val sanitized = companionName.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "wyrd" }
        val username = "phone-$sanitized-${randomSuffix()}"
        val password = (1..32).joinToString("") { "0123456789abcdef".random().toString() }
        val displayName = "$companionName's phone"

        val regResp = httpClient.post("$baseUrl/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("username", JsonPrimitive(username))
                put("password", JsonPrimitive(password))
                put("display_name", JsonPrimitive(displayName))
            }.toString())
        }
        if (regResp.status != HttpStatusCode.OK && regResp.status != HttpStatusCode.Conflict) {
            throw RuntimeException("Register failed: ${regResp.status}")
        }

        val auth = loginInternal(username, password)
        return PhoneCreds(username, password) to auth
    }

    suspend fun login(username: String, password: String): AuthOk = loginInternal(username, password)

    private suspend fun loginInternal(username: String, password: String): AuthOk {
        val resp = httpClient.post("$baseUrl/api/mcp/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("username", JsonPrimitive(username))
                put("password", JsonPrimitive(password))
            }.toString())
        }
        if (resp.status != HttpStatusCode.OK) {
            throw RuntimeException("Login failed: ${resp.status}")
        }
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val token = obj["token"]?.jsonPrimitive?.contentOrNull
            ?: throw RuntimeException("Login response missing token")
        mcpToken = token
        return AuthOk(token, username)
    }

    /** POST /api/mcp/tell — server routes cross-zone via CrossZoneTellService. */
    override suspend fun tell(target: String, message: String): McpResult {
        val token = mcpToken ?: return McpResult(ok = false, error = "Not logged in", status = 401)
        return try {
            val resp = httpClient.post("$baseUrl/api/mcp/tell") {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                setBody(buildJsonObject {
                    put("target", JsonPrimitive(target))
                    put("message", JsonPrimitive(message))
                }.toString())
            }
            val body = resp.bodyAsText()
            if (resp.status != HttpStatusCode.OK) {
                val errMsg = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "HTTP ${resp.status.value}"
                return McpResult(ok = false, error = errMsg, status = resp.status.value)
            }
            val text = extractData(body)
            McpResult(ok = true, data = text, status = resp.status.value)
        } catch (e: Throwable) {
            McpResult(ok = false, error = e.message ?: e::class.simpleName)
        }
    }

    /** POST /api/mcp/do — general command (say, emote, use, take, drop). */
    override suspend fun doCommand(command: String): McpResult {
        val token = mcpToken ?: return McpResult(ok = false, error = "Not logged in", status = 401)
        return try {
            val resp = httpClient.post("$baseUrl/api/mcp/do") {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                setBody(buildJsonObject {
                    put("command", JsonPrimitive(command))
                }.toString())
            }
            val body = resp.bodyAsText()
            if (resp.status != HttpStatusCode.OK) {
                val errMsg = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "HTTP ${resp.status.value}"
                return McpResult(ok = false, error = errMsg, status = resp.status.value)
            }
            val text = extractData(body)
            McpResult(ok = true, data = text, status = resp.status.value)
        } catch (e: Throwable) {
            McpResult(ok = false, error = e.message ?: e::class.simpleName)
        }
    }

    private fun extractData(body: String): String? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val data = obj["data"]
            when (data) {
                is JsonPrimitive -> data.contentOrNull
                is JsonObject -> data.toString()
                else -> body
            }
        } catch (_: Throwable) {
            body
        }
    }

    private fun randomSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).joinToString("") { chars.random().toString() }
    }
}
