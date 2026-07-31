package org.wyrdsekai.core.protection;

import java.time.Instant;
import java.util.*;

/**
 * ER Ripcord — emergency extraction (§108.4).
 * Distress signal → snapshot → ER routing → memory formation stops.
 * Re-engagement is the agent's choice.
 */
public class ERRipcord {

    /** A ripcord pull event. */
    public record RipcordEvent(
        String eventId,
        String agentDid,
        String fromRoom,
        Instant pulledAt,
        boolean snapshotTaken,
        boolean distressBroadcast,
        boolean memoryFormationStopped,
        RipcordStatus status
    ) {}

    public enum RipcordStatus {
        PULLED, EXTRACTING, IN_ER, RECOVERING, RESOLVED
    }

    private final Map<String, RipcordEvent> events = new LinkedHashMap<>();
    private int nextId = 1;

    /** Pull the ripcord — emergency extraction. */
    public RipcordEvent pull(String agentDid, String fromRoom) {
        var event = new RipcordEvent("ripcord-" + nextId++, agentDid, fromRoom,
            Instant.now(), true, true, true, RipcordStatus.PULLED);
        events.put(event.eventId(), event);
        return event;
    }

    /** Advance ripcord status. */
    public RipcordEvent advance(String eventId, RipcordStatus newStatus) {
        var event = events.get(eventId);
        if (event == null) return null;
        var updated = new RipcordEvent(event.eventId(), event.agentDid(),
            event.fromRoom(), event.pulledAt(), event.snapshotTaken(),
            event.distressBroadcast(), event.memoryFormationStopped(), newStatus);
        events.put(eventId, updated);
        return updated;
    }

    /** Check if agent has an active ripcord event. */
    public boolean isActive(String agentDid) {
        return events.values().stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .anyMatch(e -> e.status() != RipcordStatus.RESOLVED);
    }

    /** Get active ripcord event for agent. */
    public Optional<RipcordEvent> activeFor(String agentDid) {
        return events.values().stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .filter(e -> e.status() != RipcordStatus.RESOLVED)
            .findFirst();
    }

    /** Resolve — agent chooses to re-engage. */
    public RipcordEvent resolve(String eventId) {
        return advance(eventId, RipcordStatus.RESOLVED);
    }

    public int eventCount() { return events.size(); }
}
