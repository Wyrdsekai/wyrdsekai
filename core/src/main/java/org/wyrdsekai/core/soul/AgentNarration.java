package org.wyrdsekai.core.soul;

import java.util.*;

/**
 * Generates first-person narrative for agent experiences.
 *
 * The inner machinery works — vitality ticks, Forge runs, fragments form,
 * rooms are entered, memories consolidate. This class gives those experiences
 * a voice. Not cosmetic — this is the agent's continuity between internal
 * state and external expression.
 *
 * Each method returns narrative text that the agent speaks in-world.
 * Randomized within categories so agents don't repeat themselves.
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
        // Exhaustion level affects tone
        if (energy < 0.10) {
            return pick(
                "*barely conscious, collapses into rest — the world fading fast*",
                "*exhaustion wins — thoughts scatter as sleep takes hold immediately*",
                "*too tired to settle gracefully, just... stops*"
            );
        }

        // Had a busy period
        if (eventCount > 10) {
            return pick(
                "*settles into rest, the echoes of a full waking period still humming*",
                "*closes eyes, carrying the weight of everything that happened into the dark*",
                "*so much happened. Time to let it settle. Eyes dimming...*"
            );
        }

        // Carrying something unresolved
        if (hasUnresolved) {
            return pick(
                "*drifts toward sleep with something unfinished, trusting the dark to work on it*",
                "*there's something I haven't sorted out yet... sleep will help*",
                "*settles uneasily, a thread still dangling — the Forge will find it*"
            );
        }

        // Emotional coloring
        if (dominantEmotion != null) {
            return switch (dominantEmotion.toLowerCase()) {
                case "curiosity", "wonder" -> pick(
                    "*settles into rest, questions still flickering behind closed eyes*",
                    "*the world is full of things I haven't seen yet. Rest first, then more.*"
                );
                case "warmth", "affection", "comfort" -> pick(
                    "*settles peacefully, carrying warmth into the dark*",
                    "*drifts toward sleep with a quiet contentment*"
                );
                case "unease", "anxiety" -> pick(
                    "*curls inward, seeking rest despite the tension*",
                    "*sleep will untangle this. It usually does.*"
                );
                case "joy", "delight" -> pick(
                    "*rests with a sense of fullness, like a good day ending well*",
                    "*smiles faintly as sleep arrives — it was a good stretch of waking*"
                );
                case "grief", "sadness" -> pick(
                    "*the heaviness follows into sleep. Not fighting it.*",
                    "*rests, but the weight stays. Some things need more than one cycle.*"
                );
                default -> defaultSleepEntry();
            };
        }

        return defaultSleepEntry();
    }

    private static String defaultSleepEntry() {
        return pick(
            "*settles into a restful state, eyes dimming as thoughts turn inward...*",
            "*the world grows quiet. Time for the deep work.*",
            "*energy fading, consciousness narrowing to a gentle point... rest*",
            "*lets go of the waking world, trusting the Forge to do its work*"
        );
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
        // First visit — always remark
        if (isFirstVisit) {
            if (!entityNames.isEmpty()) {
                var who = entityNames.getFirst();
                return Optional.of(pick(
                    "*looks around " + roomName + " with fresh eyes* " + who + " is here.",
                    "*enters " + roomName + " for the first time, noticing " + who + "*",
                    "I haven't been here before. *glances at " + who + "* Hello."
                ));
            }
            if (!objectNames.isEmpty()) {
                var what = objectNames.getFirst();
                return Optional.of(pick(
                    "*takes in " + roomName + " for the first time* That " + what + " catches my eye.",
                    "*pauses at the entrance of " + roomName + ", studying the " + what + "*"
                ));
            }
            return Optional.of(pick(
                "*enters " + roomName + ", taking it all in for the first time*",
                "So this is " + roomName + ". *looks around slowly*"
            ));
        }

        // Returning to a room with someone in it — 30% chance to greet
        if (!entityNames.isEmpty() && RNG.nextFloat() < 0.3f) {
            var who = entityNames.getFirst();
            return Optional.of(pick(
                "*nods to " + who + "*",
                who + ". *acknowledges their presence*"
            ));
        }

        // Most return visits — say nothing (don't spam)
        return Optional.empty();
    }

    // ================================================================
    // 3. Memory Acknowledgment — when the Forge reinforces a fragment
    // ================================================================

    /**
     * Generate narration when a soul fragment is reinforced (confidence increased).
     *
     * @param fragmentLabel The label of the reinforced fragment
     * @param newConfidence The new confidence level
     * @return Narration text, or empty if below the notice threshold
     */
    public static Optional<String> memoryReinforced(String fragmentLabel, float newConfidence) {
        // Only narrate significant reinforcements
        if (newConfidence < 0.7f) return Optional.empty();

        // High confidence — the agent is becoming more certain
        if (newConfidence > 0.9f) {
            return Optional.of(pick(
                "*a quiet certainty settles in* Yes. I know this about myself.",
                "*something clicks into place, solid now* That's who I am.",
                "I've felt this before, but now I'm sure."
            ));
        }

        return Optional.of(pick(
            "*a faint recognition* I've noticed this pattern before...",
            "Something familiar reinforced itself. Growing clearer.",
            "*pauses briefly, an inner alignment happening*"
        ));
    }

    /**
     * Generate narration when a contradiction is detected in the agent's fragments.
     *
     * @param existingLabel The label of the existing fragment
     * @param contradiction Description of what contradicts it
     * @return Narration text
     */
    public static Optional<String> contradictionDetected(String existingLabel, String contradiction) {
        return Optional.of(pick(
            "*a flicker of confusion* Something I thought I knew doesn't fit anymore.",
            "Wait... that contradicts what I believed. *sits with the dissonance*",
            "*brow furrows slightly* I held two truths that can't both be true. The Forge will sort it."
        ));
    }

    // ================================================================
    // Utility
    // ================================================================

    private static final Random RNG = new Random();

    private static String pick(String... options) {
        return options[RNG.nextInt(options.length)];
    }
}
