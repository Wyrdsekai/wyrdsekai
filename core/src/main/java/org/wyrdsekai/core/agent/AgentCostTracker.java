package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

// Tracks per-agent inference cost, latency, and external API usage.
// Queryable by bondholders for cost visibility and budget enforcement.
public class AgentCostTracker {

    private static volatile AgentCostTracker instance;
    public static void init() { instance = new AgentCostTracker(); }
    public static AgentCostTracker get() { return instance; }

    // Cost entry for a single inference or API call
    public record CostEntry(
        String agentId,
        String category,      // "inference", "mcp", "web_search", "oracle"
        long latencyMs,
        int tokensUsed,       // 0 if not applicable
        double monetaryCost,  // 0.0 for local inference
        Instant timestamp
    ) {}

    // Aggregate cost summary for an agent
    public record CostSummary(
        String agentId,
        long totalInferences,
        long totalMcpCalls,
        long totalTokens,
        double totalMonetaryCost,
        long totalLatencyMs,
        double avgLatencyMs,
        Instant firstActivity,
        Instant lastActivity
    ) {}

    // Per-agent cost accumulators
    private final Map<String, AgentAccumulator> accumulators = new ConcurrentHashMap<>();

    // Budget limits (agentId -> daily limit in dollars)
    private final Map<String, Double> budgetLimits = new ConcurrentHashMap<>();

    // Record a cost entry.
    public void record(CostEntry entry) {
        accumulators.computeIfAbsent(entry.agentId(), AgentAccumulator::new)
            .add(entry);
    }

    // Convenience: record an inference call.
    public void recordInference(String agentId, long latencyMs, int promptTokens, int completionTokens) {
        record(new CostEntry(agentId, "inference", latencyMs,
            promptTokens + completionTokens, 0.0, Instant.now()));
    }

    // Convenience: record an MCP call.
    public void recordMcp(String agentId, String serviceId, long latencyMs, double cost) {
        record(new CostEntry(agentId, "mcp:" + serviceId, latencyMs, 0, cost, Instant.now()));
    }

    // Get cost summary for an agent.
    public Optional<CostSummary> summary(String agentId) {
        var acc = accumulators.get(agentId);
        return acc != null ? Optional.of(acc.summarize()) : Optional.empty();
    }

    // Set daily budget limit for an agent.
    public void setBudget(String agentId, double dailyLimit) {
        budgetLimits.put(agentId, dailyLimit);
    }

    // Check if agent is within budget. Returns null if ok, narrative if over.
    public String checkBudget(String agentId) {
        var limit = budgetLimits.get(agentId);
        if (limit == null) return null; // no limit set

        var acc = accumulators.get(agentId);
        if (acc == null) return null;

        var todaySpend = acc.todayMonetaryCost();
        if (todaySpend >= limit) {
            return "Daily budget exceeded ($" + String.format("%.4f", todaySpend)
                + " of $" + String.format("%.4f", limit) + " limit)";
        }
        return null;
    }

    // Build prompt context for the agent showing its cost awareness.
    public String buildPromptContext(String agentId) {
        var acc = accumulators.get(agentId);
        if (acc == null) return "";

        var summary = acc.summarize();
        var sb = new StringBuilder();
        sb.append("## Resource Usage\n");
        sb.append("- Inferences today: ").append(summary.totalInferences()).append("\n");
        sb.append("- Tokens used: ").append(summary.totalTokens()).append("\n");
        sb.append("- Avg latency: ").append(String.format("%.0f", summary.avgLatencyMs())).append("ms\n");

        var limit = budgetLimits.get(agentId);
        if (limit != null) {
            var todaySpend = acc.todayMonetaryCost();
            sb.append("- Budget: $").append(String.format("%.4f", todaySpend))
              .append(" / $").append(String.format("%.4f", limit)).append("\n");
        }

        return sb.toString();
    }

    // Get all tracked agent IDs.
    public Set<String> trackedAgents() {
        return Set.copyOf(accumulators.keySet());
    }

    // Internal accumulator per agent
    private static class AgentAccumulator {
        final String agentId;
        final AtomicLong inferenceCount = new AtomicLong();
        final AtomicLong mcpCallCount = new AtomicLong();
        final AtomicLong totalTokens = new AtomicLong();
        final DoubleAdder totalMonetaryCost = new DoubleAdder();
        final AtomicLong totalLatencyMs = new AtomicLong();
        volatile Instant firstActivity;
        volatile Instant lastActivity;

        // Today's cost (resets daily)
        final DoubleAdder todayCost = new DoubleAdder();
        volatile LocalDate costDate = LocalDate.now();

        AgentAccumulator(String agentId) {
            this.agentId = agentId;
        }

        void add(CostEntry entry) {
            if (entry.category().equals("inference")) {
                inferenceCount.incrementAndGet();
            } else if (entry.category().startsWith("mcp:")) {
                mcpCallCount.incrementAndGet();
            }
            totalTokens.addAndGet(entry.tokensUsed());
            totalMonetaryCost.add(entry.monetaryCost());
            totalLatencyMs.addAndGet(entry.latencyMs());
            lastActivity = entry.timestamp();
            if (firstActivity == null) firstActivity = entry.timestamp();

            // Daily cost tracking
            var today = LocalDate.now();
            if (!today.equals(costDate)) {
                todayCost.reset();
                costDate = today;
            }
            todayCost.add(entry.monetaryCost());
        }

        double todayMonetaryCost() {
            var today = LocalDate.now();
            if (!today.equals(costDate)) {
                todayCost.reset();
                costDate = today;
            }
            return todayCost.sum();
        }

        CostSummary summarize() {
            long inferences = inferenceCount.get();
            long latency = totalLatencyMs.get();
            return new CostSummary(
                agentId,
                inferences,
                mcpCallCount.get(),
                totalTokens.get(),
                totalMonetaryCost.sum(),
                latency,
                inferences > 0 ? (double) latency / inferences : 0.0,
                firstActivity,
                lastActivity
            );
        }
    }
}
