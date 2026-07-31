package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Wave D: Payment & Budget (§89).
 */
class McpBudgetAndKeyTest {

    // --- McpBudgetTracker Tests ---

    @Nested
    class BudgetTrackerTests {

        @Test
        void allows_within_budget() {
            var tracker = new McpBudgetTracker(10.0);
            assertNull(tracker.check("a1", "firecrawl"));
        }

        @Test
        void blocks_when_budget_exceeded() {
            var tracker = new McpBudgetTracker(1.0);
            tracker.record("a1", "firecrawl", 0.5);
            tracker.record("a1", "firecrawl", 0.5);
            var narrative = tracker.check("a1", "firecrawl");
            assertNotNull(narrative);
            assertTrue(narrative.contains("allocation"));
            assertTrue(narrative.contains("firecrawl"));
        }

        @Test
        void tracks_per_agent_separately() {
            var tracker = new McpBudgetTracker(1.0);
            tracker.record("a1", "firecrawl", 1.0);
            assertNotNull(tracker.check("a1", "firecrawl")); // a1 over
            assertNull(tracker.check("a2", "firecrawl")); // a2 still OK
        }

        @Test
        void custom_budget_overrides_default() {
            var tracker = new McpBudgetTracker(1.0);
            tracker.setBudget("a1", "firecrawl", 5.0);
            tracker.record("a1", "firecrawl", 3.0);
            assertNull(tracker.check("a1", "firecrawl")); // 3 < 5
            assertEquals(5.0, tracker.getLimit("a1", "firecrawl"));
        }

        @Test
        void remaining_decreases_with_spend() {
            var tracker = new McpBudgetTracker(10.0);
            assertEquals(10.0, tracker.remaining("a1", "s1"), 0.01);
            tracker.record("a1", "s1", 3.5);
            assertEquals(6.5, tracker.remaining("a1", "s1"), 0.01);
        }

        @Test
        void zero_cost_not_tracked() {
            var tracker = new McpBudgetTracker(10.0);
            tracker.record("a1", "searxng", 0.0);
            assertEquals(0.0, tracker.getSpend("a1", "searxng"), 0.01);
        }

        @Test
        void service_spend_aggregates_agents() {
            var tracker = new McpBudgetTracker(10.0);
            tracker.record("a1", "firecrawl", 1.5);
            tracker.record("a2", "firecrawl", 2.5);
            assertEquals(4.0, tracker.getServiceSpend("firecrawl"), 0.01);
        }

        @Test
        void negative_cost_not_tracked() {
            var tracker = new McpBudgetTracker(10.0);
            tracker.record("a1", "s1", -5.0);
            assertEquals(0.0, tracker.getSpend("a1", "s1"), 0.01);
        }
    }

    // --- McpKeyStore Tests ---

    @Nested
    class KeyStoreTests {

        private McpKeyStore createStore(Map<String, String> keys) {
            return new McpKeyStore(keys::get);
        }

        @Test
        void resolves_bearer_auth() {
            var store = createStore(Map.of("ha-token", "my-secret-token"));
            var config = new McpServiceConfig("ha", "Home Assistant", "http",
                "http://ha.local:8123", "local",
                new McpServiceConfig.AuthConfig("bearer", "ha-token", "Authorization"),
                null, true);

            String auth = store.resolveAuth(config);
            assertEquals("Bearer my-secret-token", auth);
        }

        @Test
        void resolves_api_key_auth() {
            var store = createStore(Map.of("fc-key", "sk-1234"));
            var config = new McpServiceConfig("fc", "Firecrawl", "http",
                "https://api.firecrawl.dev", "keyed",
                new McpServiceConfig.AuthConfig("api_key", "fc-key", "Authorization"),
                null, true);

            String auth = store.resolveAuth(config);
            assertEquals("sk-1234", auth);
        }

        @Test
        void returns_null_for_no_auth() {
            var store = createStore(Map.of());
            var config = new McpServiceConfig("sx", "Searxng", "http",
                "http://localhost:8888", "local",
                null, null, true);

            assertNull(store.resolveAuth(config));
        }

        @Test
        void returns_null_for_missing_key() {
            var store = createStore(Map.of());
            var config = new McpServiceConfig("fc", "Firecrawl", "http",
                "https://api.firecrawl.dev", "keyed",
                new McpServiceConfig.AuthConfig("bearer", "missing-key", "Authorization"),
                null, true);

            assertNull(store.resolveAuth(config));
        }

        @Test
        void caches_keys() {
            int[] fetchCount = {0};
            var store = new McpKeyStore(key -> {
                fetchCount[0]++;
                return "value";
            });

            var config = new McpServiceConfig("s1", "S1", "http", "http://s1", "keyed",
                new McpServiceConfig.AuthConfig("bearer", "k1", "Auth"),
                null, true);

            store.resolveAuth(config); // fetch 1
            store.resolveAuth(config); // cached
            assertEquals(1, fetchCount[0]);
        }

        @Test
        void invalidate_clears_cache() {
            int[] fetchCount = {0};
            var store = new McpKeyStore(key -> {
                fetchCount[0]++;
                return "value-" + fetchCount[0];
            });

            var config = new McpServiceConfig("s1", "S1", "http", "http://s1", "keyed",
                new McpServiceConfig.AuthConfig("bearer", "k1", "Auth"),
                null, true);

            String first = store.resolveAuth(config);
            store.invalidate("k1");
            String second = store.resolveAuth(config);

            assertEquals(2, fetchCount[0]);
            assertEquals("Bearer value-1", first);
            assertEquals("Bearer value-2", second);
        }

        @Test
        void has_key_checks_existence() {
            var store = createStore(Map.of("k1", "v1"));
            assertTrue(store.hasKey("k1"));
            assertFalse(store.hasKey("k2"));
        }
    }
}
