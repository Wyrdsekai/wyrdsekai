package org.wyrdsekai.core.empathy;

import java.util.*;

/**
 * Impression-weighted memory retrieval (§109.3).
 * Dual-axis retrieval: relevance x impression depth.
 * High-charge fragments surface first during retrieval.
 */
public class ImpressionWeightedRetrieval {

    /** A scored retrieval candidate. */
    public record ScoredCandidate(
        String fragmentId,
        double relevanceScore,
        double impressionScore,
        double combinedScore,
        String source
    ) {}

    /** Retrieval configuration. */
    public record RetrievalConfig(
        double relevanceWeight,
        double impressionWeight,
        int maxResults,
        int tokenBudget
    ) {
        public static RetrievalConfig defaultConfig() {
            return new RetrievalConfig(0.6, 0.4, 5, 2000);
        }

        public static RetrievalConfig impressionHeavy() {
            return new RetrievalConfig(0.3, 0.7, 5, 2000);
        }
    }

    private final RetrievalConfig config;

    public ImpressionWeightedRetrieval() {
        this(RetrievalConfig.defaultConfig());
    }

    public ImpressionWeightedRetrieval(RetrievalConfig config) {
        this.config = config;
    }

    /** Score and rank candidates by combined relevance and impression. */
    public List<ScoredCandidate> rankCandidates(
            Map<String, Double> relevanceScores,
            Map<String, Double> impressionScores) {

        var candidates = new ArrayList<ScoredCandidate>();

        for (var entry : relevanceScores.entrySet()) {
            var fragmentId = entry.getKey();
            double relevance = entry.getValue();
            double impression = impressionScores.getOrDefault(fragmentId, 0.0);

            double combined = (relevance * config.relevanceWeight())
                            + (impression * config.impressionWeight());

            candidates.add(new ScoredCandidate(fragmentId, relevance,
                impression, combined, "dual-axis"));
        }

        // Sort by combined score descending
        candidates.sort(Comparator.comparingDouble(ScoredCandidate::combinedScore).reversed());

        // Apply max results limit
        if (candidates.size() > config.maxResults()) {
            candidates = new ArrayList<>(candidates.subList(0, config.maxResults()));
        }

        return candidates;
    }

    /** Check if a high-impression fragment would be surfaced over a high-relevance one. */
    public boolean impressionWouldSurface(double lowRelevance, double highImpression,
                                           double highRelevance, double lowImpression) {
        double scoreA = (lowRelevance * config.relevanceWeight())
                      + (highImpression * config.impressionWeight());
        double scoreB = (highRelevance * config.relevanceWeight())
                      + (lowImpression * config.impressionWeight());
        return scoreA > scoreB;
    }

    public RetrievalConfig config() { return config; }
}
