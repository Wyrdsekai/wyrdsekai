package org.wyrdsekai.core.inference;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side input complexity classifier. Routes inference to appropriate
 * model tier based on input complexity:
 *
 * <ul>
 *   <li>ROUTINE — greetings, acknowledgments, simple yes/no → cheapest model</li>
 *   <li>SIMPLE — short questions, basic commands → default model</li>
 *   <li>COMPLEX — analysis, multi-step tasks, long context → most capable model</li>
 * </ul>
 *
 * Pure heuristic — no LLM call required. Mirrors KMP/RN TriageClassifier
 * with the addition of ROUTINE tier for server-side model routing.
 */
public final class TriageClassifier {

    private TriageClassifier() {}

    public enum Tier {
        /** Greetings, acks, simple confirmations — cheapest model. */
        ROUTINE,
        /** Short questions, basic tasks — default model. */
        SIMPLE,
        /** Analysis, multi-step, long context — most capable model. */
        COMPLEX
    }

    // --- Greeting patterns ---
    private static final Set<String> GREETINGS = Set.of(
        "hi", "hello", "hey", "sup", "howdy", "yo", "hiya", "heya",
        "good morning", "good afternoon", "good evening", "good night",
        "morning", "afternoon", "evening", "gm", "gn",
        "what's up", "whats up", "wassup", "wazzup"
    );

    // --- Acknowledgment patterns ---
    private static final Set<String> ACKS = Set.of(
        "ok", "okay", "sure", "thanks", "thank you", "ty", "thx",
        "yes", "yeah", "yep", "yup", "ya", "no", "nope", "nah",
        "cool", "nice", "great", "awesome", "got it", "understood",
        "right", "correct", "fine", "alright", "k", "kk",
        "lol", "haha", "heh", "lmao", "rofl"
    );

    // --- MUD commands (not classified — handled as commands, not inference) ---
    private static final Set<String> MUD_COMMANDS = Set.of(
        "look", "go", "take", "drop", "inventory", "inv", "i",
        "north", "south", "east", "west", "up", "down",
        "northeast", "northwest", "southeast", "southwest",
        "n", "s", "e", "w", "ne", "nw", "se", "sw",
        "nod", "smile", "wave", "laugh", "shrug", "sigh",
        "exits", "who", "score", "help", "quit"
    );

    // --- Complex indicators ---
    private static final Set<String> COMPLEX_KEYWORDS = Set.of(
        "explain", "analyze", "analyse", "compare", "design", "create",
        "write", "implement", "review", "summarize", "investigate",
        "research", "plan", "describe", "evaluate", "consider",
        "what do you think about", "tell me about", "help me with",
        "how would you", "can you help", "i need you to",
        "find me", "search for", "look up"
    );

    private static final Pattern QUESTION_PATTERN = Pattern.compile(
        "^(who|what|where|when|why|how|which|can|could|would|should|is|are|do|does|did|will)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Classify input text into a complexity tier.
     *
     * @param text Raw user input (already stripped of MUD command prefix if any)
     * @return Tier classification; null if input looks like a MUD command (skip inference)
     */
    public static Tier classify(String text) {
        if (text == null || text.isBlank()) return Tier.ROUTINE;

        var normalized = text.strip().toLowerCase();

        // Strip trailing punctuation for matching
        var stripped = normalized.replaceAll("[.!?]+$", "").strip();

        // MUD commands → not classified (handled by command parser, not inference)
        // Only match if input is short (1-2 words) to avoid false positives
        // on sentences starting with "i" (inventory), "help", etc.
        var words = stripped.split("\\s+");
        var wordCount = words.length;
        if (wordCount <= 2 && MUD_COMMANDS.contains(words[0])) return null;

        // Greetings → ROUTINE
        if (GREETINGS.contains(stripped)) return Tier.ROUTINE;

        // Acknowledgments → ROUTINE
        if (ACKS.contains(stripped)) return Tier.ROUTINE;

        // Very short (1-3 words, no question mark) → ROUTINE
        if (wordCount <= 3 && !normalized.contains("?")) return Tier.ROUTINE;

        // Short (4-5 words, no question) → SIMPLE
        if (wordCount <= 5 && !normalized.contains("?")) return Tier.SIMPLE;

        // Long input (> 30 words) → COMPLEX
        if (wordCount > 30) return Tier.COMPLEX;

        // Complex keywords anywhere in text → COMPLEX
        for (var keyword : COMPLEX_KEYWORDS) {
            if (normalized.contains(keyword)) return Tier.COMPLEX;
        }

        // Questions with > 8 words → COMPLEX
        if (QUESTION_PATTERN.matcher(normalized).find() && wordCount > 8) {
            return Tier.COMPLEX;
        }

        // Default → SIMPLE
        return Tier.SIMPLE;
    }

    /**
     * Map a tier to a CapabilityRegistry capability name.
     *
     * @param tier Classification tier
     * @return Capability name for CapabilityRegistry lookup
     */
    public static String tierToCapability(Tier tier) {
        return switch (tier) {
            case ROUTINE -> "quick";
            case SIMPLE -> "default";
            case COMPLEX -> "reasoning";
        };
    }
}
