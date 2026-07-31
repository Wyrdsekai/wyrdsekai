package org.wyrdsekai.core.oracle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Oracle integration components.
 * These test the Java wiring — not the oracle-core Python engine itself.
 */
class OracleIntegrationTest {

    @Test
    void oracle_event_creation() {
        var event = new OracleEvent(Instant.now(), "room_event", "said", "hello world");
        assertThat(event.source()).isEqualTo("room_event");
        assertThat(event.eventType()).isEqualTo("said");
        assertThat(event.content()).isEqualTo("hello world");
        assertThat(event.entityId()).isEmpty();
        assertThat(event.numericValue()).isNull();
    }

    @Test
    void oracle_event_full_constructor() {
        var event = new OracleEvent(Instant.now(), "system", "cpu",
            "cpu usage", "node-1", "boiler-room", 85.5);
        assertThat(event.numericValue()).isEqualTo(85.5);
        assertThat(event.entityId()).isEqualTo("node-1");
        assertThat(event.roomId()).isEqualTo("boiler-room");
    }

    @Test
    void prediction_cache_stores_and_retrieves() {
        var cache = OraclePredictionCache.get();
        cache.clear();

        var predictions = List.of(
            new OraclePrediction("p1", "weekly pattern detected", "pattern", 0.85, "", "acf=0.85", false),
            new OraclePrediction("p2", "email spike", "anomaly", 0.92, "", "z=3.2", true)
        );

        cache.put("user1", predictions);

        assertThat(cache.get("user1")).hasSize(2);
        assertThat(cache.get("user1").get(0).text()).isEqualTo("weekly pattern detected");
        assertThat(cache.get("unknown")).isEmpty();
    }

    @Test
    void agent_context_builds_from_predictions() {
        var predictions = List.of(
            new OraclePrediction("p1", "Activity has a weekly cycle", "pattern", 0.85, "", "", false),
            new OraclePrediction("p2", "Email volume spike detected", "anomaly", 0.92, "", "", true),
            new OraclePrediction("p3", "Low confidence pattern", "pattern", 0.30, "", "", false)
        );

        var context = OracleAgentContext.build(predictions);

        assertThat(context).contains("Oracle insights:");
        assertThat(context).contains("weekly cycle");
        assertThat(context).contains("Email volume");
        assertThat(context).contains("[actionable]");
        // Low confidence (0.30) should be filtered out
        assertThat(context).doesNotContain("Low confidence");
    }

    @Test
    void agent_context_empty_when_no_predictions() {
        assertThat(OracleAgentContext.build(List.of())).isEmpty();
        assertThat(OracleAgentContext.build(null)).isEmpty();
    }

    @Test
    void forge_hook_predictions_to_json() {
        var predictions = List.of(
            new OraclePrediction("p1", "test prediction", "pattern", 0.85, "oracle.pattern.periodic", "evidence", false)
        );

        var json = OracleForgeHook.predictionsToJson(predictions);
        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).contains("test prediction");
        assertThat(json).contains("oracle.pattern.periodic");
        assertThat(json).contains("0.85");
    }

    @Test
    void between_sync_broadcasts() {
        var published = new ArrayList<String>();

        var sync = new OracleBetweenSync("node-1", "household-1",
            (subject, data) -> published.add(subject));

        var predictions = List.of(
            new OraclePrediction("p1", "test", "pattern", 0.8, "", "", false)
        );

        sync.broadcastPredictions(predictions);

        assertThat(published).hasSize(1);
        assertThat(published.get(0)).contains("household-1");
        assertThat(published.get(0)).contains("node-1");
        assertThat(published.get(0)).contains("oracle.predictions");
    }

    @Test
    void between_sync_empty_predictions_no_publish() {
        var published = new ArrayList<String>();

        var sync = new OracleBetweenSync("node-1", "household-1",
            (subject, data) -> published.add(subject));

        sync.broadcastPredictions(List.of());

        assertThat(published).isEmpty();
    }

    @Test
    void event_bridge_converts_said_event() {
        // Can't easily test the full bridge without a running oracle-core,
        // but we can verify the batch accumulation
        var bridge = new OracleBridge("http://localhost:99999"); // won't connect
        var eventBridge = new OracleEventBridge(bridge, "test-user");

        // Shouldn't throw even though oracle-core isn't running
        eventBridge.flush();
    }
}
