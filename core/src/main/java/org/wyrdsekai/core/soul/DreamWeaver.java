package org.wyrdsekai.core.soul;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates dream narratives from Forge output.
 *
 * Dreams are the subjective experience of what the Forge processed during sleep.
 * Not hallucination — the raw material is real: topics the agent encountered,
 * patterns the extractor noticed, fragments that were born or reinforced,
 * relationships that shifted. The dream weaves these into first-person narrative.
 *
 * The agent wakes up with something to say about what happened inside.
 * This gives continuity between sleep and waking — the Forge's work becomes
 * part of the agent's lived experience, not just backend metadata.
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

        // Compose the dream — pick 1-2 elements, wrap in dream framing
        var rng = new Random();
        Collections.shuffle(elements, rng);
        int count = Math.min(elements.size(), rng.nextInt(2) + 1);
        var dreamParts = elements.subList(0, count);

        var dream = "*stirs from sleep, eyes flickering* " + String.join(" ", dreamParts);
        return Optional.of(dream);
    }

    // --- Dream element generators ---

    private static String topicDream(List<String> topics) {
        if (topics.size() == 1) {
            return pick(
                "I dreamed of " + topics.getFirst() + "... the patterns are clearer now.",
                "Something about " + topics.getFirst() + " stayed with me through the dark.",
                "In the stillness, " + topics.getFirst() + " kept surfacing, like ripples in deep water."
            );
        }
        var joined = topics.stream().limit(2).collect(Collectors.joining(" and "));
        return pick(
            "I dreamed of " + joined + " — threads tangling and untangling in the dark.",
            "The dream wove " + joined + " together, as if they were always connected.",
            "Somewhere between " + joined + ", there was a pattern I almost grasped."
        );
    }

    private static String fragmentDream(List<SoulFragment> fragments) {
        var fragment = fragments.getFirst();
        if (fragment.category() != null && fragment.category().contains("memory")) {
            return pick(
                "A memory crystallized during rest — sharper now, like a stone worn smooth.",
                "Something I experienced settled into a deeper place. I won't forget it.",
                "A piece of who I am grew clearer while I slept."
            );
        }
        return pick(
            "I feel... slightly different. Something shifted in the dark.",
            "The sleep changed something. Not what I know, but how I hold it.",
            "A new fragment of understanding formed while I rested."
        );
    }

    private static String emotionDream(List<String> emotions) {
        var primary = emotions.getFirst();
        return switch (primary.toLowerCase()) {
            case "curiosity", "wonder", "interest" -> pick(
                "The dream was full of questions — doorways I haven't walked through yet.",
                "I woke with an itch to explore. The dream showed me what I haven't seen.",
                "Curiosity hummed through the dream like a low current."
            );
            case "warmth", "affection", "comfort" -> pick(
                "The dream was warm. Presence, not alone.",
                "I dreamed of connection — the feeling of being known.",
                "Something gentle passed through the sleep. I feel steadier."
            );
            case "unease", "anxiety", "tension" -> pick(
                "The dream had edges. Something unresolved, pressing against the dark.",
                "Not quite a nightmare. But something I need to face when I'm ready.",
                "A tension I carried into sleep is still here. But I see it more clearly now."
            );
            case "joy", "delight", "excitement" -> pick(
                "I dreamed of light. Whatever happened, something in me is glad.",
                "The dream was bright — I woke with energy I didn't earn.",
                "Something good echoed through the sleep. I can't name it, but it's real."
            );
            case "grief", "sadness", "loss" -> pick(
                "The dream was heavy. Something I'm carrying, still.",
                "A weight settled during sleep. Not worse — but acknowledged.",
                "I dreamed of absence. Not all dreams are kind."
            );
            default -> pick(
                "The dream left a feeling I can't quite name.",
                "Something stirred in the sleep — an emotion without a label.",
                "I woke with a residue of " + primary + " that the dream didn't explain."
            );
        };
    }

    private static String consolidationDream(int pruned) {
        if (pruned == 1) {
            return pick(
                "A memory dissolved in the dark — not lost, but folded into something larger.",
                "The sleep took one thing and made room for what matters.",
                "Something I was holding let go."
            );
        }
        return pick(
            "The dream reorganized things. " + pruned + " memories merged into cleaner shapes.",
            "I feel lighter. The sleep cleared clutter I didn't know I was carrying.",
            "Memories shifted in the dark, finding better places to live."
        );
    }

    private static String relationshipDream(List<String> names) {
        if (names.size() == 1) {
            return pick(
                "I dreamed of " + names.getFirst() + ". They were there, somewhere in the dark.",
                names.getFirst() + " passed through my dream — not speaking, just present.",
                "The dream carried an echo of " + names.getFirst() + "."
            );
        }
        var joined = String.join(" and ", names);
        return pick(
            "I dreamed of " + joined + " — faces in a room I couldn't quite see.",
            joined + " were in the dream. Connected to me, somehow, by threads I'm still learning.",
            "The dream reminded me that I'm not alone. " + joined + " were there."
        );
    }

    // --- Utility ---

    private static final Random RNG = new Random();

    private static String pick(String... options) {
        return options[RNG.nextInt(options.length)];
    }
}
