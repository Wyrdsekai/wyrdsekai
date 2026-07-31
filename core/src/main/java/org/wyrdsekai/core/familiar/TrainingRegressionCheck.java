package org.wyrdsekai.core.familiar;

import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.SoulManifest;

import java.util.HashSet;
import java.util.Map;

/**
 * Baseline-imprint regression check for training-corpus generation.
 *
 * <p> — "If training diverges unfavorably (metrics
 * regression, eval failures), rollback to the baseline imprint and regenerate
 * corpus with stricter filters."</p>
 *
 * <p>This is the light-weight, metric-free pre-check: <em>before</em> writing
 * a new training corpus, compare the current fingerprint to the most recent
 * {@code AUTO_MILESTONE} imprint's fingerprint. If the L1 distance across
 * behavioral channels exceeds {@link #DEFAULT_DRIFT_CEILING}, the regression
 * check fires and the caller should defer corpus generation until the drift
 * is reviewed (either by sleep-pass reconciliation or steward intervention).</p>
 *
 * <p>This is NOT a full eval harness — that lives in the gpu-host training
 * pipeline and requires fresh model runs. But this <em>process-side</em> gate
 * catches the common "bunshin batch pushed fingerprint off a cliff" case
 * before it seeds a bad corpus entry.</p>
 */
public final class TrainingRegressionCheck {

    /**
     * L1 drift ceiling across merged fingerprint channels. Above this, the
     * caller is advised to skip corpus generation this cycle. Empirically-
     * tuneable; see SPEC §23 open question "training signal weight."
     */
    public static final double DEFAULT_DRIFT_CEILING = 2.0;

    public record Assessment(
        boolean regressed,
        double drift,
        String reason
    ) {
        public static Assessment ok() {
            return new Assessment(false, 0.0, "no baseline to compare against");
        }
    }

    private TrainingRegressionCheck() {}

    /**
     * Compare the current merged fingerprint against the baseline imprint's
     * fingerprint. If {@code baseline} is null (no AUTO_MILESTONE yet),
     * returns an OK assessment.
     *
     * @param current   the fingerprint about to be persisted
     * @param baseline  the baseline-imprint's fingerprint (may be null)
     * @param ceiling   L1-distance ceiling above which we flag regression
     */
    public static Assessment assess(BehavioralFingerprint current,
                                     BehavioralFingerprint baseline,
                                     double ceiling) {
        if (current == null || baseline == null) return Assessment.ok();
        var drift = l1Distance(current, baseline);
        if (drift > ceiling) {
            return new Assessment(true, drift,
                String.format("fingerprint drift %.2f > ceiling %.2f — hold corpus generation",
                    drift, ceiling));
        }
        return new Assessment(false, drift, null);
    }

    /** Convenience overload using {@link #DEFAULT_DRIFT_CEILING}. */
    public static Assessment assess(BehavioralFingerprint current,
                                     BehavioralFingerprint baseline) {
        return assess(current, baseline, DEFAULT_DRIFT_CEILING);
    }

    /**
     * Resolve the baseline fingerprint for an agent from its most recent
     * {@code AUTO_MILESTONE} imprint. Returns null when no such imprint exists.
     */
    public static BehavioralFingerprint baselineFor(ImprintManager manager) {
        if (manager == null) return null;
        return manager.latestByCreator(Imprint.CreatedBy.AUTO_MILESTONE)
            .map(Imprint::manifest)
            .map(SoulManifest::fingerprint)
            .orElse(null);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /** Sum of absolute per-key differences across all fingerprint channels. */
    static double l1Distance(BehavioralFingerprint a, BehavioralFingerprint b) {
        double d = 0.0;
        d += mapL1(a.baselineVitality(), b.baselineVitality());
        d += mapL1(a.baselineDerivatives(), b.baselineDerivatives());
        d += mapL1(a.observedSensitivity(), b.observedSensitivity());
        d += mapL1(a.actionDistribution(), b.actionDistribution());
        d += mapL1(a.topicAffinities(), b.topicAffinities());
        d += mapL1(a.avoidancePatterns(), b.avoidancePatterns());
        d += mapL1(a.emotionalResponseProfile(), b.emotionalResponseProfile());
        return d;
    }

    private static double mapL1(Map<String, Float> a, Map<String, Float> b) {
        if (a == null && b == null) return 0.0;
        var aa = a == null ? Map.<String, Float>of() : a;
        var bb = b == null ? Map.<String, Float>of() : b;
        var keys = new HashSet<String>();
        keys.addAll(aa.keySet());
        keys.addAll(bb.keySet());
        double d = 0.0;
        for (var k : keys) {
            var va = aa.getOrDefault(k, 0f);
            var vb = bb.getOrDefault(k, 0f);
            d += Math.abs(va - vb);
        }
        return d;
    }
}
