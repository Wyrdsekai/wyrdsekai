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
 * scroll create/read/list/revise/lock/share
 * lifecycle. Owner-scoping + lock semantics + embed-resolution pinned.
 */
class ScrollServiceTest {

    private ScrollService svc;
    private ArtifactService artifacts;

    @BeforeEach
    void setUp() {
        ArtifactService.resetForTesting();
        ScrollService.resetForTesting();
        artifacts = ArtifactService.get(null);
        svc = ScrollService.get(null, artifacts);
    }

    @AfterEach
    void tearDown() {
        ScrollService.resetForTesting();
        ArtifactService.resetForTesting();
    }

    @Test
    void create_with_text_section_returns_id_and_version() {
        var sections = List.<Map<String, Object>>of(
            Map.of("type", "heading", "content", "Daily Report"),
            Map.of("type", "text", "content", "Everything is fine."));
        var res = svc.create("agent-a", "report", sections);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("id").toString()).startsWith("scroll_");
        assertThat(res.get("version")).isEqualTo(1);
    }

    @Test
    void create_rejects_section_without_type() {
        var sections = List.<Map<String, Object>>of(Map.of("content", "no type"));
        var res = svc.create("agent-a", "bad", sections);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error").toString()).contains("type");
    }

    @Test
    void read_returns_full_scroll_for_owner() {
        var sections = List.<Map<String, Object>>of(
            Map.of("type", "text", "content", "hello"));
        var c = svc.create("agent-a", "Hi", sections);
        var id = c.get("id").toString();
        var got = svc.read("agent-a", id);
        assertThat(got.get("ok")).isEqualTo(true);
        assertThat(got.get("title")).isEqualTo("Hi");
        var blocks = (List<?>) got.get("sections");
        assertThat(blocks).hasSize(1);
    }

    @Test
    void read_blocks_non_owner_when_not_shared() {
        var c = svc.create("agent-a", "private",
            List.of(Map.of("type", "text", "content", "secret")));
        var id = c.get("id").toString();
        var denied = svc.read("agent-b", id);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("not_authorized");
    }

    @Test
    void list_returns_owner_scrolls_newest_first() {
        var s1 = svc.create("agent-a", "first",
            List.of(Map.of("type", "text", "content", "1"))).get("id").toString();
        try { Thread.sleep(2); } catch (InterruptedException _) {}
        var s2 = svc.create("agent-a", "second",
            List.of(Map.of("type", "text", "content", "2"))).get("id").toString();
        var list = svc.list("agent-a", null);
        assertThat(list).hasSize(2);
        assertThat(list.getFirst().get("id")).isEqualTo(s2);
    }

    @Test
    void revise_bumps_version() {
        var c = svc.create("agent-a", "rep",
            List.of(Map.of("type", "text", "content", "v1")));
        var id = c.get("id").toString();
        var newSections = List.<Map<String, Object>>of(
            Map.of("type", "text", "content", "v2"));
        var rev = svc.revise("agent-a", id, newSections);
        assertThat(rev.get("ok")).isEqualTo(true);
        assertThat(rev.get("version")).isEqualTo(2);
    }

    @Test
    void revise_blocks_non_owner() {
        var c = svc.create("agent-a", "rep",
            List.of(Map.of("type", "text", "content", "hi")));
        var id = c.get("id").toString();
        var denied = svc.revise("agent-b", id,
            List.of(Map.of("type", "text", "content", "evil")));
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("not_owner");
    }

    @Test
    void revise_rejected_when_locked() {
        var c = svc.create("agent-a", "rep",
            List.of(Map.of("type", "text", "content", "v1")));
        var id = c.get("id").toString();
        svc.lock("agent-a", id);
        var rev = svc.revise("agent-a", id,
            List.of(Map.of("type", "text", "content", "v2")));
        assertThat(rev.get("ok")).isEqualTo(false);
        assertThat(rev.get("error")).isEqualTo("locked");
    }

    @Test
    void lock_is_idempotent_for_owner() {
        var c = svc.create("agent-a", "rep",
            List.of(Map.of("type", "text", "content", "x")));
        var id = c.get("id").toString();
        var first = svc.lock("agent-a", id);
        assertThat(first.get("ok")).isEqualTo(true);
        var second = svc.lock("agent-a", id);
        assertThat(second.get("ok")).isEqualTo(true);
        assertThat(second.get("alreadyLocked")).isEqualTo(true);
    }

    @Test
    void share_grants_read_to_target_only() {
        var c = svc.create("agent-a", "shared",
            List.of(Map.of("type", "text", "content", "x")));
        var id = c.get("id").toString();
        svc.share("agent-a", id, "agent-b");
        var bRead = svc.read("agent-b", id);
        assertThat(bRead.get("ok")).isEqualTo(true);
        var cRead = svc.read("agent-c", id);
        assertThat(cRead.get("ok")).isEqualTo(false);
    }

    @Test
    void share_requires_owner() {
        var c = svc.create("agent-a", "scroll",
            List.of(Map.of("type", "text", "content", "x")));
        var id = c.get("id").toString();
        var denied = svc.share("agent-b", id, "agent-c");
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("not_owner");
    }

    @Test
    void embed_section_resolves_artifact_payload_inline() {
        var ac = artifacts.create("agent-a", "chart",
            "application/vnd.vega.v5+json", Map.of("mark", "bar"),
            Map.of("title", "Bars"));
        var artId = ac.get("id").toString();
        var c = svc.create("agent-a", "report", List.<Map<String, Object>>of(
            Map.of("type", "text", "content", "see chart below"),
            Map.of("type", "embed", "artifactId", artId)));
        var read = svc.read("agent-a", c.get("id").toString());
        @SuppressWarnings("unchecked")
        var blocks = (List<Map<String, Object>>) read.get("sections");
        var embed = blocks.get(1);
        assertThat(embed.get("artifactMime")).isEqualTo("application/vnd.vega.v5+json");
        assertThat(embed.get("artifactKind")).isEqualTo("chart");
        // Payload should be parsed back into a Map (since mime contains "vega").
        assertThat(embed.get("artifactPayload")).isInstanceOf(Map.class);
    }

    @Test
    void persistence_round_trip(@TempDir Path tempDir) {
        var dbFile = tempDir.resolve("scrolls.db");
        var jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        ScrollService.resetForTesting();
        var first = ScrollService.get(jdbcUrl, artifacts);
        var c = first.create("agent-a", "kept",
            List.of(Map.of("type", "text", "content", "v1")));
        var id = c.get("id").toString();
        first.share("agent-a", id, "agent-b");

        ScrollService.resetForTesting();
        var second = ScrollService.get(jdbcUrl, artifacts);
        var read = second.read("agent-a", id);
        assertThat(read.get("ok")).isEqualTo(true);
        assertThat(read.get("title")).isEqualTo("kept");
        var bRead = second.read("agent-b", id);
        assertThat(bRead.get("ok")).isEqualTo(true);
    }
}
