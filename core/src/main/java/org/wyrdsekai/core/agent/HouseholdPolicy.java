package org.wyrdsekai.core.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Household governance policy: budget limits, scheduling limits, quiet hours, etc.
 * Used by {@link PolicyChecker} to evaluate agent behavior against household rules.
 *
 * <p>Can be loaded from a YAML/properties file at
 * {@code ~/.wyrdsekai/household-policy.yaml} or use sensible defaults.</p>
 *
 * @param dailyCloudBudgetUSD     Daily cloud API budget in USD for the entire household
 * @param maxDeployApprovalsPerDay Maximum deploy approvals per agent per day
 * @param maxSchedulesPerAgent    Maximum active schedules per agent
 * @param maxWatchersPerAgent     Maximum active watchers per agent
 * @param probationDays           Days before a new agent can request elevated access
 * @param quietHourStart          Quiet hours start (24h, e.g. 22)
 * @param quietHourEnd            Quiet hours end (24h, e.g. 7)
 * @param codingPolicy Coding-backend policy bounds
 */
public record HouseholdPolicy(
    double dailyCloudBudgetUSD,
    int maxDeployApprovalsPerDay,
    int maxSchedulesPerAgent,
    int maxWatchersPerAgent,
    int probationDays,
    int quietHourStart,
    int quietHourEnd,
    CodingPolicy codingPolicy
) {

    /**
     * Coding-backend household policy block.
     *
     * <p>Layers on top of {@link AgentCostTracker} / {@link
     * org.wyrdsekai.core.protection.ActionPolicy} to gate which backends a
     * companion may dispatch to, with what budget. All-zero defaults mean
     * "no coding-specific enforcement until the steward configures
     * something" — the GraalJS selection policy treats unset bounds as
     * permissive.</p>
     *
     * @param maxPaidCuPerDayHousehold    Max CU households as a whole may
     *                                    spend on CLOUD_PAID backends in
     *                                    one calendar day. {@code 0} = no
     *                                    cap (paid backends pre-gated only
     *                                    by their own {@code max_cu_per_task}
     *                                    setting).
     * @param maxPaidCuPerDayPerCompanion Per-companion daily CU cap on
     *                                    CLOUD_PAID backends. {@code 0} =
     *                                    no per-companion cap.
     * @param requireApprovalFor          Backends that need explicit
     *                                    steward approval before each task
     *                                    runs (unless the estimate is
     *                                    under {@link #autoApproveUnderCu}).
     * @param autoApproveUnderCu          Auto-approve threshold for
     *                                    {@code requireApprovalFor}
     *                                    backends — tasks whose estimated
     *                                    CU is below this skip the
     *                                    approval queue. {@code 0} means
     *                                    every paid task needs approval.
     * @param weekdayOnlyPaidBackends     When true, CLOUD_PAID backends
     *                                    are only available Mon-Fri
     *                                    (household-local clock). Default
     *                                    {@code false}.
     */
    public record CodingPolicy(
        long maxPaidCuPerDayHousehold,
        long maxPaidCuPerDayPerCompanion,
        List<String> requireApprovalFor,
        long autoApproveUnderCu,
        boolean weekdayOnlyPaidBackends
    ) {
        public CodingPolicy {
            // Round-trip tolerance + JS-friendly: empty list rather than null
            // so the policy script can dot-walk without null guards.
            if (requireApprovalFor == null) requireApprovalFor = List.of();
            else requireApprovalFor = List.copyOf(requireApprovalFor);
        }

        /** No-enforcement defaults — unrestricted until steward configures. */
        public static CodingPolicy defaults() {
            return new CodingPolicy(0L, 0L, List.of(), 0L, false);
        }

        /** True when this companion/household has any coding-policy bounds set. */
        public boolean isEmpty() {
            return maxPaidCuPerDayHousehold == 0L
                && maxPaidCuPerDayPerCompanion == 0L
                && (requireApprovalFor == null || requireApprovalFor.isEmpty())
                && autoApproveUnderCu == 0L
                && !weekdayOnlyPaidBackends;
        }
    }

    /** Backwards-compat constructor — defaults coding policy to no-enforcement. */
    public HouseholdPolicy(
        double dailyCloudBudgetUSD,
        int maxDeployApprovalsPerDay,
        int maxSchedulesPerAgent,
        int maxWatchersPerAgent,
        int probationDays,
        int quietHourStart,
        int quietHourEnd
    ) {
        this(dailyCloudBudgetUSD, maxDeployApprovalsPerDay, maxSchedulesPerAgent,
             maxWatchersPerAgent, probationDays, quietHourStart, quietHourEnd,
             CodingPolicy.defaults());
    }

    public HouseholdPolicy {
        // Ensure codingPolicy is never null after construction.
        if (codingPolicy == null) codingPolicy = CodingPolicy.defaults();
    }

    /**
     * Sensible defaults for a typical household.
     */
    public static HouseholdPolicy defaults() {
        return new HouseholdPolicy(10.0, 3, 10, 20, 7, 22, 7,
            CodingPolicy.defaults());
    }

    /**
     * Load from a properties/YAML file. Falls back to defaults for missing keys.
     * Supports simple key=value format:
     * <pre>
     * daily_cloud_budget_usd=10.0
     * max_deploy_approvals_per_day=3
     * max_schedules_per_agent=10
     * max_watchers_per_agent=20
     * probation_days=7
     * quiet_hour_start=22
     * quiet_hour_end=7
     * </pre>
     *
     * @param path Path to the policy file
     * @return Loaded policy, or defaults if the file is missing/malformed
     */
    public static HouseholdPolicy fromFile(Path path) {
        var defaults = defaults();
        if (path == null || !Files.exists(path)) return defaults;

        try (var reader = Files.newBufferedReader(path)) {
            var props = new Properties();
            props.load(reader);

            // Coding policy: all keys optional; missing → no-enforcement defaults.
            // Property names use the same coding_policy.* dot-prefixed style the
            // SPEC §9.5 JSON shape demonstrates, just flattened for .properties.
            var cp = defaults.codingPolicy();
            var requireApprovalRaw = props.getProperty(
                "coding_policy.require_approval_for", "");
            var requireApproval = parseCsv(requireApprovalRaw);
            var coding = new CodingPolicy(
                Long.parseLong(props.getProperty(
                    "coding_policy.max_paid_cu_per_day_household",
                    String.valueOf(cp.maxPaidCuPerDayHousehold()))),
                Long.parseLong(props.getProperty(
                    "coding_policy.max_paid_cu_per_day_per_companion",
                    String.valueOf(cp.maxPaidCuPerDayPerCompanion()))),
                requireApproval,
                Long.parseLong(props.getProperty(
                    "coding_policy.auto_approve_under_cu",
                    String.valueOf(cp.autoApproveUnderCu()))),
                Boolean.parseBoolean(props.getProperty(
                    "coding_policy.weekday_only_paid_backends",
                    String.valueOf(cp.weekdayOnlyPaidBackends())))
            );

            return new HouseholdPolicy(
                Double.parseDouble(props.getProperty("daily_cloud_budget_usd",
                    String.valueOf(defaults.dailyCloudBudgetUSD))),
                Integer.parseInt(props.getProperty("max_deploy_approvals_per_day",
                    String.valueOf(defaults.maxDeployApprovalsPerDay))),
                Integer.parseInt(props.getProperty("max_schedules_per_agent",
                    String.valueOf(defaults.maxSchedulesPerAgent))),
                Integer.parseInt(props.getProperty("max_watchers_per_agent",
                    String.valueOf(defaults.maxWatchersPerAgent))),
                Integer.parseInt(props.getProperty("probation_days",
                    String.valueOf(defaults.probationDays))),
                Integer.parseInt(props.getProperty("quiet_hour_start",
                    String.valueOf(defaults.quietHourStart))),
                Integer.parseInt(props.getProperty("quiet_hour_end",
                    String.valueOf(defaults.quietHourEnd))),
                coding
            );
        } catch (IOException | NumberFormatException e) {
            return defaults;
        }
    }

    private static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (var token : raw.split(",")) {
            var t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }
}
