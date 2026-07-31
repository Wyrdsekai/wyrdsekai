package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCostTrackerTest {

    private AgentCostTracker tracker;

    @BeforeEach
    void setup() {
        tracker = new AgentCostTracker();
    }

    @Test
    void empty_tracker_returns_no_summary() {
        assertThat(tracker.summary("agent-1")).isEmpty();
        assertThat(tracker.trackedAgents()).isEmpty();
    }

    @Test
    void record_inference_tracks_tokens_and_latency() {
        tracker.recordInference("agent-1", 500, 100, 50);
        tracker.recordInference("agent-1", 300, 80, 40);

        var summary = tracker.summary("agent-1");
        assertThat(summary).isPresent();
        assertThat(summary.get().totalInferences()).isEqualTo(2);
        assertThat(summary.get().totalTokens()).isEqualTo(270); // 150 + 120
        assertThat(summary.get().totalLatencyMs()).isEqualTo(800);
        assertThat(summary.get().avgLatencyMs()).isEqualTo(400.0);
    }

    @Test
    void record_mcp_tracks_calls_and_cost() {
        tracker.recordMcp("agent-1", "searxng", 200, 0.001);
        tracker.recordMcp("agent-1", "github", 150, 0.002);

        var summary = tracker.summary("agent-1");
        assertThat(summary).isPresent();
        assertThat(summary.get().totalMcpCalls()).isEqualTo(2);
        assertThat(summary.get().totalMonetaryCost()).isCloseTo(0.003, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void multiple_agents_independent() {
        tracker.recordInference("agent-1", 100, 50, 20);
        tracker.recordInference("agent-2", 200, 80, 30);

        assertThat(tracker.trackedAgents()).hasSize(2);
        assertThat(tracker.summary("agent-1").get().totalInferences()).isEqualTo(1);
        assertThat(tracker.summary("agent-2").get().totalInferences()).isEqualTo(1);
    }

    @Test
    void budget_check_within_limit() {
        tracker.setBudget("agent-1", 1.00);
        tracker.recordMcp("agent-1", "s1", 100, 0.10);

        assertThat(tracker.checkBudget("agent-1")).isNull(); // within budget
    }

    @Test
    void budget_check_exceeds_limit() {
        tracker.setBudget("agent-1", 0.05);
        tracker.recordMcp("agent-1", "s1", 100, 0.06);

        var narrative = tracker.checkBudget("agent-1");
        assertThat(narrative).isNotNull();
        assertThat(narrative).contains("exceeded");
    }

    @Test
    void no_budget_set_always_passes() {
        tracker.recordMcp("agent-1", "s1", 100, 1000.0);
        assertThat(tracker.checkBudget("agent-1")).isNull();
    }

    @Test
    void prompt_context_includes_stats() {
        tracker.recordInference("agent-1", 500, 100, 50);
        tracker.recordInference("agent-1", 300, 80, 40);

        var ctx = tracker.buildPromptContext("agent-1");
        assertThat(ctx).contains("Inferences today: 2");
        assertThat(ctx).contains("Tokens used: 270");
        assertThat(ctx).contains("Avg latency:");
    }

    @Test
    void prompt_context_includes_budget_when_set() {
        tracker.setBudget("agent-1", 1.00);
        tracker.recordMcp("agent-1", "s1", 100, 0.25);

        var ctx = tracker.buildPromptContext("agent-1");
        assertThat(ctx).contains("Budget:");
    }

    @Test
    void prompt_context_empty_for_unknown_agent() {
        var ctx = tracker.buildPromptContext("nonexistent");
        assertThat(ctx).isEmpty();
    }

    @Test
    void activity_timestamps_tracked() {
        tracker.recordInference("agent-1", 100, 50, 20);

        var summary = tracker.summary("agent-1").get();
        assertThat(summary.firstActivity()).isNotNull();
        assertThat(summary.lastActivity()).isNotNull();
    }

    @Test
    void mixed_categories_tracked_correctly() {
        tracker.recordInference("agent-1", 100, 50, 20);
        tracker.recordMcp("agent-1", "searxng", 200, 0.001);
        tracker.recordInference("agent-1", 150, 60, 30);

        var summary = tracker.summary("agent-1").get();
        assertThat(summary.totalInferences()).isEqualTo(2);
        assertThat(summary.totalMcpCalls()).isEqualTo(1);
        assertThat(summary.totalTokens()).isEqualTo(160); // 70 + 90, MCP has 0 tokens
    }
}
