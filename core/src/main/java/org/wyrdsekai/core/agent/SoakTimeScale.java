package org.wyrdsekai.core.agent;

import java.time.Duration;

/**
 * SOAK-ONLY time-compression knob for the will-they-live boredom soak. Lets a
 * live zone run its gap-time loop (boredom accumulation → OODA wake → outward
 * action → drain → settle) in compressed wall-clock so a multi-day soak finishes
 * in minutes. Inference still runs at native speed — only the <i>clocks</i> that
 * feed tank accumulation and the OODA cadence gate are scaled, so the soak's
 * floor is decision-count × inference-latency, not real elapsed time.
 *
 * <pre>  WYRD_SOAK_TIME_SCALE=288   (env)   or   -Dwyrd.soak.time.scale=288  (sysprop)</pre>
 *
 * <p>288 ≈ 1 real second = 4.8 sim-minutes (a real day per 5 real minutes).
 * Default <b>1.0</b> (absent / unparseable / &lt;1) = real time, and every scaled
 * call site is a no-op — production behavior is byte-for-byte unchanged.
 *
 * <p><b>THIS IS NOT MEANT TO SHIP.</b> It exists to run the will-they-live /
 * welfare-floor soaks. To rip it out:
 * <ol>
 *   <li>{@code grep -rl SoakTimeScale core/} — three scaled call sites in
 *       {@code CompanionActor} ({@code onVitalityTick} dt + soak OODA driver,
 *       {@code buildAccumulationContext} since-clocks, {@code runInteriorityTick}
 *       cadence gate).</li>
 *   <li>Revert each to its pre-soak form (drop the {@code SoakTimeScale} factor).</li>
 *   <li>Delete this file.</li>
 * </ol>
 *
 * <p>Read live (no caching) so a soak can toggle it via system property without
 * classload-ordering hazards.
 */
public final class SoakTimeScale {

    private SoakTimeScale() {}

    /** Compression factor ≥ 1.0; 1.0 (default) means real time. */
    public static double factor() {
        String raw = System.getenv("WYRD_SOAK_TIME_SCALE");
        if (raw == null) raw = System.getProperty("wyrd.soak.time.scale");
        if (raw == null) return 1.0;
        try {
            double f = Double.parseDouble(raw.trim());
            return f >= 1.0 ? f : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    /** True only when compression is active (factor &gt; 1) — i.e. a soak is running. */
    public static boolean active() {
        return factor() > 1.0;
    }

    /** Scale a real elapsed duration into compressed sim-time (saturating, never negative). */
    public static Duration compress(Duration real) {
        double f = factor();
        if (f <= 1.0 || real == null || real.isNegative()) return real;
        double simNanos = real.toNanos() * f;
        long capped = simNanos >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : (long) simNanos;
        return Duration.ofNanos(capped);
    }
}
