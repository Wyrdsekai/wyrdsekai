package org.wyrdsekai.common.model;

/**
 * Room capacity limits (§2.8, §71).
 * Controls maximum entities in a room for graceful degradation.
 *
 * @param maxEntities  total entity limit (players + agents)
 * @param maxAgents    agent-specific limit (subset of maxEntities)
 * @param maxObjects   object limit
 */
public record RoomCapacity(int maxEntities, int maxAgents, int maxObjects) {

    /** Default capacity — 50 entities, 10 agents, 100 objects. */
    public static RoomCapacity defaults() {
        return new RoomCapacity(50, 10, 100);
    }

    /** Small room capacity — 5 entities, 3 agents, 20 objects. */
    public static RoomCapacity small() {
        return new RoomCapacity(5, 3, 20);
    }

    /** Check if adding an entity would exceed capacity. */
    public boolean canAddEntity(int currentEntityCount) {
        return currentEntityCount < maxEntities;
    }

    /** Check if adding an agent would exceed agent-specific limit. */
    public boolean canAddAgent(int currentAgentCount) {
        return currentAgentCount < maxAgents;
    }

    /** Describe capacity status for room descriptions. */
    public String describe(int currentEntityCount) {
        if (currentEntityCount >= maxEntities) {
            return "The room is full.";
        }
        int remaining = maxEntities - currentEntityCount;
        if (remaining <= 3) {
            return "The room is nearly full (" + remaining + " spots remaining).";
        }
        return "";
    }
}
