package org.wyrdsekai.core.parlor;

import java.time.Duration;

/**
 * Presence-rendering mode for a Parlor room.
 *
 * <p>A Parlor with 5 visitors should feel like a room; one with 500 should
 * feel like a town square. Both should remain cheap to render and pleasant
 * to read. The mode is chosen by occupancy, not by operator config, and the
 * Parlor transitions between modes as visitors arrive and leave.</p>
 *
 * <p>Thresholds are defined with hysteresis gaps: a Parlor transitions UP
 * at {@link #upThreshold()}, but only transitions DOWN once occupancy drops
 * to {@link #downThreshold()} (3 below the up-threshold of the next mode).
 * This prevents thrashing at mode boundaries. See {@link ParlorAutoScaler}.</p>
 */
public enum ParlorPresenceMode {

    /**
     * 1-10 occupants. Everyone sees every say/emote immediately; entrance
     * and departure narration for each arrival; companion ambient greetings
     * enabled. This is the default "small room" feel.
     */
    FULL(1, 1),

    /**
     * 11-30 occupants. Only the N most-recent speakers are rendered as a
     * live stream. Entrance narration is batched. Companion greets arrivals
     * in batches.
     */
    SAMPLED(11, 8),

    /**
     * 31-100 occupants. As {@link #SAMPLED} plus: strict content dedup,
     * mandatory 1s inter-utterance cooldown, entrance narration suppressed
     * (visible only via roll-call), companion responds to direct-address only.
     */
    SAMPLED_STRICT(31, 28),

    /**
     * 101-500 occupants. Only direct-address (@name say …, tell, whisper)
     * and companion-moderated announcements reach the full room. Other say
     * traffic surfaces as a digest. Entrance/departure invisible.
     */
    FIREHOSE(101, 98);

    /**
     * Hard ceiling on concurrent unique DIDs in a single Parlor. Past this,
     * new arrivals queue in the Docks antechamber (§2.8.1 DoS safety cap).
     */
    public static final int MAX_OCCUPANTS = 500;

    /**
     * Dwell window — how long a Parlor must sustain occupancy at a new
     * threshold before the transition fires. Prevents flapping around the
     * boundary. Spec §2.8.1: "60-second dwell".
     */
    public static final Duration TRANSITION_DWELL = Duration.ofSeconds(60);

    private final int upThreshold;
    private final int downThreshold;

    ParlorPresenceMode(int upThreshold, int downThreshold) {
        this.upThreshold = upThreshold;
        this.downThreshold = downThreshold;
    }

    /** Minimum occupancy required to enter this mode from below. */
    public int upThreshold() {
        return upThreshold;
    }

    /**
     * Occupancy at which we drop OUT of this mode back to the one below.
     * Set 3 below the upThreshold — i.e. SAMPLED (upThreshold 11) drops
     * back to FULL once occupancy ≤ 8. The gap is the hysteresis band.
     */
    public int downThreshold() {
        return downThreshold;
    }

    /**
     * @return the next-higher mode, or {@code null} if already at the top.
     */
    public ParlorPresenceMode higher() {
        var values = values();
        int idx = ordinal();
        return idx + 1 < values.length ? values[idx + 1] : null;
    }

    /**
     * @return the next-lower mode, or {@code null} if already at {@link #FULL}.
     */
    public ParlorPresenceMode lower() {
        int idx = ordinal();
        return idx > 0 ? values()[idx - 1] : null;
    }

    /**
     * Pick a mode purely from occupancy, ignoring hysteresis and dwell.
     * Used at Parlor creation (no prior state) and in unit tests that want
     * the "instantaneous" mode for a given count.
     */
    public static ParlorPresenceMode forOccupancy(int occupancy) {
        if (occupancy >= FIREHOSE.upThreshold) return FIREHOSE;
        if (occupancy >= SAMPLED_STRICT.upThreshold) return SAMPLED_STRICT;
        if (occupancy >= SAMPLED.upThreshold) return SAMPLED;
        return FULL;
    }
}
