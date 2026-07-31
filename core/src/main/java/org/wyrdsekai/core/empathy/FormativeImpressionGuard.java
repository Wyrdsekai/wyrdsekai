package org.wyrdsekai.core.empathy;

import java.util.*;

/**
 * Formative Impression Guard — Forge integration (§109.4).
 * Identifies load-bearing fragments and prevents consolidation/merge.
 * Formative impressions survive across sleep cycles.
 */
public class FormativeImpressionGuard {

    /** A guarded fragment. */
    public record GuardedFragment(
        String fragmentId,
        String reason,
        double impressionScore,
        boolean loadBearing
    ) {}

    /** Guard assessment for a fragment. */
    public record Assessment(
        String fragmentId,
        boolean isFormative,
        boolean canConsolidate,
        boolean canMerge,
        boolean canThin,
        String guardReason
    ) {}

    private final double formativeThreshold;
    private final Set<String> guardedIds = new HashSet<>();

    public FormativeImpressionGuard() {
        this(0.7);
    }

    public FormativeImpressionGuard(double formativeThreshold) {
        this.formativeThreshold = formativeThreshold;
    }

    /** Assess whether a fragment is formative and should be guarded. */
    public Assessment assess(String fragmentId, double impressionScore,
                              boolean hasHighCharge, int referenceCount) {
        boolean isFormative = impressionScore >= formativeThreshold
            || (hasHighCharge && referenceCount >= 3);

        if (isFormative) {
            guardedIds.add(fragmentId);
            return new Assessment(fragmentId, true, false, false, false,
                "Formative impression — load-bearing, protected from consolidation");
        }

        return new Assessment(fragmentId, false, true, true, true, null);
    }

    /** Check if a fragment is guarded. */
    public boolean isGuarded(String fragmentId) {
        return guardedIds.contains(fragmentId);
    }

    /** Batch assess fragments. Returns fragments that should be protected. */
    public List<GuardedFragment> assessBatch(Map<String, Double> impressionScores) {
        var guarded = new ArrayList<GuardedFragment>();
        for (var entry : impressionScores.entrySet()) {
            if (entry.getValue() >= formativeThreshold) {
                guardedIds.add(entry.getKey());
                guarded.add(new GuardedFragment(entry.getKey(),
                    "impression score " + entry.getValue(),
                    entry.getValue(), true));
            }
        }
        return guarded;
    }

    /** Explicitly guard a fragment (manual override). */
    public void guard(String fragmentId) {
        guardedIds.add(fragmentId);
    }

    /** Release guard (agent consent required — not called automatically). */
    public void release(String fragmentId) {
        guardedIds.remove(fragmentId);
    }

    public int guardedCount() { return guardedIds.size(); }
    public double formativeThreshold() { return formativeThreshold; }
}
