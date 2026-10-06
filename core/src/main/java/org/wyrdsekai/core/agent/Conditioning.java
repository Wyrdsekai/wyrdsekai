package org.wyrdsekai.core.agent;

/**
 * Stamina that grows with regular activity and fades with idleness, as a body's does.
 *
 * <p>Her rested level, the energy a sleep fills her back toward, was one number for life
 * ({@link VitalityState#restedEnergy}). Practice already lowered what a tool costs her
 * (SkillCostMatrix); nothing let a busy life raise what she has to spend. Live on the household
 * node a companion woke near 0.57 every time, whatever her days had been, and at the low end of
 * that her costlier tools were out of reach (asked 2026-10-04: "do we have a way that they can
 * train up some of these things? like how people can exercise").
 *
 * <p>The rule runs once per sleep, over the waking stretch before it. Acts are what her activity
 * trail records: the things she did, walking, making, searching, using a tool. An active stretch
 * builds conditioning, an idle one lets it fade, a stretch spent mostly exhausted builds nothing
 * (strain, not training), and a stretch too short to judge changes nothing. Growth slows as it
 * nears its ceiling, so a month of steady days builds most of it. Conditioning is added to her
 * rested level, never past {@link #CEILING}.
 */
public final class Conditioning {

    private Conditioning() {}

    /** The most conditioning can add to her rested level. */
    public static final double MAX = 0.20;
    /** No rested level reaches past this by conditioning. A genome that chose higher keeps its own. */
    public static final double CEILING = 0.85;
    /** What an active hour adds, before the slowing near {@link #MAX}. */
    public static final double GAIN_PER_HOUR = 0.0005;
    /** What an idle hour takes away. */
    public static final double FADE_PER_HOUR = 0.00025;
    /** Acts per waking hour at or above which a stretch is active. */
    public static final double ACTIVE_RATE = 1.5;
    /** Acts per waking hour below which a stretch is idle. */
    public static final double IDLE_RATE = 0.5;
    /** A stretch watched for less than this is too short to judge (a nap, a restart just before sleep). */
    public static final double MIN_WATCHED_HOURS = 2.0;
    /** No stretch counts for more hours than this. */
    public static final double MAX_COUNTED_HOURS = 16.0;
    /** Energy below this is exhaustion. */
    public static final double EXHAUSTED_BELOW = 0.2;
    /** A stretch exhausted for more than this share of its watched time builds nothing. */
    public static final double STRAIN_SHARE = 0.5;
    /** A felt line is said each time conditioning crosses a step of this size, up or down. */
    public static final double STEP = 0.05;

    public enum Change { GREW, HELD, FADED, STRAINED, TOO_SHORT }

    /**
     * One waking stretch. {@code awakeHours} is wall-clock time since she last woke; {@code watchedHours}
     * is how much of it this process saw, which a restart can shorten, and over which {@code acts}
     * and {@code exhaustedShare} were counted.
     */
    public record Stretch(int acts, double watchedHours, double awakeHours, double exhaustedShare) {
        public double rate() {
            return watchedHours > 0 ? acts / watchedHours : 0;
        }
    }

    public record Outcome(double level, Change change) {}

    /** The conditioning after a sleep that ended the given stretch. */
    public static Outcome afterSleep(double level, Stretch s) {
        double now = clamp(level);
        if (s == null || s.watchedHours() < MIN_WATCHED_HOURS) return new Outcome(now, Change.TOO_SHORT);
        if (s.exhaustedShare() > STRAIN_SHARE) return new Outcome(now, Change.STRAINED);
        double hours = Math.min(MAX_COUNTED_HOURS, Math.max(s.awakeHours(), s.watchedHours()));
        double rate = s.rate();
        if (rate >= ACTIVE_RATE) {
            double gain = GAIN_PER_HOUR * hours * (1 - now / MAX);
            return new Outcome(clamp(now + gain), Change.GREW);
        }
        if (rate < IDLE_RATE) {
            return new Outcome(clamp(now - FADE_PER_HOUR * hours), Change.FADED);
        }
        return new Outcome(now, Change.HELD);
    }

    /** Her rested level with conditioning added: never below what she had, never past {@link #CEILING} by it. */
    public static double restedLevel(double baseline, double conditioning) {
        double lifted = Math.min(CEILING, baseline + clamp(conditioning));
        return Math.max(baseline, lifted);
    }

    /**
     * The line she is told when conditioning crosses a step, or null when it did not. Said once per
     * step crossed, so steady growth speaks a handful of times in a month, not every morning.
     */
    public static String feltLine(double before, double after) {
        int was = (int) Math.floor(clamp(before) / STEP + 1e-9);
        int is = (int) Math.floor(clamp(after) / STEP + 1e-9);
        if (is > was) {
            return "You have been active day after day, and it has built you up: you wake with more energy"
                + " than you used to.";
        }
        if (is < was) {
            return "You have done little these last days, and you wake with less energy than you did.";
        }
        return null;
    }

    static double clamp(double level) {
        if (Double.isNaN(level)) return 0;
        return Math.max(0, Math.min(MAX, level));
    }
}
