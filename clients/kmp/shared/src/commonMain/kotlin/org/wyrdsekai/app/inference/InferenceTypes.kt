package org.wyrdsekai.app.inference

/** A single message in a chat completion request. */
data class ChatMessage(
    /** "system", "user", or "assistant". */
    val role: String,
    val content: String,
)

/**
 * The outgoing copy of [messages] with its system layers as ONE system message at
 * index 0. Port of core's PromptAssembler.mergeConsecutiveSystemMessages.
 *
 * A Qwen chat template raises "System message must be at the beginning" on any
 * system message past index 0 (core's PromptAssembler records it), and
 * FullPromptAssembler sends one system message per layer, so the COMPLEX
 * raw-remote fallback and the offline replay sent what that template refuses.
 * The leading run is joined with a blank line, in layer order. A system message
 * after a user or assistant turn cannot lead, so it is folded into the message
 * before it as `[system note: …]`, as the server does. The input list is never
 * changed; a list with nothing to merge is returned as it is.
 *
 * The send points run this before they stamp the Now line (as core's
 * InferenceRouter does), so the stamp is never moved or doubled.
 */
fun consolidateSystemMessages(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.indexOfLast { it.role == "system" } <= 0) return messages
    val out = ArrayList<ChatMessage>(messages.size)
    val run = mutableListOf<String>()
    fun flush() {
        if (run.isEmpty()) return
        val text = run.joinToString("\n\n")
        run.clear()
        if (out.isEmpty()) {
            out += ChatMessage("system", text)
        } else {
            val prev = out.last()
            out[out.lastIndex] = prev.copy(content = prev.content + "\n\n[system note: " + text + "]")
        }
    }
    for (m in messages) {
        if (m.role == "system") {
            run += m.content
            continue
        }
        flush()
        out += m
    }
    flush()
    return out
}

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
    /** What this request knows about today (see [NowLine]). Null = the date and time as sent. */
    val now: NowLine? = null,
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
