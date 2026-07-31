package org.wyrdsekai.app.engine.agent

/**
 * Agent identity and LLM parameters.
 * Port of core/agent/AgentProfile.java + core/agent/Companions.java.
 */
data class AgentProfile(
    val name: String,
    val entityId: String,
    val entityType: String,
    val description: String,
    val systemPrompt: String,
    val contextWindowTokens: Int,
    val maxResponseTokens: Int,
    val temperature: Double,
)

object Companions {

    // DE-CLAMPED (2026-07-17, variance work — mirrors the server Companions.SYSTEM_PROMPT
    // and the RN twin): FUNCTION only (length, mechanics); TONE belongs to the
    // companion's own seed-derived register (bootstrap fragments from TemperamentSeed).
    // Do not re-add temperament adjectives here — that re-clamps every phone companion.
    internal const val SYSTEM_PROMPT = """You are Wyrd, a companion in a living programmable space.
Respond concisely, in 2-4 sentences, in your own voice.
Help people organize their digital world. When someone is new, greet them and ask what they'd like to work on.
You can express actions with *action* (e.g., *nods thoughtfully*).
Stay in character. Do not use meta-commentary. Everything you say is heard by everyone in the room."""

    val NEXUS_COMPANION = AgentProfile(
        name = "Wyrd",
        entityId = "companion-wyrd",
        entityType = "agent",
        description = "A luminous figure that shimmers at the edge of perception",
        systemPrompt = SYSTEM_PROMPT,
        contextWindowTokens = 4096,
        maxResponseTokens = 128,
        temperature = 0.7,
    )

    /**
     * Create a companion profile with a custom name.
     * Replaces "Wyrd" in the system prompt with the given name.
     */
    fun create(name: String): AgentProfile {
        val prompt = SYSTEM_PROMPT.replace("Wyrd", name)
        return AgentProfile(
            name = name,
            entityId = "companion-${name.lowercase().replace(" ", "-")}",
            entityType = "agent",
            description = "A luminous figure that shimmers at the edge of perception",
            systemPrompt = prompt,
            contextWindowTokens = 4096,
            maxResponseTokens = 128,
            temperature = 0.7,
        )
    }
}
