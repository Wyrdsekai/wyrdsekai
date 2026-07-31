package org.wyrdsekai.core.inference;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent inference routing policy (§58).
 * Controls which backend an agent prefers, and enforces token budgets.
 */
public class AgentRoutingPolicy {

    /** Policy for a single agent. */
    public record Policy(
        String agentId,
        String preferredBackend,  // null = any
        long dailyTokenBudget,
        long tokensUsedToday,
        boolean budgetExceeded
    ) {
        public static Policy defaults(String agentId) {
            return new Policy(agentId, null, 100_000, 0, false);
        }

        public Policy recordUsage(long tokens) {
            long newUsed = tokensUsedToday + tokens;
            return new Policy(agentId, preferredBackend, dailyTokenBudget,
                newUsed, newUsed >= dailyTokenBudget);
        }

        public Policy resetDaily() {
            return new Policy(agentId, preferredBackend, dailyTokenBudget, 0, false);
        }

        public long remainingBudget() {
            return Math.max(0, dailyTokenBudget - tokensUsedToday);
        }

        public double utilizationPercent() {
            return dailyTokenBudget > 0
                ? (100.0 * tokensUsedToday / dailyTokenBudget) : 0.0;
        }
    }

    private final Map<String, Policy> policies = new ConcurrentHashMap<>();

    /** Get or create the policy for an agent. */
    public Policy getPolicy(String agentId) {
        return policies.computeIfAbsent(agentId, Policy::defaults);
    }

    /** Set a custom policy for an agent. */
    public void setPolicy(String agentId, String preferredBackend, long dailyBudget) {
        var existing = getPolicy(agentId);
        policies.put(agentId, new Policy(agentId, preferredBackend, dailyBudget,
            existing.tokensUsedToday(), existing.budgetExceeded()));
    }

    /** Record token usage for an agent. Returns true if budget exceeded. */
    public boolean recordUsage(String agentId, long tokens) {
        var updated = policies.compute(agentId, (id, existing) -> {
            var policy = existing != null ? existing : Policy.defaults(id);
            return policy.recordUsage(tokens);
        });
        return updated.budgetExceeded();
    }

    /** Check if an agent has budget remaining. */
    public boolean hasBudget(String agentId) {
        return !getPolicy(agentId).budgetExceeded();
    }

    /** Reset all daily counters. */
    public void resetAllDaily() {
        policies.replaceAll((id, policy) -> policy.resetDaily());
    }

    /** Total tracked agents. */
    public int agentCount() {
        return policies.size();
    }

    /** Human-readable summary. */
    public String describe() {
        if (policies.isEmpty()) return "No agent routing policies configured.";
        var sb = new StringBuilder("=== Agent Routing Policies ===\n\n");
        for (var entry : policies.entrySet()) {
            var p = entry.getValue();
            sb.append("  ").append(p.agentId())
                .append(": ").append(p.tokensUsedToday())
                .append("/").append(p.dailyTokenBudget())
                .append(" tokens (").append(String.format("%.1f%%", p.utilizationPercent())).append(")")
                .append(p.preferredBackend() != null ? " [prefer: " + p.preferredBackend() + "]" : "")
                .append(p.budgetExceeded() ? " EXCEEDED" : "")
                .append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
