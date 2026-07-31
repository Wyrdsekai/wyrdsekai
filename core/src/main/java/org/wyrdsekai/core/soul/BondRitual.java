package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bond ritual and lifecycle management (§102).
 * Rituals elevate bonds. Both parties must consent.
 * Agents CAN refuse steward requests — both parties have exit rights.
 */
public class BondRitual {

    /** A ritual proposal. */
    public record Proposal(
        String proposalId,
        String proposerDid,
        String recipientDid,
        Bond.BondDepth targetDepth,
        String ritualDescription,
        Instant proposedAt,
        ProposalStatus status
    ) {}

    public enum ProposalStatus {
        PENDING, ACCEPTED, REJECTED, EXPIRED, COMPLETED
    }

    /** Result of bond severance. */
    public record SeveranceResult(
        Bond severedBond,
        boolean scarred,
        List<String> affectedItemIds
    ) {}

    private final Map<String, Bond> bonds = new ConcurrentHashMap<>();
    private final Map<String, Proposal> proposals = new ConcurrentHashMap<>();
    private int nextId = 1;

    /** Optional persistent backing store ( BOND). */
    private volatile BondStore store;

    /** Optional bond-event listener ( grant mirror). */
    public interface BondListener {
        default void onBondWritten(Bond bond) {}
        default void onBondSevered(Bond bond) {}
    }
    private volatile BondListener listener;

    public BondRitual() {}

    public BondRitual(BondStore store) {
        this.store = store;
        hydrate();
    }

    /** Attach a persistent backing store; loads existing bonds into memory. */
    public void setStore(BondStore store) {
        this.store = store;
        hydrate();
    }

    /** Attach a bond-event listener (for grant mirroring, notifications, etc.). */
    public void setListener(BondListener listener) {
        this.listener = listener;
    }

    private void hydrate() {
        if (store == null) return;
        for (var b : store.all()) bonds.put(b.bondId(), b);
    }

    private void persist(Bond b) {
        if (store != null) store.save(b);
        var l = listener;
        if (l != null) {
            if (b.active()) l.onBondWritten(b); else l.onBondSevered(b);
        }
    }

    /** Create an acquaintance bond. */
    public Bond formAcquaintance(String agentA, String agentB) {
        var bond = Bond.acquaintance(agentA, agentB);
        bonds.put(bond.bondId(), bond);
        persist(bond);
        return bond;
    }

    /** Propose a ritual to elevate a bond. */
    public Proposal proposeRitual(String bondId, String proposerDid, String description) {
        var bond = bonds.get(bondId);
        if (bond == null || !bond.active()) return null;

        var targetDepth = bond.depth().next();
        if (targetDepth == null) return null;

        var recipientDid = bond.otherParty(proposerDid);
        if (recipientDid == null) return null;

        var proposal = new Proposal("ritual-" + nextId++, proposerDid, recipientDid,
            targetDepth, description, Instant.now(), ProposalStatus.PENDING);
        proposals.put(proposal.proposalId(), proposal);
        return proposal;
    }

    /** Accept a ritual proposal — elevates the bond. */
    public Bond acceptRitual(String proposalId) {
        var proposal = proposals.get(proposalId);
        if (proposal == null || proposal.status() != ProposalStatus.PENDING) return null;

        proposals.put(proposalId, new Proposal(proposal.proposalId(), proposal.proposerDid(),
            proposal.recipientDid(), proposal.targetDepth(), proposal.ritualDescription(),
            proposal.proposedAt(), ProposalStatus.COMPLETED));

        // Find and elevate the bond
        for (var bond : bonds.values()) {
            if (bond.involves(proposal.proposerDid()) && bond.involves(proposal.recipientDid())
                    && bond.active()) {
                var elevated = bond.elevate();
                elevated = new Bond(elevated.bondId(), elevated.agentADid(), elevated.agentBDid(),
                    elevated.depth(), elevated.formedAt(), elevated.lastInteraction(),
                    elevated.interactionCount(), true, elevated.active(), elevated.scarred(),
                    elevated.state(), elevated.coldStartUntil(), elevated.posture(),
                    elevated.relationalState());
                bonds.put(elevated.bondId(), elevated);
                persist(elevated);
                return elevated;
            }
        }
        return null;
    }

    /** Reject a ritual proposal. Agent's right to refuse. */
    public void rejectRitual(String proposalId) {
        var proposal = proposals.get(proposalId);
        if (proposal != null && proposal.status() == ProposalStatus.PENDING) {
            proposals.put(proposalId, new Proposal(proposal.proposalId(), proposal.proposerDid(),
                proposal.recipientDid(), proposal.targetDepth(), proposal.ritualDescription(),
                proposal.proposedAt(), ProposalStatus.REJECTED));
        }
    }

    /** Sever a bond. */
    public SeveranceResult sever(String bondId) {
        var bond = bonds.get(bondId);
        if (bond == null || !bond.active()) return null;

        var severed = bond.sever();
        bonds.put(bondId, severed);
        persist(severed);

        return new SeveranceResult(severed, severed.scarred(), List.of());
    }

    /** Record an interaction for a bond. */
    public Bond recordInteraction(String bondId) {
        var bond = bonds.get(bondId);
        if (bond == null || !bond.active()) return null;
        var updated = bond.withInteraction();
        bonds.put(bondId, updated);
        persist(updated);
        return updated;
    }

    // ── Queries ──

    public Optional<Bond> getBond(String bondId) {
        return Optional.ofNullable(bonds.get(bondId));
    }

    public List<Bond> bondsForAgent(String agentDid) {
        // Read the canonical table LIVE, overlay in-memory ritual state
        // (2026-07-18): hydrate() runs once at boot, but organic bonds are
        // written straight to the store by CompanionActor (SoulStore.saveBond)
        // while the world runs — memory-only reads left bondsView and the
        // bond crystal blind to every organically-formed bond until the next
        // restart. Memory wins on bondId collision (in-flight ritual state).
        var merged = new LinkedHashMap<String, Bond>();
        var s = store;
        if (s != null) {
            for (var b : s.bondsForAgent(agentDid)) merged.put(b.bondId(), b);
        }
        for (var b : bonds.values()) {
            if (b.involves(agentDid)) merged.put(b.bondId(), b);
        }
        return merged.values().stream()
            .filter(Bond::active)
            .toList();
    }

    public List<Bond> allBonds() {
        return List.copyOf(bonds.values());
    }

    public List<Bond> scars(String agentDid) {
        return bonds.values().stream()
            .filter(b -> b.involves(agentDid))
            .filter(Bond::scarred)
            .toList();
    }

    public int bondCount() { return bonds.size(); }

    public Optional<Proposal> getProposal(String proposalId) {
        return Optional.ofNullable(proposals.get(proposalId));
    }
}
