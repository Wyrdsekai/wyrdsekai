package org.wyrdsekai.between.skill;

import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BetweenSkillBridge — cross-node skill routing.
 */
class BetweenSkillBridgeTest {

    private SkillRegistry createRegistry(String... supportedSkills) {
        var reg = new SkillRegistry(null, null);
        reg.registerExecutor(new SkillExecutor() {
            @Override
            public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
                return SkillResult.ok("Local result: " + skillId, Map.of(),
                    5, SkillTier.NATIVE, skillId);
            }

            @Override
            public List<SkillDefinition> availableSkills() {
                return Arrays.stream(supportedSkills)
                    .map(id -> SkillDefinition.native_(id, id, "d", "r", List.of(), SkillAuth.NONE))
                    .toList();
            }

            @Override
            public boolean supports(String skillId) {
                return Arrays.asList(supportedSkills).contains(skillId);
            }

            @Override
            public SkillTier tier() { return SkillTier.NATIVE; }
        });
        reg.setPermissions("did:agent:1", SkillPermission.allowAll());
        return reg;
    }

    /** In-memory mock transport for testing. */
    static class MockTransport implements BetweenSkillBridge.BetweenTransport {
        final Map<String, List<BiConsumer<String, byte[]>>> subscriptions = new ConcurrentHashMap<>();
        final List<PublishedMessage> published = new CopyOnWriteArrayList<>();

        record PublishedMessage(String subject, byte[] payload) {}

        @Override
        public void publish(String subject, byte[] payload) {
            published.add(new PublishedMessage(subject, payload));
            // Deliver to matching subscribers (exact match or wildcard)
            subscriptions.forEach((pattern, handlers) -> {
                if (matchesSubject(pattern, subject)) {
                    handlers.forEach(h -> h.accept(subject, payload));
                }
            });
        }

        @Override
        public void subscribe(String subject, BiConsumer<String, byte[]> handler) {
            subscriptions.computeIfAbsent(subject, k -> new CopyOnWriteArrayList<>()).add(handler);
        }

        private boolean matchesSubject(String pattern, String subject) {
            if (pattern.equals(subject)) return true;
            // Simple wildcard matching for NATS-style subjects
            String regex = pattern.replace(".", "\\.").replace("*", "[^.]+");
            return subject.matches(regex);
        }
    }

    // ── Construction ────────────────────────────────────────────────────

    @Nested
    class ConstructionTests {

        @Test
        void creates_bridge() {
            var registry = createRegistry("test.skill");
            var bridge = new BetweenSkillBridge("node1", "household1", registry);
            assertNotNull(bridge);
        }

        @Test
        void sets_transport_and_subscribes() {
            var registry = createRegistry("test.skill");
            var bridge = new BetweenSkillBridge("node1", "household1", registry);
            var transport = new MockTransport();

            bridge.setTransport(transport);

            // Should subscribe to directed invoke, broadcast invoke, and result subjects
            assertEquals(3, transport.subscriptions.size());
            assertTrue(transport.subscriptions.containsKey(
                "between.household1.skill.node1.invoke"));
            assertTrue(transport.subscriptions.containsKey(
                "between.household1.skill.broadcast.invoke"));
            assertTrue(transport.subscriptions.containsKey(
                "between.household1.skill.node1.result"));
        }
    }

    // ── Local Routing ───────────────────────────────────────────────────

    @Nested
    class LocalRoutingTests {

        @Test
        void local_skill_executes_locally() {
            var registry = createRegistry("local.skill");
            var bridge = new BetweenSkillBridge("node1", "household1", registry);
            var transport = new MockTransport();
            bridge.setTransport(transport);

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);
            var result = bridge.invoke("local.skill", Map.of(), ctx, SkillLocality.ANY);

            assertTrue(result.success());
            assertTrue(result.output().contains("Local result"));
            // Should NOT publish to transport for local skills
            assertTrue(transport.published.isEmpty());
        }
    }

    // ── Remote Routing ──────────────────────────────────────────────────

    @Nested
    class RemoteRoutingTests {

        @Test
        void remote_skill_without_transport_returns_error() {
            var registry = createRegistry(); // No local skills
            var bridge = new BetweenSkillBridge("node1", "household1", registry);
            // No transport set

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);
            var result = bridge.invoke("remote.skill", Map.of(), ctx, SkillLocality.ANY);

            assertFalse(result.success());
            assertTrue(result.output().contains("transport not available"));
        }

        @Test
        void remote_skill_publishes_to_broadcast() {
            var registry = createRegistry(); // No local skills
            var bridge = new BetweenSkillBridge("node1", "household1", registry);
            var transport = new MockTransport();
            bridge.setTransport(transport);

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);

            // This will timeout since no remote node responds, but it should publish
            // Use a thread to avoid blocking
            Thread.ofVirtual().start(() ->
                bridge.invoke("remote.skill", Map.of(), ctx, SkillLocality.ANY));

            // Give it a moment to publish
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            assertFalse(transport.published.isEmpty(),
                "Should publish invoke request to broadcast subject");
            // Must be a REAL subject, not a literal "*" segment — NATS wildcards
            // only apply on subscribe, so `skill.*.invoke` reaches nobody.
            assertEquals("between.household1.skill.broadcast.invoke",
                transport.published.get(0).subject());
        }

        @Test
        void broadcast_invoke_round_trips_between_two_nodes() {
            // node1 has no skills; node2 has the skill. Shared in-memory transport.
            var transport = new MockTransport();

            var node1 = new BetweenSkillBridge("node1", "household1", createRegistry());
            node1.setRemoteTimeout(Duration.ofSeconds(3));
            node1.setTransport(transport);

            var node2 = new BetweenSkillBridge("node2", "household1",
                createRegistry("remote.skill"));
            node2.setTransport(transport);

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);
            var result = node1.invoke("remote.skill", Map.of(), ctx, SkillLocality.ANY);

            assertTrue(result.success(),
                "node2 should have executed the broadcast request and returned a result");
            assertTrue(result.output().contains("Local result: remote.skill"));
        }

        @Test
        void skill_less_node_stays_silent_on_broadcast() {
            // node1 (requester, no skills) + node3 (also no skills) + node2 (has it).
            // node3 must NOT answer the broadcast with an error that races node2.
            var transport = new MockTransport();

            var node1 = new BetweenSkillBridge("node1", "household1", createRegistry());
            node1.setRemoteTimeout(Duration.ofSeconds(3));
            node1.setTransport(transport);
            var node3 = new BetweenSkillBridge("node3", "household1", createRegistry());
            node3.setTransport(transport);
            var node2 = new BetweenSkillBridge("node2", "household1",
                createRegistry("remote.skill"));
            node2.setTransport(transport);

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);
            var result = node1.invoke("remote.skill", Map.of(), ctx, SkillLocality.ANY);

            assertTrue(result.success(),
                "the answer must come from the node that has the skill, not a skill-less error");
        }
    }

    // ── Wire Protocol ───────────────────────────────────────────────────

    @Nested
    class WireProtocolTests {

        @Test
        void invoke_request_record() {
            var req = new BetweenSkillBridge.SkillInvokeRequest(
                "req-1", "node1", "test.skill", Map.of("key", "val"),
                "did:agent:1", "room1");

            assertEquals("req-1", req.requestId());
            assertEquals("node1", req.sourceNodeId());
            assertEquals("test.skill", req.skillId());
            assertEquals("did:agent:1", req.agentDid());
        }

        @Test
        void invoke_response_record() {
            var result = SkillResult.ok("Done", Map.of(), 10, SkillTier.NATIVE, "test.skill");
            var resp = new BetweenSkillBridge.SkillInvokeResponse("req-1", result);

            assertEquals("req-1", resp.requestId());
            assertTrue(resp.result().success());
        }
    }
}
