package org.wyrdsekai.core.soul;

import org.wyrdsekai.common.event.WorldEvent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates compressed summaries of evicted conversation history.
 * When the context window overflows and old messages are dropped,
 * this produces a 1-3 sentence summary that preserves the essence
 * of what was discussed.
 *
 * Uses heuristic extraction (free, instant). An optional LLM path
 * can be added later for higher quality summaries.
 */
public final class EvictionSummarizer {

    private static final int MAX_SUMMARY_TOKENS = 100;
    private static final int MAX_STACKED_SUMMARIES = 3;

    private EvictionSummarizer() {}

    /**
     * Generate a heuristic summary from evicted Said events.
     *
     * @param evictedEvents The events being dropped from context
     * @param agentEntityId The agent's entity ID (to distinguish agent vs user speech)
     * @return A 1-3 sentence summary, or null if nothing meaningful to summarize
     */
    public static String summarize(List<WorldEvent> evictedEvents, String agentEntityId) {
        if (evictedEvents == null || evictedEvents.isEmpty()) return null;

        // Extract speakers and their topics
        var speakers = new LinkedHashSet<String>();
        var topics = new LinkedHashSet<String>();
        var actions = new ArrayList<String>();
        int saidCount = 0;

        for (var event : evictedEvents) {
            if (event instanceof WorldEvent.Said said) {
                saidCount++;
                if (!said.entityId().equals(agentEntityId)) {
                    speakers.add(said.entityName());
                }
                // Extract topic words (nouns/significant words)
                extractTopics(said.text(), topics);
            } else if (event instanceof WorldEvent.EntityEntered entered) {
                actions.add(entered.entityName() + " arrived");
            } else if (event instanceof WorldEvent.EntityLeft left) {
                actions.add(left.entityName() + " left");
            }
        }

        if (saidCount == 0 && actions.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("Earlier in this conversation: ");

        if (!speakers.isEmpty()) {
            sb.append(String.join(" and ", speakers));
            if (!topics.isEmpty()) {
                var topicList = topics.stream().limit(5).collect(Collectors.toList());
                sb.append(" discussed ").append(String.join(", ", topicList));
            } else {
                sb.append(" spoke");
            }
            sb.append(" (").append(saidCount).append(" messages)");
        }

        if (!actions.isEmpty()) {
            if (!speakers.isEmpty()) sb.append(". Also: ");
            sb.append(String.join("; ", actions.stream().limit(3).toList()));
        }

        sb.append(".");
        return sb.toString();
    }

    /**
     * Stack multiple summaries into a compressed form.
     * Older summaries get progressively compressed.
     *
     * @param previousSummaries Older summaries (most recent last)
     * @param newSummary The latest summary to add
     * @return Combined summary string
     */
    public static String stackSummaries(List<String> previousSummaries, String newSummary) {
        if (previousSummaries == null || previousSummaries.isEmpty()) {
            return newSummary;
        }

        var all = new ArrayList<>(previousSummaries);
        all.add(newSummary);

        if (all.size() <= MAX_STACKED_SUMMARIES) {
            // Just concatenate with temporal markers
            var sb = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                if (i == 0 && all.size() > 2) {
                    sb.append("Much earlier: ").append(compress(all.get(i))).append(" ");
                } else if (i < all.size() - 1) {
                    sb.append("Earlier: ").append(compress(all.get(i))).append(" ");
                } else {
                    sb.append("Recently: ").append(all.get(i));
                }
            }
            return sb.toString().trim();
        }

        // Too many — compress oldest into one sentence
        var oldest = all.subList(0, all.size() - MAX_STACKED_SUMMARIES + 1);
        var recent = all.subList(all.size() - MAX_STACKED_SUMMARIES + 1, all.size());

        var compressedOldest = "Previously: " + compress(String.join(" ", oldest));
        var result = new ArrayList<String>();
        result.add(compressedOldest);
        result.addAll(recent);

        return stackSummaries(result.subList(0, result.size() - 1), result.getLast());
    }

    /** Compress a summary to ~50 words. */
    private static String compress(String summary) {
        if (summary == null) return "";
        var words = summary.split("\\s+");
        if (words.length <= 50) return summary;

        // Take first 50 words
        var sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            if (i > 0) sb.append(" ");
            sb.append(words[i]);
        }
        sb.append("...");
        return sb.toString();
    }

    /** Extract significant topic words from text. */
    private static void extractTopics(String text, Set<String> topics) {
        if (text == null) return;
        var stopWords = Set.of("the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "must", "can", "could", "i", "you", "he", "she", "it",
            "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "its",
            "our", "their", "this", "that", "these", "those", "what", "which", "who", "whom",
            "and", "but", "or", "nor", "not", "so", "yet", "for", "to", "of", "in", "on",
            "at", "by", "with", "from", "about", "into", "through", "during", "before",
            "after", "above", "below", "between", "just", "also", "very", "really", "too",
            "how", "when", "where", "why", "all", "each", "every", "both", "few", "more",
            "most", "some", "any", "no", "than", "if", "then", "else", "there", "here",
            "like", "know", "think", "want", "need", "get", "got", "make", "take", "come",
            "go", "say", "said", "tell", "told", "ask", "asked");

        for (var word : text.toLowerCase().split("[\\s,.!?;:\"'()\\[\\]{}]+")) {
            if (word.length() > 3 && !stopWords.contains(word) && topics.size() < 10) {
                topics.add(word);
            }
        }
    }
}
