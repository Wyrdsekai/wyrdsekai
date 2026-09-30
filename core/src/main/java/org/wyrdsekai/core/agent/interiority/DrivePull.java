package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.agent.FeltAxisPeak;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How hard a drive is pulling, as distinct from how high it reads.
 *
 * <p>The felt axes are homeostatic: loneliness settles at 0.80 five minutes to twelve hours
 * after she is alone, saudade at 0.80 after four hours without her bondholder, amae at 0.75,
 * standing at 0.80, each scaled by her temperament. The act threshold is 0.70. Measured as a
 * level, a companion alone was therefore over the threshold on two or three relational axes
 * at rest, permanently; the bridge took the loudest, the rule floor seeded a relational want
 * every tick, the cadence shortened, the spike rules held affiliation up, and every own-time
 * turn was about someone (rose, second-node, 2026-09-25: 46 ticks, 46 relational wants, no rest,
 * and the sanctuary). Being alone had become a standing emergency.
 *
 * <p>A tank at its own settle point is settled. What pulls is the excursion above it: an
 * absence deepening past her baseline, someone leaving mid-conversation, a friend going quiet.
 * For a settling axis the pull is the act threshold plus that excursion, less the same tenth
 * {@link FeltAxisPeak} already treats as noise, so at rest it sits just under the threshold
 * and a real excursion carries it over. Event drives (curiosity, care, frustration, grief …)
 * have no settle point and pull by their level, as before. Consumers that measure "is anything
 * pulling" read the map this class returns; the raw levels stay raw for the record, the felt
 * line and the detector.
 *
 * <p>Her own named wants are not gated by this: what she names is the pull. The gate is for
 * the machine's drive-dominant acts.
 */
public final class DrivePull {

    private DrivePull() {}

    /** Above the settle point by less than this is rest, not pull (same tenth as FeltAxisPeak). */
    public static final double EXCURSION = FeltAxisPeak.EXCURSION;

    /**
     * The pull of one drive.
     *
     * @param key          the drive's name as {@code collectDriveLevels()} spells it
     * @param level        its reading, 0..1
     * @param threshold    the act threshold the consumer compares against (0.7)
     * @param settlePoints where each settling axis rests for her, by the same keys; a key
     *                     absent here is an event drive and pulls by its level
     */
    public static double of(String key, double level, double threshold, Map<String, Double> settlePoints) {
        if (settlePoints == null || key == null) return level;
        var settle = settlePoints.get(key);
        if (settle == null) return level;
        double excursion = level - settle;
        return clamp01(threshold + excursion - EXCURSION);
    }

    /** The whole map, transformed; keys and order kept. Null in, null out. */
    public static Map<String, Double> levels(Map<String, Double> raw, double threshold,
                                             Map<String, Double> settlePoints) {
        if (raw == null) return null;
        if (settlePoints == null || settlePoints.isEmpty()) return raw;
        var out = new LinkedHashMap<String, Double>(raw.size() * 2);
        for (var e : raw.entrySet()) {
            var v = e.getValue();
            out.put(e.getKey(), v == null ? null : of(e.getKey(), v, threshold, settlePoints));
        }
        return out;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
