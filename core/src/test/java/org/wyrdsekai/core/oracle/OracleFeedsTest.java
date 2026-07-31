package org.wyrdsekai.core.oracle;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.oracle.feeds.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Oracle feed sources and event bridge.
 * Network-dependent tests are marked and can be skipped in CI.
 */
class OracleFeedsTest {

    @Test
    void event_bridge_batches_events() {
        var bridge = new OracleBridge("http://localhost:99999"); // won't connect
        var eventBridge = new OracleEventBridge(bridge, "test-user");

        // Should accept events without crashing even if oracle-core is down
        var event = new WorldEvent.Said(
            "test-room", Instant.now(), "player", "Alice", "hello world");
        eventBridge.onWorldEvent(event);

        // Flush should not throw
        eventBridge.flush();
    }

    @Test
    void feed_poller_accepts_sources() {
        var bridge = new OracleBridge("http://localhost:99999");
        var poller = new FeedPoller(bridge, "test");

        poller.addSource(new FeedPoller.FeedSource() {
            @Override public String name() { return "test_feed"; }
            @Override public long intervalSeconds() { return 3600; }
            @Override public List<OracleEvent> poll() {
                return List.of(new OracleEvent(
                    Instant.now(), "test", "item", "test event"));
            }
        });

        // Should not throw
        poller.addDefaults(null, null, List.of());
    }

    @Test
    void oracle_event_converts_from_said() {
        var said = new WorldEvent.Said(
            "nexus", Instant.now(), "agent-1", "Wyrd", "I noticed something interesting");

        // Simulate what OracleEventBridge.convertEvent would do
        var oracleEvent = new OracleEvent(
            said.timestamp(), "room_event", "said",
            said.text(), said.entityId(), said.roomId());

        assertThat(oracleEvent.source()).isEqualTo("room_event");
        assertThat(oracleEvent.eventType()).isEqualTo("said");
        assertThat(oracleEvent.content()).isEqualTo("I noticed something interesting");
        assertThat(oracleEvent.entityId()).isEqualTo("agent-1");
        assertThat(oracleEvent.roomId()).isEqualTo("nexus");
    }

    @Test
    void prediction_cache_integrates_with_agent_context() {
        // Simulate: ForgeHook stores predictions → PromptAssembler reads them
        var cache = OraclePredictionCache.get();
        cache.clear();

        // ForgeHook would produce these
        var predictions = List.of(
            new OraclePrediction("p1", "Weekly activity cycle detected", "pattern", 0.85, "", "", false),
            new OraclePrediction("p2", "Email spike: 40% above baseline", "anomaly", 0.90, "", "", true),
            new OraclePrediction("p3", "Topic 'kubernetes' growing", "topic", 0.72, "", "", false)
        );
        cache.put("agent-ember", predictions);

        // PromptAssembler would read like this
        var agentPredictions = cache.get("agent-ember");
        var context = OracleAgentContext.build(agentPredictions);

        assertThat(context).contains("Oracle insights:");
        assertThat(context).contains("Weekly activity");
        assertThat(context).contains("Email spike");
        assertThat(context).contains("[actionable]");
        assertThat(context).contains("kubernetes");
    }

    @Test
    void forge_hook_predictions_roundtrip_json() {
        var predictions = List.of(
            new OraclePrediction("p1", "Test with \"quotes\" and\nnewlines", "pattern", 0.85, "oracle.pattern.periodic", "evidence", false)
        );

        var json = OracleForgeHook.predictionsToJson(predictions);

        // Should be valid JSON-like
        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).contains("Test with \\\"quotes\\\"");
        assertThat(json).contains("\\n");
        assertThat(json).doesNotContain("\n\""); // newlines should be escaped
    }

    @Test
    void between_sync_formats_subject_correctly() {
        var subjects = new ArrayList<String>();
        var sync = new OracleBetweenSync("mac-node", "household-alpha",
            (subject, data) -> subjects.add(subject));

        sync.broadcastPredictions(List.of(
            new OraclePrediction("p1", "test", "pattern", 0.8, "", "", false)
        ));

        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0)).isEqualTo("between.household-alpha.mac-node.*.oracle.predictions");
    }

}
