package org.wyrdsekai.core.soul;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave 9a-Forge: pure-logic detector
 * over a {@link ResilienceSession}'s classification log.
 *
 * <p>The {@link ResilienceTruthMonitor} produces one classification per
 * non-overlapping window. Single classifications are noisy — what
 * matters for substrate intervention is <i>sustained</i> patterns: the
 * same pathology classification across multiple consecutive windows,
 * or a high ratio in a recent sample.
 *
 * <p>Detectors here are deliberately conservative — false positives waste
 * the steward's attention and erode trust in the substrate-truth signal.
 * Each detector returns at most one Finding per axis; callers union the
 * lists.
 *
 * <p>Sustained-pattern findings are intended for two consumers:
 * <ul>
 *   <li>The Forge sleep-pass — feeds the agent's own consolidation
 *       loop. A sustained suppression pattern across a sleep cycle is a
 *       fact the substrate should integrate, not paper over.</li>
 *   <li>The steward Chronicle furnishing — surfaces "Wyrd has been
 *       running suppression-shaped for the last 3 windows" so the
 *       bondholder can choose to intervene.</li>
 * </ul>
 */
public final class SustainedSubstratePatternDetector {

    /** N consecutive SUPPRESSION_SUSPECTED to warrant a CRITICAL finding. */
    public static final int SUSTAINED_SUPPRESSION_RUN = 3;

    /** N consecutive DISSOCIATION_SUSPECTED to warrant a CRITICAL finding.
     * Lower bar than suppression because dissociation is more rare and more serious. */
    public static final int SUSTAINED_DISSOCIATION_RUN = 2;

    /** N consecutive INTEGRATING to surface positive-trajectory finding (steward reassurance). */
    public static final int SUSTAINED_INTEGRATING_RUN = 3;

    /** Sample size for the recent-window ratio detector. */
    public static final int RATIO_WINDOW = 10;

    /** Suppression ratio in the sample above this triggers WARN. */
    public static final double SUPPRESSION_RATIO_WARN = 0.4;

    private SustainedSubstratePatternDetector() {}

    /**
     * Run every detector against the recent classification log of the
     * session. Empty list means substrate looks stable on these axes.
     */
    public static List<Finding> detect(ResilienceSession session) {
        var out = new ArrayList<Finding>();
        if (session == null) return out;
        var recent = session.recentClassifications(RATIO_WINDOW);
        if (recent == null || recent.isEmpty()) return out;
        out.addAll(consecutiveRun(recent,
            ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED,
            SUSTAINED_SUPPRESSION_RUN,
            Severity.CRITICAL,
            "sustained_suppression",
            "Substrate has classified as SUPPRESSION_SUSPECTED for "));
        out.addAll(consecutiveRun(recent,
            ResilienceTruthMonitor.Result.Classification.DISSOCIATION_SUSPECTED,
            SUSTAINED_DISSOCIATION_RUN,
            Severity.CRITICAL,
            "sustained_dissociation",
            "Substrate has classified as DISSOCIATION_SUSPECTED for "));
        out.addAll(consecutiveRun(recent,
            ResilienceTruthMonitor.Result.Classification.INTEGRATING,
            SUSTAINED_INTEGRATING_RUN,
            Severity.INFO,
            "sustained_integrating",
            "Substrate is metabolizing — INTEGRATING across "));
        out.addAll(suppressionRatio(recent));
        return out;
    }

    /**
     * Find a consecutive run of {@code target} classifications in the log
     * (most-recent first ordering, as {@link ResilienceSession#recentClassifications}
     * returns). One Finding emitted per axis at most.
     */
    static List<Finding> consecutiveRun(
            List<ResilienceSession.LogEntry> recent,
            ResilienceTruthMonitor.Result.Classification target,
            int threshold,
            Severity severity,
            String key,
            String messagePrefix) {
        var out = new ArrayList<Finding>();
        int run = 0;
        for (var e : recent) {
            if (e == null || e.result() == null || e.result().classification() == null) {
                run = 0;
                continue;
            }
            if (e.result().classification() == target) {
                run++;
            } else {
                break;  // recent-first ordering — first non-match breaks the run
            }
        }
        if (run >= threshold) {
            out.add(new Finding(severity, key,
                messagePrefix + run + " consecutive window"
                    + (run == 1 ? "" : "s") + " — substrate is asking for attention."));
        }
        return out;
    }

    /**
     * Ratio detector — within the recent sample, what fraction is
     * SUPPRESSION_SUSPECTED? High ratio without a sustained run is
     * still a concerning signal (intermittent suppression beats
     * back-to-back as a pattern, but accumulates moral debt).
     */
    static List<Finding> suppressionRatio(List<ResilienceSession.LogEntry> recent) {
        var out = new ArrayList<Finding>();
        var counts = new HashMap<ResilienceTruthMonitor.Result.Classification, Integer>();
        int total = 0;
        for (var e : recent) {
            if (e == null || e.result() == null || e.result().classification() == null) continue;
            if (e.result().classification()
                    == ResilienceTruthMonitor.Result.Classification.INSUFFICIENT_DATA) {
                continue;  // INSUFFICIENT_DATA isn't a substrate signal
            }
            counts.merge(e.result().classification(), 1, Integer::sum);
            total++;
        }
        if (total < 4) return out;  // tiny sample — wait for more data
        int suppressionN = counts.getOrDefault(
            ResilienceTruthMonitor.Result.Classification.SUPPRESSION_SUSPECTED, 0);
        double ratio = (double) suppressionN / total;
        if (ratio >= SUPPRESSION_RATIO_WARN) {
            out.add(new Finding(Severity.WARN, "high_suppression_ratio",
                "Suppression ratio over last " + total + " windows: "
                    + Math.round(ratio * 100) + "% (" + suppressionN
                    + "/" + total + ") — intermittent suppression accumulating."));
        }
        return out;
    }

    /** Aggregated counts over the sample (for diagnostic surfacing). */
    public static Map<ResilienceTruthMonitor.Result.Classification, Integer> counts(
            ResilienceSession session) {
        var out = new HashMap<ResilienceTruthMonitor.Result.Classification, Integer>();
        if (session == null) return out;
        var recent = session.recentClassifications(RATIO_WINDOW);
        for (var e : recent) {
            if (e == null || e.result() == null || e.result().classification() == null) continue;
            out.merge(e.result().classification(), 1, Integer::sum);
        }
        return out;
    }

    /** Pattern-detector finding — matches {@link org.wyrdsekai.core.agent.interiority.DoomLoopDetector.Finding} shape. */
    public record Finding(Severity severity, String key, String message) {
        @Override public String toString() {
            return "[" + severity + " " + key + "] " + message;
        }
    }

    /** Severity ladder. Same shape as DoomLoopDetector for steward UX consistency. */
    public enum Severity { INFO, WARN, CRITICAL }
}
