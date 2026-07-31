package org.wyrdsekai.core.item;

import org.wyrdsekai.core.empathy.EpigeneticModifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-agent time-in-room for environmental imprinting.
 *
 * When time-in-room crosses a threshold, records an impression on the
 * agent's EpigeneticModifier (existing system handles exposure counting
 * and genome modification). Runs inside CompanionActor on each VitalityTick.
 */
public class RoomImprintTracker {

    /** Default imprint threshold in ticks (~5 minutes at 1 tick/second). */
    public static final int DEFAULT_THRESHOLD = 300;

    /**
     * Room imprint definition — traits and thresholds.
     */
    public record RoomImprint(
        String roomId,
        Map<String, Double> traits,
        String description,
        int threshold
    ) {
        public RoomImprint {
            if (threshold <= 0) threshold = DEFAULT_THRESHOLD;
            if (traits == null) traits = Map.of();
        }
    }

    /** Per-agent room state. */
    private record AgentRoomState(String currentRoomId, int ticksInRoom) {}

    private final Map<String, AgentRoomState> agentStates = new ConcurrentHashMap<>();
    private final Map<String, RoomImprint> imprints = new ConcurrentHashMap<>();

    /** Register a room's imprint data. */
    public void registerImprint(RoomImprint imprint) {
        if (imprint != null) {
            imprints.put(imprint.roomId(), imprint);
        }
    }

    /** Register imprint data for a room. */
    public void registerImprint(String roomId, Map<String, Double> traits,
                                 String description, int threshold) {
        registerImprint(new RoomImprint(roomId, traits, description, threshold));
    }

    /**
     * Tick for an agent — called on each VitalityTick.
     *
     * @param agentId    Agent identifier
     * @param roomId     Current room
     * @param modifier   EpigeneticModifier for recording impressions (nullable)
     * @return true if a new impression was recorded this tick
     */
    public boolean tick(String agentId, String roomId, EpigeneticModifier modifier) {
        if (agentId == null || roomId == null) return false;

        var state = agentStates.get(agentId);

        // Room changed — reset counter
        if (state == null || !roomId.equals(state.currentRoomId())) {
            agentStates.put(agentId, new AgentRoomState(roomId, 1));
            return false;
        }

        // Increment time in room
        int newTicks = state.ticksInRoom() + 1;
        agentStates.put(agentId, new AgentRoomState(roomId, newTicks));

        // Check threshold
        var imprint = imprints.get(roomId);
        if (imprint == null) return false;

        if (newTicks == imprint.threshold()) {
            // Record impression via EpigeneticModifier
            if (modifier != null) {
                // charge = 0.5 (moderate environmental impression)
                modifier.recordImpression(agentId, imprint.description(), 0.5, imprint.traits());
            }
            return true;
        }

        return false;
    }

    /** Get current ticks in room for an agent. */
    public int ticksInRoom(String agentId) {
        var state = agentStates.get(agentId);
        return state != null ? state.ticksInRoom() : 0;
    }

    /** Get current room for an agent. */
    public String currentRoom(String agentId) {
        var state = agentStates.get(agentId);
        return state != null ? state.currentRoomId() : null;
    }

    /** Number of registered room imprints. */
    public int imprintCount() {
        return imprints.size();
    }

    /** Check if a room has imprint data. */
    public boolean hasImprint(String roomId) {
        return imprints.containsKey(roomId);
    }
}
