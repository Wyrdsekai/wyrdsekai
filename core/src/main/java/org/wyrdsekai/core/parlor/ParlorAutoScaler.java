package org.wyrdsekai.core.parlor;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure-function auto-scaler for Parlor presence mode.
 *
 * <p>Given an incoming occupancy reading + the Parlor's current mode + the
 * time of its last mode change, decides whether to transition up, down, or
 * stay put. Hysteresis prevents thrashing around boundaries:</p>
 *
 * <ul>
 *   <li>Transition UP immediately when occupancy reaches the upper mode's
 *       {@link ParlorPresenceMode#upThreshold()}.</li>
 *   <li>Transition DOWN only after occupancy has been at/below the current
 *       mode's {@link ParlorPresenceMode#downThreshold()} for at least the
 *       dwell window ({@link ParlorPresenceMode#TRANSITION_DWELL}).</li>
 * </ul>
 *
 * <p>Asymmetry rationale: <b>UP is immediate</b> because a sudden influx
 * (school letting out, announcement) should silence the room before the say
 * stream drowns everyone. <b>DOWN is dwelled</b> because a short lull
 * around 10/11 shouldn't flap the UI every 3 seconds.</p>
 *
 * <p>This class returns a {@link Decision} but does not mutate anything;
 * the caller (a Parlor manager) applies the transition by publishing
 * diegetic narration and updating its cached mode. Keeping state outside
 * the scaler makes testing mechanical — feed in tuples, assert
 * transitions.</p>
 */
public final class ParlorAutoScaler {

    /** Transition outcome. */
    public sealed interface Decision
        permits Decision.Transition, Decision.NoChange, Decision.AtCap {

        /**
         * The mode has changed. Caller applies {@link #to} and surfaces
         * {@link #narration} to in-room observers.
         */
        record Transition(ParlorPresenceMode from, ParlorPresenceMode to, String narration)
            implements Decision {}

        /** Mode unchanged — either within band, or dwell not yet elapsed. */
        record NoChange(ParlorPresenceMode current) implements Decision {}

        /**
         * Occupancy exceeds {@link ParlorPresenceMode#MAX_OCCUPANTS}. The
         * caller must refuse new arrivals (queue in Docks antechamber per
         * §2.8.1) regardless of mode; the scaler still reports the target
         * mode that {@link ParlorPresenceMode#MAX_OCCUPANTS} maps to.
         */
        record AtCap(ParlorPresenceMode current, int over) implements Decision {}
    }

    private ParlorAutoScaler() {}

    /**
     * Decide the next mode.
     *
     * @param current        current presence mode (never null)
     * @param occupancy      count of unique DIDs currently in the Parlor
     * @param lastChangeAt   timestamp of the last mode transition — used
     *                       for dwell arithmetic. Pass the creation time if
     *                       no change has happened yet.
     * @param now            current wall-clock time (injected for tests)
     */
    public static Decision decide(ParlorPresenceMode current,
                                    int occupancy,
                                    Instant lastChangeAt,
                                    Instant now) {
        if (current == null) throw new IllegalArgumentException("current mode required");
        if (occupancy < 0) throw new IllegalArgumentException("occupancy cannot be negative");

        // Hard cap — report even if we wouldn't transition, so the caller
        // can enforce queuing in the Docks antechamber.
        if (occupancy > ParlorPresenceMode.MAX_OCCUPANTS) {
            var mode = ParlorPresenceMode.forOccupancy(ParlorPresenceMode.MAX_OCCUPANTS);
            return new Decision.AtCap(mode, occupancy - ParlorPresenceMode.MAX_OCCUPANTS);
        }

        // What mode SHOULD we be in based on occupancy alone (no hysteresis)?
        var target = ParlorPresenceMode.forOccupancy(occupancy);

        if (target == current) {
            return new Decision.NoChange(current);
        }

        if (target.ordinal() > current.ordinal()) {
            // UP transition — immediate.
            return new Decision.Transition(current, target, narrationUp(current, target));
        }

        // DOWN transition — require dwell. We're considering moving from
        // `current` to something lower. Only move when occupancy has been
        // at/below the CURRENT mode's downThreshold for the dwell window.
        // Lagging the transition here matches the spec's anti-flap rule.
        if (occupancy > current.downThreshold()) {
            // Still inside the current mode's "hold" band.
            return new Decision.NoChange(current);
        }
        if (lastChangeAt == null
                || Duration.between(lastChangeAt, now).compareTo(
                    ParlorPresenceMode.TRANSITION_DWELL) < 0) {
            // Below hold band, but hasn't been there long enough.
            return new Decision.NoChange(current);
        }

        return new Decision.Transition(current, target, narrationDown(current, target));
    }

    /**
     * Diegetic narration for an UP transition. The spec's examples
     * (§2.8.1 "Narrative continuity on transitions"):
     * full→sampled: "The Parlor grows busier; a hum of voices rises."
     * sampled→firehose: "The Parlor is now a crowd..."
     *
     * <p>Callers that want different wording can ignore these strings and
     * build their own from the (from, to) pair. These defaults keep the
     * pattern idiomatic without forcing operator configuration for every
     * transition edge.</p>
     */
    static String narrationUp(ParlorPresenceMode from, ParlorPresenceMode to) {
        return switch (to) {
            case SAMPLED -> "The Parlor grows busier; a hum of voices rises.";
            case SAMPLED_STRICT -> "Voices crowd together; the room settles into a steady murmur.";
            case FIREHOSE -> "The Parlor is now a crowd. Tune in to individual voices to hear them clearly.";
            default -> "The room shifts and adjusts.";  // FULL never UP-transitions-into
        };
    }

    /** Diegetic narration for a DOWN transition. */
    static String narrationDown(ParlorPresenceMode from, ParlorPresenceMode to) {
        return switch (to) {
            case FULL -> "The Parlor quiets; individual voices return.";
            case SAMPLED -> "The crowd thins; individual voices return.";
            case SAMPLED_STRICT -> "The crowd loosens; room rhythm returns.";
            default -> "The room eases.";  // FIREHOSE never DOWN-transitions-into
        };
    }
}
