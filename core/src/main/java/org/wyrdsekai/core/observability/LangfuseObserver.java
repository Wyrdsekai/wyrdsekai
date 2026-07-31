package org.wyrdsekai.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Optional Langfuse LLM observability integration (§105.4).
 * Traces LLM calls with prompt/completion pairs for cost tracking
 * and quality scoring. Self-hosted — no data leaves the household.
 */
public class LangfuseObserver {

    /** A recorded LLM observation. */
    public record Observation(
        String observationId,
        String agentDid,
        String model,
        String promptHash,
        int promptTokens,
        int completionTokens,
        Duration latency,
        double estimatedCost,
        Instant observedAt,
        boolean redacted
    ) {}

    /** Quality score for an observation. */
    public record QualityScore(
        String observationId,
        double latencyScore,
        double tokenEfficiency,
        double errorRate,
        double overall
    ) {}

    /** Cost summary for an agent. */
    public record CostSummary(
        String agentDid,
        int totalCalls,
        int totalPromptTokens,
        int totalCompletionTokens,
        double totalCost,
        String topModel,
        Instant firstObservation,
        Instant lastObservation
    ) {}

    private final List<Observation> observations = Collections.synchronizedList(new ArrayList<>());
    private boolean enabled;
    private boolean redactHumanMessages;
    private int nextId = 1;

    public LangfuseObserver() {
        this(false, true);
    }

    public LangfuseObserver(boolean enabled, boolean redactHumanMessages) {
        this.enabled = enabled;
        this.redactHumanMessages = redactHumanMessages;
    }

    /** Record an LLM call observation. */
    public Observation observe(String agentDid, String model,
                                String promptHash, int promptTokens, int completionTokens,
                                Duration latency, double costPerToken) {
        if (!enabled) return null;

        double cost = (promptTokens + completionTokens) * costPerToken;
        var obs = new Observation("obs-" + nextId++, agentDid, model,
            redactHumanMessages ? "REDACTED" : promptHash,
            promptTokens, completionTokens, latency, cost, Instant.now(),
            redactHumanMessages);
        observations.add(obs);
        return obs;
    }

    /** Score an observation's quality. */
    public QualityScore score(Observation obs) {
        // Latency score: <1s = 1.0, <3s = 0.8, <10s = 0.5, >10s = 0.2
        double latencyMs = obs.latency().toMillis();
        double latencyScore = latencyMs < 1000 ? 1.0
            : latencyMs < 3000 ? 0.8
            : latencyMs < 10000 ? 0.5 : 0.2;

        // Token efficiency: ratio of completion to prompt (lower is more efficient)
        double tokenEff = obs.promptTokens() > 0
            ? Math.min(1.0, (double) obs.completionTokens() / obs.promptTokens())
            : 0.5;

        // Simple overall
        double overall = (latencyScore * 0.4 + tokenEff * 0.3 + 0.3);
        return new QualityScore(obs.observationId(), latencyScore, tokenEff, 0.0, overall);
    }

    /** Get cost summary for an agent. */
    public Optional<CostSummary> costSummary(String agentDid) {
        var agentObs = observations.stream()
            .filter(o -> o.agentDid().equals(agentDid))
            .toList();
        if (agentObs.isEmpty()) return Optional.empty();

        int totalPrompt = agentObs.stream().mapToInt(Observation::promptTokens).sum();
        int totalCompletion = agentObs.stream().mapToInt(Observation::completionTokens).sum();
        double totalCost = agentObs.stream().mapToDouble(Observation::estimatedCost).sum();

        String topModel = agentObs.stream()
            .collect(Collectors.groupingBy(Observation::model,
                     Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("unknown");

        return Optional.of(new CostSummary(agentDid, agentObs.size(),
            totalPrompt, totalCompletion, totalCost, topModel,
            agentObs.get(0).observedAt(),
            agentObs.get(agentObs.size() - 1).observedAt()));
    }

    /** Get all observations for an agent. */
    public List<Observation> observationsFor(String agentDid) {
        return observations.stream()
            .filter(o -> o.agentDid().equals(agentDid))
            .toList();
    }

    public void enable() { this.enabled = true; }
    public void disable() { this.enabled = false; }
    public boolean isEnabled() { return enabled; }
    public void setRedactHumanMessages(boolean redact) { this.redactHumanMessages = redact; }
    public int observationCount() { return observations.size(); }
}
