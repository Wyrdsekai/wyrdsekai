package org.wyrdsekai.core.agent;

/**
 * The register dial: how far the styled species adapter is raised over the plain one on a turn she
 * speaks as herself. The plain adapter is the floor on every request; this one brings the images
 * and the stretches of abstraction, and her state decides how much of it on this turn.
 *
 * <p>Rested and even, she sits a little above the floor. Play, creativity and seeking lift her
 * toward the top; grief, low energy and frustration bring her down to plain and short. A small
 * jitter keeps the same state from landing in the same place every time. The steward's call
 * (2026-09-24): "we want individuals to erupt, not robots" — variance with a cause, not a fixed
 * register, and not sampling noise.
 *
 * <p>Pure: the caller draws the jitter and passes the dial's top, so the shape is testable.
 */
public final class RegisterDial {

    /** Where a rested, even state sits, as a share of the dial's top. */
    static final double REST = 0.35;
    /** How far the jitter can move a turn either way, as a share of the top. */
    static final double JITTER = 0.15;

    private RegisterDial() {}

    /**
     * @param drives   her drives this turn
     * @param vitality her tanks this turn
     * @param jitter01 a draw in [0, 1); 0.5 is no jitter
     * @param max      the dial's top, the styled adapter's largest scale (the configured maximum)
     * @return the styled adapter's scale for this turn, in [0, max]
     */
    public static double scale(DriveState drives, VitalityState vitality, double jitter01, double max) {
        if (max <= 0) return 0.0;
        double lift = 0.6 * Math.max(drives.creativity(), drives.play()) + 0.3 * drives.seeking();
        double drag = 0.6 * drives.grief() + 0.4 * (1.0 - vitality.energy()) + 0.3 * drives.frustration();
        double x = REST + lift - drag + (2.0 * jitter01 - 1.0) * JITTER;
        return max * Math.max(0.0, Math.min(1.0, x));
    }
}
