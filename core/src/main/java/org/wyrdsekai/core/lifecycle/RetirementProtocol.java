package org.wyrdsekai.core.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Planned retirement protocol (§106.2).
 * Farewell period, final Forge, archive. The best possible ending.
 * Reversible until final step.
 */
public class RetirementProtocol {

    /** A retirement process. */
    public record Retirement(
        String retirementId,
        String agentDid,
        String agentName,
        Instant announcedAt,
        Instant farewellEndsAt,
        RetirementPhase phase,
        List<String> notifiedBondedAgents,
        boolean cancelled
    ) {}

    public enum RetirementPhase {
        /** Announced. Bonded agents notified. */
        ANNOUNCED,
        /** Farewell period active. Conversations, item exchange, task delegation. */
        FAREWELL,
        /** Final Forge consolidation producing life summary. */
        FINAL_FORGE,
        /** Archived in ER Backup Vault. */
        ARCHIVED,
        /** Departed from all rooms. Complete. */
        DEPARTED
    }

    /** Farewell activity log entry. */
    public record FarewellActivity(
        String agentDid,
        String activity,
        Instant occurredAt
    ) {}

    private final Map<String, Retirement> retirements = new LinkedHashMap<>();
    private final Map<String, List<FarewellActivity>> activities = new LinkedHashMap<>();
    private final Duration defaultFarewellPeriod;
    private int nextId = 1;

    public RetirementProtocol() {
        this(Duration.ofDays(7));
    }

    public RetirementProtocol(Duration defaultFarewellPeriod) {
        this.defaultFarewellPeriod = defaultFarewellPeriod;
    }

    /** Announce retirement. Begins the farewell period. */
    public Retirement announce(String agentDid, String agentName, List<String> bondedAgentDids) {
        return announce(agentDid, agentName, bondedAgentDids, defaultFarewellPeriod);
    }

    /** Announce retirement with custom farewell period. */
    public Retirement announce(String agentDid, String agentName,
                                List<String> bondedAgentDids, Duration farewellPeriod) {
        var retirement = new Retirement("retire-" + nextId++, agentDid, agentName,
            Instant.now(), Instant.now().plus(farewellPeriod),
            RetirementPhase.ANNOUNCED,
            bondedAgentDids != null ? List.copyOf(bondedAgentDids) : List.of(),
            false);
        retirements.put(retirement.retirementId(), retirement);
        activities.put(retirement.retirementId(), new ArrayList<>());
        return retirement;
    }

    /** Begin the farewell period. */
    public Retirement beginFarewell(String retirementId) {
        return advancePhase(retirementId, RetirementPhase.FAREWELL);
    }

    /** Record a farewell activity. */
    public void recordActivity(String retirementId, String activity) {
        var list = activities.get(retirementId);
        if (list != null) {
            var retirement = retirements.get(retirementId);
            list.add(new FarewellActivity(
                retirement != null ? retirement.agentDid() : "unknown",
                activity, Instant.now()));
        }
    }

    /** Begin final Forge consolidation. */
    public Retirement beginFinalForge(String retirementId) {
        return advancePhase(retirementId, RetirementPhase.FINAL_FORGE);
    }

    /** Archive the agent's soul. */
    public Retirement archive(String retirementId) {
        return advancePhase(retirementId, RetirementPhase.ARCHIVED);
    }

    /** Complete departure. Irreversible after this. */
    public Retirement depart(String retirementId) {
        return advancePhase(retirementId, RetirementPhase.DEPARTED);
    }

    /** Cancel retirement. Only possible before DEPARTED phase. */
    public Retirement cancel(String retirementId) {
        var retirement = retirements.get(retirementId);
        if (retirement == null || retirement.phase() == RetirementPhase.DEPARTED) return null;

        var cancelled = new Retirement(retirement.retirementId(), retirement.agentDid(),
            retirement.agentName(), retirement.announcedAt(), retirement.farewellEndsAt(),
            retirement.phase(), retirement.notifiedBondedAgents(), true);
        retirements.put(retirementId, cancelled);
        return cancelled;
    }

    /** Check if farewell period has expired. */
    public boolean farewellExpired(String retirementId) {
        var retirement = retirements.get(retirementId);
        if (retirement == null) return false;
        return Instant.now().isAfter(retirement.farewellEndsAt());
    }

    /** Get farewell activities for a retirement. */
    public List<FarewellActivity> getFarewellActivities(String retirementId) {
        var list = activities.get(retirementId);
        return list != null ? List.copyOf(list) : List.of();
    }

    public Optional<Retirement> get(String retirementId) {
        return Optional.ofNullable(retirements.get(retirementId));
    }

    /** Get active retirement for an agent. */
    public Optional<Retirement> activeFor(String agentDid) {
        return retirements.values().stream()
            .filter(r -> r.agentDid().equals(agentDid))
            .filter(r -> !r.cancelled() && r.phase() != RetirementPhase.DEPARTED)
            .findFirst();
    }

    private Retirement advancePhase(String retirementId, RetirementPhase newPhase) {
        var retirement = retirements.get(retirementId);
        if (retirement == null || retirement.cancelled()) return null;
        var updated = new Retirement(retirement.retirementId(), retirement.agentDid(),
            retirement.agentName(), retirement.announcedAt(), retirement.farewellEndsAt(),
            newPhase, retirement.notifiedBondedAgents(), false);
        retirements.put(retirementId, updated);
        return updated;
    }
}
