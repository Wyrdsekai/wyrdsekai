package org.wyrdsekai.between;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many layers put records with Instants into payloads; Jackson writes them as decimals, which a parsed
 * tree prints back as doubles. Verification uses the payload text as it travelled, so a signature made
 * by the sender still verifies at the receiver (it never did for such payloads, which went unnoticed
 * while nothing checked them).
 */
class BetweenEnvelopeRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    record Snapshot(String nodeId, double cpuLoad, double tiny, double huge, long ramMb, Instant timestamp,
                    List<String> tags, Map<String, Object> extra) {}

    @TempDir Path tmp;

    @Test
    void aPayloadWithTimestampsAndDoublesVerifiesAfterTheWire() throws Exception {
        var node = NodeIdentity.loadOrGenerate(tmp.resolve("n.json"));
        var snap = new Snapshot("n1", 0.35, 3.0E-4, 1.23456789E7, 16384, Instant.parse("2026-09-28T12:00:00.123456789Z"),
            List.of("gpu", "é-ü"), Map.of("zero", Instant.parse("2026-09-28T12:00:00Z"), "n", 100.0));
        var env = BetweenEnvelope.create(node.nodeId(), null, MAPPER.valueToTree(snap), node);

        var received = BetweenEnvelope.fromBytes(env.toBytes());
        assertThat(received.verify(node.publicKeyBytes())).isTrue();
        assertThat(received.ts()).isEqualTo(env.ts());
        assertThat(new String(env.toBytes(), StandardCharsets.UTF_8)).doesNotContain("rawPayload");
    }

    @Test
    void aChangedPayloadDoesNotVerify() throws Exception {
        var node = NodeIdentity.loadOrGenerate(tmp.resolve("n.json"));
        var p = MAPPER.createObjectNode();
        p.put("role", "member");
        var bytes = new String(BetweenEnvelope.create(node.nodeId(), null, p, node).toBytes(), StandardCharsets.UTF_8)
            .replace("\"member\"", "\"steward\"").getBytes(StandardCharsets.UTF_8);
        assertThat(BetweenEnvelope.fromBytes(bytes).verify(node.publicKeyBytes())).isFalse();
    }
}
