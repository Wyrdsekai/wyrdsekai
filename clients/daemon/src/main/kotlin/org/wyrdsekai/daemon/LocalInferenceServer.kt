package org.wyrdsekai.daemon

import android.util.Log
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ktor embedded HTTP server wrapping LlamaCppJni.
 * Serves OpenAI-compatible /v1/chat/completions endpoint on the local network.
 *
 * This makes the phone look like any other inference endpoint (llama-server, Ollama)
 * to the household mesh.
 */
class LocalInferenceServer(
    private val modelHandle: Long,
    private val port: Int = 8080,
) {
    companion object {
        private const val TAG = "InferenceServer"
    }

    @Serializable
    data class ChatCompletionRequest(
        val model: String? = null,
        val messages: List<Message> = emptyList(),
        val max_tokens: Int = 256,
        val temperature: Double = 0.7,
        val stop: List<String>? = null,
    )

    @Serializable
    data class Message(val role: String, val content: String)

    @Serializable
    data class ChatCompletionResponse(
        val id: String,
        val `object`: String = "chat.completion",
        val created: Long,
        val choices: List<Choice>,
        val usage: Usage,
    )

    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: Message,
        val finish_reason: String = "stop",
    )

    @Serializable
    data class Usage(
        val prompt_tokens: Int = 0,
        val completion_tokens: Int = 0,
        val total_tokens: Int = 0,
    )

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                get("/health") {
                    val healthy = LlamaCppJni.healthCheck(modelHandle)
                    if (healthy) {
                        call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                    } else {
                        call.respondText(
                            """{"status":"error"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.ServiceUnavailable
                        )
                    }
                }

                get("/v1/models") {
                    val info = LlamaCppJni.modelInfo(modelHandle)
                    call.respondText("""
                        {"object":"list","data":[{
                          "id":"local-model",
                          "object":"model",
                          "owned_by":"local",
                          "context_length":${info.contextLength}
                        }]}
                    """.trimIndent(), ContentType.Application.Json)
                }

                post("/v1/chat/completions") {
                    try {
                        val request = call.receive<ChatCompletionRequest>()
                        val prompt = formatChatPrompt(request.messages)
                        val stopTokens = (request.stop ?: listOf("</s>", "<|endoftext|>", "<|im_end|>"))
                            .toTypedArray()

                        val result = LlamaCppJni.complete(
                            modelHandle,
                            prompt,
                            request.max_tokens,
                            request.temperature.toFloat(),
                            0.9f,
                            stopTokens,
                        )

                        val response = ChatCompletionResponse(
                            id = "chatcmpl-${System.currentTimeMillis()}",
                            created = System.currentTimeMillis() / 1000,
                            choices = listOf(Choice(
                                message = Message("assistant", result),
                            )),
                            usage = Usage(
                                completion_tokens = result.split(" ").size, // approximate
                            ),
                        )
                        call.respond(response)

                    } catch (e: Exception) {
                        Log.e(TAG, "Inference error", e)
                        call.respondText(
                            """{"error":{"message":"${e.message}","type":"server_error"}}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError
                        )
                    }
                }
            }
        }
        server?.start(wait = false)
        Log.i(TAG, "HTTP server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
    }

    /** Handle a raw NATS request and return the response bytes. */
    fun handleRawRequest(data: ByteArray): ByteArray {
        val json = Json { ignoreUnknownKeys = true }
        val request = json.decodeFromString<ChatCompletionRequest>(String(data))
        val prompt = formatChatPrompt(request.messages)
        val stopTokens = (request.stop ?: listOf("</s>", "<|endoftext|>", "<|im_end|>"))
            .toTypedArray()

        val result = LlamaCppJni.complete(
            modelHandle, prompt, request.max_tokens,
            request.temperature.toFloat(), 0.9f, stopTokens,
        )

        val response = ChatCompletionResponse(
            id = "chatcmpl-${System.currentTimeMillis()}",
            created = System.currentTimeMillis() / 1000,
            choices = listOf(Choice(message = Message("assistant", result))),
            usage = Usage(completion_tokens = result.split(" ").size),
        )
        return json.encodeToString(ChatCompletionResponse.serializer(), response).toByteArray()
    }

    private fun formatChatPrompt(messages: List<Message>): String {
        // ChatML format (compatible with Qwen, Gemma, etc.)
        return messages.joinToString("\n") { msg ->
            "<|im_start|>${msg.role}\n${msg.content}<|im_end|>"
        } + "\n<|im_start|>assistant\n"
    }
}
