package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBudgetTest {

    private AgentBudget budget;

    @BeforeEach
    void setUp() {
        budget = new AgentBudget();
    }

    @Test void no_config_allows_all() {
        assertThat(budget.checkCredits("agent-1", 1000).allowed()).isTrue();
        assertThat(budget.checkCU("agent-1", 100.0).allowed()).isTrue();
    }

    @Test void credit_limit_per_tx() {
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 1000, 100, 50.0, Instant.now()));

        assertThat(budget.checkCredits("agent-1", 50).allowed()).isTrue();
        assertThat(budget.checkCredits("agent-1", 100).allowed()).isTrue();
        assertThat(budget.checkCredits("agent-1", 101).allowed()).isFalse();
    }

    @Test void credit_daily_limit() {
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 100, 50, 50.0, Instant.now()));

        budget.recordCreditSpend("agent-1", 80);

        assertThat(budget.checkCredits("agent-1", 20).allowed()).isTrue();
        assertThat(budget.checkCredits("agent-1", 21).allowed()).isFalse();
    }

    @Test void cu_daily_limit() {
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 1000, 500, 10.0, Instant.now()));

        budget.recordCUSpend("agent-1", 8.0);

        assertThat(budget.checkCU("agent-1", 2.0).allowed()).isTrue();
        assertThat(budget.checkCU("agent-1", 3.0).allowed()).isFalse();
    }

    @Test void reset_daily() {
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 100, 50, 10.0, Instant.now()));

        budget.recordCreditSpend("agent-1", 100);
        assertThat(budget.checkCredits("agent-1", 1).allowed()).isFalse();

        budget.resetDaily();
        assertThat(budget.checkCredits("agent-1", 1).allowed()).isTrue();
    }

    @Test void spending_state_tracking() {
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 1000, 500, 50.0, Instant.now()));

        budget.recordCreditSpend("agent-1", 300);
        budget.recordCUSpend("agent-1", 15.0);

        var state = budget.getSpendingState("agent-1");
        assertThat(state.creditsSpentToday()).isEqualTo(300);
        assertThat(state.cuSpentToday()).isEqualTo(15.0);
    }

    @Test void config_retrieval() {
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 1000, 500, 50.0, Instant.now()));

        var config = budget.getConfig("agent-1");
        assertThat(config).isPresent();
        assertThat(config.get().principalId()).isEqualTo("human-1");
        assertThat(config.get().maxCreditsPerDay()).isEqualTo(1000);
    }

    @Test void missing_config() {
        assertThat(budget.getConfig("nonexistent")).isEmpty();
    }

    @Test void configured_count() {
        assertThat(budget.configuredCount()).isEqualTo(0);
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 100, 50, 10.0, Instant.now()));
        assertThat(budget.configuredCount()).isEqualTo(1);
    }

    @Test void budget_check_reason() {
        budget.configure(new AgentBudget.BudgetConfig(
            "agent-1", "human-1", 100, 50, 10.0, Instant.now()));

        var check = budget.checkCredits("agent-1", 60);
        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).contains("per-tx limit");
    }
}
