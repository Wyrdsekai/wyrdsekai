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
        if (v == null || d == null) return d;

        // Accumulate raw additions per drive index — sum first, clamp last (§13.3).
        double[] add = new double[DriveConfig.DRIVE_COUNT];

        // §3.1 Restlessness ≥0.7 → SEEKING+0.3, PLAY+0.2.
        if (v.restlessness() >= RESTLESSNESS_THRESHOLD) {
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
        if (v.loneliness() >= LONELINESS_THRESHOLD) {
            add[DriveConfig.AFFILIATION] += 0.3;
        }

        // §3.3 Stagnation ≥0.7 → SEEKING+0.2, FRUSTRATION+0.2.
        if (v.stagnation() >= STAGNATION_THRESHOLD) {
            add[DriveConfig.SEEKING]     += 0.2;
            add[DriveConfig.FRUSTRATION] += 0.2;
        }

        // §3.4 AutonomyPressure ≥0.7 → CREATIVITY+0.2 (during ON_OWN_TIME bias to self-initiate).
        // The "self-initiate bias" is a behavior-layer concern (ProactivityJudgment), not a
        // drive number — Phase 1B surfaces only the CREATIVITY bump.
        if (v.autonomyPressure() >= AUTONOMY_PRESSURE_THRESHOLD) {
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
        if (v.amae() >= AMAE_THRESHOLD) {
            add[DriveConfig.AFFILIATION] += 0.2;
            add[DriveConfig.GRIEF]       += 0.1;
        }

        // §4.2 Saudade ≥0.7 → AFFILIATION+0.3 (per-bondholder synthetic — global summary here).
        if (v.saudade() >= SAUDADE_THRESHOLD) {
            add[DriveConfig.AFFILIATION] += 0.3;
        }

        // §4.3 Obligation ≥0.6 → CARE+0.3.
        if (v.obligation() >= OBLIGATION_THRESHOLD) {
            add[DriveConfig.CARE] += 0.3;
        }

        // §5.1 Harmony ≥0.6 → CARE+0.2, AFFILIATION+0.1. (Withdrawal-to-Hearth at ≥0.85 is a
        // behavioral effect, not a drive value — handled by ProactivityJudgment in a later
        // pass.)
        if (v.harmony() >= HARMONY_THRESHOLD) {
            add[DriveConfig.CARE]        += 0.2;
            add[DriveConfig.AFFILIATION] += 0.1;
        }

        // §5.2 Standing ≥0.7 → VIGILANCE+0.2, FRUSTRATION+0.1. (Withdraw / formal register at
        // ≥0.9 is a behavior-layer concern, not a drive bump.)
        if (v.standing() >= STANDING_THRESHOLD) {
            add[DriveConfig.VIGILANCE]   += 0.2;
            add[DriveConfig.FRUSTRATION] += 0.1;
        }

        // Apply additive sums on top of current drive state, post-sum clamping handled by
        // DriveState.fromArray which clamps via spike(...) but we sum-then-set so we avoid
        // intermediate-clamp loss-of-information.
        double[] cur = d.toArray();
        double[] out = new double[cur.length];
        for (int i = 0; i < cur.length; i++) {
            double v2 = cur[i] + add[i];
            if (v2 < 0.0) v2 = 0.0;
            if (v2 > 1.0) v2 = 1.0;
            out[i] = v2;
        }
        return DriveState.fromArray(out);
    }
}
