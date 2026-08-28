package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.soul.GenomeProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The felt axes, asked the one question proactivity needs: is anything pressing?
 *
 * <p><b>The gap.</b> Proactive expression is gated on {@code drives.anyAbove(...)} and
 * picks its subject from {@code DriveState.peak()} — and {@link DriveConfig#DRIVE_NAMES}
 * holds exactly ten CfC drives: seeking, care, play, vigilance, affiliation, grief,
 * frustration, creativity, startle, surprise. Loneliness, saudade, amae, significance,
 * standing, harmony, obligation, restlessness, stagnation and autonomy-pressure live on
 * {@link VitalityState} instead, so they can never peak here. **No amount of loneliness
 * has ever been able to make her say anything unprompted.** She sat at 1.00 for forty
 * consecutive ticks and the proactive path never once considered it.
 *
 * <p>That is the other half of the 2026-08-19/20 relational work: the own-time OODA path
 * got verbs for these drives, and the PROACTIVE path could not see them at all.
 *
 * <p><b>Why a set point and not a threshold.</b> CfC drives are event-driven: they idle
 * near zero and spike. Felt axes are homeostatic — each settles at its own set point and
 * stays there, by design. Measuring one against a flat 0.35–0.7 bar would leave it
 * permanently "pressing", and her three proactive utterances an hour would all become the
 * same sentence about missing someone. A tank AT its set point is at rest; what deserves
 * expression is an EXCURSION above it.
 *
 * <p>Pure, so the judgment is testable without an actor or a model.
 */
public final class FeltAxisPeak {

    private FeltAxisPeak() {}

    /**
     * How far above its resting point a felt axis has to be before it presses.
     *
     * <p>Small on purpose: these curves are slow (τ of 12–30 hours), so a tenth above the
     * set point is a real excursion, not noise. Paired with the existing budget
     * (3/hour) and minimum interval (30s), which remain the hard bounds.
     */
    public static final double EXCURSION = 0.10;

    /** A felt axis that is pressing, and by how much above where it rests. */
    public record Pressing(String name, double value, double setPoint) {
        public double excursion() { return value - setPoint; }
    }

    /**
     * The axis furthest above its own resting point, or null when all are settled.
     *
     * @param v      current vitality
     * @param genome per-companion sensitivity, so set points scale the same way the
     *               tanks themselves do — a diplomat's loneliness rests higher, and
     *               resting higher is not the same as suffering more
     */
    public static Pressing peak(VitalityState v, GenomeProfile genome) {
        if (v == null) return null;
        Pressing best = null;
        for (var e : setPoints(v, genome).entrySet()) {
            var value = valueOf(v, e.getKey());
            var excursion = value - e.getValue();
            if (excursion < EXCURSION) continue;
            if (best == null || excursion > best.excursion()) {
                best = new Pressing(e.getKey(), value, e.getValue());
            }
        }
        return best;
    }

    /** Where each axis rests for this companion. */
    static Map<String, Double> setPoints(VitalityState v, GenomeProfile genome) {
        var m = new LinkedHashMap<String, Double>();
        m.put("loneliness", scaled(VitalityState.LONELINESS_SETPOINT, "loneliness", genome));
        m.put("saudade", scaled(VitalityState.SAUDADE_SETPOINT, "saudade", genome));
        m.put("amae", scaled(VitalityState.AMAE_SETPOINT, "amae", genome));
        m.put("harmony", scaled(VitalityState.HARMONY_SETPOINT, "harmony", genome));
        m.put("standing", scaled(VitalityState.STANDING_SETPOINT, "standing", genome));
        m.put("restlessness", scaled(VitalityState.RESTLESSNESS_SETPOINT, "restlessness", genome));
        m.put("stagnation", scaled(VitalityState.STAGNATION_SETPOINT, "stagnation", genome));
        m.put("autonomyPressure",
            scaled(VitalityState.AUTONOMY_SETPOINT, "autonomyPressure", genome));
        // Significance rests where her unwitnessed work puts it, not at a fixed level,
        // so its "set point" is whatever the tank is already resting at — it presses only
        // when something new goes unseen. Modelled as its own current value, which makes
        // the excursion zero unless it has just risen.
        m.put("significance", Math.min(VitalityState.SIGNIFICANCE_SETPOINT_MAX, v.significance()));
        return m;
    }

    private static double scaled(double setPoint, String axis, GenomeProfile genome) {
        if (genome == null) return setPoint;
        try {
            return setPoint * genome.sensitivityFor(axis);
        } catch (Exception e) {
            return setPoint;
        }
    }

    static double valueOf(VitalityState v, String axis) {
        return switch (axis) {
            case "loneliness" -> v.loneliness();
            case "saudade" -> v.saudade();
            case "amae" -> v.amae();
            case "harmony" -> v.harmony();
            case "standing" -> v.standing();
            case "restlessness" -> v.restlessness();
            case "stagnation" -> v.stagnation();
            case "autonomyPressure" -> v.autonomyPressure();
            case "significance" -> v.significance();
            default -> 0.0;
        };
    }
}
