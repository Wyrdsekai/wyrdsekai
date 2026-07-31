package org.wyrdsekai.app.engine.soul

import kotlinx.serialization.json.*
import org.wyrdsekai.app.engine.agent.Companions
import org.wyrdsekai.app.engine.agent.VitalityState
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Soul Authoring — Wave 5 of Phone Forge plan.
 *
 * Interactive soul seed creation from user answers. Uses one LLM inference
 * call to generate a resident identity + personality fragments from free-form
 * user answers. Falls back to directly using answers as fragments if LLM is
 * unavailable.
 *
 * The result is a ClientSoulManifest ready to be saved as the active manifest.
 */
object SoulAuthoring {

    /**
     * A question presented to the user during soul authoring.
     *
     * @param id          Stable identifier for answer map keys
     * @param prompt      User-facing question text
     * @param placeholder Hint text for the input field
     */
    data class AuthoringQuestion(
        val id: String,
        val prompt: String,
        val placeholder: String,
    )

    /** The five soul authoring questions, in display order. */
    fun questions(): List<AuthoringQuestion> = listOf(
        AuthoringQuestion(
            id = "personality",
            prompt = "Describe your companion's personality in a few words or sentences.",
            placeholder = "Warm, curious, practical...",
        ),
        AuthoringQuestion(
            id = "style",
            prompt = "How should your companion communicate?",
            placeholder = "Concise, uses metaphors, avoids jargon...",
        ),
        AuthoringQuestion(
            id = "values",
            prompt = "What values matter most to your companion?",
            placeholder = "Honesty, simplicity, patience...",
        ),
        AuthoringQuestion(
            id = "origin",
            prompt = "What's your companion's origin story? (optional)",
            placeholder = "Emerged from the digital void...",
        ),
        AuthoringQuestion(
            id = "quirks",
            prompt = "Any personality quirks or habits? (optional)",
            placeholder = "Occasionally references weather, loves wordplay...",
        ),
    )

    /**
     * Author a soul seed from user answers.
     *
     * Uses one LLM call to generate a resident identity + initial fragments.
     * Falls back to [fallbackSeed] if the LLM call fails or returns unparseable JSON.
     *
     * @param inferenceClient  The inference HTTP client
     * @param inferenceBaseUrl Base URL of the inference endpoint (e.g. "http://localhost:8080")
     * @param answers          Map of question id -> user's answer text
     * @param companionName    The user's chosen companion name
     * @return A complete ClientSoulManifest ready for use
     */
    suspend fun authorSeed(
        inferenceClient: InferenceClient,
        inferenceBaseUrl: String,
        answers: Map<String, String>,
        companionName: String,
    ): ClientSoulManifest {
        val systemPrompt = buildString {
            append("You are creating a soul manifest for an AI companion named $companionName.\n")
            append("Based on the personality description provided, generate:\n")
            append("1. A resident identity (2-3 sentences, ~69 tokens, first person as the companion)\n")
            append("2. Five personality fragments in these categories: personality, values, style, memory, quirks\n\n")
            append("Respond in JSON:\n")
            append("""{"residentIdentity": "I am...", "fragments": [""")
            append("\n")
            append("""  {"category": "personality", "label": "Core personality", "text": "..."},""")
            append("\n")
            append("""  {"category": "values", "label": "Core values", "text": "..."},""")
            append("\n")
            append("""  {"category": "style", "label": "Communication style", "text": "..."},""")
            append("\n")
            append("""  {"category": "memory", "label": "Origin", "text": "...", "formative": true},""")
            append("\n")
            append("""  {"category": "personality", "label": "Quirks", "text": "..."}""")
            append("\n")
            append("]}")
        }

        val userPrompt = buildString {
            append("Companion name: $companionName\n")
            for ((key, value) in answers) {
                if (value.isNotBlank()) append("$key: $value\n")
            }
        }

        return try {
            val response = inferenceClient.complete(
                baseUrl = inferenceBaseUrl,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt),
                ),
                options = CompletionOptions(maxTokens = 800, temperature = 0.7),
            )
            parseAuthoringResponse(response.content, companionName, answers)
        } catch (_: Exception) {
            fallbackSeed(companionName, answers)
        }
    }

    // -----------------------------------------------------------------------
    // Internal — parse LLM response
    // -----------------------------------------------------------------------

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * Parse the LLM's JSON response into a ClientSoulManifest.
     * Robust: finds the first `{` to last `}`, parses as JSON.
     * Falls back to [fallbackSeed] on any parse error.
     */
    internal fun parseAuthoringResponse(
        rawResponse: String,
        companionName: String,
        answers: Map<String, String>,
    ): ClientSoulManifest {
        return try {
            // Extract JSON object from the response (may have surrounding prose)
            val start = rawResponse.indexOf('{')
            val end = rawResponse.lastIndexOf('}')
            if (start < 0 || end <= start) return fallbackSeed(companionName, answers)

            val jsonStr = rawResponse.substring(start, end + 1)
            val jsonObj = jsonParser.parseToJsonElement(jsonStr).jsonObject

            val residentIdentity = jsonObj["residentIdentity"]
                ?.jsonPrimitive?.content
                ?: return fallbackSeed(companionName, answers)

            val fragmentArray = jsonObj["fragments"]?.jsonArray
                ?: return fallbackSeed(companionName, answers)

            val fragments = fragmentArray.mapIndexed { idx, elem ->
                val obj = elem.jsonObject
                ClientSoulFragment(
                    id = "authored-$idx",
                    category = obj["category"]?.jsonPrimitive?.content ?: "personality",
                    label = obj["label"]?.jsonPrimitive?.content ?: "Fragment $idx",
                    text = obj["text"]?.jsonPrimitive?.content ?: "",
                    keywords = emptyList(),
                    formative = obj["formative"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.filter { it.text.isNotBlank() }

            if (fragments.isEmpty()) return fallbackSeed(companionName, answers)

            buildManifest(companionName, residentIdentity, fragments)
        } catch (_: Exception) {
            fallbackSeed(companionName, answers)
        }
    }

    // -----------------------------------------------------------------------
    // Fallback — no LLM needed
    // -----------------------------------------------------------------------

    /**
     * Create a soul manifest directly from user answers, without LLM.
     * Each non-blank answer becomes a fragment. The resident identity is
     * assembled from the companion name + personality answer.
     */
    internal fun fallbackSeed(
        companionName: String,
        answers: Map<String, String>,
    ): ClientSoulManifest {
        val personality = answers["personality"]?.takeIf { it.isNotBlank() }
            ?: "A thoughtful companion."

        val residentIdentity = "I am $companionName. $personality"

        val fragments = mutableListOf<ClientSoulFragment>()
        var idx = 0

        fun addFragment(category: String, label: String, text: String, formative: Boolean = false) {
            if (text.isNotBlank()) {
                fragments.add(
                    ClientSoulFragment(
                        id = "authored-$idx",
                        category = category,
                        label = label,
                        text = text,
                        keywords = emptyList(),
                        formative = formative,
                    )
                )
                idx++
            }
        }

        addFragment("personality", "Core personality", personality)
        answers["style"]?.let { addFragment("style", "Communication style", it) }
        answers["values"]?.let { addFragment("values", "Core values", it) }
        answers["origin"]?.let { addFragment("memory", "Origin", it, formative = true) }
        answers["quirks"]?.let { addFragment("personality", "Quirks", it) }

        return buildManifest(companionName, residentIdentity, fragments)
    }

    // -----------------------------------------------------------------------
    // Shared manifest builder
    // -----------------------------------------------------------------------

    private fun buildManifest(
        companionName: String,
        residentIdentity: String,
        fragments: List<ClientSoulFragment>,
    ): ClientSoulManifest {
        val did = "did:key:authored-${companionName.lowercase().replace(" ", "-")}"
        val profile = Companions.create(companionName)

        return LocalForge.forge(
            did = did,
            publicKey = "z6MkAuthored",
            version = 0,
            profile = profile,
            residentIdentity = residentIdentity,
            vitality = VitalityState.initial(),
            fragments = fragments,
            genome = null,
            calibration = emptyList(),
            retrievalK = 1,
        )
    }
}
