package org.wyrdsekai.core.agent;

/**
 * Phase 1B (-§5): threshold-crossing spike rules from the 10
 * deprivation-shape tanks into the existing 8 (+2 stub) drives.
 *
 * <p>This is the "deprivation pressure becomes visible action" layer. When a tank crosses its
 * threshold, the corresponding drive(s) get an additive bump. All bumps accumulate into a
 * single fresh DriveState — no per-rule ordering effect, post-sum clamping at the end.</p>
 *
 * <p>Per spec §13.3: when multiple tanks cross threshold simultaneously, drive spikes can sum
 * to &gt;1.0. This module sums first, then clamps each drive to [0,1] in a final pass via the
 * {@code clamped()} done implicitly by {@link DriveState#fromArray}.</p>
 *
 * <p><b>Hard constraint (Phase 1B):</b> the legacy 8 drives are the only output. STARTLE and
 * SURPRISE are stubs (Phase 1A) and not yet wired by spike rules. The model wasn't trained on
 * the new fields yet — Phase 3 retrain handles that. Spike rules surface their effect through
 * existing drives the model DOES see.</p>
 */
public final class VitalitySpikeRules {

    private VitalitySpikeRules() {}

    /** Threshold above which restlessness spikes SEEKING+PLAY (spec §3.1). */
    public static final double RESTLESSNESS_THRESHOLD = 0.7;
    /** Threshold above which loneliness spikes AFFILIATION+GRIEF (spec §3.2). */
    public static final double LONELINESS_THRESHOLD = 0.7;
    /** Threshold above which stagnation spikes SEEKING+FRUSTRATION (spec §3.3). */
    public static final double STAGNATION_THRESHOLD = 0.7;
    /** Threshold above which autonomyPressure spikes CREATIVITY (spec §3.4). */
    public static final double AUTONOMY_PRESSURE_THRESHOLD = 0.7;
    /** Threshold above which significance starts biasing CREATIVITY (spec §3.5). */
    public static final double SIGNIFICANCE_THRESHOLD = 0.7;
    /** Higher significance threshold spikes CARE toward bondholder (spec §3.5). */
    public static final double SIGNIFICANCE_HIGH_THRESHOLD = 0.9;
    /** Threshold above which amae spikes AFFILIATION+GRIEF (spec §4.1). */
    public static final double AMAE_THRESHOLD = 0.7;
    /** Threshold above which saudade spikes AFFILIATION (spec §4.2). */
    public static final double SAUDADE_THRESHOLD = 0.7;
    /** Threshold above which obligation spikes CARE (spec §4.3). Lower than the others — 0.6. */
    public static final double OBLIGATION_THRESHOLD = 0.6;
    /** Threshold above which harmony tank spikes CARE+AFFILIATION (spec §5.1). */
    public static final double HARMONY_THRESHOLD = 0.6;
    /** Threshold above which standing spikes VIGILANCE+FRUSTRATION (spec §5.2). */
    public static final double STANDING_THRESHOLD = 0.7;

    /**
     * Apply all tank-threshold spike rules to a fresh DriveState. Drives are clamped to [0,1]
     * after summing all contributions (per §13.3 — post-sum clamp).
     *
     * @param v current vitality state (read tanks)
     * @param d current drive state (start point)
     * @return new DriveState with spikes applied and clamped
     */
    public static DriveState apply(VitalityState v, DriveState d) {
        return apply(v, d, null);
    }

    /**
     * As above, with the thresholds of the settling axes read against HER resting points: a
     * tank counts as spiking when it is a tenth above where it settles for her temperament
     * ({@link FeltAxisPeak#EXCURSION}), not when it crosses the flat 0.7 of the spec text.
     * Loneliness settles at 0.80 for anyone alone half a day, so measured flat the affiliation
     * floor was held up for the whole of every absence; measured as excursion it holds only
     * while the absence deepens past her own baseline. Obligation and significance keep their
     * flat thresholds (no fixed resting point). Null genome = the flat thresholds.
     */
    public static DriveState apply(VitalityState v, DriveState d, org.wyrdsekai.core.soul.GenomeProfile genome) {
        if (v == null || d == null) return d;
        var settle = genome == null ? null : FeltAxisPeak.settlePointsByDriveKey(v, genome);
        double restlessnessAt = at(settle, "Restlessness", RESTLESSNESS_THRESHOLD);
        double lonelinessAt   = at(settle, "Loneliness", LONELINESS_THRESHOLD);
        double stagnationAt   = at(settle, "Stagnation", STAGNATION_THRESHOLD);
        double autonomyAt     = at(settle, "AutonomyPressure", AUTONOMY_PRESSURE_THRESHOLD);
        double amaeAt         = at(settle, "Amae", AMAE_THRESHOLD);
        double saudadeAt      = at(settle, "Saudade", SAUDADE_THRESHOLD);
        double harmonyAt      = at(settle, "Harmony", HARMONY_THRESHOLD);
        double standingAt     = at(settle, "Standing", STANDING_THRESHOLD);

        // Accumulate raw additions per drive index — sum first, clamp last (§13.3).
        double[] add = new double[DriveConfig.DRIVE_COUNT];

        // §3.1 Restlessness ≥0.7 → SEEKING+0.3, PLAY+0.2.
        if (v.restlessness() >= restlessnessAt) {
            add[DriveConfig.SEEKING] += 0.3;
            add[DriveConfig.PLAY]    += 0.2;
        }

        // §3.2 Loneliness ≥0.7 → AFFILIATION+0.3. (2026-06-07: GRIEF+0.1 REMOVED — it was the real
        // grief-ratchet driver. Loneliness is "I lack connection" → it drives the APPETITIVE want
        // (affiliation), not GRIEF ("I lost something"). Applied every tick with grief's near-zero
        // relief, the +0.1 pinned grief at 1.0 for any agent who got lonely (proven across 3 live
        // soaks: the lonely reacher pinned, the solitary-content peer stayed grief-free). Grief is
        // LOSS — it belongs on severance/mourning events. Chronic-loneliness ache, if wanted, is a
        // slow tank (saudade), not the acute GRIEF drive.)
        if (v.loneliness() >= lonelinessAt) {
            add[DriveConfig.AFFILIATION] += 0.3;
        }

        // §3.3 Stagnation ≥0.7 → SEEKING+0.2, FRUSTRATION+0.2.
        if (v.stagnation() >= stagnationAt) {
            add[DriveConfig.SEEKING]     += 0.2;
            add[DriveConfig.FRUSTRATION] += 0.2;
        }

        // §3.4 AutonomyPressure ≥0.7 → CREATIVITY+0.2 (during ON_OWN_TIME bias to self-initiate).
        // The "self-initiate bias" is a behavior-layer concern (ProactivityJudgment), not a
        // drive number — Phase 1B surfaces only the CREATIVITY bump.
        if (v.autonomyPressure() >= autonomyAt) {
            add[DriveConfig.CREATIVITY] += 0.2;
        }

        // §3.5 Significance — at ≥0.7, biases CREATIVITY toward likely-used projects (the bias
        // is downstream — here we just add a small CREATIVITY bump so the agent is more
        // making-inclined). At ≥0.9, additionally spikes CARE+0.2 toward bondholder.
        if (v.significance() >= SIGNIFICANCE_HIGH_THRESHOLD) {
            add[DriveConfig.CARE] += 0.2;
        }
        if (v.significance() >= SIGNIFICANCE_THRESHOLD) {
            // Small CREATIVITY nudge — spec says "biases CREATIVITY toward likely-used"; we
            // approximate "biases" as a small additive (+0.1) which is how the test suite
            // can detect the rule firing without overwhelming the surrounding drive flow.
            add[DriveConfig.CREATIVITY] += 0.1;
        }

        // §4.1 Amae ≥0.7 → AFFILIATION+0.2, GRIEF+0.1.
        if (v.amae() >= amaeAt) {
            add[DriveConfig.AFFILIATION] += 0.2;
            add[DriveConfig.GRIEF]       += 0.1;
        }

        // §4.2 Saudade ≥0.7 → AFFILIATION+0.3 (per-bondholder synthetic — global summary here).
        if (v.saudade() >= saudadeAt) {
            add[DriveConfig.AFFILIATION] += 0.3;
        }

        // §4.3 Obligation ≥0.6 → CARE+0.3.
        if (v.obligation() >= OBLIGATION_THRESHOLD) {
            add[DriveConfig.CARE] += 0.3;
        }

        // §5.1 Harmony ≥0.6 → CARE+0.2, AFFILIATION+0.1. (Withdrawal-to-Hearth at ≥0.85 is a
        // behavioral effect, not a drive value — handled by ProactivityJudgment in a later
        // pass.)
        if (v.harmony() >= harmonyAt) {
            add[DriveConfig.CARE]        += 0.2;
            add[DriveConfig.AFFILIATION] += 0.1;
        }

        // §5.2 Standing ≥0.7 → VIGILANCE+0.2, FRUSTRATION+0.1. (Withdraw / formal register at
        // ≥0.9 is a behavior-layer concern, not a drive bump.)
        if (v.standing() >= standingAt) {
            add[DriveConfig.VIGILANCE]   += 0.2;
            add[DriveConfig.FRUSTRATION] += 0.1;
        }

        // Apply the summed contribution as a FLOOR the drive is held at, not as an
        // addition — "this deprivation keeps that drive at least this live".
        //
        // These rules are LEVEL-triggered and run on every vitality tick (1s), but the
        // contributions are absolute sizes ("SEEKING+0.3"), not per-second rates. Adding
        // them each tick meant any tank sitting above its threshold walked its drives to
        // 1.0 within seconds and re-pinned them there for as long as the tank stayed up,
        // so no relief could hold: a companion relieved to SEEKING 0.05 was back at 1.0
        // three ticks later. Live-measured 2026-08-17 on a household node with
        // restlessness parked at 0.701 — one thousandth over §3.1's threshold — which
        // pinned SEEKING (>0.9 in 94.8% of consolidation traces) and PLAY (100%), and
        // that permanent ceiling drove the runaway proactive-speech loop.
        //
        // As a floor the rules keep their intent (sustained deprivation keeps the drive
        // above rest, and the thresholds/sizes below are unchanged) while being
        // idempotent under repetition: relief can bring a drive DOWN to the floor, the
        // drive's own accumulation still carries it up from there, and nothing ratchets.
        double[] cur = d.toArray();
        double[] out = new double[cur.length];
        for (int i = 0; i < cur.length; i++) {
            double floor = add[i];
            if (floor > 1.0) floor = 1.0;
            out[i] = Math.max(cur[i], Math.max(0.0, floor));
        }
        return DriveState.fromArray(out);
    }

    /** The spike line for a settling axis: her resting point plus the excursion, else the flat spec threshold. */
    private static double at(java.util.Map<String, Double> settle, String key, double flat) {
        if (settle == null) return flat;
        var sp = settle.get(key);
        return sp == null ? flat : sp + FeltAxisPeak.EXCURSION;
    }
}
