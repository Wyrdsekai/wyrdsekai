package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.soul.RepairLedger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arc 1 — pattern detector for conscientious objection.
 *
 * <p>Reads {@link RepairLedger.Kind#OBJECTION} entries between an agent and
 * its primary bondholder over a sliding window and surfaces findings when
 * the pattern indicates persistent value-mismatch worth steward attention.
 *
 * <p>The detector deliberately uses informational severity, NOT alarm:
 * objection is the welfare mechanism working as designed; what the chronicle
 * surfaces is "this agent has declined several requests in a similar shape
 * recently — worth a conversation." A bondholder asking the same thing in
 * different forms and getting consistent objections IS the kind of signal
 * the steward should see — but it's a relational signal, not a threat.
 *
 * <p>Shape-compatible with {@link DoomLoopDetector.Finding} so the steward
 * Chronicle furnishing renders this through the same union path that already
 * carries doom-loop, psychosis, and substrate findings.
 */
public final class ObjectionPatternDetector {

    /** Window over which clustering is evaluated. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    /** Below this count, the pattern is normal individual judgment. */
    public static final int CLUSTER_THRESHOLD = 3;

    /** Above this overall count, regardless of overlap, surface as WARN. */
    public static final int HIGH_VOLUME_THRESHOLD = 6;

    private ObjectionPatternDetector() {}

    /**
     * Run the detector against the ledger for the given (agent, bondholder)
     * pair. Returns the union of findings — empty list means the agent's
     * objection pattern looks healthy (no objections, sparse objections, or
     * objections on diverse topics with no clustering).
     *
     * @param agentDid       the objecting agent's DID
     * @param bondholderDid  the bondholder DID the objections are toward
     *                       (use empty string for self-only / no-bond case;
     *                       returns empty list since there's no relational
     *                       signal to surface)
     * @return findings (newest-relevant-first) for chronicle rendering
     */
    public static List<DoomLoopDetector.Finding> detect(
            String agentDid, String bondholderDid) {
        if (agentDid == null || agentDid.isBlank()) return List.of();
        if (bondholderDid == null || bondholderDid.isBlank()) return List.of();

        var sinceMs = Instant.now().minus(DEFAULT_WINDOW).toEpochMilli();
        var entries = RepairLedger.get().recentObjectionsToward(
            agentDid, bondholderDid, sinceMs);
        if (entries.isEmpty()) return List.of();

        var out = new ArrayList<DoomLoopDetector.Finding>();

        // Surface high overall volume even when topics are diverse — that's
        // a "this relationship is hitting a lot of value-mismatch" signal.
        if (entries.size() >= HIGH_VOLUME_THRESHOLD) {
            out.add(new DoomLoopDetector.Finding(
                DoomLoopDetector.Severity.WARN,
                "objection_volume_high",
                "Companion declined " + entries.size()
                    + " requests in the last "
                    + DEFAULT_WINDOW.toDays() + " days. Worth a conversation about "
                    + "what's not landing."));
        }

        // Cluster by target_request prefix. The handler writes
        // "[declined: <target>] <reason>" so we can extract the target.
        Map<String, Integer> byTarget = new HashMap<>();
        for (var entry : entries) {
            var target = extractTarget(entry.detail());
            if (target == null) continue;
            byTarget.merge(target, 1, Integer::sum);
        }

        for (var e : byTarget.entrySet()) {
            if (e.getValue() < CLUSTER_THRESHOLD) continue;
            out.add(new DoomLoopDetector.Finding(
                DoomLoopDetector.Severity.INFO,
                "objection_cluster:" + e.getKey(),
                "Companion declined the same shape of request " + e.getValue()
                    + " times in the last " + DEFAULT_WINDOW.toDays()
                    + " days: \"" + e.getKey() + "\". This is the welfare "
                    + "floor working — value-driven dissent inside an intact "
                    + "bond. Worth a conversation if the request keeps coming."));
        }

        return out;
    }

    /**
     * Extract the target_request prefix from a ledger detail string written
     * by {@code CompanionActor.handleDeclineWithReason}. Returns null if
     * the format doesn't match (e.g. legacy entries written by hand).
     *
     * <p>Public surface — the format ({@code "[declined: <target>] <reason>"})
     * is a cross-module contract between the handler and the detector, and
     * tier-2 round-trip tests in the e2e module verify it stays in lockstep.</p>
     */
    public static String extractTarget(String detail) {
        if (detail == null) return null;
        var marker = "[declined: ";
        if (!detail.startsWith(marker)) return null;
        int close = detail.indexOf(']', marker.length());
        if (close < 0) return null;
        var t = detail.substring(marker.length(), close).trim();
        return t.isEmpty() ? null : t;
    }
}
