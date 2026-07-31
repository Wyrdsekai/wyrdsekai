package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * artifact create/get/list/attach/revoke
 * lifecycle. Owner-scoping pinned: agent-A cannot read or revoke agent-B's
 * artifacts.
 */
class ArtifactServiceTest {

    private ArtifactService svc;

    @BeforeEach
    void setUp() {
        ArtifactService.resetForTesting();
        svc = ArtifactService.get(null); // in-memory only
    }

    @AfterEach
    void tearDown() {
        ArtifactService.resetForTesting();
    }

    @Test
    void create_returns_id_and_size_metadata() {
        var res = svc.create("agent-a", "chart", "application/json",
            Map.of("k", "v"), Map.of("title", "Test"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("id").toString()).startsWith("art_");
        assertThat(res.get("kind")).isEqualTo("chart");
        assertThat(res.get("mime")).isEqualTo("application/json");
        assertThat((Integer) res.get("sizeBytes")).isPositive();
    }

    @Test
    void create_with_null_kind_returns_error() {
        var res = svc.create("agent-a", null, null, "x", null);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error").toString()).contains("kind");
    }

    @Test
    void get_returns_payload_for_owner() {
        var created = svc.create("agent-a", "report", "text/plain", "hello", null);
        var id = created.get("id").toString();
        var got = svc.get("agent-a", id);
        assertThat(got.get("ok")).isEqualTo(true);
        assertThat(got.get("payload")).isEqualTo("hello");
    }

    @Test
    void get_blocks_non_owner_unless_attached() {
        var created = svc.create("agent-a", "report", "text/plain", "secret", null);
        var id = created.get("id").toString();
        var denied = svc.get("agent-b", id);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("not_owner");
    }

    @Test
    void get_unknown_id_returns_not_found() {
        var res = svc.get("agent-a", "no-such-id");
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error")).isEqualTo("not_found");
    }

    @Test
    void list_returns_only_owner_artifacts() {
        svc.create("agent-a", "chart", "application/json", "x", null);
        svc.create("agent-b", "chart", "application/json", "y", null);
        svc.create("agent-a", "report", "text/plain", "z", null);
        var aList = svc.list("agent-a", null);
        assertThat(aList).hasSize(2);
        var bList = svc.list("agent-b", null);
        assertThat(bList).hasSize(1);
    }

    @Test
    void list_filters_by_kind() {
        svc.create("agent-a", "chart", "application/json", "x", null);
        svc.create("agent-a", "report", "text/plain", "z", null);
        var charts = svc.list("agent-a", Map.of("kind", "chart"));
        assertThat(charts).hasSize(1);
        assertThat(charts.getFirst().get("kind")).isEqualTo("chart");
    }

    @Test
    void list_respects_limit() {
        for (int i = 0; i < 5; i++) {
            svc.create("agent-a", "chart", "application/json", i, null);
        }
        var capped = svc.list("agent-a", Map.of("limit", 2));
        assertThat(capped).hasSize(2);
    }

    @Test
    void attach_marks_artifact_visible_to_room_occupants() {
        var created = svc.create("agent-a", "report", "text/plain", "hi", null);
        var id = created.get("id").toString();
        var att = svc.attach("agent-a", "room-foyer", id);
        assertThat(att.get("ok")).isEqualTo(true);
        assertThat(att.get("attachedRoomId")).isEqualTo("room-foyer");
        // After attach, non-owner can read
        var bRead = svc.get("agent-b", id);
        assertThat(bRead.get("ok")).isEqualTo(true);
    }

    @Test
    void attach_requires_owner() {
        var created = svc.create("agent-a", "report", "text/plain", "hi", null);
        var id = created.get("id").toString();
        var denied = svc.attach("agent-b", "room-foyer", id);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("not_owner");
    }

    @Test
    void revoke_marks_artifact_unreadable() {
        var created = svc.create("agent-a", "report", "text/plain", "hi", null);
        var id = created.get("id").toString();
        var revoked = svc.revoke("agent-a", id);
        assertThat(revoked.get("ok")).isEqualTo(true);
        var read = svc.get("agent-a", id);
        assertThat(read.get("ok")).isEqualTo(false);
    }

    @Test
    void revoke_blocks_non_owner() {
        var created = svc.create("agent-a", "report", "text/plain", "hi", null);
        var id = created.get("id").toString();
        var denied = svc.revoke("agent-b", id);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("not_owner");
        // Original is still live
        assertThat(svc.size()).isEqualTo(1);
    }

    @Test
    void persistence_round_trip(@TempDir Path tempDir) {
        var dbFile = tempDir.resolve("artifacts.db");
        var jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        ArtifactService.resetForTesting();
        var first = ArtifactService.get(jdbcUrl);
        var created = first.create("agent-a", "chart", "application/json",
            Map.of("v", 1), Map.of("title", "kept"));
        var id = created.get("id").toString();

        ArtifactService.resetForTesting();
        var second = ArtifactService.get(jdbcUrl);
        var got = second.get("agent-a", id);
        assertThat(got.get("ok")).isEqualTo(true);
        assertThat(got.get("title")).isEqualTo("kept");
    }
}
