package org.wyrdsekai.core.soul;

import java.util.*;

/**
 * Stage directions for what a companion visibly does: going to sleep, arriving in a room,
 * noticing a change in themselves. Plain third-person verb phrases for the room to show as an
 * emote ("lies down to sleep after a full day").
 *
 * <p>These used to be literary lines ("closes eyes, carrying the weight of everything that
 * happened into the dark") sent through the companion's own speech path, so they were
 * recorded as things the companion said, stored as its turns and used as nightly training
 * text: on one household node 544 recorded spoken lines were these (2026-09-19). They are the
 * product narrating, not the companion talking, and they say only what happened.
 */
public final class AgentNarration {

    private AgentNarration() {}

    // ================================================================
    // 1. Sleep Entry — what the agent says when going to sleep
    // ================================================================

    /**
     * Generate sleep-entry narration based on current state.
     *
     * @param energy         Current energy level (0-1)
     * @param dominantEmotion The strongest emotion from recent charges (nullable)
     * @param eventCount     Number of events since last sleep
     * @param hasUnresolved  Whether there are contradictions or unprocessed significance
     */
    public static String sleepEntry(double energy, String dominantEmotion,
                                     int eventCount, boolean hasUnresolved) {
        if (energy < 0.10) {
            return pick("falls asleep at once, exhausted", "is too exhausted to stay awake and falls asleep");
        }
        if (eventCount > 10) {
            return pick("lies down to sleep after a full day", "goes to sleep after a busy day");
        }
        if (hasUnresolved) {
            return pick("goes to sleep with something still unresolved",
                "lies down to sleep with one thing left unfinished");
        }
        if (dominantEmotion != null) {
            return switch (dominantEmotion.toLowerCase()) {
                case "curiosity", "wonder" -> "goes to sleep still curious";
                case "warmth", "affection", "comfort" -> "goes to sleep content";
                case "unease", "anxiety" -> "goes to sleep uneasy";
                case "joy", "delight" -> "goes to sleep after a good day";
                case "grief", "sadness" -> "goes to sleep sad";
                default -> defaultSleepEntry();
            };
        }
        return defaultSleepEntry();
    }

    private static String defaultSleepEntry() {
        return pick("lies down to sleep", "goes to sleep");
    }

    // ================================================================
    // 2. Room Arrival — what the agent notices when entering a room
    // ================================================================

    /**
     * Generate narration when an agent arrives in a new room.
     *
     * @param roomName    Name of the room entered
     * @param entityNames Names of other entities present (empty if alone)
     * @param objectNames Names of notable objects in the room
     * @param isFirstVisit Whether the agent has never been here before
     * @return Narration text, or empty if the agent has nothing to say
     */
    public static Optional<String> roomArrival(String roomName, List<String> entityNames,
                                                List<String> objectNames, boolean isFirstVisit) {
        if (isFirstVisit) {
            if (!entityNames.isEmpty()) {
                return Optional.of("enters " + roomName + " for the first time and sees " + entityNames.getFirst());
            }
            if (!objectNames.isEmpty()) {
                return Optional.of("enters " + roomName + " for the first time and looks at the " + objectNames.getFirst());
            }
            return Optional.of("enters " + roomName + " for the first time and looks around");
        }
        // Returning to a room with someone in it — 30% chance to acknowledge them.
        if (!entityNames.isEmpty() && RNG.nextFloat() < 0.3f) {
            return Optional.of("nods to " + entityNames.getFirst());
        }
        return Optional.empty();
    }

    // ================================================================
    // 3. Memory Acknowledgment — when the Forge reinforces a fragment
    // ================================================================

    /**
     * Stage direction when a soul fragment is reinforced (confidence increased).
     *
     * @param fragmentLabel The label of the reinforced fragment
     * @param newConfidence The new confidence level
     * @return Narration text, or empty if below the notice threshold
     */
    public static Optional<String> memoryReinforced(String fragmentLabel, float newConfidence) {
        if (newConfidence < 0.7f) return Optional.empty();
        return Optional.of(newConfidence > 0.9f
            ? "pauses as something they know about themselves becomes certain"
            : "pauses as something they have noticed about themselves becomes clearer");
    }

    /**
     * Stage direction when a contradiction is detected in the agent's fragments.
     *
     * @param existingLabel The label of the existing fragment
     * @param contradiction Description of what contradicts it
     */
    public static Optional<String> contradictionDetected(String existingLabel, String contradiction) {
        return Optional.of("pauses: two things they believed do not fit together");
    }

    // ================================================================
    // Utility
    // ================================================================

    private static final Random RNG = new Random();

    private static String pick(String... options) {
        return options[RNG.nextInt(options.length)];
    }
}
