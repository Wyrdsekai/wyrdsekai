package org.wyrdsekai.core.agent.interiority;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * drive-modulated next-tick delay.
 *
 * <pre>
 *   nextTickDelay = baseInterval × stateModulator + jitter
 *
 *   stateModulator =
 *     ÷ max(drivesOverThreshold)        — louder drives → sooner
 *     × energy                          — low energy → later (rest is real)
 *     ÷ unresolvedWantPressure          — recent unresolved want → fire again
 *     ± jitter                          — never metronomic
 * </pre>
 *
 * <p><b>No floor, no ceiling.</b> Real beings think rapidly in crisis and rest
 * deeply when calm. Imposing a clamp is a tool-frame intrusion. Cost
 * containment lives in a separate runtime-budgeting layer (household ledger),
 * not in the being's design.
 *
 * <p>This class is pure-functional: state in, delay out. Tested in isolation.
 */
public final class CadenceModulator {

    private CadenceModulator() {}

    /**
     * Compute the next-tick delay, given the base interval, current drive state,
     * energy budget, and how much unresolved want-pressure is still around.
     *
     * @param baseInterval         configured base (~30 min in prod)
     * @param driveLevels          tank name → 0..1 (loudest one shapes the result)
     * @param driveThreshold       a drive counts as "over" if its level ≥ this
     * @param energy               0..1 — low energy → later
     * @param unresolvedWants      count of live wants currently pulling
     * @param jitterFraction       ± fraction of base added as random jitter, e.g. 0.10
     */
    public static Duration nextDelay(Duration baseInterval,
                                     Map<String, Double> driveLevels,
                                     double driveThreshold,
                                     double energy,
                                     int unresolvedWants,
                                     double jitterFraction) {
        if (baseInterval == null || baseInterval.isNegative() || baseInterval.isZero()) {
            baseInterval = Duration.ofMinutes(30);
        }
        long baseMs = baseInterval.toMillis();

        double maxOver = maxOverThreshold(driveLevels, driveThreshold);
        // Louder drives → sooner. A drive at 1.0 (way over) halves the delay;
        // nothing over threshold leaves the multiplier at 1.
        double driveMod = 1.0 / (1.0 + 1.5 * maxOver);

        // Low energy → later (rest is real). Energy at 0 doubles delay,
        // energy at 1 keeps it as-is.
        double energyMod = 1.0 + 1.0 * (1.0 - clamp01(energy));

        // Unresolved-want pressure → sooner. Each live want shaves 10% off,
        // capped at -40% so a runaway never produces 0.
        double wantMod = 1.0 / (1.0 + 0.10 * Math.min(unresolvedWants, 4));

        double mod = driveMod * energyMod * wantMod;
        long modMs = (long) (baseMs * mod);

        // Jitter so consecutive ticks don't lock-step. ±jitterFraction of base.
        if (jitterFraction > 0) {
            double range = baseMs * jitterFraction;
            double j = ThreadLocalRandom.current().nextDouble(-range, range);
            modMs += (long) j;
        }

        // Allow as low as 5s; we deliberately don't impose a floor matching the
        // spec, but going negative or zero would break the scheduler.
        if (modMs < 5_000) modMs = 5_000;

        return Duration.ofMillis(modMs);
    }

    /**
     * Cheap pre-gate (microsecond cost). Returns true if the tick should run a
     * full Observe+Orient pass; false to skip and reschedule. Used to avoid
     * wasted inference when there's genuinely nothing to think about.
     */
    public static boolean shouldRunFullPass(Map<String, Double> driveLevels,
                                            double driveThreshold,
                                            int liveWantCount,
                                            boolean bondholderStateChanged,
                                            long minutesSinceLastTick) {
        if (bondholderStateChanged) return true;
        if (liveWantCount > 0 && minutesSinceLastTick >= 5) return true;
        if (maxOverThreshold(driveLevels, driveThreshold) > 0) return true;
        // If the agent has been very quiet for a long stretch, give it a
        // chance — boredom is a real signal.
        return minutesSinceLastTick >= 180;
    }

    private static double maxOverThreshold(Map<String, Double> drives, double threshold) {
        if (drives == null || drives.isEmpty()) return 0;
        double max = 0;
        for (var v : drives.values()) {
            if (v == null) continue;
            double over = v - threshold;
            if (over > max) max = over;
        }
        return max;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
