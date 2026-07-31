package org.wyrdsekai.daemon.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for gossip client logic.
 * Cannot test NATS transport without a live server; tests focus on
 * serialization compatibility and peer tracking.
 */
class DaemonGossipClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void subject_matchesServer() {
        assertThat(DaemonGossipClient.SUBJECT).isEqualTo("wyrd.inference.capabilities");
    }

    @Test
    void expirySeconds_matchesServer() {
        // Must stay in sync with InferenceGossip.EXPIRY_SECONDS
        assertThat(DaemonGossipClient.EXPIRY_SECONDS).isEqualTo(90);
    }

    @Test
    void announceInterval_isThirdOfExpiry() {
        // 30s announce, 90s expiry = 3x safety margin
        assertThat(DaemonGossipClient.ANNOUNCE_INTERVAL_SECONDS * 3)
            .isEqualTo(DaemonGossipClient.EXPIRY_SECONDS);
    }

    @Test
    void capabilityJson_compatibleWithServerInferenceGossip() throws Exception {
        // Build a daemon capability
        var cap = DaemonCapability.now(
            "daemon-phone-1",
            List.of(new DaemonModel("qwen3-4b-q4", "medium",
                "http://198.51.100.42:8080", 1, 0)),
            0, 0, 1, 0, 2500.0
        );

        var json = MAPPER.writeValueAsString(cap);

        // Parse as a generic tree and verify all server-expected fields exist
        var tree = MAPPER.readTree(json);
        assertThat(tree.get("nodeId").asText()).isEqualTo("daemon-phone-1");
        assertThat(tree.get("models").isArray()).isTrue();
        assertThat(tree.get("models").get(0).get("modelId").asText()).isEqualTo("qwen3-4b-q4");
        assertThat(tree.get("totalGpuCount").asInt()).isZero();
        assertThat(tree.get("totalFreeVramMB").asLong()).isZero();
        assertThat(tree.get("availableSlots").asInt()).isEqualTo(1);
        assertThat(tree.get("queueDepth").asInt()).isZero();
        assertThat(tree.get("avgLatencyMs").asDouble()).isEqualTo(2500.0);
        assertThat(tree.get("timestamp").asLong()).isPositive();
    }

    @Test
    void serverCapability_parsableByDaemon() throws Exception {
        // JSON that the server's InferenceGossip would produce
        // (uses same field names since both are Jackson-serialized records)
        var serverJson = """
            {
              "nodeId": "server-main",
              "models": [
                {"modelId": "qwen:7b", "tier": "large", "endpoint": "http://192.0.2.1:11434", "maxConcurrent": 4, "activeLeases": 2}
              ],
              "totalGpuCount": 1,
              "totalFreeVramMB": 16000,
              "availableSlots": 2,
              "queueDepth": 1,
              "avgLatencyMs": 450.5,
              "timestamp": 1709891234
            }
            """;

        var cap = MAPPER.readValue(serverJson, DaemonCapability.class);
        assertThat(cap.nodeId()).isEqualTo("server-main");
        assertThat(cap.models()).hasSize(1);
        assertThat(cap.totalGpuCount()).isEqualTo(1);
    }
}
