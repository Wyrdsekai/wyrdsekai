package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A single resource usage record — typically one inference request.
 * Immutable, serializable for Pekko Persistence.
 */
public record ResourceUsage(
    @JsonProperty("agentId") String agentId,
    @JsonProperty("resourceType") String resourceType,  // "inference", "compute", "storage"
    @JsonProperty("promptTokens") int promptTokens,
    @JsonProperty("completionTokens") int completionTokens,
    @JsonProperty("model") String model,
    @JsonProperty("timestamp") Instant timestamp
) {
    @JsonCreator
    public ResourceUsage {}

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
