package org.wyrdsekai.core.agent.interiority;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * quantitative detectors over the tick log.
 *
 * <p>Detectors don't auto-intervene. They surface to the steward via the
 * Chronicle furnishing as warnings. False positives are tuning errors; true
 * positives are real problems to look at.
 *
 * <p>The detectors here are deliberately simple — count rules over the most
 * recent window of ticks. More sophisticated trend analysis can be layered on
 * later as the log accumulates real data.
 */
public final class DoomLoopDetector {

    /** Threshold for "same want chosen too many ticks in a row". */
    public static final int SAME_WANT_RUN_LIMIT = 8;
    /** Threshold for "same action verb without state change". */
    public static final int SAME_VERB_RUN_LIMIT = 10;
    /** Threshold for "pre-gate skip ratio is suspiciously high". */
    public static final double PREGATE_SKIP_RATIO_LIMIT = 0.85;
    /** Threshold for "rest:act ratio is outside the expected band". */
    public static final double REST_RATIO_FLOOR = 0.05;
    public static final double REST_RATIO_CEILING = 0.85;

    private DoomLoopDetector() {}

    /**
     * Run every detector against the given window of ticks. Returns the union of
     * findings — each is a short string describing the problem. Empty list means
     * the agent looks healthy on these axes.
     */
    public static List<Finding> detect(List<TickLogReader.TickEvent> ticks) {
        var out = new ArrayList<Finding>();
        if (ticks == null || ticks.isEmpty()) return out;

        out.addAll(stuckOnSameWant(ticks));
        out.addAll(stuckOnSameVerb(ticks));
        out.addAll(driveStuckHigh(ticks));
        out.addAll(pregateSkipRatio(ticks));
        out.addAll(restRatioOffBand(ticks));
        return out;
    }

    /** Same want chosen ≥ {@link #SAME_WANT_RUN_LIMIT} consecutive ticks, no satisfaction. */
    static List<Finding> stuckOnSameWant(List<TickLogReader.TickEvent> ticks) {
        var out = new ArrayList<Finding>();
        int run = 1;
        String prev = null;
        for (var t : ticks) {
            if (t.chosenWantId() == null) { run = 1; prev = null; continue; }
            if (t.chosenWantId().equals(prev)) run++;
            else { run = 1; prev = t.chosenWantId(); }
            if (run >= SAME_WANT_RUN_LIMIT) {
                out.add(new Finding(
                    Severity.WARN,
                    "stuck_want",
                    "Same want chosen " + run + " ticks in a row without satisfaction: "
                        + safe(t.chosenWantText())));
                return out;  // one finding per axis is plenty
            }
        }
        return out;
    }

    /** Same action verb ≥ {@link #SAME_VERB_RUN_LIMIT} times in the window. */
    static List<Finding> stuckOnSameVerb(List<TickLogReader.TickEvent> ticks) {
        var out = new ArrayList<Finding>();
        var counts = new HashMap<String, Integer>();
        for (var t : ticks) {
            if (t.actionVerb() == null) continue;
            counts.merge(t.actionVerb(), 1, Integer::sum);
        }
        for (var e : counts.entrySet()) {
            if (e.getValue() >= SAME_VERB_RUN_LIMIT) {
                out.add(new Finding(
                    Severity.WARN,
                    "verb_loop",
                    "Action verb '" + e.getKey() + "' fired " + e.getValue() + " times in window"));
            }
        }
        return out;
    }

    /** Any drive stays > 0.7 across more than 70% of the window's ticks. */
    static List<Finding> driveStuckHigh(List<TickLogReader.TickEvent> ticks) {
        var out = new ArrayList<Finding>();
        var totals = new HashMap<String, Integer>();
        var highs = new HashMap<String, Integer>();
        for (var t : ticks) {
            if (t.driveSnapshot() == null) continue;
            for (var e : t.driveSnapshot().entrySet()) {
                totals.merge(e.getKey(), 1, Integer::sum);
                if (e.getValue() != null && e.getValue() > 0.7) {
                    highs.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        for (var e : highs.entrySet()) {
            var total = totals.getOrDefault(e.getKey(), 1);
            if (total < 4) continue;  // need a few samples
            double ratio = (double) e.getValue() / total;
            if (ratio > 0.7) {
                out.add(new Finding(
                    Severity.WARN,
                    "drive_stuck_high",
                    "Drive '" + e.getKey() + "' over 0.7 in "
                        + e.getValue() + "/" + total + " ticks (no recovery)"));
            }
        }
        return out;
    }

    /** Pre-gate skipped the tick > {@link #PREGATE_SKIP_RATIO_LIMIT} of the window. */
    static List<Finding> pregateSkipRatio(List<TickLogReader.TickEvent> ticks) {
        var out = new ArrayList<Finding>();
        if (ticks.size() < 8) return out;
        int skips = 0;
        for (var t : ticks) {
            if ("pregate_skip".equals(t.gateOutcome())) skips++;
        }
        double ratio = (double) skips / ticks.size();
        if (ratio > PREGATE_SKIP_RATIO_LIMIT) {
            out.add(new Finding(
                Severity.INFO,
                "high_pregate_skip",
                "Pre-gate skipped " + skips + "/" + ticks.size() + " ticks — agent may be too quiet"));
        }
        return out;
    }

    /** Rest ratio outside [{@value REST_RATIO_FLOOR}, {@value REST_RATIO_CEILING}]. */
    static List<Finding> restRatioOffBand(List<TickLogReader.TickEvent> ticks) {
        var out = new ArrayList<Finding>();
        if (ticks.size() < 8) return out;
        int rests = 0;
        int acted = 0;
        for (var t : ticks) {
            if ("chose_rest".equals(t.gateOutcome())) rests++;
            if ("acted".equals(t.gateOutcome())) acted++;
        }
        int total = rests + acted;
        if (total < 8) return out;
        double ratio = (double) rests / total;
        if (ratio < REST_RATIO_FLOOR) {
            out.add(new Finding(
                Severity.WARN,
                "no_rest",
                "Rest ratio " + fmt(ratio) + " — agent is never resting; risk of grinding"));
        } else if (ratio > REST_RATIO_CEILING) {
            out.add(new Finding(
                Severity.WARN,
                "all_rest",
                "Rest ratio " + fmt(ratio) + " — agent is rarely choosing action"));
        }
        return out;
    }

    /** Convenience: a Finding with severity + key + human prose. */
    public record Finding(Severity severity, String key, String message) {
        @Override public String toString() {
            return "[" + severity + "] " + key + ": " + message;
        }
    }

    public enum Severity { INFO, WARN, CRITICAL }

    /** Default lookback for detector runs: the last 24h. */
    public static Instant defaultLookback() {
        return Instant.now().minus(Duration.ofHours(24));
    }

    private static String safe(String s) { return s == null ? "(unnamed)" : s; }
    private static String fmt(double d) { return String.format("%.2f", d); }
}
