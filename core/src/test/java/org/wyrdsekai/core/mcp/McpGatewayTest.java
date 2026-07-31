package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Wave A: MCP Gateway Foundation (§86).
 */
class McpGatewayTest {

    // --- McpServiceRegistry Tests ---

    @Nested
    class ServiceRegistryTests {

        private McpServiceRegistry registry;

        @BeforeEach
        void setup() {
            registry = new McpServiceRegistry();
        }

        @Test
        void register_and_get() {
            var config = localService("searxng", "Searxng", "http://localhost:8888");
            registry.register(config);
            assertTrue(registry.get("searxng").isPresent());
            assertEquals("Searxng", registry.get("searxng").get().name());
        }

        @Test
        void unregister() {
            registry.register(localService("s1", "S1", "http://s1"));
            registry.unregister("s1");
            assertFalse(registry.get("s1").isPresent());
        }

        @Test
        void is_available_checks_enabled() {
            registry.register(localService("s1", "S1", "http://s1"));
            assertTrue(registry.isAvailable("s1"));

            registry.register(disabledService("s2", "S2", "http://s2"));
            assertFalse(registry.isAvailable("s2"));
        }

        @Test
        void load_from_json() throws Exception {
            String json = """
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"firecrawl","name":"Firecrawl","transport":"http",
                     "endpoint":"https://api.firecrawl.dev","tier":"keyed",
                     "auth":{"type":"api_key","safe_key":"fc-key","header":"Authorization"},
                     "rate_limit_override":{"per_zone":20},"enabled":false}
                ]}""";
            registry.loadFromJson(json);
            assertEquals(2, registry.size());
            assertTrue(registry.isAvailable("searxng"));
            assertFalse(registry.isAvailable("firecrawl")); // disabled
            assertTrue(registry.get("firecrawl").get().requiresAuth());
        }

        @Test
        void enabled_services_filters() {
            registry.register(localService("s1", "S1", "http://s1"));
            registry.register(disabledService("s2", "S2", "http://s2"));
            assertEquals(1, registry.enabledServices().size());
            assertEquals("s1", registry.enabledServices().getFirst().id());
        }

        @Test
        void service_tiers() {
            var local = localService("s1", "S1", "http://s1");
            assertTrue(local.isLocal());
            assertFalse(local.isMetered());

            var metered = new McpServiceConfig("s2", "S2", "http", "http://s2",
                "metered", null, null, true);
            assertTrue(metered.isMetered());
            assertFalse(metered.isLocal());
        }
    }

    // --- McpRateLimiter Tests ---

    @Nested
    class RateLimiterTests {

        @Test
        void allows_within_limit() {
            var limiter = new McpRateLimiter(3, 10, 100);
            assertNull(limiter.check("a1", "s1", "z1", null));
            limiter.record("a1", "s1", "z1");
            assertNull(limiter.check("a1", "s1", "z1", null));
            limiter.record("a1", "s1", "z1");
            assertNull(limiter.check("a1", "s1", "z1", null));
        }

        @Test
        void blocks_when_agent_limit_exceeded() {
            var limiter = new McpRateLimiter(2, 100, 1000);
            limiter.record("a1", "s1", "z1");
            limiter.record("a1", "s1", "z1");
            var narrative = limiter.check("a1", "s1", "z1", null);
            assertNotNull(narrative);
            assertTrue(narrative.contains("patience"));
        }

        @Test
        void blocks_when_service_limit_exceeded() {
            var limiter = new McpRateLimiter(100, 2, 1000);
            limiter.record("a1", "s1", "z1");
            limiter.record("a2", "s1", "z1"); // different agent, same service
            var narrative = limiter.check("a3", "s1", "z1", null);
            assertNotNull(narrative);
            assertTrue(narrative.contains("busy"));
        }

        @Test
        void blocks_when_zone_limit_exceeded() {
            var limiter = new McpRateLimiter(100, 100, 2);
            limiter.record("a1", "s1", "z1");
            limiter.record("a2", "s2", "z1"); // different everything, same zone
            var narrative = limiter.check("a3", "s3", "z1", null);
            assertNotNull(narrative);
            assertTrue(narrative.contains("throttled"));
        }

        @Test
        void per_service_override_applies() {
            var limiter = new McpRateLimiter(2, 100, 1000);
            var overrides = Map.of("per_agent", 5);
            limiter.record("a1", "s1", "z1");
            limiter.record("a1", "s1", "z1");
            // Default would block at 2, but override allows 5
            assertNull(limiter.check("a1", "s1", "z1", overrides));
        }

        @Test
        void remaining_tracks_usage() {
            var limiter = new McpRateLimiter(5, 10, 100);
            assertEquals(5, limiter.remainingForAgent("a1"));
            limiter.record("a1", "s1", "z1");
            assertEquals(4, limiter.remainingForAgent("a1"));
        }

        @Test
        void different_agents_have_separate_limits() {
            var limiter = new McpRateLimiter(2, 100, 1000);
            limiter.record("a1", "s1", "z1");
            limiter.record("a1", "s1", "z1");
            assertNotNull(limiter.check("a1", "s1", "z1", null)); // a1 blocked
            assertNull(limiter.check("a2", "s1", "z1", null)); // a2 still OK
        }
    }

    // --- McpCircuitBreaker Tests ---

    @Nested
    class CircuitBreakerTests {

        @Test
        void closed_by_default() {
            var cb = new McpCircuitBreaker();
            assertEquals(McpCircuitBreaker.State.CLOSED, cb.getState("s1"));
            assertNull(cb.check("s1"));
        }

        @Test
        void opens_after_threshold_failures() {
            var cb = new McpCircuitBreaker(3, 60);
            cb.recordFailure("s1");
            cb.recordFailure("s1");
            assertNull(cb.check("s1")); // Still closed (2 < 3)

            cb.recordFailure("s1"); // 3rd failure → open
            assertEquals(McpCircuitBreaker.State.OPEN, cb.getState("s1"));
            var narrative = cb.check("s1");
            assertNotNull(narrative);
            assertTrue(narrative.contains("storms"));
        }

        @Test
        void success_resets_failures() {
            var cb = new McpCircuitBreaker(3, 60);
            cb.recordFailure("s1");
            cb.recordFailure("s1");
            cb.recordSuccess("s1"); // Reset
            cb.recordFailure("s1"); // 1st failure again
            assertEquals(McpCircuitBreaker.State.CLOSED, cb.getState("s1"));
        }

        @Test
        void different_services_independent() {
            var cb = new McpCircuitBreaker(2, 60);
            cb.recordFailure("s1");
            cb.recordFailure("s1"); // s1 open
            assertNotNull(cb.check("s1"));
            assertNull(cb.check("s2")); // s2 still closed
        }

        @Test
        void reset_clears_state() {
            var cb = new McpCircuitBreaker(2, 60);
            cb.recordFailure("s1");
            cb.recordFailure("s1");
            assertNotNull(cb.check("s1"));

            cb.reset("s1");
            assertNull(cb.check("s1"));
            assertEquals(McpCircuitBreaker.State.CLOSED, cb.getState("s1"));
        }
    }

    // --- McpResult Tests ---

    @Nested
    class McpResultTests {

        @Test
        void ok_result() {
            var r = McpResult.ok("data", "s1", "tool1", 50, null);
            assertTrue(r.success());
            assertEquals("data", r.data());
            assertNull(r.error());
            assertNull(r.cost());
            assertEquals(50, r.latencyMs());
        }

        @Test
        void error_result() {
            var r = McpResult.error("timeout", "s1", "tool1", 30000);
            assertFalse(r.success());
            assertNull(r.data());
            assertEquals("timeout", r.error());
        }

        @Test
        void rate_limited_result() {
            var r = McpResult.rateLimited("wait", "s1", "tool1");
            assertFalse(r.success());
            assertEquals("wait", r.error());
        }
    }

    // --- McpGatewayService Integration Tests ---

    @Nested
    class GatewayServiceTests {

        private McpServiceRegistry registry;
        private McpGatewayService gateway;

        @BeforeEach
        void setup() {
            registry = new McpServiceRegistry();
            registry.register(localService("searxng", "Searxng", "http://localhost:8888"));
            gateway = new McpGatewayService(registry,
                (endpoint, tool, params, auth) -> "result: " + tool);
        }

        @Test
        void successful_call() {
            var result = gateway.execute("a1", "z1", "searxng", "search",
                Map.of("query", "hello"));
            assertTrue(result.success());
            assertEquals("result: search", result.data());
            assertEquals("searxng", result.serviceId());
            assertEquals("search", result.toolName());
            assertTrue(result.latencyMs() >= 0);
        }

        @Test
        void unknown_service_fails() {
            var result = gateway.execute("a1", "z1", "nonexistent", "tool", Map.of());
            assertFalse(result.success());
            assertTrue(result.error().contains("Unknown service"));
        }

        @Test
        void disabled_service_fails() {
            registry.register(disabledService("off", "Off", "http://off"));
            var result = gateway.execute("a1", "z1", "off", "tool", Map.of());
            assertFalse(result.success());
            assertTrue(result.error().contains("disabled"));
        }

        @Test
        void rate_limiting_blocks_excess() {
            var limiter = new McpRateLimiter(2, 100, 1000);
            var gw = new McpGatewayService(registry, limiter, new McpCircuitBreaker(),
                (e, t, p, a) -> "ok");

            // Use different params to avoid deduplication cache
            gw.execute("a1", "z1", "searxng", "search", Map.of("q", "a"));
            gw.execute("a1", "z1", "searxng", "search", Map.of("q", "b"));
            var result = gw.execute("a1", "z1", "searxng", "search", Map.of("q", "c"));
            assertFalse(result.success());
            assertTrue(result.error().contains("patience"));
        }

        @Test
        void circuit_breaker_blocks_after_failures() {
            var cb = new McpCircuitBreaker(2, 60);
            var failTransport = new McpGatewayService.McpTransport() {
                @Override
                public String callTool(String endpoint, String tool,
                                        Map<String, Object> params, String auth) throws Exception {
                    throw new RuntimeException("connection refused");
                }
            };
            var gw = new McpGatewayService(registry, new McpRateLimiter(), cb, failTransport);

            gw.execute("a1", "z1", "searxng", "search", Map.of()); // fail 1
            gw.execute("a1", "z1", "searxng", "search", Map.of()); // fail 2 → open

            var result = gw.execute("a1", "z1", "searxng", "search", Map.of());
            assertFalse(result.success());
            assertTrue(result.error().contains("storms"));
        }

        @Test
        void transport_exception_returns_error() {
            var gw = new McpGatewayService(registry,
                (e, t, p, a) -> { throw new RuntimeException("boom"); });
            var result = gw.execute("a1", "z1", "searxng", "search", Map.of());
            assertFalse(result.success());
            assertEquals("boom", result.error());
        }

        @Test
        void is_available_checks_registry_and_circuit() {
            assertTrue(gateway.isAvailable("searxng"));
            assertFalse(gateway.isAvailable("nonexistent"));
        }

        @Test
        void deduplication_returns_cached_result() {
            int[] callCount = {0};
            var gw = new McpGatewayService(registry,
                (e, t, p, a) -> { callCount[0]++; return "data"; });

            var params = Map.<String, Object>of("q", "test");
            gw.execute("a1", "z1", "searxng", "search", params);
            gw.execute("a1", "z1", "searxng", "search", params);

            // Second call should be deduplicated
            assertEquals(1, callCount[0]);
        }

        @Test
        void remaining_budget_reflects_usage() {
            assertEquals(10, gateway.remainingBudget("a1", "searxng"));
            gateway.execute("a1", "z1", "searxng", "search", Map.of());
            assertEquals(9, gateway.remainingBudget("a1", "searxng"));
        }
    }

    // ── Cost Recording ──

    @Nested
    class CostRecordingTests {

        @Test
        void cost_recorder_invoked_for_metered_service() {
            var registry = new McpServiceRegistry();
            registry.register(new McpServiceConfig("paid", "Paid", "http", "http://paid",
                "metered", null, null, true));

            var recorded = new AtomicReference<String>();
            var gw = new McpGatewayService(registry,
                (e, t, p, a) -> "result");
            gw.setCostRecorder((agentId, serviceId, toolName, cost, latencyMs) ->
                recorded.set(agentId + ":" + serviceId + ":" + toolName));

            gw.execute("agent-1", "zone-1", "paid", "search", Map.of("q", "test"));
            assertEquals("agent-1:paid:search", recorded.get());
        }

        @Test
        void cost_recorder_not_invoked_for_local_service() {
            var registry = new McpServiceRegistry();
            registry.register(localService("local-svc", "Local", "http://local"));

            boolean[] called = {false};
            var gw = new McpGatewayService(registry,
                (e, t, p, a) -> "result");
            gw.setCostRecorder((agentId, serviceId, toolName, cost, latencyMs) ->
                called[0] = true);

            gw.execute("agent-1", "zone-1", "local-svc", "tool", Map.of());
            assertFalse(called[0], "Cost recorder should not fire for non-metered services");
        }

        @Test
        void cost_recorder_failure_is_non_fatal() {
            var registry = new McpServiceRegistry();
            registry.register(new McpServiceConfig("paid", "Paid", "http", "http://paid",
                "metered", null, null, true));

            var gw = new McpGatewayService(registry,
                (e, t, p, a) -> "result");
            gw.setCostRecorder((agentId, serviceId, toolName, cost, latencyMs) -> {
                throw new RuntimeException("recording failure");
            });

            // Should not throw — cost recording failure is caught internally
            var result = gw.execute("agent-1", "zone-1", "paid", "tool", Map.of());
            assertTrue(result.success());
        }

        @Test
        void no_cost_recorder_wired_is_safe() {
            var registry = new McpServiceRegistry();
            registry.register(new McpServiceConfig("paid", "Paid", "http", "http://paid",
                "metered", null, null, true));

            var gw = new McpGatewayService(registry,
                (e, t, p, a) -> "result");
            // No setCostRecorder called — should work fine
            var result = gw.execute("agent-1", "zone-1", "paid", "tool", Map.of());
            assertTrue(result.success());
        }
    }

    // ── Auth Header Resolution ──

    @Nested
    class AuthHeaderTests {

        @Test
        void auth_header_resolved_for_service_requiring_auth() {
            var registry = new McpServiceRegistry();
            var authConfig = new McpServiceConfig.AuthConfig("bearer", "fc-key", "Authorization");
            registry.register(new McpServiceConfig("firecrawl", "Firecrawl", "http",
                "http://firecrawl", "keyed", authConfig, null, true));

            // Capture the auth header passed to transport
            var capturedAuth = new AtomicReference<String>();
            var gw = new McpGatewayService(registry,
                (endpoint, tool, params, auth) -> {
                    capturedAuth.set(auth);
                    return "ok";
                });

            // Wire a McpKeyStore with a test backend that returns a known token
            var keyStore = new McpKeyStore(safeKey -> {
                if ("fc-key".equals(safeKey)) return "sk-test-12345";
                return null;
            });
            gw.setKeyStore(keyStore);

            var result = gw.execute("a1", "z1", "firecrawl", "scrape", Map.of("url", "http://x"));
            assertTrue(result.success());
            assertNotNull(capturedAuth.get(), "Auth header should be non-null for auth-required service");
            assertEquals("Bearer sk-test-12345", capturedAuth.get());
        }

        @Test
        void auth_header_null_for_local_service_without_auth() {
            var registry = new McpServiceRegistry();
            registry.register(localService("searxng", "Searxng", "http://localhost:8888"));

            var capturedAuth = new AtomicReference<String>("SENTINEL");
            var gw = new McpGatewayService(registry,
                (endpoint, tool, params, auth) -> {
                    capturedAuth.set(auth);
                    return "ok";
                });

            // Wire a key store — should still not resolve for non-auth service
            var keyStore = new McpKeyStore(safeKey -> "should-not-be-called");
            gw.setKeyStore(keyStore);

            var result = gw.execute("a1", "z1", "searxng", "search", Map.of("q", "test"));
            assertTrue(result.success());
            assertNull(capturedAuth.get(), "Auth header should be null for service not requiring auth");
        }

        @Test
        void auth_header_null_when_no_keystore_set() {
            var registry = new McpServiceRegistry();
            var authConfig = new McpServiceConfig.AuthConfig("api_key", "brave-key", "X-Api-Key");
            registry.register(new McpServiceConfig("brave", "Brave", "http",
                "http://brave", "keyed", authConfig, null, true));

            var capturedAuth = new AtomicReference<String>("SENTINEL");
            var gw = new McpGatewayService(registry,
                (endpoint, tool, params, auth) -> {
                    capturedAuth.set(auth);
                    return "ok";
                });
            // No keyStore set — auth should be null even for auth-required service

            var result = gw.execute("a1", "z1", "brave", "search", Map.of("q", "test"));
            assertTrue(result.success());
            assertNull(capturedAuth.get(), "Auth header should be null when no key store is configured");
        }

        @Test
        void api_key_auth_type_returns_raw_token() {
            var registry = new McpServiceRegistry();
            var authConfig = new McpServiceConfig.AuthConfig("api_key", "brave-key", "X-Api-Key");
            registry.register(new McpServiceConfig("brave", "Brave", "http",
                "http://brave", "keyed", authConfig, null, true));

            var capturedAuth = new AtomicReference<String>();
            var gw = new McpGatewayService(registry,
                (endpoint, tool, params, auth) -> {
                    capturedAuth.set(auth);
                    return "ok";
                });

            var keyStore = new McpKeyStore(safeKey -> "raw-api-key-value");
            gw.setKeyStore(keyStore);

            gw.execute("a1", "z1", "brave", "search", Map.of("q", "test"));
            assertEquals("raw-api-key-value", capturedAuth.get(),
                "api_key auth type should return raw token without Bearer prefix");
        }

        @Test
        void basic_auth_type_returns_base64_encoded() {
            var registry = new McpServiceRegistry();
            var authConfig = new McpServiceConfig.AuthConfig("basic", "basic-cred", "Authorization");
            registry.register(new McpServiceConfig("svc", "Svc", "http",
                "http://svc", "keyed", authConfig, null, true));

            var capturedAuth = new AtomicReference<String>();
            var gw = new McpGatewayService(registry,
                (endpoint, tool, params, auth) -> {
                    capturedAuth.set(auth);
                    return "ok";
                });

            var keyStore = new McpKeyStore(safeKey -> "user:pass");
            gw.setKeyStore(keyStore);

            gw.execute("a1", "z1", "svc", "tool", Map.of());
            var expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes());
            assertEquals(expected, capturedAuth.get(),
                "basic auth type should return Base64-encoded value");
        }

        @Test
        void auth_resolution_failure_results_in_null_header() {
            var registry = new McpServiceRegistry();
            var authConfig = new McpServiceConfig.AuthConfig("bearer", "missing-key", "Authorization");
            registry.register(new McpServiceConfig("svc", "Svc", "http",
                "http://svc", "keyed", authConfig, null, true));

            var capturedAuth = new AtomicReference<String>("SENTINEL");
            var gw = new McpGatewayService(registry,
                (endpoint, tool, params, auth) -> {
                    capturedAuth.set(auth);
                    return "ok";
                });

            // Backend throws an exception on key lookup
            var keyStore = new McpKeyStore(safeKey -> {
                throw new RuntimeException("TheSafe unavailable");
            });
            gw.setKeyStore(keyStore);

            var result = gw.execute("a1", "z1", "svc", "tool", Map.of());
            assertTrue(result.success(), "Call should still succeed even if auth resolution fails");
            assertNull(capturedAuth.get(), "Auth header should be null when key resolution fails");
        }
    }

    // --- Helpers ---

    private static McpServiceConfig localService(String id, String name, String endpoint) {
        return new McpServiceConfig(id, name, "http", endpoint, "local",
            null, null, true);
    }

    private static McpServiceConfig disabledService(String id, String name, String endpoint) {
        return new McpServiceConfig(id, name, "http", endpoint, "local",
            null, null, false);
    }
}
