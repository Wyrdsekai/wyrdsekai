package org.wyrdsekai.app.engine.soul

import kotlinx.serialization.json.*
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Wave 2 LLM extraction — enriches a heuristic [PhoneFingerprint] with
 * topic affinities, stylistic markers, and emotional patterns.
 *
 * Makes one inference call during sleep. On any failure, returns the
 * fingerprint unchanged (extraction is best-effort, never fatal).
 *
 * Phone port of core/soul/BehavioralExtractor pass2 logic.
 */
object LlmExtractor {

    data class LlmExtractionResult(
        val topicAffinities: Map<String, Double> = emptyMap(),
        val stylisticMarkers: List<String> = emptyList(),
        val emotionalPatterns: Map<String, Double> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run LLM extraction on accumulated events to enrich a heuristic fingerprint.
     * Makes one inference call. On any failure, returns the fingerprint unchanged.
     */
    suspend fun extractWithLlm(
        inferenceClient: InferenceClient,
        inferenceBaseUrl: String,
        fingerprint: PhoneFingerprint,
        events: List<WorldEvent>,
        agentName: String,
    ): PhoneFingerprint {
        val messages = buildExtractionPrompt(fingerprint, events, agentName)
        return try {
            val response = inferenceClient.complete(
                baseUrl = inferenceBaseUrl,
                messages = messages,
                options = CompletionOptions(maxTokens = 500, temperature = 0.3),
            )
            val result = parseExtractionResponse(response.content)
            mergeWithHeuristic(fingerprint, result)
        } catch (_: Exception) {
            fingerprint // Return unchanged on failure
        }
    }

    /**
     * Build system + user messages for the LLM extraction call.
     * System prompt instructs structured JSON output; user prompt includes
     * heuristic summary and recent Said events (capped at 30).
     */
    internal fun buildExtractionPrompt(
        fingerprint: PhoneFingerprint,
        events: List<WorldEvent>,
        agentName: String,
    ): List<ChatMessage> {
        val systemPrompt = buildString {
            append("You are analyzing conversation patterns for an AI companion named ")
            append(agentName)
            append(".\n")
            append("Based on the behavioral data and conversation excerpts below, extract:\n\n")
            append("1. topicAffinities: topics the companion gravitates toward, with weights 0.0-1.0\n")
            append("2. stylisticMarkers: distinctive speech patterns, word choices, or habits (list of strings)\n")
            append("3. emotionalPatterns: how the companion responds to different emotions, ")
            append("with responsiveness weights 0.0-1.0\n\n")
            append("Respond ONLY with a JSON object:\n")
            append("{\"topicAffinities\": {\"topic\": 0.8, ...}, ")
            append("\"stylisticMarkers\": [\"marker1\", ...], ")
            append("\"emotionalPatterns\": {\"emotion\": 0.7, ...}}")
        }

        val maxEvents = 30
        val recentEvents = events.takeLast(maxEvents)
        val userPrompt = buildString {
            append("## Behavioral Summary\n")
            append("Action distribution: ${fingerprint.actionDistribution}\n")
            append("Average response length: ${fingerprint.averageResponseLength} words\n")
            append("Average response latency: ${fingerprint.averageLatency}s\n")
            append("Top topics: ${fingerprint.topicKeywords.joinToString(", ")}\n")
            append("\n## Recent Conversation (last ${recentEvents.size} events)\n")
            for (event in recentEvents) {
                if (event is WorldEvent.Said) {
                    append("${event.entityName}: ${event.text}\n")
                }
            }
        }

        return listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userPrompt),
        )
    }

    /**
     * Parse LLM response into structured extraction result.
     * Robust: finds first '{' to last '}', parses JSON, extracts known fields.
     * On ANY failure at any step, returns empty [LlmExtractionResult].
     */
    internal fun parseExtractionResponse(response: String): LlmExtractionResult {
        return try {
            val openIdx = response.indexOf('{')
            val closeIdx = response.lastIndexOf('}')
            if (openIdx < 0 || closeIdx < 0 || closeIdx <= openIdx) {
                return LlmExtractionResult()
            }

            val jsonStr = response.substring(openIdx, closeIdx + 1)
            val root = json.parseToJsonElement(jsonStr).jsonObject

            val topicAffinities = root["topicAffinities"]
                ?.jsonObject
                ?.mapValues { (_, v) -> v.jsonPrimitive.double }
                ?: emptyMap()

            val stylisticMarkers = root["stylisticMarkers"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()

            val emotionalPatterns = root["emotionalPatterns"]
                ?.jsonObject
                ?.mapValues { (_, v) -> v.jsonPrimitive.double }
                ?: emptyMap()

            LlmExtractionResult(
                topicAffinities = topicAffinities,
                stylisticMarkers = stylisticMarkers,
                emotionalPatterns = emotionalPatterns,
            )
        } catch (_: Exception) {
            LlmExtractionResult()
        }
    }

    /**
     * Merge LLM extraction results into the heuristic fingerprint.
     * Overwrites the LLM-derived fields; heuristic fields are preserved.
     */
    internal fun mergeWithHeuristic(
        fingerprint: PhoneFingerprint,
        llmResult: LlmExtractionResult,
    ): PhoneFingerprint {
        return fingerprint.copy(
            topicAffinities = llmResult.topicAffinities,
            stylisticMarkers = llmResult.stylisticMarkers,
            emotionalPatterns = llmResult.emotionalPatterns,
        )
    }
}
