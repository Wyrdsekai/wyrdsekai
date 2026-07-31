package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Budget tracking for MCP service usage (§89.2).
 * Integrates with the Counting House for cost tracking and enforcement.
 *
 * Tracks spend at three levels:
 * - Per-call (individual MCP call cost for audit)
 * - Per-agent-per-service (budget enforcement)
 * - Per-service (household-level cost control)
 *
 * Cost sources:
 * - local tier: cost = 0 (free)
 * - keyed tier: configured flat rate per call
 * - metered tier: response-reported cost or configured estimate
 */
public class McpBudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(McpBudgetTracker.class);

    /**
     * Budget key → daily spend tracker.
     * Keys: "agent:{agentId}:{serviceId}:{date}" or "service:{serviceId}:{date}"
     */
    private final Map<String, DoubleAdder> dailySpend = new ConcurrentHashMap<>();

    /**
     * Budget limits: "agent:{agentId}:{serviceId}" → daily limit.
     * Default: no limit (Double.MAX_VALUE).
     */
    private final Map<String, Double> budgetLimits = new ConcurrentHashMap<>();

    /** Default daily limit per agent per metered service. */
    private final double defaultDailyLimit;

    public McpBudgetTracker() {
        this(10.0); // $10/day default
    }

    public McpBudgetTracker(double defaultDailyLimit) {
        this.defaultDailyLimit = defaultDailyLimit;
    }

    /**
     * Check if an agent has budget remaining for a service.
     *
     * @return null if within budget, narrative string if over budget
     */
    public String check(String agentId, String serviceId) {
        String key = agentKey(agentId, serviceId);
        double limit = budgetLimits.getOrDefault(key, defaultDailyLimit);
        double spent = getSpend(agentId, serviceId);

        if (spent >= limit) {
            log.debug("Budget exceeded: agent={}, service={}, spent={}, limit={}",
                agentId, serviceId, spent, limit);
            return "Your allocation for " + serviceId + " is spent for today. "
                + "Speak with the counting house to request more.";
        }
        return null;
    }

    /**
     * Record a cost for a service call.
     *
     * @param agentId   Agent making the call
     * @param serviceId Service called
     * @param cost      Cost of the call (0 for local/free)
     */
    public void record(String agentId, String serviceId, double cost) {
        if (cost <= 0) return;

        String date = today();

        // Per-agent-per-service spend
        String agentDailyKey = "agent:" + agentId + ":" + serviceId + ":" + date;
        dailySpend.computeIfAbsent(agentDailyKey, _ -> new DoubleAdder()).add(cost);

        // Per-service spend
        String serviceDailyKey = "service:" + serviceId + ":" + date;
        dailySpend.computeIfAbsent(serviceDailyKey, _ -> new DoubleAdder()).add(cost);

        log.debug("Budget: agent={}, service={}, cost={}", agentId, serviceId, cost);
    }

    /**
     * Set a daily budget limit for an agent+service pair.
     *
     * @param agentId   Agent to limit
     * @param serviceId Service to limit
     * @param dailyLimit Maximum daily spend
     */
    public void setBudget(String agentId, String serviceId, double dailyLimit) {
        budgetLimits.put(agentKey(agentId, serviceId), dailyLimit);
    }

    /** Get current daily spend for an agent+service pair. */
    public double getSpend(String agentId, String serviceId) {
        String key = "agent:" + agentId + ":" + serviceId + ":" + today();
        var adder = dailySpend.get(key);
        return adder != null ? adder.sum() : 0.0;
    }

    /** Get current daily spend for a service across all agents. */
    public double getServiceSpend(String serviceId) {
        String key = "service:" + serviceId + ":" + today();
        var adder = dailySpend.get(key);
        return adder != null ? adder.sum() : 0.0;
    }

    /** Get the daily limit for an agent+service pair. */
    public double getLimit(String agentId, String serviceId) {
        return budgetLimits.getOrDefault(agentKey(agentId, serviceId), defaultDailyLimit);
    }

    /** Get remaining budget for an agent+service pair. */
    public double remaining(String agentId, String serviceId) {
        return Math.max(0, getLimit(agentId, serviceId) - getSpend(agentId, serviceId));
    }

    private static String agentKey(String agentId, String serviceId) {
        return "agent:" + agentId + ":" + serviceId;
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }
}
