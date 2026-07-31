package org.wyrdsekai.app.engine.agent

import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.oracle.PhonePrediction
import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import org.wyrdsekai.app.engine.soul.LocalForge
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.protocol.RoomSnapshot

/**
 * Full 8-layer sandwich prompt assembler.
 * Port of core/agent/PromptAssembler.java.
 *
 * Layout (high -> low -> high LLM attention):
 *   1.   System prompt         (identity, never trimmed)
 *   1.5  Soul fragments        (retrieved from manifest, budget-capped at 30%)
 *   1.7  Mirror calibration    (emotional charge few-shot examples)
 *   2.   Room context          (critical for current interaction)
 *   2.5  Additional context    (system metrics, trimmable)
 *   2.6  Bond context          (relationship depth + calibration, trimmable)
 *   3.   Vitality description  (background modulation, trimmable)
 *   5.   Memory buffer         (room history, trimmable)
 *   5.5  Recency anchor        (state reinforcement)
 *   6.   Conversation history  (recent messages)
 *   7.   Trigger event         (what to respond to)
 */
object FullPromptAssembler {

    private const val CHARS_PER_TOKEN = 4
    private const val USABLE_FRACTION = 0.85
    /** Maximum fraction of remaining budget that soul fragments may consume. */
    private const val FRAGMENT_BUDGET_FRACTION = 0.30

    /** Maximum Oracle predictions to include in prompt context. */
    private const val MAX_ORACLE_PREDICTIONS = 5
    private const val MIN_ORACLE_CONFIDENCE = 0.5

    fun assemble(
        profile: AgentProfile,
        roomSnapshot: RoomSnapshot?,
        recentSaid: List<WorldEvent.Said>,
        triggerEvent: WorldEvent.Said?,
        vitality: VitalityState? = null,
        additionalContext: String? = null,
        memoryBuffer: String? = null,
        soulManifest: ClientSoulManifest? = null,
        oraclePredictions: List<PhonePrediction>? = null,
        bondContext: String? = null,
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // Layer 1: System prompt (never trimmed)
        messages.add(ChatMessage(role = "system", content = profile.systemPrompt))

        // Calculate token budget
        val usableTokens = (profile.contextWindowTokens * USABLE_FRACTION).toInt() -
            profile.maxResponseTokens
        val systemTokens = estimateTokens(profile.systemPrompt)
        val conversationTokens = recentSaid.sumOf { e ->
            estimateTokens(formatSaidEvent(e, profile.entityId))
        } + if (triggerEvent != null) {
            estimateTokens("${triggerEvent.entityName} says: ${triggerEvent.text}")
        } else 0
        var remainingBudget = usableTokens - systemTokens - conversationTokens

        // Layer 1.5: Soul fragments (retrieved from manifest, budget-capped)
        if (soulManifest != null && soulManifest.fragments.isNotEmpty()) {
            val fragmentBudget = (remainingBudget * FRAGMENT_BUDGET_FRACTION).toInt()
            val contextKeywords = buildRetrievalInput(roomSnapshot, triggerEvent, recentSaid)
            val retrieved = LocalForge.retrieveFragments(
                input = contextKeywords,
                fragments = soulManifest.fragments,
                k = soulManifest.retrievalK,
            )
            if (retrieved.isNotEmpty()) {
                val fragmentText = buildFragmentContext(retrieved)
                val fragmentTokens = estimateTokens(fragmentText)
                if (fragmentTokens <= fragmentBudget) {
                    messages.add(ChatMessage(role = "system", content = fragmentText))
                    remainingBudget -= fragmentTokens
                }
            }
        }

        // Layer 1.7: Mirror calibration (emotional charge few-shot examples)
        if (soulManifest != null && soulManifest.mirrorCalibration.isNotEmpty()) {
            val calibrationText = buildMirrorCalibration(soulManifest.mirrorCalibration)
            val calibrationTokens = estimateTokens(calibrationText)
            if (calibrationTokens <= remainingBudget) {
                messages.add(ChatMessage(role = "system", content = calibrationText))
                remainingBudget -= calibrationTokens
            }
        }

        // Layer 2: Room context
        if (roomSnapshot != null) {
            val roomContext = buildRoomContext(roomSnapshot)
            val roomTokens = estimateTokens(roomContext)
            if (roomTokens <= remainingBudget) {
                messages.add(ChatMessage(role = "system", content = roomContext))
                remainingBudget -= roomTokens
            } else {
                val trimmed = buildTrimmedContext(roomSnapshot)
                messages.add(ChatMessage(role = "system", content = trimmed))
                remainingBudget -= estimateTokens(trimmed)
            }
        }

        // Layer 2.5: Additional context (trimmable)
        if (!additionalContext.isNullOrBlank()) {
            val extraTokens = estimateTokens(additionalContext)
            if (extraTokens <= remainingBudget) {
                messages.add(ChatMessage(role = "system", content = additionalContext))
                remainingBudget -= extraTokens
            }
        }

        // Layer 2.6: Bond context (relationship depth + calibration preferences, trimmable)
        if (!bondContext.isNullOrBlank()) {
            val bondTokens = estimateTokens(bondContext)
            if (bondTokens <= remainingBudget) {
                messages.add(ChatMessage(role = "system", content = bondContext))
                remainingBudget -= bondTokens
            }
        }

        // Layer 3: Time awareness (wall-clock, time-of-day, elapsed since last human speech)
        run {
            // Find last human speech from recent events
            val lastHumanSaid = recentSaid
                ?.lastOrNull { it.entityName != profile.name }
                ?.timestamp
            val timeCtx = TimeContext.build(lastHumanSaid)
            val timeTokens = estimateTokens(timeCtx)
            if (timeTokens <= remainingBudget) {
                messages.add(ChatMessage(role = "system", content = timeCtx))
                remainingBudget -= timeTokens
            }
        }

        // Layer 3.25: Oracle predictions (anticipatory insights, trimmable)
        if (!oraclePredictions.isNullOrEmpty()) {
            val oracleCtx = buildOracleContext(oraclePredictions)
            if (oracleCtx.isNotEmpty()) {
                val oracleTokens = estimateTokens(oracleCtx)
                if (oracleTokens <= remainingBudget) {
                    messages.add(ChatMessage(role = "system", content = oracleCtx))
                    remainingBudget -= oracleTokens
                }
            }
        }

        // Layer 3.5: Vitality state (background modulation, trimmable)
        if (vitality != null) {
            val vitalityContext = vitality.describe()
            val vitalityTokens = estimateTokens(vitalityContext)
            if (vitalityTokens <= remainingBudget) {
                messages.add(ChatMessage(role = "system", content = vitalityContext))
                remainingBudget -= vitalityTokens
            }
        }

        // Layer 5: Memory buffer (hot/warm room memory, trimmable)
        if (!memoryBuffer.isNullOrBlank()) {
            val memoryTokens = estimateTokens(memoryBuffer)
            if (memoryTokens <= remainingBudget) {
                messages.add(ChatMessage(role = "system", content = memoryBuffer))
                remainingBudget -= memoryTokens
            }
        }

        // Layer 5.5: Recency anchor
        if (roomSnapshot != null && recentSaid.isNotEmpty()) {
            val anchor = buildRecencyAnchor(roomSnapshot, triggerEvent)
            messages.add(ChatMessage(role = "system", content = anchor))
        }

        // Layer 6: Conversation history
        for (event in recentSaid) {
            val role = if (event.entityId == profile.entityId) "assistant" else "user"
            val content = formatSaidEvent(event, profile.entityId)
            messages.add(ChatMessage(role = role, content = content))
        }

        // Layer 7: Trigger event (if not already in history)
        if (triggerEvent != null) {
            val alreadyInHistory = recentSaid.isNotEmpty() && recentSaid.last() == triggerEvent
            if (!alreadyInHistory) {
                messages.add(ChatMessage(
                    role = "user",
                    content = "${triggerEvent.entityName} says: ${triggerEvent.text}",
                ))
            }
        }

        return messages
    }

    internal fun buildRoomContext(snapshot: RoomSnapshot): String {
        val sb = StringBuilder()
        sb.append("Current location: ").append(snapshot.name).append("\n")
        sb.append(snapshot.description).append("\n")

        if (snapshot.entities.isNotEmpty()) {
            sb.append("\nPresent: ")
            sb.append(snapshot.entities.joinToString(", ") { "${it.name} (${it.type})" })
            sb.append("\n")
        }

        if (snapshot.exits.isNotEmpty()) {
            sb.append("Exits: ")
            sb.append(snapshot.exits.joinToString("; ") { "${it.direction} — ${it.label}" })
            sb.append("\n")
        }

        if (snapshot.objects.isNotEmpty()) {
            sb.append("Objects: ")
            sb.append(snapshot.objects.joinToString("; ") { "${it.name} — ${it.description}" })
            sb.append("\n")
        }

        return sb.toString()
    }

    internal fun buildTrimmedContext(snapshot: RoomSnapshot): String {
        val sb = StringBuilder()
        sb.append("You are in ").append(snapshot.name).append(". ")
        if (snapshot.entities.isNotEmpty()) {
            sb.append("Present: ")
            sb.append(snapshot.entities.joinToString(", ") { it.name })
            sb.append(".")
        }
        return sb.toString()
    }

    internal fun buildRecencyAnchor(snapshot: RoomSnapshot, triggerEvent: WorldEvent.Said?): String {
        val sb = StringBuilder()
        sb.append("[Current state: ").append(snapshot.name)
        if (snapshot.entities.isNotEmpty()) {
            sb.append(", present: ")
            sb.append(snapshot.entities.joinToString(", ") { it.name })
        }
        if (triggerEvent != null) {
            sb.append(". Responding to ").append(triggerEvent.entityName)
        }
        sb.append("]")
        return sb.toString()
    }

    private fun formatSaidEvent(event: WorldEvent.Said, selfEntityId: String): String =
        if (event.entityId == selfEntityId) event.text
        else "${event.entityName} says: ${event.text}"

    internal fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else maxOf(1, text.length / CHARS_PER_TOKEN)

    /**
     * Build retrieval input from current context — combines room name/description,
     * trigger text, and recent conversation to form keyword context for fragment lookup.
     */
    internal fun buildRetrievalInput(
        roomSnapshot: RoomSnapshot?,
        triggerEvent: WorldEvent.Said?,
        recentSaid: List<WorldEvent.Said>,
    ): String {
        val sb = StringBuilder()
        if (roomSnapshot != null) {
            sb.append(roomSnapshot.name).append(" ")
            sb.append(roomSnapshot.description).append(" ")
        }
        if (triggerEvent != null) {
            sb.append(triggerEvent.text).append(" ")
        }
        // Include last 3 messages for keyword context
        recentSaid.takeLast(3).forEach { sb.append(it.text).append(" ") }
        return sb.toString().trim()
    }

    /**
     * Format retrieved soul fragments into a prompt section.
     */
    internal fun buildFragmentContext(
        fragments: List<org.wyrdsekai.app.engine.soul.ClientSoulFragment>,
    ): String {
        val sb = StringBuilder("## Soul Memory\n")
        for (fragment in fragments) {
            sb.append("[").append(fragment.category)
            if (fragment.formative) sb.append(", formative")
            sb.append("] ").append(fragment.text).append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Format mirror calibration examples into a prompt section.
     * These are few-shot examples that help the LLM detect emotional charge.
     */
    internal fun buildMirrorCalibration(calibration: List<String>): String {
        val sb = StringBuilder("## Emotional Calibration\n")
        for (example in calibration) {
            sb.append(example).append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Build Oracle prediction context for Layer 3.25 injection.
     * Mirrors server OracleAgentContext.build() logic.
     */
    internal fun buildOracleContext(predictions: List<PhonePrediction>): String {
        val relevant = predictions
            .filter { it.confidence >= MIN_ORACLE_CONFIDENCE }
            .sortedByDescending { it.confidence }
            .take(MAX_ORACLE_PREDICTIONS)
        if (relevant.isEmpty()) return ""

        val sb = StringBuilder("Oracle insights:")
        for ((i, p) in relevant.withIndex()) {
            sb.append(" (").append(i + 1).append(") ").append(p.text)
            if (p.actionable) sb.append(" [actionable]")
            sb.append(".")
        }
        return sb.toString()
    }
}
