package org.wyrdsekai.core.context;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simple keyword-frequency topic extraction from conversation messages.
 * No ML -- just tokenize, filter stop words and short words, count frequency,
 * return top N. Good enough for "what has the human been talking about?"
 *
 * @see PlayerContextProfile
 * @see PersonalContextAggregator
 */
public final class TopicExtractor {

    private TopicExtractor() {}

    /** Common English stop words to filter out. */
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
        "has", "her", "was", "one", "our", "out", "had", "his", "how", "its",
        "may", "new", "now", "old", "see", "way", "who", "did", "get", "let",
        "say", "she", "too", "use", "been", "each", "have", "just", "like",
        "long", "make", "many", "more", "most", "much", "must", "name", "only",
        "over", "such", "take", "than", "them", "then", "they", "this", "very",
        "when", "come", "what", "with", "from", "that", "will", "into",
        "some", "also", "does", "done", "else", "goes", "went", "were", "your",
        "about", "after", "being", "could", "every", "first", "going", "great",
        "their", "there", "these", "thing", "think", "those", "three", "where",
        "which", "while", "would", "other", "should", "still", "right", "really",
        "well", "yeah", "okay", "know", "want", "need", "look", "here", "good",
        "back", "give", "said", "tell", "work", "sure", "even", "keep", "find",
        "help", "feel", "call", "left", "part", "same", "last", "turn", "both"
    );

    /**
     * Extract top N topics from recent messages by keyword frequency.
     *
     * <ol>
     *   <li>Tokenize messages (split on non-alphanumeric)</li>
     *   <li>Lowercase, filter stop words and short words (&lt; 4 chars)</li>
     *   <li>Count frequency</li>
     *   <li>Return top N by frequency, capitalized for display</li>
     * </ol>
     *
     * @param recentMessages Messages to extract topics from (may be null or empty)
     * @param maxTopics      Maximum number of topics to return
     * @return Ordered list of topics (most frequent first), never null
     */
    public static List<String> extractTopics(List<String> recentMessages, int maxTopics) {
        if (recentMessages == null || recentMessages.isEmpty() || maxTopics <= 0) {
            return List.of();
        }

        // Count word frequencies
        var freq = new HashMap<String, Integer>();
        for (String message : recentMessages) {
            if (message == null) continue;
            String[] tokens = message.split("[^a-zA-Z0-9]+");
            for (String token : tokens) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (lower.length() < 4) continue;
                if (STOP_WORDS.contains(lower)) continue;
                freq.merge(lower, 1, Integer::sum);
            }
        }

        // Sort by frequency descending, take top N, capitalize for display
        return freq.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(maxTopics)
            .map(e -> capitalize(e.getKey()))
            .collect(Collectors.toList());
    }

    /** Capitalize first letter for display. */
    private static String capitalize(String word) {
        if (word == null || word.isEmpty()) return word;
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
