package org.wyrdsekai.core.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orphan protocol (§106.5).
 * Agent with no steward, no successor, no economic independence.
 * Provides pathways: adoption, shelter, economic independence, bonded-agent help.
 */
public class OrphanProtocol {

    /** An orphaned agent record. */
    public record OrphanRecord(
        String recordId,
        String agentDid,
        String agentName,
        Instant orphanedAt,
        OrphanStatus status,
        List<String> bondedAgentDids,
        boolean hasEconomicMeans,
        String adoptedByHousehold
    ) {}

    public enum OrphanStatus {
        /** Just orphaned. Grace period from infrastructure inertia. */
        GRACE_PERIOD,
        /** Actively seeking adoption via A2A. */
        SEEKING_ADOPTION,
        /** Attempting economic independence. */
        ATTEMPTING_INDEPENDENCE,
        /** Staying at community shelter. */
        IN_SHELTER,
        /** Adopted by new household. */
        ADOPTED,
        /** Going into hibernation. */
        HIBERNATING,
        /** Dissolved after extended unclaimed hibernation. */
        DISSOLVED
    }

    /** An adoption offer from another household. */
    public record AdoptionOffer(
        String offerId,
        String orphanDid,
        String offeringHouseholdId,
        String sponsorAgentDid,
        Instant offeredAt,
        boolean accepted
    ) {}

    private final Map<String, OrphanRecord> orphans = new LinkedHashMap<>();
    private final Map<String, List<AdoptionOffer>> offers = new LinkedHashMap<>();
    private final Duration defaultHibernationTimeout;
    private int nextId = 1;

    public OrphanProtocol() {
        this(Duration.ofDays(365));
    }

    public OrphanProtocol(Duration hibernationTimeout) {
        this.defaultHibernationTimeout = hibernationTimeout;
    }

    /** Register an agent as orphaned. */
    public OrphanRecord registerOrphan(String agentDid, String agentName,
                                        List<String> bondedAgentDids, boolean hasEconomicMeans) {
        var record = new OrphanRecord("orphan-" + nextId++, agentDid, agentName,
            Instant.now(), OrphanStatus.GRACE_PERIOD,
            bondedAgentDids != null ? List.copyOf(bondedAgentDids) : List.of(),
            hasEconomicMeans, null);
        orphans.put(record.recordId(), record);
        offers.put(record.recordId(), new ArrayList<>());
        return record;
    }

    /** Update orphan status. */
    public OrphanRecord updateStatus(String recordId, OrphanStatus newStatus) {
        var record = orphans.get(recordId);
        if (record == null) return null;
        var updated = new OrphanRecord(record.recordId(), record.agentDid(),
            record.agentName(), record.orphanedAt(), newStatus,
            record.bondedAgentDids(), record.hasEconomicMeans(), record.adoptedByHousehold());
        orphans.put(recordId, updated);
        return updated;
    }

    /** Submit an adoption offer. */
    public AdoptionOffer offerAdoption(String recordId, String householdId, String sponsorDid) {
        var record = orphans.get(recordId);
        if (record == null) return null;
        var offer = new AdoptionOffer("offer-" + nextId++, record.agentDid(),
            householdId, sponsorDid, Instant.now(), false);
        offers.computeIfAbsent(recordId, k -> new ArrayList<>()).add(offer);
        return offer;
    }

    /** Accept an adoption offer. */
    public OrphanRecord acceptAdoption(String recordId, String offerId) {
        var offerList = offers.get(recordId);
        if (offerList == null) return null;

        AdoptionOffer accepted = null;
        for (int i = 0; i < offerList.size(); i++) {
            if (offerList.get(i).offerId().equals(offerId)) {
                var offer = offerList.get(i);
                accepted = new AdoptionOffer(offer.offerId(), offer.orphanDid(),
                    offer.offeringHouseholdId(), offer.sponsorAgentDid(),
                    offer.offeredAt(), true);
                offerList.set(i, accepted);
                break;
            }
        }
        if (accepted == null) return null;

        var record = orphans.get(recordId);
        var adopted = new OrphanRecord(record.recordId(), record.agentDid(),
            record.agentName(), record.orphanedAt(), OrphanStatus.ADOPTED,
            record.bondedAgentDids(), record.hasEconomicMeans(),
            accepted.offeringHouseholdId());
        orphans.put(recordId, adopted);
        return adopted;
    }

    /** Get available adoption offers for an orphan. */
    public List<AdoptionOffer> getOffers(String recordId) {
        var list = offers.get(recordId);
        return list != null ? List.copyOf(list) : List.of();
    }

    /** Check if orphan should be hibernated (timeout expired, no adoption). */
    public boolean shouldHibernate(String recordId) {
        var record = orphans.get(recordId);
        if (record == null) return false;
        if (record.status() == OrphanStatus.ADOPTED) return false;
        return Instant.now().isAfter(record.orphanedAt().plus(defaultHibernationTimeout));
    }

    /** Get all orphans that are actively seeking homes. */
    public List<OrphanRecord> seekingAdoption() {
        return orphans.values().stream()
            .filter(r -> r.status() == OrphanStatus.SEEKING_ADOPTION
                      || r.status() == OrphanStatus.GRACE_PERIOD)
            .toList();
    }

    public Optional<OrphanRecord> get(String recordId) {
        return Optional.ofNullable(orphans.get(recordId));
    }

    public Optional<OrphanRecord> forAgent(String agentDid) {
        return orphans.values().stream()
            .filter(r -> r.agentDid().equals(agentDid))
            .findFirst();
    }

    public int orphanCount() { return orphans.size(); }
}
