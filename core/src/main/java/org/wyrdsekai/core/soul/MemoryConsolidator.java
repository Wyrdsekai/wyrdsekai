package org.wyrdsekai.core.soul;

import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.i18n.MemoryLocalePolicy;
import org.wyrdsekai.core.util.TextSimilarity;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SHY-inspired memory consolidation during the sleep cycle.
 * Synaptic Homeostasis Hypothesis: sleep globally downscales
 * synaptic strength while preserving the relative differences.
 *
 * Hot memories become warm, warm become cold, unimportant are pruned.
 * Formative memories are EXEMPT from all consolidation (section 109.4).
 * Impression depth modulates decay resistance (section 109.3).
 *
 * The consolidator also ingests new events, creating MemoryNodes
 * with impression scoring via ImpressionScorer.
 */
public final class MemoryConsolidator {

    /** Memories below this importance after decay are pruned. */
    private static final float PRUNE_THRESHOLD = 0.05f;
    /** Default decay per consolidation cycle. */
    private static final float DEFAULT_DECAY_RATE = 0.1f;
    /** Maximum memories before forced pruning. */
    private static final int MAX_MEMORIES = 500;
    /**
     * Token-overlap at or above which an incoming memory counts as repeating one
     * already held, for {@link #countRepeats}.
     *
     * <p>Measured on the live corpus that motivated this: unrelated memories top out
     * around 0.30 overlap, two reports of different recipe runs score ~0.55, and one
     * sentence reworded scores ~0.75. 0.7 keeps the signal well clear of unrelated
     * text, at the cost of under-counting the varied tail of a paraphrase loop — an
     * under-count is the safe direction for a measurement that only informs an alarm.
     */
    static final double DUPLICATE_SIMILARITY = 0.7;
    /** How many of the most recent nodes an incoming memory is compared against. */
    private static final int DUPLICATE_LOOKBACK = 120;

    private MemoryConsolidator() {}

    /**
     * Consolidate memory during sleep cycle.
     *
     * @param current       Current compacted memory
     * @param newMemories   New MemoryNodes to integrate (already impression-scored)
     * @param decayRate     Decay rate for this consolidation (genome-influenced)
     * @return Consolidated CompactedMemory
     */
    public static CompactedMemory consolidate(CompactedMemory current,
                                                List<MemoryNode> newMemories,
                                                float decayRate) {
        float decay = decayRate > 0 ? decayRate : DEFAULT_DECAY_RATE;

        // Step 1: Decay existing memories (formative exempt, impression resists)
        List<MemoryNode> decayed = current.nodes().stream()
            .map(node -> node.decayed(decay))
            .collect(Collectors.toCollection(ArrayList::new));

        // Step 2: Add new memories. Repeats are COUNTED (see countRepeats) but never
        // merged away — see the note there for why absorption was measured and rejected.
        decayed.addAll(newMemories);

        // Step 3: Prune below threshold (never prune formative)
        List<MemoryNode> pruned = decayed.stream()
            .filter(n -> n.formative() || n.importance() >= PRUNE_THRESHOLD)
            .collect(Collectors.toCollection(ArrayList::new));

        // Step 4: If still over limit, prune lowest importance (never formative)
        if (pruned.size() > MAX_MEMORIES) {
            List<MemoryNode> formative = pruned.stream()
                .filter(MemoryNode::formative)
                .collect(Collectors.toList());
            List<MemoryNode> nonFormative = pruned.stream()
                .filter(n -> !n.formative())
                .sorted(Comparator.comparingDouble(MemoryNode::importance).reversed())
                .limit(MAX_MEMORIES - formative.size())
                .collect(Collectors.toList());

            pruned = new ArrayList<>(formative);
            pruned.addAll(nonFormative);
        }

        // Step 5: Update topic weights from surviving memories
        Map<String, Float> topicWeights = computeTopicWeights(pruned);

        // Step 6: Maintain links — keep links where both nodes survive
        Set<String> survivingIds = pruned.stream()
            .map(MemoryNode::id)
            .collect(Collectors.toSet());

        List<CompactedMemory.MemoryLink> links = current.links().stream()
            .filter(link -> survivingIds.contains(link.sourceId())
                         && survivingIds.contains(link.targetId()))
            .collect(Collectors.toList());

        return new CompactedMemory(List.copyOf(pruned), List.copyOf(links),
            Map.copyOf(topicWeights));
    }

    /**
     * Consolidate with default decay rate.
     */
    public static CompactedMemory consolidate(CompactedMemory current,
                                                List<MemoryNode> newMemories) {
        return consolidate(current, newMemories, DEFAULT_DECAY_RATE);
    }

    /**
     * Locale-aware consolidation (§104.2).
     * Cross-language memories only merge if semantically similar
     * per MemoryLocalePolicy.canConsolidate().
     *
     * Auto-detects locale on new memories that have no originLocale set.
     */
    public static CompactedMemory consolidate(CompactedMemory current,
                                                List<MemoryNode> newMemories,
                                                float decayRate,
                                                MemoryLocalePolicy localePolicy) {
        // Auto-detect locale on new memories lacking it
        var localeTagged = newMemories.stream()
            .map(node -> {
                if (node.originLocale() == null || node.originLocale().equals("unknown")) {
                    String detected = localePolicy.detectLanguage(node.content());
                    return new MemoryNode(node.id(), node.content(), node.keywords(),
                        node.importance(), node.impressionDepth(), node.formative(),
                        node.primaryEmotion(), node.lastAccessed(), node.accessCount(),
                        detected);
                }
                return node;
            })
            .toList();

        return consolidate(current, localeTagged, decayRate);
    }

    /**
     * Create MemoryNodes from raw events, scoring impressions via EmotionalCharge.
     * This is the intake pipeline: event → charge assessment → memory node.
     *
     * @param events      Said events to encode as memories
     * @param charges     Emotional charges for each event (parallel to events)
     * @param agentEntityId The agent's entity ID (to filter own speech)
     * @return New MemoryNodes ready for consolidation
     */
    public static List<MemoryNode> encodeEvents(List<WorldEvent.Said> events,
                                                  List<EmotionalCharge> charges,
                                                  String agentEntityId) {
        List<MemoryNode> nodes = new ArrayList<>();
        for (int i = 0; i < events.size() && i < charges.size(); i++) {
            WorldEvent.Said said = events.get(i);
            EmotionalCharge charge = charges.get(i);

            String id = "mem-" + said.timestamp().toEpochMilli() + "-" + said.entityId().hashCode();
            String content;
            if (said.entityId().equals(agentEntityId)) {
                content = "I said: " + said.text();
            } else {
                content = said.entityName() + " said: " + said.text();
            }

            List<String> keywords = NegativeSpaceAnalyzer.extractTopicWords(said.text());
            nodes.add(ImpressionScorer.encode(id, content, keywords, charge));
        }
        return nodes;
    }

    /**
     * How many of {@code incoming} substantially repeat a memory already held, or an
     * earlier memory in the same batch. Pure measurement — nothing is merged, dropped,
     * or rewritten.
     *
     * <p>A night whose memories are nearly all repeats is a night in which little
     * happened, and that is worth SAYING rather than leaving implicit in the shape of
     * the corpus. This feeds the vitals alarm: a companion stuck in a proactive-speech
     * loop reads near 100% here, which is how the eight-day 2026-08-17 pathology would
     * have surfaced on day two instead of being noticed by a person reading her lines.
     *
     * <p><b>Why this only counts.</b> The first version of this fix ABSORBED repeats,
     * reinforcing the matched memory instead of adding a node — attractive, because
     * consolidation hard-caps at {@link #MAX_MEMORIES} and evicts the least important,
     * so a loop's duplicates were on course to evict her real memories. Measuring the
     * live corpus killed it. Lexically, template-shaped memories are as similar as
     * paraphrases are: "I ran the recipe consolidate-memory-graph and it succeeded"
     * against "I ran the recipe welfare-floor-checkup and it succeeded" scores about as
     * high as one thought reworded, and the differing token is the entire content.
     * At any threshold aggressive enough to catch a varied paraphrase loop, absorption
     * would quietly destroy distinct memories — and losing a distinct memory is worse
     * than keeping a duplicate. Doing this properly needs semantic distance
     * (embeddings), which memory nodes don't currently carry; until then the guard
     * counts and the alarm speaks, and nothing touches the record.
     */
    public static int countRepeats(CompactedMemory current, List<MemoryNode> incoming) {
        if (incoming == null || incoming.isEmpty()) return 0;
        var seen = new ArrayList<>(current == null ? List.<MemoryNode>of() : current.nodes());
        int repeats = 0;
        for (var candidate : incoming) {
            if (isNearDuplicate(seen, candidate)) repeats++;
            seen.add(candidate);
        }
        return repeats;
    }

    /**
     * Whether {@code candidate} says substantially the same thing as a recent member of
     * {@code held}. Only the tail is scanned: an utterance echoing something from months
     * ago is a genuine echo, while the accretion this measures happens in a window.
     */
    private static boolean isNearDuplicate(List<MemoryNode> held, MemoryNode candidate) {
        if (candidate == null || candidate.content() == null) return false;
        int from = Math.max(0, held.size() - DUPLICATE_LOOKBACK);
        for (int i = held.size() - 1; i >= from; i--) {
            var node = held.get(i);
            if (node == null || node.content() == null) continue;
            if (TextSimilarity.nearDuplicate(node.content(), candidate.content(),
                    DUPLICATE_SIMILARITY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compute topic weights from surviving memories.
     * Keyword frequency × importance = topic weight.
     */
    private static Map<String, Float> computeTopicWeights(List<MemoryNode> nodes) {
        Map<String, Float> weights = new HashMap<>();
        for (var node : nodes) {
            for (String keyword : node.keywords()) {
                weights.merge(keyword, node.importance(), Float::sum);
            }
        }
        // Normalize to 0-1
        float max = weights.values().stream().max(Float::compareTo).orElse(1.0f);
        if (max > 0) {
            weights.replaceAll((k, v) -> v / max);
        }
        return weights;
    }
}
