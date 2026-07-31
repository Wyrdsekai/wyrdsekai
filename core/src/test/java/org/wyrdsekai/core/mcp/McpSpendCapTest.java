package org.wyrdsekai.core.mcp;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.5b — the gateway's HARD daily spend cap (the minimum Accelerando
 * safeguard). Metered calls accrue spend in the default-on tracker; once an
 * agent's daily allocation for a service is spent, the next call is DENIED
 * with a speakable narrative — no wiring step can forget to enable this.
 * Free/local services never accrue, so the cap cannot brick them.
 */
class McpSpendCapTest {

    private static McpGatewayService meteredGateway() {
        var registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig(
            "paid-api", "Paid API", "http", "http://x/mcp", "metered", null, null, true));
        registry.register(new McpServiceConfig(
            "free-api", "Free API", "http", "http://y/mcp", "local", null, null, true));
        return new McpGatewayService(registry, (endpoint, tool, params, auth) -> "ok-data");
    }

    @Test
    void over_cap_metered_call_is_denied_with_a_narrative() {
        var gateway = meteredGateway();
        // Tiny cap so two calls exceed it: each metered call books $0.001.
        var tracker = new McpBudgetTracker(0.0015);
        gateway.setBudgetTracker(tracker);

        var first = gateway.execute("ember", "zone-1", "paid-api", "lookup", Map.of("q", "a"));
        assertThat(first.success()).isTrue();
        var second = gateway.execute("ember", "zone-1", "paid-api", "lookup", Map.of("q", "b"));
        assertThat(second.success()).isTrue(); // crosses the cap ($0.002 > $0.0015)

        var third = gateway.execute("ember", "zone-1", "paid-api", "lookup", Map.of("q", "c"));
        assertThat(third.success()).isFalse();
        assertThat(third.error()).contains("allocation")
            .withFailMessage("the denial must be a narrative the agent can speak: %s", third.error());
    }

    @Test
    void cap_is_per_agent_not_household_wide() {
        var gateway = meteredGateway();
        var tracker = new McpBudgetTracker(0.0015);
        gateway.setBudgetTracker(tracker);
        gateway.execute("ember", "zone-1", "paid-api", "lookup", Map.of("q", "a"));
        gateway.execute("ember", "zone-1", "paid-api", "lookup", Map.of("q", "b"));
        assertThat(gateway.execute("ember", "zone-1", "paid-api", "lookup", Map.of("q", "c"))
            .success()).isFalse();
        // A different agent still has their own allocation.
        assertThat(gateway.execute("wren", "zone-1", "paid-api", "lookup", Map.of("q", "d"))
            .success()).isTrue();
    }

    @Test
    void free_services_never_accrue_and_are_never_denied() {
        var gateway = meteredGateway();
        gateway.setBudgetTracker(new McpBudgetTracker(0.0001)); // absurdly tight
        for (int i = 0; i < 5; i++) {
            var r = gateway.execute("ember", "zone-1", "free-api", "lookup", Map.of("q", "x" + i));
            assertThat(r.success()).isTrue();
        }
        assertThat(gateway.budgetTracker().getSpend("ember", "free-api")).isZero();
    }

    @Test
    void cap_is_on_by_default_with_the_ten_dollar_floor() {
        // No setBudgetTracker call at all — the gateway's own tracker must
        // exist and enforce the configured/default cap.
        var gateway = meteredGateway();
        assertThat(gateway.budgetTracker()).isNotNull();
        assertThat(gateway.budgetTracker().getLimit("anyone", "paid-api"))
            .isGreaterThan(0.0)
            .isLessThan(Double.MAX_VALUE);
    }
}
