package org.wyrdsekai.app.engine.agent

import org.wyrdsekai.app.inference.ChatMessage

/**
 * Compresses conversation history when it exceeds a fraction of the context budget (KMP port).
 *
 * Deterministic extraction — no LLM call. Pattern-matches action types and
 * key nouns from older messages, producing a single summary prefix followed by
 * the most recent messages verbatim.
 */
object ConversationCompressor {

    /** When conversation exceeds this fraction of usable context, compress. */
    const val COMPRESS_THRESHOLD = 0.40

    /** Keep this many recent messages verbatim (never compressed). */
    const val KEEP_RECENT = 3

    private const val CHARS_PER_TOKEN = 4

    /** JSON action block pattern for extraction. */
    private val ACTION_PATTERN = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Compress conversation history if it exceeds the budget threshold.
     *
     * @param history             full conversation history (user + assistant messages)
     * @param contextWindowTokens total context window size in tokens
     * @param maxResponseTokens   tokens reserved for response
     * @return compressed history (may be unchanged if under threshold)
     */
    fun compress(
        history: List<ChatMessage>,
        contextWindowTokens: Int,
        maxResponseTokens: Int,
    ): List<ChatMessage> {
        if (history.size <= KEEP_RECENT) return history

        val usableTokens = (contextWindowTokens * 0.85).toInt() - maxResponseTokens
        val historyTokens = history.sumOf { estimateTokens(it.content) }

        if (historyTokens <= (usableTokens * COMPRESS_THRESHOLD).toInt()) {
            return history // under threshold — no compression needed
        }

        // Split into older and recent
        val splitPoint = history.size - KEEP_RECENT
        val older = history.subList(0, splitPoint)
        val recent = history.subList(splitPoint, history.size)

        // Extract summaries from older messages
        val summaries = older.mapNotNull { summarizeMessage(it) }

        val result = mutableListOf<ChatMessage>()
        if (summaries.isNotEmpty()) {
            val summaryText = "[Earlier conversation: ${summaries.joinToString(". ")}]"
            result.add(ChatMessage("system", summaryText))
        }
        result.addAll(recent)
        return result
    }

    /**
     * Extract a one-line summary from a single message.
     * Returns null if the message has no useful content to summarize.
     */
    internal fun summarizeMessage(msg: ChatMessage): String? {
        val content = msg.content
        if (content.isBlank()) return null

        // Extract action type if present
        val actionMatch = ACTION_PATTERN.find(content)
        val actionType = actionMatch?.groupValues?.get(1)

        // Determine speaker
        var speaker = if (msg.role == "assistant") "Agent" else "User"

        if (actionType != null) {
            return "$speaker ${describeActionBriefly(actionType, content)}"
        }

        // For plain speech, extract first meaningful sentence
        var text = content
        // Strip any speaker prefix like "Name says: "
        val saysIdx = text.indexOf(" says: ")
        if (saysIdx in 1..39) {
            speaker = text.substring(0, saysIdx)
            text = text.substring(saysIdx + 7)
        }

        // Truncate to first sentence
        var end = minOf(text.length, 80)
        for (i in 0 until end) {
            if (text[i] == '.' || text[i] == '!' || text[i] == '?') {
                end = i + 1
                break
            }
        }
        return "$speaker said: ${text.substring(0, end).trim()}"
    }

    private fun describeActionBriefly(actionType: String, content: String): String = when (actionType) {
        "go_to_room" -> "navigated${extractTarget(content)}"
        "library_search" -> "searched library${extractQuery(content)}"
        "web_search" -> "searched web${extractQuery(content)}"
        "tell_agent" -> "told agent${extractTarget(content)}"
        "create_task_plan" -> "created a task plan"
        "goal_done" -> "completed a goal"
        "read_content" -> "read content"
        "query_oracle" -> "queried Oracle"
        "remember" -> "remembered something"
        else -> "performed ${actionType.replace('_', ' ')}"
    }

    private fun extractTarget(content: String): String {
        val m = Regex("\"target\"\\s*:\\s*\"([^\"]+)\"").find(content)
        return if (m != null) " to ${m.groupValues[1]}" else ""
    }

    private fun extractQuery(content: String): String {
        val m = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(content)
        return if (m != null) " for '${m.groupValues[1]}'" else ""
    }

    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return maxOf(1, text.length / CHARS_PER_TOKEN)
    }
}
