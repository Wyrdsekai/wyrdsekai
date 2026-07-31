package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRoutingPolicyTest {

    private AgentRoutingPolicy policy;

    @BeforeEach void setUp() {
        policy = new AgentRoutingPolicy();
    }

    @Test void default_policy_has_100k_budget() {
        var p = policy.getPolicy("agent-1");
        assertThat(p.dailyTokenBudget()).isEqualTo(100_000);
        assertThat(p.tokensUsedToday()).isEqualTo(0);
        assertThat(p.budgetExceeded()).isFalse();
    }

    @Test void recordUsage_tracks_tokens() {
        policy.recordUsage("agent-1", 5000);
        var p = policy.getPolicy("agent-1");
        assertThat(p.tokensUsedToday()).isEqualTo(5000);
        assertThat(p.budgetExceeded()).isFalse();
    }

    @Test void recordUsage_detects_budget_exceeded() {
        policy.setPolicy("agent-1", null, 1000);
        boolean exceeded = policy.recordUsage("agent-1", 1500);
        assertThat(exceeded).isTrue();
        assertThat(policy.hasBudget("agent-1")).isFalse();
    }

    @Test void setPolicy_with_preferred_backend() {
        policy.setPolicy("agent-1", "ollama-local", 50_000);
        var p = policy.getPolicy("agent-1");
        assertThat(p.preferredBackend()).isEqualTo("ollama-local");
        assertThat(p.dailyTokenBudget()).isEqualTo(50_000);
    }

    @Test void resetAllDaily_clears_counters() {
        policy.recordUsage("agent-1", 90_000);
        policy.recordUsage("agent-2", 50_000);
        policy.resetAllDaily();

        assertThat(policy.getPolicy("agent-1").tokensUsedToday()).isEqualTo(0);
        assertThat(policy.getPolicy("agent-2").tokensUsedToday()).isEqualTo(0);
    }

    @Test void remainingBudget_calculates_correctly() {
        policy.setPolicy("agent-1", null, 10_000);
        policy.recordUsage("agent-1", 3_000);
        assertThat(policy.getPolicy("agent-1").remainingBudget()).isEqualTo(7_000);
    }

    @Test void describe_shows_all_agents() {
        policy.recordUsage("agent-1", 5000);
        policy.recordUsage("agent-2", 10000);
        var desc = policy.describe();
        assertThat(desc).contains("agent-1");
        assertThat(desc).contains("agent-2");
    }
}
