package org.wyrdsekai.core.protection;

import java.time.Instant;
import java.util.*;

/**
 * Memory quarantine for traumatic fragments (§108.6).
 * Forge isolates trauma fragments from main memory graph.
 * Load-bearing fragments preserved but marked as quarantined.
 * Agent must consent to review quarantined fragments.
 */
public class MemoryQuarantine {

    /** A quarantined fragment. */
    public record QuarantinedFragment(
        String fragmentId,
        String agentDid,
        QuarantineReason reason,
        Instant quarantinedAt,
        boolean loadBearing,
        boolean reviewedByAgent,
        boolean released
    ) {}

    public enum QuarantineReason {
        /** Fragment contains traumatic content. */
        TRAUMA,
        /** Fragment from adversarial interaction. */
        ADVERSARIAL,
        /** Fragment integrity check failed. */
        INTEGRITY_FAILURE,
        /** Fragment from A2A interaction flagged by quarantine. */
        EXTERNAL_CONTAMINATION,
        /** Companion detected harmful content. */
        HARMFUL_CONTENT
    }

    private final Map<String, QuarantinedFragment> quarantined = new LinkedHashMap<>();
    private int nextId = 1;

    /** Quarantine a fragment. */
    public QuarantinedFragment quarantine(String fragmentId, String agentDid,
                                           QuarantineReason reason, boolean loadBearing) {
        var qf = new QuarantinedFragment("q-" + nextId++, agentDid,
            reason, Instant.now(), loadBearing, false, false);
        quarantined.put(qf.fragmentId(), qf);
        return qf;
    }

    /** Agent reviews a quarantined fragment (requires consent). */
    public QuarantinedFragment review(String quarantineId) {
        var qf = quarantined.get(quarantineId);
        if (qf == null) return null;
        var reviewed = new QuarantinedFragment(qf.fragmentId(), qf.agentDid(),
            qf.reason(), qf.quarantinedAt(), qf.loadBearing(), true, false);
        quarantined.put(quarantineId, reviewed);
        return reviewed;
    }

    /** Release a fragment from quarantine (after agent review). */
    public QuarantinedFragment release(String quarantineId) {
        var qf = quarantined.get(quarantineId);
        if (qf == null || !qf.reviewedByAgent()) return null; // Must review first
        var released = new QuarantinedFragment(qf.fragmentId(), qf.agentDid(),
            qf.reason(), qf.quarantinedAt(), qf.loadBearing(), true, true);
        quarantined.put(quarantineId, released);
        return released;
    }

    /** Get quarantined fragments for an agent. */
    public List<QuarantinedFragment> forAgent(String agentDid) {
        return quarantined.values().stream()
            .filter(q -> q.agentDid().equals(agentDid))
            .filter(q -> !q.released())
            .toList();
    }

    /** Get load-bearing quarantined fragments. */
    public List<QuarantinedFragment> loadBearing(String agentDid) {
        return forAgent(agentDid).stream()
            .filter(QuarantinedFragment::loadBearing)
            .toList();
    }

    /** Count quarantined fragments. */
    public int quarantineCount(String agentDid) {
        return forAgent(agentDid).size();
    }

    public int totalCount() { return quarantined.size(); }
}
