package org.wyrdsekai.daemon.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests wire-compatibility between daemon's DaemonCapability and
 * server's InferenceGossip.InferenceCapability.
 */
class DaemonCapabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serialization_roundTrip() throws Exception {
        var cap = new DaemonCapability(
            "daemon-phone-kitchen",
            List.of(new DaemonModel("qwen3-4b-q4", "medium",
                "http://198.51.100.42:8080", 1, 0)),
            0, 0, 1, 0, 2300.0, 1709891234L
        );

        var json = MAPPER.writeValueAsString(cap);
        var parsed = MAPPER.readValue(json, DaemonCapability.class);

        assertThat(parsed).isEqualTo(cap);
    }

    @Test
    void json_matchesServerWireFormat() throws Exception {
        // Simulate what the server's InferenceGossip produces
        var serverJson = """
            {
              "nodeId": "server-desktop-a",
              "models": [{
                "modelId": "qwen:7b",
                "tier": "large",
                "endpoint": "http://localhost:8080",
                "maxConcurrent": 4,
                "activeLeases": 1
              }],
              "totalGpuCount": 1,
              "totalFreeVramMB": 20000,
              "availableSlots": 3,
              "queueDepth": 2,
              "avgLatencyMs": 150.0,
              "timestamp": 1709891234
            }
            """;

        // Daemon must be able to parse server announcements
        var cap = MAPPER.readValue(serverJson, DaemonCapability.class);

        assertThat(cap.nodeId()).isEqualTo("server-desktop-a");
        assertThat(cap.models()).hasSize(1);
        assertThat(cap.models().getFirst().modelId()).isEqualTo("qwen:7b");
        assertThat(cap.models().getFirst().tier()).isEqualTo("large");
        assertThat(cap.totalGpuCount()).isEqualTo(1);
        assertThat(cap.totalFreeVramMB()).isEqualTo(20000);
        assertThat(cap.availableSlots()).isEqualTo(3);
        assertThat(cap.queueDepth()).isEqualTo(2);
        assertThat(cap.avgLatencyMs()).isEqualTo(150.0);
        assertThat(cap.timestamp()).isEqualTo(1709891234L);
    }

    @Test
    void daemonJson_parsableByServerFormat() throws Exception {
        // What the daemon produces must be parseable by server's Jackson
        var cap = DaemonCapability.now(
            "daemon-phone-kitchen",
            List.of(new DaemonModel("qwen3-4b-q4", "medium",
                "http://198.51.100.42:8080", 1, 0)),
            0, 0, 1, 0, 2300.0
        );

        var json = MAPPER.writeValueAsString(cap);

        // Verify JSON field names match server expectations
        var tree = MAPPER.readTree(json);
        assertThat(tree.has("nodeId")).isTrue();
        assertThat(tree.has("models")).isTrue();
        assertThat(tree.has("totalGpuCount")).isTrue();
        assertThat(tree.has("totalFreeVramMB")).isTrue();
        assertThat(tree.has("availableSlots")).isTrue();
        assertThat(tree.has("queueDepth")).isTrue();
        assertThat(tree.has("avgLatencyMs")).isTrue();
        assertThat(tree.has("timestamp")).isTrue();

        // Model fields
        var model = tree.get("models").get(0);
        assertThat(model.has("modelId")).isTrue();
        assertThat(model.has("tier")).isTrue();
        assertThat(model.has("endpoint")).isTrue();
        assertThat(model.has("maxConcurrent")).isTrue();
        assertThat(model.has("activeLeases")).isTrue();
    }

    @Test
    void now_setsTimestamp() {
        var before = Instant.now().getEpochSecond();
        var cap = DaemonCapability.now("test", List.of(), 0, 0, 1, 0, 0);
        var after = Instant.now().getEpochSecond();

        assertThat(cap.timestamp()).isBetween(before, after);
    }
}
