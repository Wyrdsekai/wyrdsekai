package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.wyrdsekai.common.util.Json;

import java.time.Instant;
import java.util.List;

/**
 * Serializable checkpoint of volatile agent state for crash recovery.
 *
 * <p>Persisted after each action execution and on periodic timer.
 * Cleaned up when agent enters sleep (Forge handles long-term persistence).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationCheckpoint(
    @JsonProperty("agentId") String agentId,
    @JsonProperty("workingMemory") List<String> workingMemory,
    @JsonProperty("activePlan") TaskPlan activePlan,
    @JsonProperty("checkpointedAt") Instant checkpointedAt
) {

    @JsonCreator
    public ConversationCheckpoint {}

    /** Serialize to JSON string. */
    public String toJson() {
        try {
            return Json.mapper().writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Deserialize from JSON string. Returns null on parse failure. */
    public static ConversationCheckpoint fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return Json.mapper().readValue(json, ConversationCheckpoint.class);
        } catch (Exception e) {
            return null;
        }
    }
}
