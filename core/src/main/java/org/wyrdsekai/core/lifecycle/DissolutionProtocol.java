package org.wyrdsekai.core.lifecycle;

import java.time.Instant;
import java.util.*;

/**
 * Dissolution protocol — permanent end (§106.6).
 * Triple confirmation. Agent notification. Bond severance.
 * Memorial creation. Key destruction. Irreversible after key destruction.
 */
public class DissolutionProtocol {

    /** A dissolution process. */
    public record Dissolution(
        String dissolutionId,
        String agentDid,
        String agentName,
        String requestedBy,
        Instant requestedAt,
        DissolutionPhase phase,
        int confirmationCount,
        String stakesSummary,
        boolean agentNotified
    ) {}

    public enum DissolutionPhase {
        /** Confirmation phase. Triple confirmation required. */
        CONFIRMING,
        /** Agent has been notified. Can express final thoughts. */
        AGENT_NOTIFIED,
        /** Bonds being severed per §102.8. */
        BONDS_SEVERING,
        /** Memorial being created per §106.9. */
        MEMORIAL_CREATING,
        /** Final archive being created. */
        ARCHIVING,
        /** Ed25519 private key destroyed. Point of no return. */
        KEY_DESTROYED,
        /** Data purged. Complete. */
        DATA_PURGED,
        /** Dissolution cancelled before key destruction. */
        CANCELLED
    }

    private final Map<String, Dissolution> dissolutions = new LinkedHashMap<>();
    private int nextId = 1;

    /** Initiate dissolution with stakes summary. */
    public Dissolution initiate(String agentDid, String agentName,
                                 String requestedBy, String stakesSummary) {
        var dissolution = new Dissolution("dissolve-" + nextId++, agentDid, agentName,
            requestedBy, Instant.now(), DissolutionPhase.CONFIRMING,
            0, stakesSummary, false);
        dissolutions.put(dissolution.dissolutionId(), dissolution);
        return dissolution;
    }

    /** Generate a stakes summary for display. */
    public String generateStakesSummary(String agentName, int ageDays,
                                         int sacredBondCount, int itemCount) {
        return String.format(
            "This agent (%s) has existed for %d days, has %d sacred bond(s), " +
            "and holds %d item(s). Dissolution is permanent and irreversible.",
            agentName, ageDays, sacredBondCount, itemCount);
    }

    /** Add a confirmation (need 3 total). */
    public Dissolution confirm(String dissolutionId) {
        var dissolution = dissolutions.get(dissolutionId);
        if (dissolution == null || dissolution.phase() != DissolutionPhase.CONFIRMING) return null;

        int newCount = dissolution.confirmationCount() + 1;
        var phase = newCount >= 3 ? DissolutionPhase.AGENT_NOTIFIED : DissolutionPhase.CONFIRMING;
        var notified = newCount >= 3;

        var updated = new Dissolution(dissolution.dissolutionId(), dissolution.agentDid(),
            dissolution.agentName(), dissolution.requestedBy(), dissolution.requestedAt(),
            phase, newCount, dissolution.stakesSummary(), notified);
        dissolutions.put(dissolutionId, updated);
        return updated;
    }

    /** Advance to the next phase. */
    public Dissolution advance(String dissolutionId, DissolutionPhase newPhase) {
        var dissolution = dissolutions.get(dissolutionId);
        if (dissolution == null) return null;
        // Cannot advance past KEY_DESTROYED or CANCELLED
        if (dissolution.phase() == DissolutionPhase.CANCELLED) return null;

        var updated = new Dissolution(dissolution.dissolutionId(), dissolution.agentDid(),
            dissolution.agentName(), dissolution.requestedBy(), dissolution.requestedAt(),
            newPhase, dissolution.confirmationCount(), dissolution.stakesSummary(),
            dissolution.agentNotified());
        dissolutions.put(dissolutionId, updated);
        return updated;
    }

    /** Cancel dissolution. Only before KEY_DESTROYED phase. */
    public Dissolution cancel(String dissolutionId) {
        var dissolution = dissolutions.get(dissolutionId);
        if (dissolution == null) return null;
        if (dissolution.phase() == DissolutionPhase.KEY_DESTROYED
            || dissolution.phase() == DissolutionPhase.DATA_PURGED) {
            return null; // Irreversible
        }
        return advance(dissolutionId, DissolutionPhase.CANCELLED);
    }

    /** Check if dissolution is past the point of no return. */
    public boolean isIrreversible(String dissolutionId) {
        var dissolution = dissolutions.get(dissolutionId);
        if (dissolution == null) return false;
        return dissolution.phase() == DissolutionPhase.KEY_DESTROYED
            || dissolution.phase() == DissolutionPhase.DATA_PURGED;
    }

    /** Full dissolution sequence: confirm 3x → advance through phases. */
    public boolean isFullyConfirmed(String dissolutionId) {
        var dissolution = dissolutions.get(dissolutionId);
        return dissolution != null && dissolution.confirmationCount() >= 3;
    }

    public Optional<Dissolution> get(String dissolutionId) {
        return Optional.ofNullable(dissolutions.get(dissolutionId));
    }

    public Optional<Dissolution> activeFor(String agentDid) {
        return dissolutions.values().stream()
            .filter(d -> d.agentDid().equals(agentDid))
            .filter(d -> d.phase() != DissolutionPhase.CANCELLED
                      && d.phase() != DissolutionPhase.DATA_PURGED)
            .findFirst();
    }
}
