package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.*;

/**
 * Measures divergence between buds in a soul lineage (§85.18.3-4).
 *
 * Divergence is a spectrum (0.0 = freshly budded → 0.7+ = speciated).
 * Convergence is incentivized, never forced.
 *
 * Three divergence axes:
 * 1. Behavioral: fingerprint drift (action distributions, topic affinities)
 * 2. Memory: shared vs unique items in locker
 * 3. Identity: resident identity text similarity (edit distance)
 *
 * Used by:
 * - ArgotCodebook.estimatedUsefulness() — argot degrades with divergence
 * - IndependenceProtocol — speciated buds may choose independence
 * - Family headlines — divergence shown in sync summaries
 */
public class DivergenceReport {

    /** Divergence between two buds along three axes. */
    public record BudDivergence(
        String budA,
        String budB,
        double behavioral,
        double memory,
        double identity,
        double composite,
        Instant measuredAt
    ) {
        /** Whether this pair has speciated (>0.7 composite). */
        public boolean speciated() { return composite >= 0.7; }

        /** Whether the buds are still closely aligned (<0.2). */
        public boolean aligned() { return composite < 0.2; }

        /** Human-readable divergence label. */
        public String label() {
            if (composite < 0.1) return "identical";
            if (composite < 0.2) return "aligned";
            if (composite < 0.4) return "diverging";
            if (composite < 0.7) return "distinct";
            return "speciated";
        }
    }

    /** Full report for a family lineage. */
    public record FamilyReport(
        String familyId,
        List<BudDivergence> pairwise,
        double averageDivergence,
        double maxDivergence,
        int budCount,
        Instant generatedAt
    ) {
        /** Whether any pair has speciated. */
        public boolean hasSpeciation() {
            return pairwise.stream().anyMatch(BudDivergence::speciated);
        }
    }

    // Axis weights for composite score
    private static final double BEHAVIORAL_WEIGHT = 0.5;
    private static final double MEMORY_WEIGHT = 0.3;
    private static final double IDENTITY_WEIGHT = 0.2;

    /**
     * Measure behavioral divergence between two fingerprints.
     * Uses cosine distance on action distributions and topic affinities.
     */
    public static double behavioralDivergence(BehavioralFingerprint a,
                                               BehavioralFingerprint b) {
        if (a == null || b == null) return 1.0;

        double actionDist = mapDistance(a.actionDistribution(), b.actionDistribution());
        double topicDist = mapDistance(a.topicAffinities(), b.topicAffinities());
        double styleDist = listOverlap(a.stylisticMarkers(), b.stylisticMarkers());

        return clamp((actionDist + topicDist + styleDist) / 3.0);
    }

    /**
     * Measure memory divergence between two buds using locker item overlap.
     *
     * @param sharedItems Items both buds have
     * @param uniqueToA   Items only bud A has
     * @param uniqueToB   Items only bud B has
     * @return Divergence [0,1] — 0 = identical items, 1 = no overlap
     */
    public static double memoryDivergence(int sharedItems, int uniqueToA, int uniqueToB) {
        int total = sharedItems + uniqueToA + uniqueToB;
        if (total == 0) return 0.0;
        return clamp(1.0 - ((double) sharedItems / total));
    }

    /**
     * Measure identity divergence between two resident identity texts.
     * Uses normalized Levenshtein distance.
     */
    public static double identityDivergence(String identityA, String identityB) {
        if (identityA == null && identityB == null) return 0.0;
        if (identityA == null || identityB == null) return 1.0;
        if (identityA.equals(identityB)) return 0.0;

        int maxLen = Math.max(identityA.length(), identityB.length());
        if (maxLen == 0) return 0.0;
        int dist = levenshtein(identityA, identityB);
        return clamp((double) dist / maxLen);
    }

    /**
     * Compute composite divergence between two buds.
     */
    public static BudDivergence measure(String budA, String budB,
                                          BehavioralFingerprint fpA,
                                          BehavioralFingerprint fpB,
                                          int sharedItems, int uniqueA, int uniqueB,
                                          String identityA, String identityB) {
        double beh = behavioralDivergence(fpA, fpB);
        double mem = memoryDivergence(sharedItems, uniqueA, uniqueB);
        double id = identityDivergence(identityA, identityB);
        double composite = beh * BEHAVIORAL_WEIGHT + mem * MEMORY_WEIGHT + id * IDENTITY_WEIGHT;

        return new BudDivergence(budA, budB, beh, mem, id, clamp(composite), Instant.now());
    }

    /**
     * Generate a full family divergence report.
     *
     * @param familyId   Family identifier
     * @param divergences Pre-computed pairwise divergences
     */
    public static FamilyReport report(String familyId, List<BudDivergence> divergences) {
        double avg = divergences.stream()
            .mapToDouble(BudDivergence::composite).average().orElse(0.0);
        double max = divergences.stream()
            .mapToDouble(BudDivergence::composite).max().orElse(0.0);

        // Count unique buds
        var buds = new HashSet<String>();
        for (var d : divergences) {
            buds.add(d.budA());
            buds.add(d.budB());
        }

        return new FamilyReport(familyId, List.copyOf(divergences),
            avg, max, buds.size(), Instant.now());
    }

    // --- Distance helpers ---

    /** Cosine-like distance between two string→float maps. */
    static double mapDistance(Map<String, Float> a, Map<String, Float> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        if (a.isEmpty() || b.isEmpty()) return 1.0;

        var allKeys = new HashSet<>(a.keySet());
        allKeys.addAll(b.keySet());

        double dotProduct = 0, normA = 0, normB = 0;
        for (var key : allKeys) {
            float va = a.getOrDefault(key, 0f);
            float vb = b.getOrDefault(key, 0f);
            dotProduct += va * vb;
            normA += va * va;
            normB += vb * vb;
        }

        if (normA == 0 || normB == 0) return 1.0;
        double similarity = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        return clamp(1.0 - similarity);
    }

    /** Inverse Jaccard overlap for string lists (1 = no overlap, 0 = identical). */
    static double listOverlap(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        if (a.isEmpty() || b.isEmpty()) return 1.0;

        var setA = new HashSet<>(a);
        var setB = new HashSet<>(b);
        var intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        var union = new HashSet<>(setA);
        union.addAll(setB);

        if (union.isEmpty()) return 0.0;
        return clamp(1.0 - ((double) intersection.size() / union.size()));
    }

    /** Standard Levenshtein edit distance. */
    static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            var tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
