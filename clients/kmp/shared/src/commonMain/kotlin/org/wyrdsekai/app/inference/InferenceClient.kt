package org.wyrdsekai.app.inference

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Auth header format used by a remote inference endpoint.
 *  - X_API_KEY: Anthropic native (`x-api-key: <key>` + `anthropic-version`)
 *  - BEARER:   OpenAI / OpenRouter / llama-server (`Authorization: Bearer <key>`)
 *  - NONE:     unauthenticated LAN llama-server
 */
enum class RemoteAuthType { NONE, X_API_KEY, BEARER }

/**
 * HTTP client that talks to llama-server's OpenAI-compatible
 * `/v1/chat/completions` endpoint.
 *
 * Uses raw JSON building (no content negotiation plugin) to keep
 * dependencies minimal and avoid platform-specific serialization issues.
 *
 * Cloud-API auth is set via [setRemoteAuth] (typically from
 * NodeManager.android.kt reading the system properties WyrdApp set
 * after the Welcome wizard). Without a call, requests are unauthenticated
 * — fine for local/LAN llama-server.
 */
open class InferenceClient(
    private val httpClient: HttpClient = HttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private var authType: RemoteAuthType = RemoteAuthType.NONE
    private var apiKey: String? = null
    private var remoteModel: String? = null

    /**
     * Configure the auth header shape + key for cloud providers.
     * Pass NONE/null to clear.
     */
    fun setRemoteAuth(authType: RemoteAuthType, apiKey: String?) {
        this.authType = authType
        this.apiKey = apiKey?.takeIf { it.isNotEmpty() }
    }

    /**
     * Configure the model name sent in the request body. Cloud providers
     * (Anthropic / OpenAI / OpenRouter) reject `/v1/chat/completions` with
     * HTTP 400 when `model` is absent; llama-server ignores it. Pass the
     * provider's default model (e.g. anthropic → "claude-sonnet-4-6-…").
     */
    fun setRemoteModel(model: String?) {
        this.remoteModel = model?.takeIf { it.isNotEmpty() }
    }

    /**
     * Sends a chat completion request and returns the parsed response.
     *
     * @param baseUrl e.g. "http://localhost:8080"
     * @param messages the conversation history
     * @param options generation parameters
     */
    open suspend fun complete(
        baseUrl: String,
        messages: List<ChatMessage>,
        options: CompletionOptions = CompletionOptions(),
    ): ChatResponse {
        val requestBody = buildJsonObject {
            // Cloud providers (Anthropic/OpenAI/OpenRouter) 400 without `model`;
            // llama-server ignores it. Defaults to "local-model" for the local path.
            put("model", remoteModel ?: "local-model")
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
            put("max_tokens", options.maxTokens)
            put("temperature", options.temperature)
            putJsonArray("stop") {
                add("</s>")
                add("<|endoftext|>")
                add("<|im_end|>")
            }
            // GBNF grammar for constrained generation (llama-server)
            if (options.grammar != null) {
                put("grammar", options.grammar)
            }
        }

        val response: HttpResponse = httpClient.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            // Cloud-API auth: Anthropic uses x-api-key + anthropic-version;
            // OpenAI / OpenRouter / llama-server use Authorization: Bearer.
            val key = apiKey
            if (key != null) {
                when (authType) {
                    RemoteAuthType.X_API_KEY -> {
                        header("x-api-key", key)
                        header("anthropic-version", "2023-06-01")
                    }
                    RemoteAuthType.BEARER -> header(HttpHeaders.Authorization, "Bearer $key")
                    RemoteAuthType.NONE -> { /* no auth header */ }
                }
            }
            setBody(requestBody.toString())
        }

        val body = response.bodyAsText()
        val jsonResponse = json.parseToJsonElement(body).jsonObject

        val choices = jsonResponse["choices"]?.jsonArray
            ?: error("No choices in response")
        val firstChoice = choices[0].jsonObject
        val message = firstChoice["message"]?.jsonObject
            ?: error("No message in choice")
        val content = message["content"]?.jsonPrimitive?.content ?: ""

        val usage = jsonResponse["usage"]?.jsonObject
        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0
        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0

        return ChatResponse(
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
        )
    }
}
