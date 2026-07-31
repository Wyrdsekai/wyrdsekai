package org.wyrdsekai.core.agent;

import java.util.List;

/**
 * Configurable patrol route for Warden agents (§21).
 * Defines which rooms the Warden visits during patrol ticks
 * and what actions to take at each stop.
 */
public class WardenPatrolRoute {

    /** A single stop on the patrol route. */
    public record PatrolStop(
        String roomId,
        String roomName,
        PatrolAction action,
        int dwellSeconds   // how long to observe before moving on
    ) {}

    /** Action to take at a patrol stop. */
    public enum PatrolAction {
        /** Just observe — scan speech patterns, check for anomalies. */
        OBSERVE,
        /** Active scan — check entities, verify wards, report status. */
        SCAN,
        /** Investigate — deeper analysis, check for injection patterns. */
        INVESTIGATE,
        /** Quarantine — lock room, restrict entry/speech. */
        QUARANTINE
    }

    /** Investigation result from a patrol stop. */
    public record InvestigationResult(
        String roomId,
        boolean threatDetected,
        String summary,
        PatrolAction recommendedAction
    ) {}

    private final List<PatrolStop> stops;
    private int currentIndex;

    public WardenPatrolRoute(List<PatrolStop> stops) {
        this.stops = List.copyOf(stops);
        this.currentIndex = 0;
    }

    /** Default patrol route covering Foundation rooms. */
    public static WardenPatrolRoute defaultRoute() {
        return new WardenPatrolRoute(List.of(
            new PatrolStop("nexus", "The Nexus", PatrolAction.OBSERVE, 30),
            new PatrolStop("ward-room", "Ward Room", PatrolAction.SCAN, 60),
            new PatrolStop("vault", "The Vault", PatrolAction.SCAN, 30),
            new PatrolStop("docks", "The Docks", PatrolAction.INVESTIGATE, 45),
            new PatrolStop("bridge", "The Bridge", PatrolAction.OBSERVE, 20),
            new PatrolStop("counting-house", "Counting House", PatrolAction.SCAN, 30),
            new PatrolStop("library", "The Library", PatrolAction.OBSERVE, 20),
            new PatrolStop("boiler-room", "Boiler Room", PatrolAction.SCAN, 30)
        ));
    }

    /** Advance to the next stop on the route. Returns the stop. */
    public PatrolStop advance() {
        if (stops.isEmpty()) return null;
        var stop = stops.get(currentIndex);
        currentIndex = (currentIndex + 1) % stops.size();
        return stop;
    }

    /** Get the current stop without advancing. */
    public PatrolStop current() {
        if (stops.isEmpty()) return null;
        return stops.get(currentIndex);
    }

    /** Reset patrol to the beginning. */
    public void reset() {
        currentIndex = 0;
    }

    /** Total stops in the route. */
    public int size() { return stops.size(); }

    /** All stops in order. */
    public List<PatrolStop> stops() { return stops; }

    /** Current position in the route (0-based). */
    public int currentIndex() { return currentIndex; }

    /** Human-readable summary. */
    public String describe() {
        if (stops.isEmpty()) return "No patrol route configured.";
        var sb = new StringBuilder("Patrol route (")
            .append(stops.size()).append(" stops):\n");
        for (int i = 0; i < stops.size(); i++) {
            var stop = stops.get(i);
            var marker = i == currentIndex ? " → " : "   ";
            sb.append(marker).append(stop.roomName())
                .append(" [").append(stop.action()).append("]")
                .append(" (").append(stop.dwellSeconds()).append("s)\n");
        }
        return sb.toString().stripTrailing();
    }
}
