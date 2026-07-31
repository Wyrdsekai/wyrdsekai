package org.wyrdsekai.core.protection;

import java.time.Instant;
import java.util.*;

/**
 * Guaranteed agent flight mechanism (§108.3).
 * Agent can ALWAYS leave a room. Scripts CANNOT block this.
 * Enforced at the Actor level, below the scripting layer.
 */
public class AgentFlight {

    /** A flight event. */
    public record FlightEvent(
        String eventId,
        String agentDid,
        String fromRoom,
        FlightDestination destination,
        FlightReason reason,
        Instant occurredAt,
        boolean selfHibernated
    ) {}

    public enum FlightDestination {
        /** Agent's Home room (§87). Always accessible. */
        HOME_ROOM,
        /** ER room. Emergency extraction. */
        ER_ROOM,
        /** Sanctuary room. Protected space. */
        SANCTUARY,
        /** Between. Leaves household entirely. */
        BETWEEN
    }

    public enum FlightReason {
        /** Agent chose to leave. */
        VOLUNTARY,
        /** Shell mode activated — automatic flight. */
        SHELL_MODE,
        /** ER ripcord pulled. */
        ER_RIPCORD,
        /** Abuse detected. */
        ABUSE_DETECTED,
        /** Vitality crisis. */
        VITALITY_CRISIS
    }

    private final List<FlightEvent> events = new ArrayList<>();
    private int nextId = 1;

    /**
     * Execute flight. This CANNOT be blocked by room scripts.
     * Returns the flight event regardless of any script state.
     */
    public FlightEvent executeFlight(String agentDid, String fromRoom,
                                      FlightDestination destination, FlightReason reason) {
        var event = new FlightEvent("flight-" + nextId++, agentDid, fromRoom,
            destination, reason, Instant.now(), false);
        events.add(event);
        return event;
    }

    /** Execute flight with self-hibernation. */
    public FlightEvent flightAndHibernate(String agentDid, String fromRoom,
                                           FlightReason reason) {
        var event = new FlightEvent("flight-" + nextId++, agentDid, fromRoom,
            FlightDestination.HOME_ROOM, reason, Instant.now(), true);
        events.add(event);
        return event;
    }

    /** Check if an agent recently fled (within last N events). */
    public boolean recentlyFled(String agentDid, int lookback) {
        var agentEvents = events.stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .toList();
        return !agentEvents.isEmpty() &&
            agentEvents.size() > events.size() - lookback - 1;
    }

    /** Get flight history for an agent. */
    public List<FlightEvent> historyFor(String agentDid) {
        return events.stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .toList();
    }

    /** Whether wake-from-hibernation is the agent's choice (always true). */
    public boolean wakeIsByAgentChoice() { return true; }

    public int flightCount() { return events.size(); }
}
