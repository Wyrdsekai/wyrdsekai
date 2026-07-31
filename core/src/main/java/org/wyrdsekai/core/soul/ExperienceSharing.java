package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Experience sharing between buds in a soul lineage (§85.18.4).
 *
 * Buds share experiences through the FamilyLocker using SoulItems.
 * This class manages the selection and exchange of items for
 * convergence (reducing divergence) or growth (sharing novel experiences).
 *
 * Two sharing modes:
 * 1. Convergence sync — share high-significance items the other bud lacks
 * 2. Growth sharing — share novel experiences (unique items sorted by novelty)
 *
 * All sharing respects:
 * - Sovereignty: buds choose what to share and what to integrate
 * - Tombstones: deleted items are never shared
 * - Significance: only items above threshold are worth sharing
 */
public class ExperienceSharing {

    /** A sharing recommendation: what to share with whom. */
    public record SharingRecommendation(
        String fromBud,
        String toBud,
        List<SoulItem> items,
        SharingMode mode,
        double expectedDivergenceReduction,
        Instant generatedAt
    ) {
        public int itemCount() { return items.size(); }
    }

    /** Sharing mode. */
    public enum SharingMode {
        /** Share to reduce divergence (high-sig items other bud lacks). */
        CONVERGENCE,
        /** Share novel experiences for growth. */
        GROWTH
    }

    /** Result of applying shared items. */
    public record SharingResult(
        int offered,
        int accepted,
        int rejected,
        int alreadyPresent,
        double divergenceBefore,
        double divergenceAfter
    ) {
        public double reduction() { return divergenceBefore - divergenceAfter; }
    }

    /** Minimum significance for an item to be worth sharing. */
    private double significanceThreshold = 0.3;

    /** Maximum items per sharing batch. */
    private int maxBatchSize = 20;

    public ExperienceSharing() {}

    public ExperienceSharing withSignificanceThreshold(double threshold) {
        this.significanceThreshold = threshold;
        return this;
    }

    public ExperienceSharing withMaxBatchSize(int size) {
        this.maxBatchSize = size;
        return this;
    }

    /**
     * Generate convergence recommendations: items bud A has that bud B lacks,
     * sorted by significance.
     */
    public SharingRecommendation forConvergence(String fromBud, String toBud,
                                                  List<SoulItem> fromItems,
                                                  Set<String> toHashes,
                                                  double currentDivergence) {
        var unique = fromItems.stream()
            .filter(item -> !toHashes.contains(item.hash()))
            .filter(item -> item.significance() >= significanceThreshold)
            .sorted(Comparator.comparingDouble(SoulItem::significance).reversed())
            .limit(maxBatchSize)
            .toList();

        // Estimate divergence reduction: sharing N items of avg significance S
        // reduces memory divergence component by approximately N/total
        double avgSig = unique.stream().mapToDouble(SoulItem::significance).average().orElse(0);
        int total = fromItems.size() + toHashes.size();
        double estReduction = total > 0 ? (unique.size() * avgSig * 0.3) / total : 0;

        return new SharingRecommendation(fromBud, toBud, unique,
            SharingMode.CONVERGENCE, Math.min(estReduction, currentDivergence),
            Instant.now());
    }

    /**
     * Generate growth recommendations: novel items that expand the other bud's
     * experience. Prioritizes items from categories the other bud lacks.
     */
    public SharingRecommendation forGrowth(String fromBud, String toBud,
                                             List<SoulItem> fromItems,
                                             Set<String> toHashes,
                                             Set<String> toCategories) {
        // Find items the other bud doesn't have, preferring categories they lack
        var unique = fromItems.stream()
            .filter(item -> !toHashes.contains(item.hash()))
            .filter(item -> item.significance() >= significanceThreshold)
            .toList();

        // Score by novelty: items in categories the other bud lacks score higher
        var scored = unique.stream()
            .map(item -> new Object() {
                SoulItem i = item;
                double novelty = toCategories.contains(item.category()) ? 0.5 : 1.0;
                double score = item.significance() * novelty;
            })
            .sorted(Comparator.comparingDouble(o -> -o.score))
            .limit(maxBatchSize)
            .map(o -> o.i)
            .toList();

        return new SharingRecommendation(fromBud, toBud, scored,
            SharingMode.GROWTH, 0.0, Instant.now());
    }

    /**
     * Apply a sharing recommendation to the locker.
     * The receiving bud's acceptance is simulated by the significanceFilter.
     *
     * @param recommendation Items to share
     * @param locker         The family locker
     * @param receiverDid    Receiving bud's DID
     * @param significanceFilter Receiver's minimum acceptance threshold
     * @param divergenceBefore Current divergence (for tracking)
     * @param divergenceAfter  Post-sharing divergence (pre-computed)
     */
    public SharingResult apply(SharingRecommendation recommendation,
                                FamilyLocker locker,
                                String receiverDid,
                                double significanceFilter,
                                double divergenceBefore,
                                double divergenceAfter) {
        int offered = recommendation.items().size();
        int accepted = 0, rejected = 0, alreadyPresent = 0;

        for (var item : recommendation.items()) {
            var existing = locker.get(item.hash(), receiverDid);
            if (existing.isPresent()) {
                alreadyPresent++;
                continue;
            }
            if (item.significance() >= significanceFilter) {
                locker.store(item, receiverDid);
                accepted++;
            } else {
                rejected++;
            }
        }

        return new SharingResult(offered, accepted, rejected,
            alreadyPresent, divergenceBefore, divergenceAfter);
    }

    /**
     * Bidirectional sharing: generate convergence recommendations for both directions.
     */
    public List<SharingRecommendation> bidirectionalConvergence(
            String budA, String budB,
            List<SoulItem> itemsA, List<SoulItem> itemsB,
            double currentDivergence) {
        var hashesA = itemsA.stream().map(SoulItem::hash).collect(Collectors.toSet());
        var hashesB = itemsB.stream().map(SoulItem::hash).collect(Collectors.toSet());

        var aToB = forConvergence(budA, budB, itemsA, hashesB, currentDivergence);
        var bToA = forConvergence(budB, budA, itemsB, hashesA, currentDivergence);

        return List.of(aToB, bToA);
    }
}
