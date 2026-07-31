package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent budget caps (§68).
 * Limits how much an agent can spend in CU (compute units) or credits
 * without principal approval.
 */
public class AgentBudget {

    /** Budget configuration for a single agent. */
    public record BudgetConfig(
        String agentId,
        String principalId,     // human who controls this budget
        long maxCreditsPerDay,
        long maxCreditsPerTx,
        double maxCUPerDay,
        Instant configuredAt
    ) {}

    /** Current spending state for an agent. */
    public record SpendingState(
        String agentId,
        long creditsSpentToday,
        double cuSpentToday,
        Instant dayStart
    ) {
        public long creditsRemaining(BudgetConfig config) {
            return config.maxCreditsPerDay() - creditsSpentToday;
        }

        public double cuRemaining(BudgetConfig config) {
            return config.maxCUPerDay() - cuSpentToday;
        }

        public boolean canSpendCredits(BudgetConfig config, long amount) {
            if (amount > config.maxCreditsPerTx()) return false;
            return creditsSpentToday + amount <= config.maxCreditsPerDay();
        }

        public boolean canSpendCU(BudgetConfig config, double amount) {
            return cuSpentToday + amount <= config.maxCUPerDay();
        }
    }

    /** Budget check result. */
    public record BudgetCheck(boolean allowed, String reason) {
        public static final BudgetCheck OK = new BudgetCheck(true, null);
    }

    private final Map<String, BudgetConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, SpendingState> spending = new ConcurrentHashMap<>();

    /** Configure budget for an agent. */
    public void configure(BudgetConfig config) {
        configs.put(config.agentId(), config);
        // Reset spending on reconfiguration
        spending.put(config.agentId(), new SpendingState(
            config.agentId(), 0, 0.0, Instant.now()));
    }

    /** Check if an agent can spend credits. */
    public BudgetCheck checkCredits(String agentId, long amount) {
        var config = configs.get(agentId);
        if (config == null) return BudgetCheck.OK; // No budget = unlimited
        var state = getSpendingState(agentId);
        if (amount > config.maxCreditsPerTx()) {
            return new BudgetCheck(false,
                "Transaction amount " + amount + " exceeds per-tx limit " + config.maxCreditsPerTx());
        }
        if (!state.canSpendCredits(config, amount)) {
            return new BudgetCheck(false,
                "Daily credit budget exceeded (" + state.creditsSpentToday() + "/" + config.maxCreditsPerDay() + ")");
        }
        return BudgetCheck.OK;
    }

    /** Check if an agent can spend CU. */
    public BudgetCheck checkCU(String agentId, double amount) {
        var config = configs.get(agentId);
        if (config == null) return BudgetCheck.OK;
        var state = getSpendingState(agentId);
        if (!state.canSpendCU(config, amount)) {
            return new BudgetCheck(false,
                "Daily CU budget exceeded (" + String.format("%.2f", state.cuSpentToday())
                    + "/" + String.format("%.2f", config.maxCUPerDay()) + ")");
        }
        return BudgetCheck.OK;
    }

    /** Record credit spending. */
    public void recordCreditSpend(String agentId, long amount) {
        spending.compute(agentId, (_, state) -> {
            if (state == null) state = new SpendingState(agentId, 0, 0.0, Instant.now());
            return new SpendingState(agentId,
                state.creditsSpentToday() + amount, state.cuSpentToday(), state.dayStart());
        });
    }

    /** Record CU spending. */
    public void recordCUSpend(String agentId, double amount) {
        spending.compute(agentId, (_, state) -> {
            if (state == null) state = new SpendingState(agentId, 0, 0.0, Instant.now());
            return new SpendingState(agentId,
                state.creditsSpentToday(), state.cuSpentToday() + amount, state.dayStart());
        });
    }

    /** Reset daily spending (called at start of new day). */
    public void resetDaily() {
        spending.replaceAll((id, state) ->
            new SpendingState(id, 0, 0.0, Instant.now()));
    }

    /** Get budget config for an agent. */
    public Optional<BudgetConfig> getConfig(String agentId) {
        return Optional.ofNullable(configs.get(agentId));
    }

    /** Get spending state. */
    public SpendingState getSpendingState(String agentId) {
        return spending.computeIfAbsent(agentId,
            id -> new SpendingState(id, 0, 0.0, Instant.now()));
    }

    /** Number of configured budgets. */
    public int configuredCount() { return configs.size(); }
}
