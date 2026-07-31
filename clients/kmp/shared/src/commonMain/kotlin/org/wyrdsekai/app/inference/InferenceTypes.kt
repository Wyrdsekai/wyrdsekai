package org.wyrdsekai.app.inference

/** A single message in a chat completion request. */
data class ChatMessage(
    /** "system", "user", or "assistant". */
    val role: String,
    val content: String,
)

/** Response from a chat completion endpoint. */
data class ChatResponse(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
)

/** Options controlling generation behavior. */
data class CompletionOptions(
    val maxTokens: Int = 256,
    val temperature: Double = 0.7,
    val onToken: ((String) -> Unit)? = null,
    /** GBNF grammar string for constrained generation (llama.cpp). Null = unconstrained. */
    val grammar: String? = null,
)

/** Metadata about a downloadable GGUF model. */
data class ModelInfo(
    val id: String,
    val name: String,
    val filename: String,
    /** HuggingFace CDN download URL. */
    val url: String,
    /** Expected file size in bytes. */
    val size: Long,
    /** "tiny", "small", or "medium". */
    val tier: String,
    val description: String,
)
