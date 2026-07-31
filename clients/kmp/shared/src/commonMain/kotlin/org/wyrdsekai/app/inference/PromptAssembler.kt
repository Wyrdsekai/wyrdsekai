package org.wyrdsekai.app.inference

import org.wyrdsekai.app.protocol.RoomSnapshot
import org.wyrdsekai.app.state.ProseEntry

/**
 * Assembles a prompt using the 4-layer sandwich pattern:
 *
 *   1. System prompt (never trimmed)
 *   2. Room context (trimmed if budget exceeded)
 *   3. Conversation history (most-recent-first fill, reversed after)
 *   4. Trigger / user input (always last)
 *
 * This mirrors the server-side PromptAssembler (§55) and the RN client's
 * equivalent.  The sandwich pattern prevents middle-context degradation
 * by keeping the system prompt and user trigger at the edges.
 */
object PromptAssembler {

    data class Config(
        val systemPrompt: String = "You are a companion in Wyrdsekai, a text-based world. Respond in character, concisely.",
        val roomSnapshot: RoomSnapshot? = null,
        val conversationHistory: List<ProseEntry> = emptyList(),
        val triggerText: String,
        val contextWindowTokens: Int = 2048,
        val maxResponseTokens: Int = 256,
    )

    /**
     * Builds the message list for a chat completion request.
     *
     * Fills the context window bottom-up: system prompt first, then room
     * context, then as much conversation history as fits, then the trigger.
     */
    fun assemble(config: Config): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val tokenBudget = (config.contextWindowTokens * 0.85).toInt() - config.maxResponseTokens
        var tokensUsed = 0

        // Layer 1: System prompt (never trimmed)
        val systemText = config.systemPrompt
        messages.add(ChatMessage(role = "system", content = systemText))
        tokensUsed += estimateTokens(systemText)

        // Layer 2: Room context
        if (config.roomSnapshot != null) {
            val roomContext = buildRoomContext(config.roomSnapshot)
            val roomTokens = estimateTokens(roomContext)
            if (tokensUsed + roomTokens < tokenBudget) {
                messages.add(ChatMessage(role = "system", content = roomContext))
                tokensUsed += roomTokens
            }
        }

        // Layer 3: Conversation history (most recent first, reversed after)
        val historyMessages = mutableListOf<ChatMessage>()
        for (entry in config.conversationHistory.reversed()) {
            val role = if (entry.speaker == "narrator" || entry.speaker == "system") "assistant" else "user"
            val tokens = estimateTokens(entry.text)
            if (tokensUsed + tokens > tokenBudget) break
            historyMessages.add(0, ChatMessage(role = role, content = entry.text))
            tokensUsed += tokens
        }
        messages.addAll(historyMessages)

        // Layer 4: Trigger (user input, always last)
        messages.add(ChatMessage(role = "user", content = config.triggerText))

        return messages
    }

    private fun buildRoomContext(room: RoomSnapshot): String {
        val parts = mutableListOf<String>()
        parts.add("You are in: ${room.name}")
        if (room.description.isNotBlank()) parts.add(room.description)
        if (room.exits.isNotEmpty()) {
            parts.add("Exits: ${room.exits.joinToString(", ") { it.direction }}")
        }
        if (room.entities.isNotEmpty()) {
            parts.add("Present: ${room.entities.joinToString(", ") { it.name }}")
        }
        if (room.objects.isNotEmpty()) {
            parts.add("Objects: ${room.objects.joinToString(", ") { it.name }}")
        }
        return parts.joinToString("\n")
    }

    /** Rough token estimate: ~4 characters per token for English text. */
    private fun estimateTokens(text: String): Int = text.length / 4
}
