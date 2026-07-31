package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PolicyChecker} — evaluating agent behavior against household policy.
 */
class PolicyCheckerTest {

    private PolicyChecker checker;

    @BeforeEach
    void setUp() {
        checker = new PolicyChecker(HouseholdPolicy.defaults());
    }

    @Test
    void budget_under_limit_no_concern() {
        var concerns = checker.checkComputeBudget("agent-ma", 5.0);
        assertThat(concerns).isEmpty();
    }

    @Test
    void budget_over_limit_generates_alert() {
        var concerns = checker.checkComputeBudget("agent-ma", 12.0);
        assertThat(concerns).hasSize(1);
        assertThat(concerns.getFirst().category()).isEqualTo("compute_budget");
        assertThat(concerns.getFirst().severity()).isEqualTo("alert");
        assertThat(concerns.getFirst().description()).contains("exceeded");
    }

    @Test
    void budget_at_80_percent_generates_advisory() {
        var concerns = checker.checkComputeBudget("agent-ma", 8.5);
        assertThat(concerns).hasSize(1);
        assertThat(concerns.getFirst().severity()).isEqualTo("advisory");
    }

    @Test
    void schedule_count_over_limit() {
        var concerns = checker.checkScheduleCount("agent-ma", 15);
        assertThat(concerns).hasSize(1);
        assertThat(concerns.getFirst().category()).isEqualTo("schedule_count");
        assertThat(concerns.getFirst().description()).contains("15");
        assertThat(concerns.getFirst().description()).contains("10");
    }

    @Test
    void schedule_count_under_limit() {
        var concerns = checker.checkScheduleCount("agent-ma", 5);
        assertThat(concerns).isEmpty();
    }

    @Test
    void watcher_count_over_limit() {
        var concerns = checker.checkWatcherCount("agent-ma", 25);
        assertThat(concerns).hasSize(1);
        assertThat(concerns.getFirst().category()).isEqualTo("watcher_count");
        assertThat(concerns.getFirst().description()).contains("25");
    }

    @Test
    void watcher_count_under_limit() {
        var concerns = checker.checkWatcherCount("agent-ma", 10);
        assertThat(concerns).isEmpty();
    }

    @Test
    void quiet_hours_violation() {
        // Create an instant at 23:00 in system timezone
        var zdt = ZonedDateTime.now(ZoneId.systemDefault())
            .withHour(23).withMinute(0).withSecond(0);
        var concerns = checker.checkQuietHours(zdt.toInstant());
        assertThat(concerns).hasSize(1);
        assertThat(concerns.getFirst().category()).isEqualTo("quiet_hours");
    }

    @Test
    void within_quiet_hours_daytime_no_concern() {
        // Create an instant at 14:00 in system timezone
        var zdt = ZonedDateTime.now(ZoneId.systemDefault())
            .withHour(14).withMinute(0).withSecond(0);
        var concerns = checker.checkQuietHours(zdt.toInstant());
        assertThat(concerns).isEmpty();
    }

    @Test
    void defaults_are_reasonable() {
        var defaults = HouseholdPolicy.defaults();
        assertThat(defaults.dailyCloudBudgetUSD()).isEqualTo(10.0);
        assertThat(defaults.maxDeployApprovalsPerDay()).isEqualTo(3);
        assertThat(defaults.maxSchedulesPerAgent()).isEqualTo(10);
        assertThat(defaults.maxWatchersPerAgent()).isEqualTo(20);
        assertThat(defaults.probationDays()).isEqualTo(7);
        assertThat(defaults.quietHourStart()).isEqualTo(22);
        assertThat(defaults.quietHourEnd()).isEqualTo(7);
    }

    @Test
    void buildPolicyContext_formatting() {
        String ctx = checker.buildPolicyContext();
        assertThat(ctx).contains("## Household Policy");
        assertThat(ctx).contains("$10.00");
        assertThat(ctx).contains("max 3 per day per agent");
        assertThat(ctx).contains("max 10 active schedules");
        assertThat(ctx).contains("max 20 active watchers");
        assertThat(ctx).contains("7 days");
        assertThat(ctx).contains("22:00 - 7:00");
    }

    @Test
    void custom_policy_limits() {
        var customPolicy = new HouseholdPolicy(5.0, 1, 5, 10, 14, 20, 8);
        var customChecker = new PolicyChecker(customPolicy);

        // Budget of 4.5 is OK for $5 limit
        assertThat(customChecker.checkComputeBudget("agent-ma", 4.5)).hasSize(1); // 90% = advisory

        // 6 schedules exceeds custom limit of 5
        assertThat(customChecker.checkScheduleCount("agent-ma", 6)).hasSize(1);

        // 11 watchers exceeds custom limit of 10
        assertThat(customChecker.checkWatcherCount("agent-ma", 11)).hasSize(1);
    }
}
