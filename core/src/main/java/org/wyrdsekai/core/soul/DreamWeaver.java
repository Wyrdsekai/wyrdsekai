package org.wyrdsekai.core.soul;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A plain report of what a sleep cycle did: which topics were most present, whether a new
 * fragment formed, which feeling was strongest, how many memories were merged.
 *
 * <p>This used to compose literary first-person lines ("The dream was heavy. Something I'm
 * carrying, still.") that the companion then spoke on waking. They were the product's words
 * recorded as the companion's own speech, and they fed the nightly training text. The report
 * now states the facts, is written to the companion's journal, and is not spoken.
 */
public final class DreamWeaver {

    private DreamWeaver() {}

    /**
     * Generate a dream narrative from the Forge's output.
     *
     * @param manifest     The newly forged manifest (post-sleep)
     * @param memoryBefore Memory state before consolidation
     * @param memoryAfter  Memory state after consolidation
     * @return A dream narrative, or empty if there's nothing to dream about
     */
    public static Optional<String> weave(SoulManifest manifest,
                                          CompactedMemory memoryBefore,
                                          CompactedMemory memoryAfter) {
        if (manifest == null) return Optional.empty();

        var elements = new ArrayList<String>();

        // 1. Topic affinities — what was on the agent's mind
        if (manifest.fingerprint() != null && manifest.fingerprint().topicAffinities() != null) {
            var topics = manifest.fingerprint().topicAffinities().entrySet().stream()
                .filter(e -> e.getValue() > 0.3f)
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
            if (!topics.isEmpty()) {
                elements.add(topicDream(topics));
            }
        }

        // 2. New fragments born this cycle — identity crystallizing
        if (manifest.soulFragments() != null && !manifest.soulFragments().isEmpty()) {
            var recentFragments = manifest.soulFragments().stream()
                .filter(f -> f.firstObserved() != null && f.reinforcementCount() != null
                    && f.reinforcementCount() <= 1)
                .limit(2)
                .toList();
            if (!recentFragments.isEmpty()) {
                elements.add(fragmentDream(recentFragments));
            }
        }

        // 3. Emotional profile — the feeling tone of the dream
        if (manifest.fingerprint() != null && manifest.fingerprint().emotionalResponseProfile() != null) {
            var emotions = manifest.fingerprint().emotionalResponseProfile().entrySet().stream()
                .filter(e -> e.getValue() > 0.4f)
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(2)
                .map(Map.Entry::getKey)
                .toList();
            if (!emotions.isEmpty()) {
                elements.add(emotionDream(emotions));
            }
        }

        // 4. Memory consolidation — if memories were pruned or merged
        if (memoryBefore != null && memoryAfter != null) {
            int before = memoryBefore.nodes() != null ? memoryBefore.nodes().size() : 0;
            int after = memoryAfter.nodes() != null ? memoryAfter.nodes().size() : 0;
            if (before > after && before > 0) {
                elements.add(consolidationDream(before - after));
            }
        }

        // 5. Relationships — if any are present
        if (manifest.relationships() != null && !manifest.relationships().isEmpty()) {
            var names = manifest.relationships().stream()
                .map(Relationship::entityName)
                .filter(Objects::nonNull)
                .limit(2)
                .toList();
            if (!names.isEmpty()) {
                elements.add(relationshipDream(names));
            }
        }

        if (elements.isEmpty()) return Optional.empty();

        // Every element, in the order gathered: a report does not vary for effect.
        return Optional.of("While I slept: " + String.join(" ", elements));
    }

    // --- Report elements: one plain sentence each ---

    private static String topicDream(List<String> topics) {
        return "most on my mind: " + String.join(", ", topics) + ".";
    }

    private static String fragmentDream(List<SoulFragment> fragments) {
        var fragment = fragments.getFirst();
        return fragment.category() != null && fragment.category().contains("memory")
            ? "one memory became a lasting one."
            : "one new piece of understanding about myself formed.";
    }

    private static String emotionDream(List<String> emotions) {
        return "strongest feeling: " + emotions.getFirst().toLowerCase() + ".";
    }

    private static String consolidationDream(int pruned) {
        return pruned == 1 ? "one memory was merged into others." : pruned + " memories were merged.";
    }

    private static String relationshipDream(List<String> names) {
        return "people on my mind: " + String.join(", ", names) + ".";
    }
}
