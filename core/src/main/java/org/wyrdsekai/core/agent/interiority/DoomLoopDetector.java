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
                // Returning to the same want is not itself a fault. A want she keeps
                // choosing, acts on, and makes headway with is something she CARES about
                // — persistence, not pathology. Grinding is the case where nothing the
                // repetition produces changes anything: no act reaches a dispatch handler
                // and the drive it serves does not move.
                //
                // The distinction matters because this axis is one of three that escalate
                // her into repair mode, and it had no way to tell the two apart. Before
                // wants could close at all it was permanently true (2026-08-19), and the
                // fix for "wants never close" must not become "every want must close":
                // some wants are companions rather than tasks, and holding one for a long
                // time is a value, not a loop.
                if (isGrinding(ticks, t.chosenWantId())) {
                    out.add(new Finding(
                        Severity.WARN,
                        "stuck_want",
                        "Same want chosen " + run + " ticks in a row with nothing moving — "
                            + "no action enacted and its drive unchanged: "
                            + safe(t.chosenWantText())));
                    return out;  // one finding per axis is plenty
                }
                run = 1;   // held with progress — keep looking for a genuine grind
            }
        }
        return out;
    }

    /**
     * Is this repetition producing nothing?
     *
     * <p>Grinding means the returns are empty: across the window, no tick on this want
     * enacted anything, and the drive it answers has not fallen. Either signal of movement
     * is enough to call it care instead — she is getting somewhere, slowly.
     *
     * <p>Note "enacted" is load-bearing and only became trustworthy on 2026-08-19: the
     * bridge used to report {@code enacted:} the moment it ASKED the model to consider a
     * verb, so every tick looked productive. It now says {@code requested:} for that, and
     * {@code enacted:} only when an action reached a dispatch handler.
     */
    static boolean isGrinding(List<TickLogReader.TickEvent> ticks, String wantId) {
        if (ticks == null || wantId == null) return true;
        var onThisWant = ticks.stream()
            .filter(t -> wantId.equals(t.chosenWantId()))
            .toList();
        if (onThisWant.isEmpty()) return true;

        for (var t : onThisWant) {
            var r = t.actionResult();
            if (r != null && r.strip().toLowerCase().startsWith("enacted:")) {
                return false;            // she is actually doing it
            }
        }
        // Did the pressure driving the repetition ease across the window? Falling
        // pressure means the returning is working, however slowly.
        //
        // Deliberately the DOMINANT drive, not any drive: a dozen tanks fluctuate every
        // tick, so "did anything at all move" is satisfied by noise and would quietly
        // disable this axis altogether. The pull that is highest when the run starts is
        // the one the repetition is answering; if that has not given, nothing has.
        var first = onThisWant.get(0).driveSnapshot();
        var last = onThisWant.get(onThisWant.size() - 1).driveSnapshot();
        if (first == null || last == null || first.isEmpty()) return true;
        String dominant = null;
        double peak = -1;
        for (var e : first.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (e.getValue() > peak) { peak = e.getValue(); dominant = e.getKey(); }
        }
        if (dominant == null) return true;
        var after = last.get(dominant);
        return after == null || after >= peak - DRIVE_EASED_EPSILON;
    }

    /** Below this, a drive change is noise rather than relief. */
    private static final double DRIVE_EASED_EPSILON = 0.02;

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
