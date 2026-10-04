package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What {@code world.llm.budget_remaining()} tells an item: the being's real spend today, and a
 * cap only if a steward set one.
 *
 * <p>Until 2026-10-03 the call returned tokens 0, cost 0 and "reset in 24 h" every time, while the
 * tracker beside it had the numbers. There is no cap on her thinking unless one is set; the call
 * says so rather than implying one.
 */
public final class BudgetSnapshot {

    private BudgetSnapshot() {}

    /** The snapshot for one being from the live tracker; {@code tracker} null means nothing is tracked here. */
    public static Map<String, Object> of(AgentCostTracker tracker, String agentId, ZoneId zone, Instant now) {
        var m = new LinkedHashMap<String, Object>();
        var resetAt = LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        if (tracker == null) {
            m.put("tracked", false);
            m.put("tokens", 0L);
            m.put("costUsd", 0.0);
            m.put("dailyResetAt", resetAt);
            m.put("capped", false);
            return m;
        }
        var today = tracker.today(agentId);
        var all = tracker.summary(agentId).orElse(null);
        m.put("tracked", true);
        // The three keys items already read, now true: today's numbers.
        m.put("tokens", today.tokens());
        m.put("costUsd", today.costUsd());
        m.put("dailyResetAt", resetAt);
        m.put("inferencesToday", today.inferences());
        m.put("promptTokensToday", today.promptTokens());
        m.put("completionTokensToday", today.completionTokens());
        m.put("tokensAllTime", all == null ? 0L : all.totalTokens());
        m.put("costUsdAllTime", all == null ? 0.0 : all.totalMonetaryCost());
        var budget = tracker.budgetUsd(agentId);
        m.put("capped", budget.isPresent());
        budget.ifPresent(b -> {
            m.put("budgetUsd", b);
            m.put("remainingUsd", Math.max(0.0, b - today.costUsd()));
        });
        return m;
    }
}
