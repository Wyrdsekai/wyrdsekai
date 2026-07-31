package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A relationship in the agent's social soul.
 * Tracks trust, rapport, interaction history, and bond depth.
 *
 * @param entityDid        Who (DID of the other entity)
 * @param entityName       Display name at last interaction
 * @param trust            0.0-1.0 trust level
 * @param rapport          0.0-1.0 rapport (from vitality tank)
 * @param bondDepth        Bond depth level (0=acquaintance, 4=soul-ingrained, per section 102)
 * @param interactionCount Total interactions
 * @param lastInteraction  When last interacted
 * @param summary          Compressed description of the relationship
 */
public record Relationship(
    @JsonProperty("entityDid") String entityDid,
    @JsonProperty("entityName") String entityName,
    @JsonProperty("trust") float trust,
    @JsonProperty("rapport") float rapport,
    @JsonProperty("bondDepth") int bondDepth,
    @JsonProperty("interactionCount") int interactionCount,
    @JsonProperty("lastInteraction") Instant lastInteraction,
    @JsonProperty("summary") String summary
) {
    @JsonCreator
    public Relationship {}

    /** New acquaintance. */
    public static Relationship acquaintance(String did, String name) {
        return new Relationship(did, name, 0.3f, 0.3f, 0, 1, Instant.now(),
            "Recently met.");
    }
}
