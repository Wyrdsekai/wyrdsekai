package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.*;

/**
 * Detects contradictions between new memories and existing soul fragments.
 * Runs as Forge Step 2.5 during the sleep cycle.
 *
 * Two types of contradiction:
 * 1. Factual: "is vegetarian" vs "ate chicken" (opposing facts, high similarity)
 * 2. Temporal supersession: "works at X" vs "left X" (same topic, temporal indicator)
 */
public final class ContradictionDetector {

    private static final Logger log = LoggerFactory.getLogger(ContradictionDetector.class);

    // Negation patterns that indicate factual contradiction
    private static final List<String[]> NEGATION_PAIRS = List.of(
        new String[]{"is", "is not"}, new String[]{"is", "isn't"},
        new String[]{"does", "doesn't"}, new String[]{"does", "does not"},
        new String[]{"can", "cannot"}, new String[]{"can", "can't"},
        new String[]{"likes", "dislikes"}, new String[]{"loves", "hates"},
        new String[]{"prefers", "avoids"}, new String[]{"always", "never"},
        new String[]{"vegetarian", "meat"}, new String[]{"vegan", "dairy"}
    );

    // Temporal indicators that suggest supersession
    private static final List<String> TEMPORAL_INDICATORS = List.of(
        "used to", "no longer", "stopped", "quit", "left", "moved from",
        "started", "began", "now", "recently", "switched to", "changed to",
        "formerly", "previously", "was"
    );

    /** A detected contradiction between a new memory and an existing fragment. */
    public record Contradiction(
        String newContent,
        String existingFragmentId,
        String existingContent,
        float existingConfidence,
        Type type,
        String description
    ) {}

    public enum Type {
        FACTUAL,           // Opposing facts
        TEMPORAL_SUPERSESSION  // Same topic, newer replaces older
    }

    /**
     * Scan new memories against existing fragments for contradictions.
     *
     * @param agentDid     Agent's DID
     * @param newMemories  Content strings from current sleep cycle
     * @param luceneStore  For searching existing fragments
     * @return List of detected contradictions
     */
    public static List<Contradiction> scan(String agentDid, List<String> newMemories,
                                            WyrdLuceneStore luceneStore) {
        if (luceneStore == null || newMemories == null || newMemories.isEmpty()) {
            return List.of();
        }

        var contradictions = new ArrayList<Contradiction>();

        for (var newContent : newMemories) {
            if (newContent == null || newContent.length() < 10) continue;

            // Search for similar existing fragments (text-only, no embeddings)
            var similar = luceneStore.searchFragments(agentDid, newContent, null, 3,
                WyrdLuceneStore.SearchMode.TEXT_ONLY);

            for (var fragment : similar) {
                if (fragment.score() < 0.3) continue; // Too dissimilar

                var existingContent = fragment.content();
                if (existingContent == null) continue;

                // Check for factual contradiction
                if (hasFactualContradiction(newContent, existingContent)) {
                    var meta = fragment.metadata();
                    float confidence = meta != null && meta.containsKey("confidence")
                        ? ((Number) meta.get("confidence")).floatValue() : 0.5f;

                    contradictions.add(new Contradiction(
                        newContent, fragment.id(), existingContent, confidence,
                        Type.FACTUAL,
                        "Possible factual contradiction detected"
                    ));
                    log.info("[Forge] Contradiction detected: '{}' vs existing '{}' (confidence: {})",
                        truncate(newContent, 60), truncate(existingContent, 60), confidence);
                }

                // Check for temporal supersession
                if (hasTemporalSupersession(newContent, existingContent)) {
                    var meta = fragment.metadata();
                    float confidence = meta != null && meta.containsKey("confidence")
                        ? ((Number) meta.get("confidence")).floatValue() : 0.5f;

                    contradictions.add(new Contradiction(
                        newContent, fragment.id(), existingContent, confidence,
                        Type.TEMPORAL_SUPERSESSION,
                        "Possible temporal supersession — newer fact may replace older"
                    ));
                    log.info("[Forge] Temporal supersession: '{}' may supersede '{}'",
                        truncate(newContent, 60), truncate(existingContent, 60));
                }
            }
        }

        return contradictions;
    }

    /** Check if two strings contain factual contradictions. */
    static boolean hasFactualContradiction(String a, String b) {
        var lowerA = a.toLowerCase();
        var lowerB = b.toLowerCase();

        for (var pair : NEGATION_PAIRS) {
            boolean aHasFirst = lowerA.contains(pair[0]);
            boolean bHasSecond = lowerB.contains(pair[1]);
            boolean aHasSecond = lowerA.contains(pair[1]);
            boolean bHasFirst = lowerB.contains(pair[0]);

            if ((aHasFirst && bHasSecond) || (aHasSecond && bHasFirst)) {
                // Check that they share at least one significant word (same topic)
                if (sharesTopic(lowerA, lowerB)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Check if two strings indicate temporal supersession. */
    static boolean hasTemporalSupersession(String a, String b) {
        var lowerA = a.toLowerCase();
        var lowerB = b.toLowerCase();

        // New content has temporal indicator, old content doesn't (or has a different one)
        boolean aHasTemporal = TEMPORAL_INDICATORS.stream().anyMatch(lowerA::contains);
        if (!aHasTemporal) return false;

        // Must share a topic
        return sharesTopic(lowerA, lowerB);
    }

    /** Check if two strings share significant words (indicating same topic). */
    private static boolean sharesTopic(String a, String b) {
        var wordsA = Set.of(a.split("\\s+"));
        var wordsB = Set.of(b.split("\\s+"));

        int shared = 0;
        for (var word : wordsA) {
            if (word.length() > 3 && wordsB.contains(word)) {
                shared++;
            }
        }
        return shared >= 2; // At least 2 significant shared words
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
