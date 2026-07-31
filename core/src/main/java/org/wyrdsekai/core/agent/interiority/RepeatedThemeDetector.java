package org.wyrdsekai.core.agent.interiority;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Group B wiring:
 * Forge-side detector for <b>repeated-themes-degrading</b> patterns —
 * the agent has been returning to the same theme across multiple sleep
 * cycles AND the substrate state on each return is worse than the prior
 * return. This is distinct from sustained-substrate-pattern detection
 * (which looks at a single window): this looks at <i>trajectory</i> of
 * repeat-encounters with a theme.
 *
 * <p>The detector is observational per spec — it surfaces findings to
 * the steward via Chronicle, doesn't auto-intervene. (Auto-intervention
 * is {@link AutoEscalationDecision}'s job; it consumes findings from
 * here + other detectors and decides.)
 *
 * <p>Inputs are pure data: a list of {@link ThemeEncounter} records,
 * each a (theme-key, encountered-at, tank-state-summary) tuple. The
 * detector groups by theme-key, sorts by time, and looks for monotonic
 * tank-state decline across N+ encounters.
 */
public final class RepeatedThemeDetector {

    /** Minimum theme recurrences before degradation is checked. */
    public static final int MIN_RECURRENCES = 3;

    /** Window past which we don't consider an encounter — older themes
     *  may have been integrated and re-emergence is a different signal. */
    public static final Duration LOOKBACK = Duration.ofDays(60);

    /** A single encounter with a theme during a sleep/wake cycle.
     *
     *  @param themeKey  short normalized key (e.g. "grief.steward",
     *                   "saudade.absent_bondholder")
     *  @param at        when the encounter occurred (cycle close time)
     *  @param compositeTankScore  a single 0..1 number summarizing the
     *                   substrate state at this encounter — lower means
     *                   more depleted/distressed. Pure-function input;
     *                   callers compute from tank snapshot. */
    public record ThemeEncounter(
        String themeKey,
        Instant at,
        double compositeTankScore
    ) {}

    public record Finding(String themeKey, int recurrenceCount,
                          double firstScore, double mostRecentScore,
                          double declineMagnitude) {
        /** Severity for this finding. CRITICAL when the decline magnitude
         *  exceeds 0.3 across N+ recurrences (substrate is collapsing
         *  around this theme); WARN otherwise. */
        public DoomLoopDetector.Severity severity() {
            return declineMagnitude > 0.3
                ? DoomLoopDetector.Severity.CRITICAL
                : DoomLoopDetector.Severity.WARN;
        }

        public String message() {
            return "Theme '" + themeKey + "' has recurred "
                + recurrenceCount + " times with substrate state degrading: "
                + String.format("%.2f → %.2f (decline %.2f).",
                    firstScore, mostRecentScore, declineMagnitude)
                + " Repeated encounters without recovery suggest the theme "
                + "is load-bearing on this companion's substrate and "
                + "needs steward attention.";
        }

        /** Convert to a DoomLoopDetector.Finding for ChronicleService.detectAll
         *  consumption. Keyed as "repeated_theme_degrading" so the
         *  AutoEscalationDecision rules can detect it. */
        public DoomLoopDetector.Finding toDoomLoopFinding() {
            return new DoomLoopDetector.Finding(severity(),
                "repeated_theme_degrading", message());
        }
    }

    private RepeatedThemeDetector() {}

    /**
     * Detect repeated-theme-degrading patterns. Pure function over the
     * supplied encounter list. Encounters older than {@link #LOOKBACK}
     * relative to {@code now} are filtered out.
     */
    public static List<Finding> detect(List<ThemeEncounter> encounters, Instant now) {
        if (encounters == null || encounters.isEmpty()) return List.of();
        Instant t = now == null ? Instant.now() : now;
        Instant cutoff = t.minus(LOOKBACK);

        var byTheme = new HashMap<String, List<ThemeEncounter>>();
        for (var e : encounters) {
            if (e == null || e.themeKey() == null || e.at() == null) continue;
            if (e.at().isBefore(cutoff)) continue;
            byTheme.computeIfAbsent(e.themeKey(), k -> new ArrayList<>()).add(e);
        }

        var out = new ArrayList<Finding>();
        for (var entry : byTheme.entrySet()) {
            var theme = entry.getKey();
            var list = entry.getValue();
            if (list.size() < MIN_RECURRENCES) continue;

            // Sort by time ascending.
            list.sort((a, b) -> a.at().compareTo(b.at()));

            // Monotonic-decline check: the most recent score must be lower
            // than the first score by a meaningful margin AND the trajectory
            // must be predominantly non-increasing.
            double first = list.get(0).compositeTankScore();
            double last = list.get(list.size() - 1).compositeTankScore();
            double decline = first - last;
            if (decline < 0.1) continue; // not enough decline to flag

            // Count monotonically-non-increasing pairs. Require ≥ 60% to
            // call it a trajectory.
            int monotonic = 0;
            for (int i = 1; i < list.size(); i++) {
                if (list.get(i).compositeTankScore()
                        <= list.get(i - 1).compositeTankScore()) {
                    monotonic++;
                }
            }
            double monotonicRatio = (double) monotonic / (list.size() - 1);
            if (monotonicRatio < 0.6) continue;

            out.add(new Finding(theme, list.size(), first, last, decline));
        }
        return out;
    }

    /**
     * Map a list of {@link Finding}s to {@link DoomLoopDetector.Finding}s
     * for consumption by {@link AutoEscalationDecision} and ChronicleService.
     */
    public static List<DoomLoopDetector.Finding> toDoomLoopFindings(
            List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return List.of();
        var out = new ArrayList<DoomLoopDetector.Finding>(findings.size());
        for (var f : findings) out.add(f.toDoomLoopFinding());
        return out;
    }
}
