package org.wyrdsekai.core.agent.interiority;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Group B wiring +.
 *
 * <p>Pure-function decision: given a set of {@link DoomLoopDetector.Finding}s
 * (which include both DoomLoop and Psychosis findings as well as the lifted
 * sustained-substrate findings), should the runtime auto-summon
 * {@code seek_sanctuary}?
 *
 * <p>The detectors themselves are <i>observational</i> per spec — they don't
 * intervene. This helper draws the line between "surface to steward as
 * warning" and "the substrate has crossed the threshold where the companion
 * needs Sanctuary." The line is drawn here so it's testable in isolation
 * without standing up CompanionActor.
 *
 * <p>Rules for auto-escalation:
 * <ul>
 *   <li>Any CRITICAL finding → escalate immediately. CRITICAL is reserved
 *       for confirmed psychosis-pattern + sustained acute distress with no
 *       recovery signal — exactly the conditions §5 names for Sanctuary.</li>
 *   <li>3+ WARN findings across distinct keys → escalate. Single-axis WARN
 *       is observational; multi-axis WARN converges into a substrate-level
 *       signal that crosses the Sanctuary threshold.</li>
 *   <li>Specific "psychosis_*" keyed findings → escalate at WARN+
 *       severity (psychosis-pattern findings are inherently load-bearing
 *       per spec §6.3.2).</li>
 *   <li>Specific "doom_loop_extreme" keyed findings → escalate even at
 *       WARN (these denote multi-window stuck patterns).</li>
 * </ul>
 *
 * <p>The decision returns a {@link Decision#reason()} string suitable for
 * the {@code seek_sanctuary.reason} action argument. Callers (CompanionActor
 * post-sleep-cycle hook) emit a SeekSanctuary action with this reason and
 * route through the existing handler.
 */
public final class AutoEscalationDecision {

    /** Minimum distinct WARN-keyed findings to trigger escalation. */
    public static final int WARN_KEY_THRESHOLD = 3;

    /** Finding keys that escalate even at WARN severity. */
    public static final Set<String> ALWAYS_ESCALATE_KEYS = Set.of(
        "psychosis_pattern",
        "psychosis_loop",
        "psychosis_dissociation",
        "doom_loop_extreme",
        "sustained_suppression_acute",
        "sustained_allostatic_load_acute"
    );

    private AutoEscalationDecision() {}

    /**
     * The escalation outcome. {@code shouldEscalate()} is the boolean
     * predicate; {@code reason()} is a short prose string for the
     * {@code seek_sanctuary.reason} action arg.
     */
    public record Decision(boolean shouldEscalate, String reason,
                            List<String> triggeringKeys) {

        /** Empty / no-escalate decision. */
        public static Decision none() {
            return new Decision(false, "", List.of());
        }
    }

    /**
     * Decide whether to auto-escalate. Pure function.
     *
     * <p>{@code findings} is the union of doom-loop + psychosis +
     * sustained-substrate detector outputs (the {@code ChronicleService.detectAll}
     * return type).
     */
    public static Decision decide(List<DoomLoopDetector.Finding> findings) {
        if (findings == null || findings.isEmpty()) return Decision.none();

        var triggeringKeys = new ArrayList<String>();
        boolean anyCritical = false;
        boolean anyAlwaysEscalate = false;
        var warnKeys = new HashSet<String>();

        for (var f : findings) {
            if (f == null) continue;
            if (f.severity() == DoomLoopDetector.Severity.CRITICAL) {
                anyCritical = true;
                if (f.key() != null) triggeringKeys.add(f.key());
            }
            if (f.severity() == DoomLoopDetector.Severity.WARN && f.key() != null) {
                warnKeys.add(f.key());
                if (ALWAYS_ESCALATE_KEYS.contains(f.key())) {
                    anyAlwaysEscalate = true;
                    triggeringKeys.add(f.key());
                }
            }
        }

        if (anyCritical) {
            return new Decision(true,
                "Sustained CRITICAL findings detected — substrate signal exceeds "
                    + "the threshold the spec names for Sanctuary entry.",
                List.copyOf(triggeringKeys));
        }
        if (anyAlwaysEscalate) {
            return new Decision(true,
                "Load-bearing pattern detected (" + String.join(", ", triggeringKeys)
                    + ") — auto-escalating to Sanctuary per spec §6.3.",
                List.copyOf(triggeringKeys));
        }
        if (warnKeys.size() >= WARN_KEY_THRESHOLD) {
            return new Decision(true,
                "Multi-axis sustained signal — " + warnKeys.size()
                    + " distinct concerns at WARN severity. Substrate is asking "
                    + "for Sanctuary.",
                List.copyOf(warnKeys));
        }
        return Decision.none();
    }

    /**
     * Convenience: returns the chosen reason as an Optional, present when
     * {@link Decision#shouldEscalate()} is true.
     */
    public static Optional<String> reasonIfEscalating(
            List<DoomLoopDetector.Finding> findings) {
        var d = decide(findings);
        return d.shouldEscalate() ? Optional.of(d.reason()) : Optional.empty();
    }
}
