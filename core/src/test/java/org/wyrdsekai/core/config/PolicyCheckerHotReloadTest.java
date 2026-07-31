package org.wyrdsekai.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.HouseholdPolicy;
import org.wyrdsekai.core.agent.PolicyChecker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PolicyChecker hot-reload integration with HotReloadableConfig.
 */
class PolicyCheckerHotReloadTest {

    @TempDir
    Path tempDir;

    @Test
    void hot_reloads_policy_when_file_changes() throws Exception {
        var file = tempDir.resolve("household-policy.properties");
        Files.writeString(file, """
            daily_cloud_budget_usd=10.0
            max_deploy_approvals_per_day=3
            max_schedules_per_agent=10
            max_watchers_per_agent=20
            probation_days=7
            quiet_hour_start=22
            quiet_hour_end=7
            """);

        var checker = PolicyChecker.withHotReload(file);

        // Initial policy from file
        assertThat(checker.policy().dailyCloudBudgetUSD()).isEqualTo(10.0);
        assertThat(checker.policy().maxSchedulesPerAgent()).isEqualTo(10);

        // Budget check with $10 limit — $8.5 is 85%, should be advisory
        var concerns = checker.checkComputeBudget("agent-ma", 8.5);
        assertThat(concerns).hasSize(1);
        assertThat(concerns.getFirst().severity()).isEqualTo("advisory");

        // Modify the file — change budget to $20
        Thread.sleep(50);
        Files.writeString(file, """
            daily_cloud_budget_usd=20.0
            max_deploy_approvals_per_day=5
            max_schedules_per_agent=15
            max_watchers_per_agent=30
            probation_days=14
            quiet_hour_start=23
            quiet_hour_end=6
            """);
        file.toFile().setLastModified(System.currentTimeMillis() + 1000);

        // Policy should reflect new values
        assertThat(checker.policy().dailyCloudBudgetUSD()).isEqualTo(20.0);
        assertThat(checker.policy().maxSchedulesPerAgent()).isEqualTo(15);
        assertThat(checker.policy().probationDays()).isEqualTo(14);

        // Budget check with $20 limit — $8.5 is 42.5%, should be no concern
        var newConcerns = checker.checkComputeBudget("agent-ma", 8.5);
        assertThat(newConcerns).isEmpty();
    }

    @Test
    void falls_back_to_defaults_when_file_missing() {
        var missing = tempDir.resolve("nonexistent.properties");
        var checker = PolicyChecker.withHotReload(missing);

        // Should use defaults
        assertThat(checker.policy().dailyCloudBudgetUSD()).isEqualTo(10.0);
        assertThat(checker.policy().maxSchedulesPerAgent()).isEqualTo(10);
    }

    @Test
    void schedule_check_respects_hot_reloaded_limit() throws Exception {
        var file = tempDir.resolve("household-policy.properties");
        Files.writeString(file, "max_schedules_per_agent=5\n");

        var checker = PolicyChecker.withHotReload(file);

        // 6 schedules exceeds limit of 5
        assertThat(checker.checkScheduleCount("agent-ma", 6)).hasSize(1);

        // Increase limit to 10
        Thread.sleep(50);
        Files.writeString(file, "max_schedules_per_agent=10\n");
        file.toFile().setLastModified(System.currentTimeMillis() + 1000);

        // 6 schedules now within limit
        assertThat(checker.checkScheduleCount("agent-ma", 6)).isEmpty();
    }

    @Test
    void backward_compatible_static_constructor_still_works() {
        var checker = new PolicyChecker(HouseholdPolicy.defaults());
        assertThat(checker.policy().dailyCloudBudgetUSD()).isEqualTo(10.0);

        // null policy falls back to defaults
        var nullChecker = new PolicyChecker((HouseholdPolicy) null);
        assertThat(nullChecker.policy().dailyCloudBudgetUSD()).isEqualTo(10.0);
    }

    @Test
    void buildPolicyContext_reflects_hot_reloaded_values() throws Exception {
        var file = tempDir.resolve("household-policy.properties");
        Files.writeString(file, "daily_cloud_budget_usd=25.0\n");

        var checker = PolicyChecker.withHotReload(file);
        var ctx = checker.buildPolicyContext();
        assertThat(ctx).contains("$25.00");
    }
}
