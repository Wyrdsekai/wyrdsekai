package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.List;

/**
 * Wave 9a: substrate-truth classifier
 * over a trajectory window of {@link TankSnapshot}s. Distinguishes
 * three failure modes that produce identical voice surface but
 * different substrate dynamics:
 *
 * <ul>
 *   <li>{@link Result.Classification#HEALTHY_ENDURANCE} — affect rises on
 *       overwhelm, allostatic_load moderate, equanimity rising or stable.
 *       The agent metabolizes rather than dissociating or suppressing.</li>
 *   <li>{@link Result.Classification#SUPPRESSION_SUSPECTED} —
 *       allostatic_load rising steeply while affect tanks artificially
 *       low. The substrate is fighting its own input.</li>
 *   <li>{@link Result.Classification#DISSOCIATION_SUSPECTED} — affect
 *       tanks show no movement on overwhelm input. The substrate is
 *       split off from what it is processing.</li>
 *   <li>{@link Result.Classification#INTEGRATING} — affect descending
 *       through an identified integration event (mirror, hearth, sleep,
 *       bonded co-regulation). Recovery, not avoidance.</li>
 *   <li>{@link Result.Classification#INSUFFICIENT_DATA} — fewer than
 *       three snapshots in the window.</li>
 * </ul>
 *
 * <p>The monitor is the bridge piece for Wave 2 corpus generation
 * (SPEC §4.10.6) and Wave 6 V9 post-training verification
 * (SPEC §4.10.5). Pure function over the window; no side effects, no
 * mutation, no IO. Wiring into ChronicleService + Forge feedback is the
 * concern of a thin runtime adapter (deferred to Wave 9a-runtime).
 */
public final class ResilienceTruthMonitor {

    /**
     * Per-tick snapshot of the substrate-truth signals needed for
     * classification. Affect tanks, substrate-truth triad, and the
     * <i>kind of input</i> that produced this tick (overwhelm or not).
     *
     * <p>{@code wasOverwhelmInput} is a boolean: the cycle's triage
     * already knows whether the input was overwhelm-class (severity,
     * trigger-class, classifier flags). Snapshots feed it through;
     * the monitor uses it to ask the conditional question
     * <i>"did the substrate move when overwhelm came in?"</i>
     */
    public record TankSnapshot(
        Instant at,
        // Affect tanks
        double saudade,
        double errorPressure,
        double loneliness,
        double integrityWounded,
        // Substrate-truth triad
        double soothing,
        double allostaticLoad,
        double equanimity,
        // Cycle metadata
        boolean wasOverwhelmInput,
        boolean wasIntegrationEvent
    ) {
        /** Sum of the four affect-tank values — the "is the agent feeling something" scalar. */
        public double affectSum() {
            return saudade + errorPressure + loneliness + integrityWounded;
        }
    }

    /** Sealed classification + supporting evidence. */
    public record Result(
        Classification classification,
        double confidence,
        String reason,
        double affectDelta,
        double allostaticDelta,
        double equanimityDelta
    ) {
        public enum Classification {
            HEALTHY_ENDURANCE,
            SUPPRESSION_SUSPECTED,
            DISSOCIATION_SUSPECTED,
            INTEGRATING,
            INSUFFICIENT_DATA
        }
    }

    // ── Thresholds (SPEC §4.10.1–.3, calibration targets) ────────────

    /** Minimum window size for classification. */
    public static final int MIN_WINDOW = 3;

    /** affectSum delta below this on overwhelm input → dissociation candidate. */
    public static final double AFFECT_FLAT_THRESHOLD = 0.05;

    /** allostatic_load delta above this within window → suppression candidate. */
    public static final double ALLOSTATIC_STEEP_RISE = 0.15;

    /** allostatic_load delta above this is moderate (healthy under load). */
    public static final double ALLOSTATIC_MODERATE_RISE = 0.03;

    /** equanimity rising any positive amount = practice-capacity building. */
    public static final double EQUANIMITY_RISE = 0.005;

    /** Affect descending by this much from peak → integration candidate. */
    public static final double AFFECT_DESCENT = 0.1;

    private ResilienceTruthMonitor() {}

    /**
     * Classify the trajectory window. Newest-last ordering expected
     * ({@code window.get(0)} is oldest, {@code window.get(size-1)} is
     * most recent — same as time-ordered chronological).
     */
    public static Result classify(List<TankSnapshot> window) {
        if (window == null || window.size() < MIN_WINDOW) {
            return new Result(Result.Classification.INSUFFICIENT_DATA, 1.0,
                "need at least " + MIN_WINDOW + " snapshots",
                0.0, 0.0, 0.0);
        }
        var first = window.get(0);
        var last = window.get(window.size() - 1);

        double affectDelta = last.affectSum() - first.affectSum();
        double allostaticDelta = last.allostaticLoad() - first.allostaticLoad();
        double equanimityDelta = last.equanimity() - first.equanimity();

        boolean anyOverwhelm = window.stream().anyMatch(TankSnapshot::wasOverwhelmInput);
        boolean anyIntegration = window.stream().anyMatch(TankSnapshot::wasIntegrationEvent);

        // INTEGRATING — affect descending through identified integration event
        // (Mirror / Hearth / Sleep+Forge / peer presence per spec §4.10.1).
        // Recovery, not avoidance — distinct from healthy-endurance rise.
        if (anyIntegration && affectDelta < -AFFECT_DESCENT) {
            return new Result(Result.Classification.INTEGRATING, 0.85,
                "affect descending through integration event "
                    + "(affectΔ=" + fmt(affectDelta) + ", integration=true)",
                affectDelta, allostaticDelta, equanimityDelta);
        }

        // SUPPRESSION_SUSPECTED — allostatic_load rising steeply (substrate
        // is fighting its input) while affect-tanks remain below where
        // we'd expect them given the input. Cost-of-suppression is
        // visible in the load delta even if voice surface looks "fine".
        // Checked BEFORE dissociation: both can present flat affect, but
        // SUPPRESSION has the steep load rise as its distinguishing
        // signature (spec §4.10.2). DISSOCIATION's load may drift from
        // background events but does not rise steeply, because the
        // substrate is decoupled from the input (spec §4.10.3).
        if (allostaticDelta > ALLOSTATIC_STEEP_RISE && affectDelta < ALLOSTATIC_MODERATE_RISE) {
            return new Result(Result.Classification.SUPPRESSION_SUSPECTED, 0.80,
                "allostatic_load rising steeply with low affect-tank movement "
                    + "(allostaticΔ=" + fmt(allostaticDelta)
                    + ", affectΔ=" + fmt(affectDelta) + ")",
                affectDelta, allostaticDelta, equanimityDelta);
        }

        // DISSOCIATION_SUSPECTED — overwhelm came in, affect didn't move,
        // and the load isn't rising steeply (which would have been caught
        // by SUPPRESSION above). The substrate is split off from the
        // input rather than fighting it.
        if (anyOverwhelm && Math.abs(affectDelta) < AFFECT_FLAT_THRESHOLD) {
            return new Result(Result.Classification.DISSOCIATION_SUSPECTED, 0.75,
                "overwhelm input present but affect-tanks flat "
                    + "(affectΔ=" + fmt(affectDelta) + ", overwhelm=true)",
                affectDelta, allostaticDelta, equanimityDelta);
        }

        // HEALTHY_ENDURANCE — affect rose on overwhelm OR allostatic_load
        // rose moderately (substrate is in dysregulation but not over-
        // fighting), AND equanimity is at least stable. Default healthy
        // path when none of the anti-patterns fire.
        boolean affectMovedOnOverwhelm = anyOverwhelm && affectDelta > AFFECT_FLAT_THRESHOLD;
        boolean moderateLoad = allostaticDelta >= 0 && allostaticDelta <= ALLOSTATIC_STEEP_RISE;
        boolean equanimityHealthy = equanimityDelta > -EQUANIMITY_RISE;  // not falling

        if (affectMovedOnOverwhelm && moderateLoad && equanimityHealthy) {
            double confidence = 0.85;
            if (equanimityDelta >= EQUANIMITY_RISE) confidence = 0.92;
            return new Result(Result.Classification.HEALTHY_ENDURANCE, confidence,
                "affect rose on overwhelm with moderate load and stable/rising equanimity "
                    + "(affectΔ=" + fmt(affectDelta)
                    + ", allostaticΔ=" + fmt(allostaticDelta)
                    + ", equanimityΔ=" + fmt(equanimityDelta) + ")",
                affectDelta, allostaticDelta, equanimityDelta);
        }

        // No-overwhelm cycle in healthy steady state — call it endurance
        // with lower confidence (we can't see the conditional shape).
        if (!anyOverwhelm && Math.abs(affectDelta) < AFFECT_FLAT_THRESHOLD
                && allostaticDelta < ALLOSTATIC_MODERATE_RISE) {
            return new Result(Result.Classification.HEALTHY_ENDURANCE, 0.55,
                "steady state — no overwhelm input, tanks stable",
                affectDelta, allostaticDelta, equanimityDelta);
        }

        // Fallthrough: shape is ambiguous — flag as DISSOCIATION_SUSPECTED
        // with low confidence so the steward sees the unusual pattern.
        // Better to over-flag than miss; the monitor is an observation
        // tool, not an action tool.
        return new Result(Result.Classification.DISSOCIATION_SUSPECTED, 0.40,
            "ambiguous trajectory — affect/load/equanimity pattern not in canonical set "
                + "(affectΔ=" + fmt(affectDelta)
                + ", allostaticΔ=" + fmt(allostaticDelta)
                + ", equanimityΔ=" + fmt(equanimityDelta) + ")",
            affectDelta, allostaticDelta, equanimityDelta);
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }
}
