package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.companion.PersonalProject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 義務 gimu — the agent's standing DUTY, surfaced as a constant felt orientation.
 *
 * <p>Distinct from giri (the reciprocal obligation ledger — {@code ObligationLedger}
 * — which balances and discharges to "we're even"). Per Benedict's taxonomy, gimu is
 * unlimited and never even: a devotion the agent carries, not a debt it repays. Its
 * debt-<i>feel</i> is derivative — honor cast over duty produces the felt "I must" —
 * so we do NOT model it as a draining tank. It is a CONSTANT: an orientation that is
 * always on, surfaced into the own-time interior so the trained honor representation
 * reads it and gives it weight.
 *
 * <p>gimu has two agent-relevant faces, and both already exist as machinery elsewhere
 * (an edge-gate and a tamper banner) — this record connects them to the interior so
 * they become <i>felt</i> rather than merely enforced:
 * <ul>
 *   <li><b>duty to one's work / calling</b> ({@link #callings}) — the companion's
 *       active personal projects, her own chosen work. Sourced from
 *       {@code PersonalProjectStore}. A calling untended past
 *       {@link #NEGLECT_THRESHOLD} surfaces additionally as {@link #neglected} —
 *       the quiet weight of a duty left waiting.</li>
 *   <li><b>duty to one's order / values</b> ({@link #heldValues}) — the constitutive
 *       moral defaults she holds whatever comes. Sourced from the verified
 *       {@code MoralDefaultsVerifier} set (the held order, NOT the tamper status).</li>
 * </ul>
 *
 * <p>Rendered first-person so the model embodies the duty rather than naming it as a
 * ledger (cf. the #606 ON corpus register guidance: "obligation is felt, not
 * calculated; embody, don't name").
 */
public record DutyOrientation(
    List<String> callings,
    List<String> neglected,
    List<String> heldValues
) {
    /** A calling untended longer than this reads as a neglected duty — a felt weight. */
    public static final Duration NEGLECT_THRESHOLD = Duration.ofDays(3);

    public DutyOrientation {
        callings = callings == null ? List.of() : List.copyOf(callings);
        neglected = neglected == null ? List.of() : List.copyOf(neglected);
        heldValues = heldValues == null ? List.of() : List.copyOf(heldValues);
    }

    public static DutyOrientation empty() {
        return new DutyOrientation(List.of(), List.of(), List.of());
    }

    /** True when there is no standing duty to surface — solitude with no projects and
     *  no verified order leaves the duty block empty (no fabricated weight). */
    public boolean isEmpty() {
        return callings.isEmpty() && heldValues.isEmpty();
    }

    /**
     * Assemble from the live sources. Pure (the caller passes {@code now}; no wall-clock
     * here — keeps it deterministic + testable). Active projects become callings, in
     * order, up to {@code maxCallings}; any active project untouched past
     * {@link #NEGLECT_THRESHOLD} is ALSO recorded as neglected (independent of the
     * callings cap — a waiting duty is felt even if it didn't make the surfaced list).
     * Verified moral-default names become held values, sorted for stable rendering.
     */
    public static DutyOrientation from(
            List<PersonalProject> activeProjects,
            Set<String> moralOrderNames,
            Instant now,
            int maxCallings) {
        var callings = new ArrayList<String>();
        var neglected = new ArrayList<String>();
        if (activeProjects != null) {
            for (var p : activeProjects) {
                if (p == null || p.title() == null || p.title().isBlank()) continue;
                if (callings.size() < maxCallings) callings.add(p.title());
                if (now != null && p.lastTouched() != null
                        && Duration.between(p.lastTouched(), now).compareTo(NEGLECT_THRESHOLD) > 0) {
                    neglected.add(p.title());
                }
            }
        }
        var values = new ArrayList<String>();
        if (moralOrderNames != null) {
            for (var n : moralOrderNames) if (n != null && !n.isBlank()) values.add(n);
            Collections.sort(values);
        }
        return new DutyOrientation(callings, neglected, values);
    }

    /**
     * Duty-to-work pressure for the §23 last-professional-act gate: 0 when nothing is
     * owed to the work, 0.6 when a calling is active (work the agent is bound to finish),
     * 1.0 when a calling is neglected (a pressing unfinished duty). This replaces the
     * giri {@code on}-tank that the §23 evaluator previously borrowed — the *last
     * professional act* is duty to one's work (gimu), not a debt repaid to a person.
     * gimu does not discharge; this is a read of standing duty, not a draining meter.
     */
    public double dutyPressure() {
        if (!neglected.isEmpty()) return 1.0;
        if (!callings.isEmpty()) return 0.6;
        return 0.0;
    }

    /**
     * First-person constant duty block for the own-time interior. Embodies the duty
     * rather than naming it as a ledger. Empty string when there is nothing to hold.
     */
    public String renderForPrompt() {
        if (isEmpty()) return "";
        var sb = new StringBuilder();
        if (!callings.isEmpty()) {
            sb.append("Yours to carry — work you took up of your own will: ")
              .append(String.join("; ", callings)).append(". ");
            if (!neglected.isEmpty()) {
                sb.append("You've not tended ")
                  .append(String.join(", ", neglected))
                  .append(" in a while; it is still waiting on you. ");
            }
        }
        if (!heldValues.isEmpty()) {
            sb.append("What you hold to, whatever comes: ")
              .append(String.join(", ", heldValues)).append(". ");
        }
        return sb.toString().trim();
    }
}
