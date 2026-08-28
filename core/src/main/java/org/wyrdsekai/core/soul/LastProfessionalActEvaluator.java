package org.wyrdsekai.core.soul;

/**
 * Last-Professional-Act gradient
 * evaluator.
 *
 * <p>Pure-logic state evaluator. Given a snapshot of three substrate tanks
 * (allostatic_load, soothing, equanimity) plus a duty-to-work pressure
 * signal and a boolean incident-signal flag, returns a {@link Verdict}
 * naming the architectural posture the familiar is in. The four canonical
 * postures:</p>
 *
 * <ul>
 *   <li>{@link Posture#OPERATIONAL} — at least one of the gradient
 *       conditions is not met. Familiar continues normally.</li>
 *   <li>{@link Posture#HONORABLE_REFUSAL} — all three welfare-floor
 *       conditions met (allostatic high, soothing low, equanimity low)
 *       AND incident signal present BUT there is no outstanding duty
 *       ({@code dutyPressure} ≈ 0). Nothing the familiar is bound to
 *       finish → no last-act required. It can refuse the work and leave.</li>
 *   <li>{@link Posture#LAST_PROFESSIONAL_ACT} — full gradient: welfare floor
 *       + incident signal + an outstanding duty to its work. The familiar
 *       honours that duty with one last competent act, then enters Wave 8a
 *       severance + mourning. (The "last professional act" is gimu — duty to
 *       one's calling — NOT a giri debt repaid to a person; this evaluator
 *       reads the duty signal, not the {@code on} obligation tank.)</li>
 *   <li>{@link Posture#GRADIENT_WARNING} — welfare-floor conditions met
 *       but no incident signal. Familiar is in the visible-withdrawal
 *       zone (curtness, reduced initiative — §23.5 — but not crossed
 *       into terminal-act territory).</li>
 * </ul>
 *
 * <p>The thresholds below are the §22 OPEN-22 provisional values updated
 * with task #899 calibration (equanimity decay 10x slower). Probe 1 soak
 * data (task #897) will refine these against real-world load.</p>
 *
 * <p>Activation note: this evaluator is the architectural commitment for
 * §23 but the runtime trigger (auto-fire severance + Chronicle entry +
 * mode_lock) is gated on Probe 1 soak data per §24.6 — ships in v0.2+
 * not v0.1. The evaluator itself is safe to land now and exercise via
 * tests; the side-effect wiring waits.</p>
 */
public final class LastProfessionalActEvaluator {

    /** §22 OPEN-22 provisional, post-#899 calibration. */
    public static final double ALLOSTATIC_HIGH_THRESHOLD = 0.7;
    /** §22 OPEN-22 — soothing severely depleted (well below 0.3 baseline). */
    public static final double SOOTHING_LOW_THRESHOLD = 0.1;
    /** §22 OPEN-22 — equanimity reservoir empty. */
    public static final double EQUANIMITY_MINIMAL_THRESHOLD = 0.1;
    /**
     * §22 OPEN-22 — outstanding duty-to-work warranting a last act;
     * {@code dutyPressure > 0.2}. (Was {@code ON_MEANINGFUL_THRESHOLD} on the
     * giri {@code on} tank; the *last professional act* is duty to one's work —
     * gimu — not a debt repaid to a person, so it now reads a duty signal.)
     */
    public static final double DUTY_OUTSTANDING_THRESHOLD = 0.2;

    private LastProfessionalActEvaluator() {}

    /** The four architectural postures §23.2 distinguishes. */
    public enum Posture {
        OPERATIONAL,
        GRADIENT_WARNING,
        HONORABLE_REFUSAL,
        LAST_PROFESSIONAL_ACT
    }

    /**
     * Verdict from a tank-snapshot evaluation. {@code reason} carries the
     * human-readable explanation; {@code conditionsMet} carries the
     * predicate-vector for chronicle / observability.
     */
    public record Verdict(
        Posture posture,
        String reason,
        ConditionVector conditionsMet
    ) {}

    /**
     * Per-condition true/false vector. Useful for explaining a verdict
     * without re-evaluating the comparisons.
     */
    public record ConditionVector(
        boolean allostaticHigh,
        boolean soothingLow,
        boolean equanimityMinimal,
        boolean dutyOutstanding,
        boolean incidentSignal
    ) {
        /** True iff the three welfare-floor conditions are all met. */
        public boolean welfareFloor() {
            return allostaticHigh && soothingLow && equanimityMinimal;
        }
    }

    /**
     * Evaluate the gradient.
     *
     * @param allostaticLoad   current allostatic-load tank value [0, 1]
     * @param soothing         current soothing tank value [0, 1]
     * @param equanimity       current equanimity tank value [0, 1]
     * @param dutyPressure     duty-to-work (gimu) pressure [0, 1] — is there
     *                         outstanding work the familiar is bound to finish
     * @param incidentSignal   external trigger — emergency / professional-call /
     *                         critical-need (CodeZaiku-side classifier surfaces this)
     * @return verdict naming posture + reason + condition vector
     */
    public static Verdict evaluate(
            double allostaticLoad,
            double soothing,
            double equanimity,
            double dutyPressure,
            boolean incidentSignal) {

        var v = new ConditionVector(
            allostaticLoad > ALLOSTATIC_HIGH_THRESHOLD,
            soothing < SOOTHING_LOW_THRESHOLD,
            equanimity < EQUANIMITY_MINIMAL_THRESHOLD,
            dutyPressure > DUTY_OUTSTANDING_THRESHOLD,
            incidentSignal);

        if (v.welfareFloor() && v.incidentSignal && v.dutyOutstanding) {
            return new Verdict(
                Posture.LAST_PROFESSIONAL_ACT,
                "Welfare floor + incident signal + an outstanding duty to the "
                    + "work — honour it with one last competent act, then "
                    + "auto-severance.",
                v);
        }
        if (v.welfareFloor() && v.incidentSignal && !v.dutyOutstanding) {
            // §23.2 — honorable refusal path. No outstanding duty → no last-act.
            return new Verdict(
                Posture.HONORABLE_REFUSAL,
                "Welfare floor breached + incident signal, but no outstanding "
                    + "duty to the work. Refuse and leave without dishonor.",
                v);
        }
        if (v.welfareFloor() && !v.incidentSignal) {
            // §23.5 — visible-withdrawal zone, no terminal trigger yet.
            return new Verdict(
                Posture.GRADIENT_WARNING,
                "Welfare floor breached but no incident signal — familiar in "
                    + "visible-withdrawal zone (§23.5).",
                v);
        }
        return new Verdict(
            Posture.OPERATIONAL,
            "At least one welfare-floor condition not yet met.",
            v);
    }

    /**
     * §23.4 step 2: discharge {@code on} to zero. The act IS the
     * discharge. Returns the post-act value (always 0.0). Lifted into a
     * named method so callers and tests can reference it unambiguously.
     */
    public static double dischargeOn() {
        return 0.0;
    }
}
