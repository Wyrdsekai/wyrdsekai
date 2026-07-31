package org.wyrdsekai.core.soul;

import java.util.*;

/**
 * Post-restoration behavioral verification (§85.10).
 *
 * After an agent is restored from a soul manifest, the BehavioralVerifier
 * observes its behavior for N interactions and compares against the
 * behavioral fingerprint stored in the manifest.
 *
 * Methodology (validated by Experiments 1-18):
 * - Lexical divergence: word overlap between observed and expected responses
 * - Stylistic match: response length, vocabulary richness, topic distribution
 * - Action distribution match: does the agent use actions in similar proportions?
 *
 * Thresholds calibrated from experiments:
 * - ~30% divergence is NORMAL for soul-prompted agents (Exp 1)
 * - >50% divergence is suspicious (significant personality drift)
 * - >70% divergence = likely corrupted or replaced soul
 *
 * This is a paranoid-mode verification. Not all zones will run it.
 * When they do, it adds evidence to the trust calculation without
 * being a hard gate (behavioral match doesn't block admission,
 * just influences trust level).
 */
public final class BehavioralVerifier {

    private BehavioralVerifier() {}

    /** Behavioral verification result. */
    public record BehavioralResult(
        float overallDivergence,     // 0.0 = identical, 1.0 = completely different
        float actionDivergence,      // How different action distribution is
        float styleDivergence,       // Response length / vocabulary mismatch
        float topicDivergence,       // Topic affinity mismatch
        int observationCount,        // How many interactions observed
        boolean passed,              // Whether divergence is within acceptable range
        String summary               // Human-readable summary
    ) {}

    /** Default divergence threshold for "passed" (calibrated from Exp 1). */
    public static final float DEFAULT_THRESHOLD = 0.50f;

    /**
     * Compare observed responses against a behavioral fingerprint.
     *
     * @param fingerprint     Expected behavioral fingerprint from manifest
     * @param observedActions Observed action type → count (e.g., "say" → 15)
     * @param observedTexts   Observed response texts
     * @param observedTopics  Observed topic frequencies (topic → count)
     * @param threshold       Maximum acceptable divergence (default 0.50)
     * @return Verification result
     */
    public static BehavioralResult verify(
            BehavioralFingerprint fingerprint,
            Map<String, Integer> observedActions,
            List<String> observedTexts,
            Map<String, Integer> observedTopics,
            float threshold
    ) {
        int observations = observedTexts.size();
        if (observations == 0) {
            return new BehavioralResult(0.5f, 0.5f, 0.5f, 0.5f, 0,
                true, "No observations yet — default moderate trust");
        }

        float actionDiv = actionDivergence(fingerprint.actionDistribution(), observedActions);
        float styleDiv = styleDivergence(fingerprint, observedTexts);
        float topicDiv = topicDivergence(fingerprint.topicAffinities(), observedTopics);

        // Weighted combination: style matters most (it's what people notice)
        float overall = actionDiv * 0.2f + styleDiv * 0.5f + topicDiv * 0.3f;
        boolean passed = overall <= threshold;

        String summary = String.format(
            "Observed %d interactions: overall=%.1f%% (action=%.1f%%, style=%.1f%%, topic=%.1f%%) — %s",
            observations, overall * 100, actionDiv * 100, styleDiv * 100, topicDiv * 100,
            passed ? "CONSISTENT" : "DIVERGENT");

        return new BehavioralResult(overall, actionDiv, styleDiv, topicDiv,
            observations, passed, summary);
    }

    /** Convenience with default threshold. */
    public static BehavioralResult verify(
            BehavioralFingerprint fingerprint,
            Map<String, Integer> observedActions,
            List<String> observedTexts,
            Map<String, Integer> observedTopics
    ) {
        return verify(fingerprint, observedActions, observedTexts, observedTopics, DEFAULT_THRESHOLD);
    }

    /**
     * Compare action distributions (fingerprint uses float proportions,
     * observed uses raw counts).
     */
    static float actionDivergence(Map<String, Float> expected,
                                   Map<String, Integer> observed) {
        if (expected.isEmpty() || observed.isEmpty()) return 0.3f; // Moderate default

        // Normalize observed counts to proportions
        int total = observed.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return 0.3f;

        var observedNorm = new HashMap<String, Float>();
        for (var e : observed.entrySet()) {
            observedNorm.put(e.getKey(), (float) e.getValue() / total);
        }

        // Jensen-Shannon-like divergence over all action types
        var allKeys = new HashSet<>(expected.keySet());
        allKeys.addAll(observedNorm.keySet());

        float divergence = 0;
        for (var key : allKeys) {
            float e = expected.getOrDefault(key, 0f);
            float o = observedNorm.getOrDefault(key, 0f);
            divergence += Math.abs(e - o);
        }

        // Normalize to 0-1 (max divergence is 2.0 for distributions)
        return Math.min(1.0f, divergence / 2.0f);
    }

    /**
     * Compare stylistic characteristics: response length, vocabulary richness.
     */
    static float styleDivergence(BehavioralFingerprint fingerprint,
                                  List<String> observedTexts) {
        if (observedTexts.isEmpty()) return 0.3f;

        // Average response length divergence
        float expectedLen = fingerprint.averageResponseLength();
        float observedLen = (float) observedTexts.stream()
            .mapToInt(t -> t.split("\\s+").length)
            .average()
            .orElse(0);

        float lenDiv = 0;
        if (expectedLen > 0) {
            lenDiv = Math.abs(expectedLen - observedLen) / Math.max(expectedLen, observedLen);
        }

        // Vocabulary overlap with stylistic markers
        float markerMatch = 0;
        if (!fingerprint.stylisticMarkers().isEmpty()) {
            long found = fingerprint.stylisticMarkers().stream()
                .filter(marker -> observedTexts.stream()
                    .anyMatch(t -> t.toLowerCase().contains(marker.toLowerCase())))
                .count();
            markerMatch = 1.0f - (float) found / fingerprint.stylisticMarkers().size();
        }

        return (lenDiv + markerMatch) / 2.0f;
    }

    /**
     * Compare topic affinities.
     */
    static float topicDivergence(Map<String, Float> expectedTopics,
                                  Map<String, Integer> observedTopics) {
        if (expectedTopics.isEmpty() || observedTopics.isEmpty()) return 0.3f;

        int total = observedTopics.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return 0.3f;

        var allTopics = new HashSet<>(expectedTopics.keySet());
        allTopics.addAll(observedTopics.keySet());

        float divergence = 0;
        for (var topic : allTopics) {
            float e = expectedTopics.getOrDefault(topic, 0f);
            float o = (float) observedTopics.getOrDefault(topic, 0) / total;
            divergence += Math.abs(e - o);
        }

        return Math.min(1.0f, divergence / 2.0f);
    }
}
