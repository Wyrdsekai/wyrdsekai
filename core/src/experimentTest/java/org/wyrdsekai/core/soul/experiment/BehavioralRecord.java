package org.wyrdsekai.core.soul.experiment;

import java.time.Instant;
import java.util.List;

/**
 * Records all agent responses during an experiment run.
 */
public record BehavioralRecord(
    String runId,
    String agentName,
    String modelName,
    String systemPrompt,
    String soulLayer,       // null for baseline runs
    Instant startedAt,
    List<ScenarioResponse> responses
) {
    /**
     * A single scenario response from the agent.
     */
    public record ScenarioResponse(
        String scenarioId,
        String category,
        String playerMessage,
        String agentResponse,
        int responseTokens,
        long latencyMs
    ) {}
}
