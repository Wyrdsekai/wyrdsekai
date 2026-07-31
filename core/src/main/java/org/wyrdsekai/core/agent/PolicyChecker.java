package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.config.HotReloadableConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluates agent behavior against household policy.
 * Used by the Governor agent to detect policy concerns.
 *
 * <p>The Governor observes and advises — it does NOT block, revoke, or punish.
 * Concerns are categorized by severity:
 * <ul>
 *   <li>"note" — log only</li>
 *   <li>"advisory" — inform steward</li>
 *   <li>"alert" — immediate notification</li>
 * </ul>
 *
 * <p>Supports hot-reloading: if constructed with a file path, the policy is
 * re-read from disk whenever the file changes. Use {@link #withHotReload(Path)}
 * for file-backed policy, or {@link #PolicyChecker(HouseholdPolicy)} for static policy.</p>
 *
 * @see HouseholdPolicy
 */
public class PolicyChecker {

    /**
     * A policy concern detected by the checker.
     *
     * @param category       Concern category (e.g. "compute_budget", "schedule_count")
     * @param severity       "note", "advisory", or "alert"
     * @param description    Human-readable description of the concern
     * @param recommendation Suggested action for the steward
     */
    public record PolicyConcern(
        String category,
        String severity,
        String description,
        String recommendation
    ) {}

    private final HotReloadableConfig<HouseholdPolicy> policyConfig;

    /**
     * Create a PolicyChecker with a static (non-reloadable) policy.
     *
     * @param policy The household policy (null falls back to defaults)
     */
    public PolicyChecker(HouseholdPolicy policy) {
        var resolved = policy != null ? policy : HouseholdPolicy.defaults();
        this.policyConfig = new HotReloadableConfig<>(null, p -> resolved, resolved);
    }

    /**
     * Create a PolicyChecker backed by a hot-reloadable config file.
     *
     * @param policyConfig Hot-reloadable config wrapping HouseholdPolicy
     */
    public PolicyChecker(HotReloadableConfig<HouseholdPolicy> policyConfig) {
        this.policyConfig = policyConfig;
    }

    /**
     * Create a PolicyChecker that hot-reloads from a properties file.
     * The file is checked on each policy access (cheap stat() call).
     *
     * @param path Path to household-policy.properties
     * @return PolicyChecker with hot-reload enabled
     */
    public static PolicyChecker withHotReload(Path path) {
        var config = new HotReloadableConfig<>(path, HouseholdPolicy::fromFile,
            HouseholdPolicy.defaults());
        return new PolicyChecker(config);
    }

    /**
     * Check compute budget usage for an agent.
     *
     * @param agentId    Agent entity ID
     * @param spentToday Amount spent today in USD
     * @return List of concerns (empty if within budget)
     */
    public List<PolicyConcern> checkComputeBudget(String agentId, double spentToday) {
        var concerns = new ArrayList<PolicyConcern>();
        var p = policy();
        double ratio = spentToday / p.dailyCloudBudgetUSD();

        if (ratio >= 1.0) {
            concerns.add(new PolicyConcern("compute_budget", "alert",
                "Agent " + agentId + " has exceeded the daily cloud budget ($"
                    + String.format("%.2f", spentToday) + " / $"
                    + String.format("%.2f", p.dailyCloudBudgetUSD()) + ")",
                "Review cloud API usage and consider throttling this agent."));
        } else if (ratio >= 0.8) {
            concerns.add(new PolicyConcern("compute_budget", "advisory",
                "Agent " + agentId + " is at " + String.format("%.0f", ratio * 100)
                    + "% of the daily cloud budget ($"
                    + String.format("%.2f", spentToday) + " / $"
                    + String.format("%.2f", p.dailyCloudBudgetUSD()) + ")",
                "Monitor cloud API usage. May need to throttle soon."));
        }
        return Collections.unmodifiableList(concerns);
    }

    /**
     * Check schedule count for an agent.
     *
     * @param agentId          Agent entity ID
     * @param activeSchedules  Number of currently active schedules
     * @return List of concerns
     */
    public List<PolicyConcern> checkScheduleCount(String agentId, int activeSchedules) {
        var concerns = new ArrayList<PolicyConcern>();
        var p = policy();
        if (activeSchedules > p.maxSchedulesPerAgent()) {
            concerns.add(new PolicyConcern("schedule_count", "advisory",
                "Agent " + agentId + " has " + activeSchedules + " active schedules (limit: "
                    + p.maxSchedulesPerAgent() + ")",
                "Review active schedules. Some may be redundant or no longer needed."));
        }
        return Collections.unmodifiableList(concerns);
    }

    /**
     * Check watcher count for an agent.
     *
     * @param agentId         Agent entity ID
     * @param activeWatchers  Number of currently active watchers
     * @return List of concerns
     */
    public List<PolicyConcern> checkWatcherCount(String agentId, int activeWatchers) {
        var concerns = new ArrayList<PolicyConcern>();
        var p = policy();
        if (activeWatchers > p.maxWatchersPerAgent()) {
            concerns.add(new PolicyConcern("watcher_count", "advisory",
                "Agent " + agentId + " has " + activeWatchers + " active watchers (limit: "
                    + p.maxWatchersPerAgent() + ")",
                "Review active watchers. Some may be redundant or no longer needed."));
        }
        return Collections.unmodifiableList(concerns);
    }

    /**
     * Check if a given instant falls within quiet hours.
     *
     * @param now The time to check
     * @return List of concerns (empty if outside quiet hours)
     */
    public List<PolicyConcern> checkQuietHours(Instant now) {
        var concerns = new ArrayList<PolicyConcern>();
        var p = policy();
        int hour = ZonedDateTime.ofInstant(now, ZoneId.systemDefault()).getHour();

        boolean inQuietHours;
        if (p.quietHourStart() > p.quietHourEnd()) {
            // Wraps midnight: e.g. 22:00 - 07:00
            inQuietHours = hour >= p.quietHourStart() || hour < p.quietHourEnd();
        } else {
            // Same-day: e.g. 01:00 - 06:00
            inQuietHours = hour >= p.quietHourStart() && hour < p.quietHourEnd();
        }

        if (inQuietHours) {
            concerns.add(new PolicyConcern("quiet_hours", "note",
                "Current time (" + hour + ":00) is within quiet hours ("
                    + p.quietHourStart() + ":00 - " + p.quietHourEnd() + ":00)",
                "Non-critical operations should be deferred until quiet hours end."));
        }
        return Collections.unmodifiableList(concerns);
    }

    /**
     * Build a policy context string for the Governor's prompt.
     * Summarizes the household policy rules for the LLM.
     *
     * @return Policy context string
     */
    public String buildPolicyContext() {
        var p = policy();
        return "## Household Policy\n"
            + "- Cloud API daily budget: $" + String.format("%.2f", p.dailyCloudBudgetUSD())
            + " total household\n"
            + "- Deploy approvals: max " + p.maxDeployApprovalsPerDay() + " per day per agent\n"
            + "- Script scheduling: max " + p.maxSchedulesPerAgent() + " active schedules per agent\n"
            + "- Watchers: max " + p.maxWatchersPerAgent() + " active watchers per agent\n"
            + "- New agent probation: " + p.probationDays() + " days before requesting elevated access\n"
            + "- Quiet hours: " + p.quietHourStart() + ":00 - "
            + p.quietHourEnd() + ":00 (non-critical only)\n";
    }

    /** Get the current policy (may trigger hot-reload if file-backed). */
    public HouseholdPolicy policy() {
        return policyConfig.get();
    }

    /** Get the underlying hot-reloadable config. */
    public HotReloadableConfig<HouseholdPolicy> policyConfig() {
        return policyConfig;
    }
}
