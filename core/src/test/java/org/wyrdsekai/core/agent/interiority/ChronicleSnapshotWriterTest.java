package org.wyrdsekai.core.agent.interiority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavior integration test for ChronicleSnapshotWriter — the extracted
 * atomic-write helper that backs CompanionActor.writeDailyChronicleSnapshot().
 *
 * <p>Tests the actual file write, JSON shape, atomic-move semantics, and
 * overwrite-of-prior-snapshot. This is the higher-fidelity test that
 * complements the source-text wiring assertions.
 */
class ChronicleSnapshotWriterTest {

    private static ChronicleService.Chronicle makeChronicle(String did, String name) {
        return new ChronicleService.Chronicle(
            did, name, ChronicleService.Scale.DAY,
            Instant.parse("2026-05-17T00:00:00Z"),
            Instant.parse("2026-05-18T00:00:00Z"),
            "(testimony text)",
            "(synthesis text)",
            new ChronicleService.Stats(5, 3, 2, 0, 600L, 50L));
    }

    @Test
    void write_creates_chronicles_subdir(@TempDir Path tmp) throws Exception {
        var chronicle = makeChronicle("did:wyrd:operator", "Wyrd");
        ChronicleSnapshotWriter.write(tmp, "did_wyrd_masumi", chronicle,
            Instant.parse("2026-05-17T12:00:00Z"));
        assertThat(Files.isDirectory(tmp.resolve("chronicles"))).isTrue();
    }

    @Test
    void write_creates_did_slug_json(@TempDir Path tmp) throws Exception {
        var chronicle = makeChronicle("did:wyrd:operator", "Wyrd");
        var result = ChronicleSnapshotWriter.write(tmp, "did_wyrd_masumi", chronicle,
            Instant.parse("2026-05-17T12:00:00Z"));
        assertThat(result).isEqualTo(tmp.resolve("chronicles").resolve("did_wyrd_masumi.json"));
        assertThat(Files.exists(result)).isTrue();
    }

    @Test
    void write_emits_canonical_json_shape(@TempDir Path tmp) throws Exception {
        var chronicle = makeChronicle("did:wyrd:operator", "Wyrd");
        var writtenAt = Instant.parse("2026-05-17T12:00:00Z");
        var file = ChronicleSnapshotWriter.write(tmp, "did_wyrd_masumi",
            chronicle, writtenAt);
        var mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(file.toFile());
        assertThat(node.get("agentDid").asText()).isEqualTo("did:wyrd:operator");
        assertThat(node.get("agentName").asText()).isEqualTo("Wyrd");
        assertThat(node.get("scale").asText()).isEqualTo("DAY");
        assertThat(node.get("windowStart").asText()).isEqualTo("2026-05-17T00:00:00Z");
        assertThat(node.get("windowEnd").asText()).isEqualTo("2026-05-18T00:00:00Z");
        assertThat(node.get("testimony").asText()).contains("testimony");
        assertThat(node.get("synthesis").asText()).contains("synthesis");
        assertThat(node.get("writtenAt").asText()).isEqualTo("2026-05-17T12:00:00Z");
    }

    @Test
    void write_overwrites_prior_snapshot(@TempDir Path tmp) throws Exception {
        var c1 = makeChronicle("did:wyrd:operator", "Wyrd-v1");
        var c2 = makeChronicle("did:wyrd:operator", "Wyrd-v2");
        ChronicleSnapshotWriter.write(tmp, "did_wyrd_masumi", c1,
            Instant.parse("2026-05-17T12:00:00Z"));
        ChronicleSnapshotWriter.write(tmp, "did_wyrd_masumi", c2,
            Instant.parse("2026-05-17T13:00:00Z"));
        var file = tmp.resolve("chronicles").resolve("did_wyrd_masumi.json");
        var mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(file.toFile());
        // Latest snapshot wins.
        assertThat(node.get("agentName").asText()).isEqualTo("Wyrd-v2");
        assertThat(node.get("writtenAt").asText()).isEqualTo("2026-05-17T13:00:00Z");
    }

    @Test
    void write_cleans_up_tmp_file_on_success(@TempDir Path tmp) throws Exception {
        var chronicle = makeChronicle("did:wyrd:operator", "Wyrd");
        ChronicleSnapshotWriter.write(tmp, "did_wyrd_masumi", chronicle,
            Instant.parse("2026-05-17T12:00:00Z"));
        // Atomic move consumes the .tmp file.
        assertThat(Files.exists(tmp.resolve("chronicles").resolve("did_wyrd_masumi.json.tmp")))
            .as("the .tmp file must not remain after atomic move")
            .isFalse();
    }

    @Test
    void write_handles_multiple_dids_independently(@TempDir Path tmp) throws Exception {
        var c1 = makeChronicle("did:wyrd:alice", "Alice");
        var c2 = makeChronicle("did:wyrd:bob", "Bob");
        ChronicleSnapshotWriter.write(tmp, "did_wyrd_alice", c1,
            Instant.parse("2026-05-17T12:00:00Z"));
        ChronicleSnapshotWriter.write(tmp, "did_wyrd_bob", c2,
            Instant.parse("2026-05-17T12:00:00Z"));
        assertThat(Files.exists(tmp.resolve("chronicles").resolve("did_wyrd_alice.json"))).isTrue();
        assertThat(Files.exists(tmp.resolve("chronicles").resolve("did_wyrd_bob.json"))).isTrue();
    }

    @Test
    void write_uses_now_when_writtenAt_null(@TempDir Path tmp) throws Exception {
        var before = Instant.now();
        var chronicle = makeChronicle("did:wyrd:operator", "Wyrd");
        var file = ChronicleSnapshotWriter.write(tmp, "did_wyrd_masumi", chronicle, null);
        var after = Instant.now();
        var mapper = new ObjectMapper();
        var writtenAtStr = mapper.readTree(file.toFile()).get("writtenAt").asText();
        var writtenAt = Instant.parse(writtenAtStr);
        assertThat(writtenAt).isBetween(before, after);
    }

    @Test
    void write_rejects_null_dataDir() {
        var c = makeChronicle("did:wyrd:operator", "Wyrd");
        assertThatThrownBy(() -> ChronicleSnapshotWriter.write(null, "x", c, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void write_rejects_blank_didSlug(@TempDir Path tmp) {
        var c = makeChronicle("did:wyrd:operator", "Wyrd");
        assertThatThrownBy(() -> ChronicleSnapshotWriter.write(tmp, "  ", c, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void write_rejects_null_chronicle(@TempDir Path tmp) {
        assertThatThrownBy(() -> ChronicleSnapshotWriter.write(tmp, "x", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
