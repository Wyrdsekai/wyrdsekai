package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * A compiled successful behavior pattern for reuse.
 * Inspired by Voyager (Minecraft agent): store successful behaviors
 * as reusable templates for similar future tasks.
 */
public record LearnedSkill(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("goalTemplates") List<String> goalTemplates,
    @JsonProperty("domain") String domain,
    @JsonProperty("successCount") int successCount,
    @JsonProperty("learnedAt") Instant learnedAt
) {
    @JsonCreator public LearnedSkill {}

    public LearnedSkill withIncrementedSuccess() {
        return new LearnedSkill(name, description, goalTemplates, domain,
            successCount + 1, learnedAt);
    }
}
