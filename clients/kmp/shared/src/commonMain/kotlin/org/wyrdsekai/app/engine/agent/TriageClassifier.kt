package org.wyrdsekai.app.engine.agent

import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Classifies user input as SIMPLE or COMPLEX to route between fast local
 * inference (0.6B) and deeper household inference (7B+).
 *
 * Uses a three-tier approach:
 * 1. Heuristic fast-path (no inference): greetings, MUD commands, long input
 * 2. LLM classification (~2s on 0.6B): one-word response
 * 3. Fallback: SIMPLE (if classification fails)
 */
object TriageClassifier {

    enum class Tier { ROUTINE, SIMPLE, COMPLEX }

    // Acknowledgment patterns → ROUTINE (cheapest model)
    private val ACK_PATTERNS = setOf(
        "ok", "okay", "sure", "thanks", "thank you", "ty", "thx",
        "yes", "yeah", "yep", "yup", "ya", "no", "nope", "nah",
        "cool", "nice", "great", "awesome", "got it", "understood",
        "right", "correct", "fine", "alright", "k", "kk",
        "lol", "haha", "heh", "lmao", "rofl",
    )

    /**
     * Map tier to capability name for InferenceRouter.
     */
    fun tierToCapability(tier: Tier): String = when (tier) {
        Tier.ROUTINE -> "quick"
        Tier.SIMPLE -> "default"
        Tier.COMPLEX -> "reasoning"
    }

    // Greeting patterns (case-insensitive)
    private val GREETING_PATTERNS = setOf(
        "hi", "hello", "hey", "yo", "sup", "howdy", "hiya",
        "good morning", "good evening", "good afternoon",
        "what's up", "whats up", "wassup",
    )

    // MUD commands that don't need inference at all
    private val MUD_COMMANDS = setOf(
        "look", "l", "go", "move", "take", "get", "drop", "use",
        "inventory", "help", "socials",
        "nod", "smile", "laugh", "grin", "frown", "shrug", "sigh",
        "gasp", "blink", "wince", "wave", "bow", "clap", "dance",
        "stretch", "yawn", "pace", "fidget", "cry", "cheer", "groan",
        "blush", "ponder", "brood", "beam", "sulk", "hug", "thank",
        "agree", "disagree", "salute", "welcome",
    )

    private const val CLASSIFICATION_PROMPT = """Classify this message as SIMPLE or COMPLEX.
SIMPLE: greetings, yes/no, navigation, short answers, acknowledgments.
COMPLEX: questions needing thought, personal topics, creative requests, help requests, multi-step tasks.
Answer with one word only."""

    /**
     * Classify input complexity. Returns SIMPLE or COMPLEX.
     *
     * @param text The user's input text
     * @param inferenceClient Local inference client (0.6B)
     * @param inferenceBaseUrl Base URL for inference
     * @return Tier.SIMPLE or Tier.COMPLEX
     */
    suspend fun classify(
        text: String,
        inferenceClient: InferenceClient,
        inferenceBaseUrl: String,
    ): Tier {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // Heuristic fast-path: no inference needed
        val heuristic = heuristicClassify(lower, trimmed)
        if (heuristic != null) return heuristic

        // LLM classification
        return try {
            llmClassify(trimmed, inferenceClient, inferenceBaseUrl)
        } catch (_: Exception) {
            Tier.SIMPLE // Fallback: treat as simple if classification fails
        }
    }

    /**
     * Pure heuristic classification. Returns null if undecided (needs LLM).
     */
    internal fun heuristicClassify(lower: String, original: String): Tier? {
        // Strip trailing punctuation for matching
        val stripped = lower.replace(Regex("[.!?]+$"), "").trim()

        // Short greetings → ROUTINE
        if (stripped in GREETING_PATTERNS) return Tier.ROUTINE

        // Acknowledgments → ROUTINE
        if (stripped in ACK_PATTERNS) return Tier.ROUTINE

        // MUD commands (1-2 words only) → skip inference entirely
        val words = stripped.split("\\s+".toRegex())
        val wordCount = words.size
        if (wordCount <= 2 && words[0] in MUD_COMMANDS) return null

        // Very short (1-3 words, no question mark) → ROUTINE
        if (wordCount <= 3 && !original.contains("?")) return Tier.ROUTINE

        // Short (4-5 words, no question) → SIMPLE
        if (wordCount <= 5 && !original.contains("?")) return Tier.SIMPLE

        // Long input (> 30 words) → COMPLEX
        if (wordCount > 30) return Tier.COMPLEX

        // Contains question mark → likely needs thought
        if (original.contains("?") && wordCount > 8) return Tier.COMPLEX

        // Keywords indicating need for external knowledge or deep reasoning → COMPLEX
        val complexKeywords = listOf(
            "news", "today", "yesterday", "latest", "recent", "current",
            "happening", "happened", "explain", "analyze", "research",
            "summarize", "review", "compare", "investigate", "story about",
            "tell me about", "what do you think", "help me", "create a",
            "write a", "build a", "design a", "plan a",
        )
        if (complexKeywords.any { lower.contains(it) }) return Tier.COMPLEX

        // Undecided — needs LLM
        return null
    }

    /**
     * LLM-based classification. One inference call with tiny prompt.
     */
    private suspend fun llmClassify(
        text: String,
        inferenceClient: InferenceClient,
        inferenceBaseUrl: String,
    ): Tier {
        val messages = listOf(
            ChatMessage(role = "system", content = CLASSIFICATION_PROMPT),
            ChatMessage(role = "user", content = "Message: \"$text\""),
        )
        val response = inferenceClient.complete(
            baseUrl = inferenceBaseUrl,
            messages = messages,
            options = CompletionOptions(maxTokens = 8, temperature = 0.1),
        )
        val answer = response.content.trim().uppercase()
        return if (answer.contains("COMPLEX")) Tier.COMPLEX else Tier.SIMPLE
    }
}
