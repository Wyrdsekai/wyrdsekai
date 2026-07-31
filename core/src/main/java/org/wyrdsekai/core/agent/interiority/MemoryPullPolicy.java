package org.wyrdsekai.core.agent.interiority;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * variable-N random memory pull.
 *
 * <p>"Lightest touch" means we don't engineer the formula precisely. Hand the
 * model its state, let it implicitly do the right thing. But we DO need to
 * decide how many memories to surface this tick. That number is sometimes 0,
 * sometimes 5, modulated by:
 *
 * <ul>
 *   <li>energy high → more (rested mind wanders further)
 *   <li>capacity high → more (room to hold them)
 *   <li>prior action reflective → more (reflective tick begets reflection)
 *   <li>prior action intense → fewer (focused mind stays focused)
 *   <li>high Curiosity → wider (will explored further later in retrieval)
 *   <li>high Frustration → narrower (stays close to the pain point)
 *   <li>pre-sleep → more (dream-shaped consolidation)
 * </ul>
 *
 * <p>The output of this class is just an {@code int N ∈ [0, 5]}. Where those
 * N memories come from is the retrieval surface's problem — by default a
 * random selection from the agent's medium-term memory store.
 */
public final class MemoryPullPolicy {

    private MemoryPullPolicy() {}

    /** Maximum random pulls per tick — guards against runaway costs. */
    public static final int MAX_PULLS = 5;

    /**
     * Decide how many random memories to surface this tick.
     *
     * @param energy            0..1 (high → more)
     * @param capacity          0..1 (high → more)
     * @param priorActionLabel  "rest"|"reflect"|"intense"|"none"|null
     * @param driveLevels       tank values — Curiosity/Frustration are read if present
     * @param preSleep          true if energy under sleep threshold
     * @return number of memory pulls to perform ∈ [0, MAX_PULLS]
     */
    public static int decideN(double energy,
                              double capacity,
                              String priorActionLabel,
                              Map<String, Double> driveLevels,
                              boolean preSleep) {
        // Start from a base shaped by energy + capacity.
        double base = 1.0 + 2.0 * clamp01(energy) + 1.0 * clamp01(capacity);

        // Reflective prior → more wandering; intense prior → less.
        if ("reflect".equalsIgnoreCase(priorActionLabel)) base += 1.0;
        else if ("intense".equalsIgnoreCase(priorActionLabel)) base -= 1.0;
        else if ("rest".equalsIgnoreCase(priorActionLabel)) base += 0.5;

        // Drive modulators (only read what's present).
        if (driveLevels != null) {
            base += 0.7 * lookupNorm(driveLevels, "Curiosity");
            base -= 0.5 * lookupNorm(driveLevels, "Frustration");
        }

        // Pre-sleep ticks get extra (dream-shaped consolidation).
        if (preSleep) base += 1.0;

        // Add light jitter so consecutive ticks aren't identical.
        base += ThreadLocalRandom.current().nextDouble(-0.4, 0.4);

        int n = (int) Math.round(base);
        if (n < 0) n = 0;
        if (n > MAX_PULLS) n = MAX_PULLS;
        return n;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    /**
     * Look up a drive value with case-insensitive matching, normalized to 0..1.
     * Returns 0 if absent.
     */
    private static double lookupNorm(Map<String, Double> drives, String key) {
        if (drives == null || key == null) return 0;
        for (var e : drives.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return clamp01(e.getValue() != null ? e.getValue() : 0);
            }
        }
        return 0;
    }
}
