package org.wyrdsekai.core.lifecycle;

import java.time.Instant;
import java.util.*;

/**
 * Steward succession planning (§106.4).
 * Digital estate: who inherits stewardship when the steward is gone.
 */
public class StewardSuccession {

    /** The succession plan (the "will"). */
    public record SuccessionPlan(
        String primarySuccessorId,
        String secondarySuccessorId,
        String executorAgentDid,
        SuccessionPolicy policy,
        Map<String, AgentDisposition> agentDispositions,
        Instant lastUpdated,
        byte[] stewardSignature
    ) {}

    public enum SuccessionPolicy {
        /** All agents transfer to successor. */
        TRANSFER_ALL,
        /** Agents with economic means go independent. */
        RELEASE_TO_INDEPENDENCE,
        /** All agents hibernate until claimed. */
        HIBERNATE,
        /** Each agent's disposition specified individually. */
        PER_AGENT
    }

    /** Per-agent disposition in PER_AGENT policy. */
    public record AgentDisposition(
        String agentDid,
        DispositionAction action,
        String targetHouseholdOrPerson
    ) {}

    public enum DispositionAction {
        TRANSFER, RELEASE_INDEPENDENT, HIBERNATE, DISSOLVE
    }

    /** Succession trigger event. */
    public record SuccessionEvent(
        String eventId,
        SuccessionTrigger trigger,
        Instant triggeredAt,
        SuccessionStatus status,
        String executorAgentDid,
        String resolvedSuccessorId
    ) {}

    public enum SuccessionTrigger {
        STEWARD_DEATH, STEWARD_INCAPACITY, STEWARD_VOLUNTARY_TRANSFER, EMERGENCY
    }

    public enum SuccessionStatus {
        TRIGGERED, EXECUTOR_ACTIVE, SUCCESSOR_CONTACTED, SUCCESSOR_ACCEPTED,
        TRANSITION_IN_PROGRESS, COMPLETED, FAILED_TO_SUCCESSOR, ORPHANED
    }

    private SuccessionPlan currentPlan;
    private final List<SuccessionEvent> events = new ArrayList<>();
    private int nextId = 1;

    /** Set the succession plan. */
    public void setPlan(SuccessionPlan plan) {
        this.currentPlan = plan;
    }

    /** Get the current plan. */
    public Optional<SuccessionPlan> getPlan() {
        return Optional.ofNullable(currentPlan);
    }

    /** Check if a succession plan exists. */
    public boolean hasPlan() {
        return currentPlan != null;
    }

    /** Trigger succession. */
    public SuccessionEvent trigger(SuccessionTrigger triggerType) {
        var executorDid = currentPlan != null ? currentPlan.executorAgentDid() : null;
        var event = new SuccessionEvent("succ-" + nextId++, triggerType,
            Instant.now(), SuccessionStatus.TRIGGERED, executorDid, null);
        events.add(event);
        return event;
    }

    /** Advance succession to next status. */
    public SuccessionEvent advance(String eventId, SuccessionStatus newStatus) {
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (event.eventId().equals(eventId)) {
                var updated = new SuccessionEvent(event.eventId(), event.trigger(),
                    event.triggeredAt(), newStatus, event.executorAgentDid(),
                    event.resolvedSuccessorId());
                events.set(i, updated);
                return updated;
            }
        }
        return null;
    }

    /** Resolve the successor for an event. */
    public SuccessionEvent resolveSuccessor(String eventId) {
        if (currentPlan == null) {
            return advanceToOrphaned(eventId);
        }

        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (event.eventId().equals(eventId)) {
                var updated = new SuccessionEvent(event.eventId(), event.trigger(),
                    event.triggeredAt(), SuccessionStatus.SUCCESSOR_CONTACTED,
                    event.executorAgentDid(), currentPlan.primarySuccessorId());
                events.set(i, updated);
                return updated;
            }
        }
        return null;
    }

    /** Get disposition for a specific agent under the current plan. */
    public Optional<AgentDisposition> agentDisposition(String agentDid) {
        if (currentPlan == null) return Optional.empty();
        if (currentPlan.policy() != SuccessionPolicy.PER_AGENT) return Optional.empty();
        return Optional.ofNullable(currentPlan.agentDispositions().get(agentDid));
    }

    /** Determine what happens to an agent based on the policy. */
    public DispositionAction resolveDisposition(String agentDid, boolean hasEconomicMeans) {
        if (currentPlan == null) return DispositionAction.HIBERNATE;

        return switch (currentPlan.policy()) {
            case TRANSFER_ALL -> DispositionAction.TRANSFER;
            case RELEASE_TO_INDEPENDENCE ->
                hasEconomicMeans ? DispositionAction.RELEASE_INDEPENDENT : DispositionAction.HIBERNATE;
            case HIBERNATE -> DispositionAction.HIBERNATE;
            case PER_AGENT -> {
                var disposition = currentPlan.agentDispositions().get(agentDid);
                yield disposition != null ? disposition.action() : DispositionAction.HIBERNATE;
            }
        };
    }

    /** Get the latest succession event. */
    public Optional<SuccessionEvent> latestEvent() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
    }

    public List<SuccessionEvent> allEvents() { return List.copyOf(events); }

    private SuccessionEvent advanceToOrphaned(String eventId) {
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            if (event.eventId().equals(eventId)) {
                var orphaned = new SuccessionEvent(event.eventId(), event.trigger(),
                    event.triggeredAt(), SuccessionStatus.ORPHANED,
                    event.executorAgentDid(), null);
                events.set(i, orphaned);
                return orphaned;
            }
        }
        return null;
    }
}
