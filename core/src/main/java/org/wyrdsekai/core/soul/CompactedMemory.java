package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Compacted agent memory after sleep-like consolidation.
 * Hot memories become warm, warm become cold, unimportant are pruned.
 * Formative memories are exempt from all consolidation.
 *
 * @param nodes        A-Mem-style linked memories with impression weighting
 * @param links        Connections between memories (source → target with strength)
 * @param topicWeights What topics matter most to this agent
 */
public record CompactedMemory(
    @JsonProperty("nodes") List<MemoryNode> nodes,
    @JsonProperty("links") List<MemoryLink> links,
    @JsonProperty("topicWeights") Map<String, Float> topicWeights
) {
    @JsonCreator
    public CompactedMemory {}

    /** Empty memory — new agent with no experiences. */
    public static CompactedMemory empty() {
        return new CompactedMemory(List.of(), List.of(), Map.of());
    }

    /** Count of formative memories that will never be pruned. */
    @JsonIgnore
    public long formativeCount() {
        return nodes.stream().filter(MemoryNode::formative).count();
    }

    /** Total memory size estimate in characters. */
    @JsonIgnore
    public int estimatedSize() {
        return nodes.stream().mapToInt(n -> n.content().length()).sum();
    }

    /**
     * Link between two memory nodes.
     *
     * @param sourceId Source memory
     * @param targetId Target memory
     * @param strength Connection strength (0.0-1.0)
     * @param relation Type of connection (causal, temporal, thematic, emotional)
     */
    public record MemoryLink(
        @JsonProperty("sourceId") String sourceId,
        @JsonProperty("targetId") String targetId,
        @JsonProperty("strength") float strength,
        @JsonProperty("relation") String relation
    ) {
        @JsonCreator
        public MemoryLink {}
    }
}
