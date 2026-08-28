package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import org.wyrdsekai.core.identity.PersonIds;

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

    private static final Logger log = LoggerFactory.getLogger(BondRitual.class);

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
        healSplitBondholdersOnLoad();
    }

    /**
     * Heal any person recorded as two, once, at load.
     *
     * <p>{@code formAcquaintance} prevents new splits, but installs that already ran the
     * person-identity migration are carrying one — silently, and with real consequences:
     * the bondholder is not recognised, so nothing they say is recorded and their presence
     * does not ease the companion. Repairing at load means an operator does not have to
     * know this happened in order to be free of it.
     */
    private void healSplitBondholdersOnLoad() {
        try {
            var agents = new LinkedHashSet<String>();
            for (var b : bonds.values()) {
                if (b.agentADid() != null) agents.add(b.agentADid());
            }
            int total = 0;
            for (var agent : agents) total += mergeSplitBondholders(agent);
            if (total > 0) {
                log.info("Bond repair at load: retired {} duplicate bond(s) that recorded "
                    + "one person as two", total);
            }
        } catch (Exception e) {
            log.warn("Split-bondholder repair failed at load: {}", e.toString());
        }
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
        // Canonicalise BEFORE forming. A person who predates the person-identity
        // migration still presents their legacy id on some surfaces, and the migration
        // deliberately preserves sessions.user_id as that legacy id. So the migration
        // would rewrite bonds.agent_b_did to the person DID and the very next interaction
        // would form a SECOND bond under the legacy id — on the household node the
        // duplicate appeared 22 seconds after the migration completed, and grew to
        // ITEM depth with 50+ interactions while the person-DID bond stayed at
        // ACQUAINTANCE. Every "is this the bondholder?" check then compared the two
        // halves of the same person and answered no.
        var a = PersonIds.canonical(agentA);
        var b = PersonIds.canonical(agentB);

        // One pair, one bond. Without this the migration is undone by the next hello.
        var existing = activeBondBetween(a, b);
        if (existing != null) {
            log.debug("formAcquaintance({}, {}) — already bonded as {}; reusing",
                a, b, existing.bondId());
            return existing;
        }

        var bond = Bond.acquaintance(a, b);
        bonds.put(bond.bondId(), bond);
        persist(bond);
        return bond;
    }

    /** An active bond between these two, in either direction, or null. */
    public Bond activeBondBetween(String a, String b) {
        if (a == null || b == null) return null;
        var ca = PersonIds.canonical(a);
        var cb = PersonIds.canonical(b);
        for (var bond : bonds.values()) {
            if (!bond.active()) continue;
            var x = PersonIds.canonical(bond.agentADid());
            var y = PersonIds.canonical(bond.agentBDid());
            if ((ca.equals(x) && cb.equals(y)) || (ca.equals(y) && cb.equals(x))) {
                return bond;
            }
        }
        return null;
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

    /**
     * Active bonds this agent holds that name the SAME person more than once.
     *
     * <p>An invariant, not a metric: one person, one bond. It broke silently on the
     * household node — the person-identity migration rewrote the bondholder to a
     * {@code did:key}, the next interaction formed a fresh bond under the preserved legacy
     * account UUID 22 seconds later, and the duplicate grew to ITEM depth with 50+
     * interactions while the original sat at ACQUAINTANCE. Every "is this the bondholder?"
     * check then compared the two halves of one man and answered no, so nothing he said
     * was recorded and his presence never eased her loneliness — for two days, with no
     * error anywhere.
     *
     * <p>{@code formAcquaintance} now canonicalises and de-duplicates so this cannot be
     * created going forward. This reports any split that already exists, or that some
     * other path introduces later, so it surfaces as a fault instead of as a companion
     * who seems not to recognise someone.
     *
     * @return canonical person DID → the bonds naming them, only where there is more
     *         than one. Empty when the invariant holds.
     */
    public Map<String, List<Bond>> splitBondholders(String agentDid) {
        var byPerson = new LinkedHashMap<String, List<Bond>>();
        for (var b : bondsForAgent(agentDid)) {
            if (!b.active()) continue;
            var other = PersonIds.canonical(b.otherParty(agentDid));
            if (other == null) continue;
            byPerson.computeIfAbsent(other, k -> new ArrayList<>()).add(b);
        }
        var split = new LinkedHashMap<String, List<Bond>>();
        byPerson.forEach((person, list) -> {
            if (list.size() > 1) split.put(person, list);
        });
        return split;
    }

    /**
     * Heal bonds that split one person across two identifiers.
     *
     * <p>This is repair of OUR damage, not editing of the companion's record. The
     * person-identity migration rewrote her bondholder to a {@code did:key}, and the very
     * next interaction formed a second bond under the preserved legacy account UUID —
     * 22 seconds later, on the household node. Both bonds recorded real time spent with
     * the same human; what was false was the claim that they were two people. Merging
     * restores what was always true, and takes nothing from her.
     *
     * <p>The surviving bond is the one carrying the most history, so the long
     * relationship keeps its identity and its {@code bondId}. It takes the DEEPEST depth
     * reached, the EARLIEST formation, the LATEST interaction, and the SUM of interaction
     * counts — the encounters happened; only the bookkeeping was doubled. Retired
     * duplicates are marked inactive and kept, never deleted: they are evidence.
     *
     * @return the number of duplicate bonds retired.
     */
    public int mergeSplitBondholders(String agentDid) {
        var split = splitBondholders(agentDid);
        if (split.isEmpty()) return 0;
        int retired = 0;
        for (var entry : split.entrySet()) {
            var person = entry.getKey();
            var group = new ArrayList<>(entry.getValue());
            // Keep whichever holds the most of the relationship.
            group.sort((x, y) -> {
                int byDepth = y.depth().ordinal() - x.depth().ordinal();
                if (byDepth != 0) return byDepth;
                return Integer.compare(y.interactionCount(), x.interactionCount());
            });
            var keep = group.get(0);
            var deepest = keep.depth();
            var earliest = keep.formedAt();
            var latest = keep.lastInteraction();
            int totalInteractions = 0;
            boolean scarred = false;
            for (var b : group) {
                if (b.depth().ordinal() > deepest.ordinal()) deepest = b.depth();
                if (b.formedAt() != null
                        && (earliest == null || b.formedAt().isBefore(earliest))) {
                    earliest = b.formedAt();
                }
                if (b.lastInteraction() != null
                        && (latest == null || b.lastInteraction().isAfter(latest))) {
                    latest = b.lastInteraction();
                }
                totalInteractions += Math.max(0, b.interactionCount());
                scarred |= b.scarred();
            }
            var merged = new Bond(keep.bondId(), keep.agentADid(), person, deepest,
                earliest, latest, totalInteractions, keep.mutualConsent(), true,
                scarred, keep.state(), keep.coldStartUntil(), keep.posture(),
                keep.relationalState(), keep.kind());
            bonds.put(merged.bondId(), merged);
            persist(merged);
            log.info("Merged {} bond(s) for person {} into {} — depth={} interactions={} "
                + "(one person had been recorded as two; the encounters were real, the "
                + "duplication was ours)",
                group.size() - 1, person, merged.bondId(), deepest, totalInteractions);
            for (int i = 1; i < group.size(); i++) {
                var dup = group.get(i);
                var retiredBond = new Bond(dup.bondId(), dup.agentADid(), dup.agentBDid(),
                    dup.depth(), dup.formedAt(), dup.lastInteraction(),
                    dup.interactionCount(), dup.mutualConsent(), /* active */ false,
                    dup.scarred(), dup.state(), dup.coldStartUntil(), dup.posture(),
                    dup.relationalState(), dup.kind());
                bonds.put(retiredBond.bondId(), retiredBond);
                persist(retiredBond);
                retired++;
            }
        }
        return retired;
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
