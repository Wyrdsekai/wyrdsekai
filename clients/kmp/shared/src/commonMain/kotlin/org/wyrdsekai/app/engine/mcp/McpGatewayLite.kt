package org.wyrdsekai.app.engine.mcp

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.wyrdsekai.app.engine.between.BetweenClient

/**
 * Lightweight MCP gateway for the phone node.
 *
 * Supports two call modes:
 * - **Direct**: HTTP call from phone to MCP server (T3, low-risk services)
 * - **Proxy**: Request forwarded via Between to household server (T2+, credential-heavy services)
 *
 * Rate limiting is per-server with hourly reset windows.
 *
 */
class McpGatewayLite(
    private val httpClient: HttpClient = HttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Registry of allowed direct MCP servers. */
    private val servers = mutableMapOf<String, McpServerConfig>()

    /** Per-server call counts for rate limiting. Key: server name, Value: (count, windowStartMs). */
    private val rateLimitState = mutableMapOf<String, RateLimitEntry>()

    /** Optional Between client for proxy calls. */
    var betweenClient: BetweenClient? = null

    /** Node ID for proxy request routing. */
    var nodeId: String = "unknown"

    /** Household ID for Between subject construction. */
    var householdId: String = ""

    /** Pending proxy responses keyed by requestId. */
    private val pendingProxyResponses = mutableMapOf<String, CompletableDeferred<McpResult>>()

    /** Unsubscribe function for proxy response subscription. */
    private var proxyResponseUnsub: (() -> Unit)? = null

    /**
     * Register an MCP server for direct calls.
     */
    fun registerServer(config: McpServerConfig) {
        servers[config.name] = config
    }

    /**
     * Register all default MCP servers.
     */
    fun registerDefaults() {
        DefaultMcpServers.ALL.values.forEach { registerServer(it) }
    }

    /**
     * Get a registered server config by name.
     */
    fun getServer(name: String): McpServerConfig? = servers[name]

    /**
     * Call an MCP server directly via HTTP.
     *
     * @param server Server name (must be registered)
     * @param tool Tool/endpoint name
     * @param args Tool arguments
     * @param apiKey Optional API key (from local Safe lookup)
     * @return McpResult with response content or error
     */
    suspend fun callDirect(
        server: String,
        tool: String,
        args: Map<String, String>,
        apiKey: String? = null,
    ): McpResult {
        val config = servers[server]
            ?: return McpResult(success = false, error = "Server not registered: $server")

        // Rate limit check
        if (!checkRateLimit(config.name, config.rateLimit)) {
            return McpResult(success = false, error = "Rate limit exceeded for $server (${config.rateLimit}/hour)")
        }

        return try {
            val requestBody = buildJsonObject {
                put("tool", tool)
                putJsonObject("arguments") {
                    args.forEach { (k, v) -> put(k, v) }
                }
            }

            val response: HttpResponse = httpClient.post("${config.url}/$tool") {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
                if (apiKey != null) {
                    header("Authorization", "Bearer $apiKey")
                } else if (config.credentialKey != null) {
                    // credentialKey is set but no apiKey provided — caller should have looked it up
                    return McpResult(success = false, error = "API key required for $server but not provided")
                }
            }

            val body = response.bodyAsText()
            McpResult(success = response.status.isSuccess(), content = body)
        } catch (e: Exception) {
            McpResult(success = false, error = "MCP call failed: ${e.message}")
        }
    }

    /**
     * Call an MCP server via household proxy (Between).
     *
     * Sends an McpProxyRequest via Between and waits for the household
     * server to respond with an McpProxyResponse.
     *
     *
     * @param server Server name on the household
     * @param tool Tool/endpoint name
     * @param args Tool arguments
     * @param timeoutMs Maximum time to wait for response (default 30s)
     * @return McpResult with response content or error
     */
    suspend fun callProxy(
        server: String,
        tool: String,
        args: Map<String, String>,
        timeoutMs: Long = 30_000,
    ): McpResult {
        val between = betweenClient
            ?: return McpResult(success = false, error = "No Between client configured for proxy calls")

        if (!between.isConnected) {
            return McpResult(success = false, error = "Between client not connected")
        }

        if (householdId.isBlank()) {
            return McpResult(success = false, error = "Household ID not configured")
        }

        val requestId = generateRequestId()
        val request = McpProxyRequest(
            requestId = requestId,
            server = server,
            tool = tool,
            args = args,
            nodeId = nodeId,
        )

        // Set up response listener if not already active
        ensureProxyResponseSubscription()

        val deferred = CompletableDeferred<McpResult>()
        pendingProxyResponses[requestId] = deferred

        try {
            // Publish request to household
            val requestJson = json.encodeToString(McpProxyRequest.serializer(), request)
            between.publish(
                "between.$householdId.mcp.request",
                requestJson.encodeToByteArray(),
            )

            // Wait for response
            return withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: Exception) {
            return McpResult(success = false, error = "Proxy call failed: ${e.message}")
        } finally {
            pendingProxyResponses.remove(requestId)
        }
    }

    /**
     * Subscribe to proxy response subject on the Between client.
     * Responses arrive on: between.{householdId}.mcp.{nodeId}.response
     */
    private fun ensureProxyResponseSubscription() {
        if (proxyResponseUnsub != null) return
        val between = betweenClient ?: return

        proxyResponseUnsub = between.subscribe(
            "between.$householdId.mcp.$nodeId.response",
        ) { _, data ->
            try {
                val response = json.decodeFromString(McpProxyResponse.serializer(), data.decodeToString())
                val deferred = pendingProxyResponses[response.requestId]
                deferred?.complete(
                    McpResult(
                        success = response.success,
                        content = response.content,
                        error = response.error,
                    )
                )
            } catch (_: Exception) {
                // Malformed response — ignore
            }
        }
    }

    /**
     * Check and increment rate limit for a server.
     * Returns true if the call is allowed, false if rate limited.
     *
     * Rate limit window: 1 hour (3_600_000 ms). On window expiry, the
     * counter resets. Uses wall clock time for simplicity.
     */
    internal fun checkRateLimit(server: String, limit: Int): Boolean {
        val now = currentTimeMillis()
        val entry = rateLimitState[server]

        if (entry == null || (now - entry.windowStartMs) >= RATE_LIMIT_WINDOW_MS) {
            // New window
            rateLimitState[server] = RateLimitEntry(count = 1, windowStartMs = now)
            return true
        }

        if (entry.count >= limit) {
            return false
        }

        rateLimitState[server] = entry.copy(count = entry.count + 1)
        return true
    }

    /**
     * Reset rate limit state. Useful for testing.
     */
    internal fun resetRateLimits() {
        rateLimitState.clear()
    }

    /**
     * Shut down the gateway. Cancels proxy response subscription.
     */
    fun shutdown() {
        proxyResponseUnsub?.invoke()
        proxyResponseUnsub = null
        pendingProxyResponses.values.forEach { it.cancel() }
        pendingProxyResponses.clear()
    }

    companion object {
        /** Rate limit window: 1 hour in milliseconds. */
        const val RATE_LIMIT_WINDOW_MS = 3_600_000L

        /** Simple request ID generator. */
        private var requestCounter = 0L
        internal fun generateRequestId(): String {
            return "mcp-${++requestCounter}-${currentTimeMillis()}"
        }

        /** Platform-agnostic current time. Uses kotlinx-datetime if available, falls back to epoch. */
        internal fun currentTimeMillis(): Long {
            return kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
    }
}

/**
 * Internal rate limit tracking entry.
 */
internal data class RateLimitEntry(
    val count: Int,
    val windowStartMs: Long,
)
