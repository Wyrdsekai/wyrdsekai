package org.wyrdsekai.common.embodiment;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Layer 5 — the time-of-day phase a zone's ambient state
 * cycles through.
 *
 * <p>Four phases cover the day cleanly: DAWN (early light), MIDDAY (peak
 * activity), DUSK (settling), NIGHT (rest). The {@link org.wyrdsekai.core.room.WorldClock}
 * actor owns the per-zone authoritative phase and emits {@code AmbientChanged}
 * world events on transition; {@link AmbientRenderer} (in {@code core}) reads
 * the phase to choose room descriptors and tank imprints.
 *
 * <p>Default mapping from wall-clock hour to phase (system zone), used by
 * {@link #fromInstant} when the clock isn't being driven by tests:
 * <ul>
 *   <li>05:00–10:59 → DAWN</li>
 *   <li>11:00–16:59 → MIDDAY</li>
 *   <li>17:00–20:59 → DUSK</li>
 *   <li>21:00–04:59 → NIGHT</li>
 * </ul>
 */
public enum AmbientPhase {
    DAWN,
    MIDDAY,
    DUSK,
    NIGHT;

    /**
     * Map an {@link Instant} to its phase using the system default zone.
     * Tests that need a deterministic mapping pass an explicit {@link ZoneId}
     * via {@link #fromInstant(Instant, ZoneId)}.
     */
    public static AmbientPhase fromInstant(Instant at) {
        return fromInstant(at, ZoneId.systemDefault());
    }

    /** Map an {@link Instant} to its phase in the given zone. */
    public static AmbientPhase fromInstant(Instant at, ZoneId zone) {
        var hour = at.atZone(zone).getHour();
        return fromHour(hour);
    }

    /** Map an hour-of-day (0–23) to a phase. */
    public static AmbientPhase fromHour(int hour) {
        if (hour >= 5 && hour < 11) return DAWN;
        if (hour >= 11 && hour < 17) return MIDDAY;
        if (hour >= 17 && hour < 21) return DUSK;
        return NIGHT;
    }

    /** Lowercase key used for i18n (e.g. {@code room.library.ambient.dawn}). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Reverse lookup; null on unknown input. */
    public static AmbientPhase ofKey(String key) {
        if (key == null) return null;
        try {
            return AmbientPhase.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Compute the phase as if every full day fit into a compressed
     * {@code dayLengthSeconds} window. Used by tests that drive the clock
     * synthetically: a 4-second "day" gives 1 second per phase, so a tick
     * loop can sweep all four phases in a handful of polls.
     *
     * @param epochSecondsInDay seconds elapsed within the synthetic day
     *                          (modulo the day length)
     * @param dayLengthSeconds  total length of one synthetic day; must be > 0
     */
    public static AmbientPhase syntheticPhase(long epochSecondsInDay, long dayLengthSeconds) {
        if (dayLengthSeconds <= 0) throw new IllegalArgumentException("dayLengthSeconds must be > 0");
        var quarter = dayLengthSeconds / 4L;
        if (quarter <= 0) quarter = 1L;
        var bucket = (epochSecondsInDay % dayLengthSeconds) / quarter;
        if (bucket >= 4) bucket = 3; // guard against rounding to ==4 at boundary
        return values()[(int) bucket];
    }

    /** UTC zone, useful for reproducible tests. */
    public static final ZoneId UTC = ZoneOffset.UTC;

    /** Construct a {@link LocalTime} at the canonical center of this phase, for fixtures. */
    public LocalTime canonicalLocalTime() {
        return switch (this) {
            case DAWN -> LocalTime.of(7, 30);
            case MIDDAY -> LocalTime.of(13, 0);
            case DUSK -> LocalTime.of(18, 30);
            case NIGHT -> LocalTime.of(23, 0);
        };
    }
}
