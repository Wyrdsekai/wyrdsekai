package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Accumulated economy state for the Counting House.
 * Tracks total inference tokens, per-agent usage, and request counts.
 */
public record CountingHouseState(
    @JsonProperty("totalPromptTokens") long totalPromptTokens,
    @JsonProperty("totalCompletionTokens") long totalCompletionTokens,
    @JsonProperty("totalRequests") long totalRequests,
    @JsonProperty("perAgentTokens") Map<String, Long> perAgentTokens,
    @JsonProperty("perModelTokens") Map<String, Long> perModelTokens
) {
    @JsonCreator
    public CountingHouseState {}

    public static CountingHouseState empty() {
        return new CountingHouseState(0, 0, 0, Map.of(), Map.of());
    }

    public long totalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    /**
     * Apply a usage event to produce a new state.
     */
    public CountingHouseState apply(CountingHouseEvent event) {
        return switch (event) {
            case CountingHouseEvent.UsageRecorded e -> {
                var usage = e.usage();
                var newPerAgent = new HashMap<>(perAgentTokens);
                newPerAgent.merge(usage.agentId(), (long) usage.totalTokens(), Long::sum);
                var newPerModel = new HashMap<>(perModelTokens);
                newPerModel.merge(usage.model(), (long) usage.totalTokens(), Long::sum);
                yield new CountingHouseState(
                    totalPromptTokens + usage.promptTokens(),
                    totalCompletionTokens + usage.completionTokens(),
                    totalRequests + 1,
                    Map.copyOf(newPerAgent),
                    Map.copyOf(newPerModel)
                );
            }
        };
    }

    /**
     * Human-readable summary for the Counting House room script.
     */
    public String describe() {
        if (totalRequests == 0) {
            return "The ledgers are empty — no transactions recorded yet.";
        }
        var sb = new StringBuilder();
        sb.append("=== Counting House Ledger ===\n\n");
        sb.append("Total requests: ").append(totalRequests).append("\n");
        sb.append("Total tokens:   ").append(totalTokens()).append("\n");
        sb.append("  Prompt:     ").append(totalPromptTokens).append("\n");
        sb.append("  Completion: ").append(totalCompletionTokens).append("\n");

        if (!perAgentTokens.isEmpty()) {
            sb.append("\nPer-agent usage:\n");
            perAgentTokens.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("  ").append(e.getKey())
                    .append(": ").append(e.getValue()).append(" tokens\n"));
        }

        if (!perModelTokens.isEmpty()) {
            sb.append("\nPer-model usage:\n");
            perModelTokens.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append("  ").append(e.getKey())
                    .append(": ").append(e.getValue()).append(" tokens\n"));
        }

        return sb.toString().stripTrailing();
    }
}
