package org.wyrdsekai.app.engine.between

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Bud delegation — delegates COMPLEX queries to the server companion.
 *
 * Primary path: HTTP POST /api/companion/ask (reliable, server URL + device token).
 * Secondary path: NATS publish "delegate" → server processes → NATS response.
 *
 * The server companion processes the query through its full pipeline (tools, MCP,
 * soul, memory) and returns a response. This is the §95 bud delegation protocol
 * from the phone bud's perspective.
 *
 * and.
 */
class BudDelegation(
    private val between: BetweenClient?,
    private val nodeId: String,
    private val familyId: String,
    private val serverUrl: String?,
    private val deviceToken: String?,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 90_000   // 90s — server inference can take 10-15s
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 90_000
        }
    }

    // Pending NATS request callbacks, keyed by requestId
    private val pendingCallbacks = mutableMapOf<String, CompletableDeferred<DelegationResponse>>()
    private var unsubscribe: (() -> Unit)? = null

    @Serializable
    data class DelegationRequest(
        val type: String = "delegate",
        val requestId: String,
        val fromBudDid: String,
        val message: String,
        val recentHistory: List<String> = emptyList(),
        val locale: String = "en",
        val timestamp: Long,
    )

    @Serializable
    data class DelegationActionDto(
        val type: String = "",
        val data: Map<String, JsonElement> = emptyMap(),
    )

    data class DelegationResult(
        val text: String,
        val actions: List<DelegationActionDto>,
    )

    @Serializable
    data class DelegationResponse(
        val requestId: String = "",
        val text: String = "",
        val latencyMs: Long = 0,
        val actions: List<DelegationActionDto> = emptyList(),
    )

    /** Start listening for delegation responses on NATS. */
    fun startListening() {
        if (between == null || !between.isConnected) return

        // Subscribe to delegation responses from any source node in the household.
        // The server publishes delegate-response on the soul layer; phone Between
        // uses "between.household.{familyId}.*.soul.delegate-response".
        val subject = "between.household.$familyId.*.soul.delegate-response"
        unsubscribe = between.subscribe(subject) { _, data ->
            try {
                val response = json.decodeFromString<DelegationResponse>(data.decodeToString())
                pendingCallbacks.remove(response.requestId)?.complete(response)
            } catch (_: Exception) {
                // Malformed response — skip
            }
        }
    }

    /** Stop listening and cancel any pending requests. */
    fun stopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
        // Cancel all pending deferreds
        for (deferred in pendingCallbacks.values) {
            deferred.cancel()
        }
        pendingCallbacks.clear()
    }

    /**
     * Delegate a COMPLEX query to the server companion.
     * Tries HTTP first (reliable path), falls back to NATS.
     * Returns the companion's response text, or null on failure.
     */
    suspend fun delegate(
        message: String,
        recentHistory: List<String> = emptyList(),
        locale: String = "en",
        timeoutMs: Long = 60_000,
    ): DelegationResult? {
        // Try HTTP first — reliable primary path
        val httpResult = delegateViaHttp(message, recentHistory, locale)
        if (httpResult != null) return httpResult

        // Fall back to NATS
        if (between != null && between.isConnected) {
            return delegateViaNats(message, recentHistory, locale, timeoutMs)
        }

        return null
    }

    private suspend fun delegateViaHttp(
        message: String,
        recentHistory: List<String>,
        locale: String,
    ): DelegationResult? {
        if (serverUrl == null || deviceToken == null) return null

        return try {
            val url = normalizeUrl(serverUrl)
            println("HTTP delegation: POST $url/api/companion/ask (token=${deviceToken?.take(20)}...)")
            val response = http.post("$url/api/companion/ask") {
                header("Authorization", "Bearer $deviceToken")
                contentType(ContentType.Application.Json)
                setBody(HttpDelegationRequest(
                    message = message,
                    recentHistory = recentHistory,
                    locale = locale,
                ))
            }
            println("HTTP delegation response: ${response.status.value}")
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                println("HTTP delegation body: ${body.take(200)}")
                val parsed = json.decodeFromString<DelegationResponse>(body)
                DelegationResult(parsed.text, parsed.actions).takeIf { it.text.isNotBlank() }
            } else {
                println("HTTP delegation non-200: ${response.bodyAsText().take(200)}")
                null
            }
        } catch (e: Exception) {
            println("HTTP delegation EXCEPTION: ${e::class.simpleName}: ${e.message?.take(200)}")
            null
        }
    }

    private suspend fun delegateViaNats(
        message: String,
        recentHistory: List<String>,
        locale: String,
        timeoutMs: Long,
    ): DelegationResult? {
        val requestId = generateRequestId()
        val deferred = CompletableDeferred<DelegationResponse>()
        pendingCallbacks[requestId] = deferred

        val request = DelegationRequest(
            requestId = requestId,
            fromBudDid = nodeId,
            message = message,
            recentHistory = recentHistory,
            locale = locale,
            timestamp = currentTimeMillis(),
        )

        // Publish on the soul subject — the server's BetweenActor routes
        // messages with type "delegate" to SoulLayer.ReceiveDelegateQuery.
        val payload = json.encodeToString(request)
        between?.publish(
            delegateSubject(nodeId),
            payload.encodeToByteArray()
        )

        return try {
            withTimeout(timeoutMs) {
                val resp = deferred.await()
                DelegationResult(resp.text, resp.actions).takeIf { it.text.isNotBlank() }
            }
        } catch (_: TimeoutCancellationException) {
            pendingCallbacks.remove(requestId)
            null
        }
    }

    /** Subject for publishing a delegation request from this node. */
    private fun delegateSubject(src: String): String =
        "between.household.$familyId.$src.soul.delegate"

    private fun generateRequestId(): String =
        "del-${nodeId.takeLast(8)}-${currentTimeMillis()}-${(0..999).random()}"

    private fun currentTimeMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }

    /** HTTP request body matching CompanionAskRoutes.AskRequest on the server. */
    @Serializable
    private data class HttpDelegationRequest(
        val message: String,
        val recentHistory: List<String>,
        val locale: String,
    )
}
