package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2b — unit tests for {@link OpenCodeEventAdapter}.
 *
 * <p>Mirrors the shape of the existing CodeZaiku adapter tests: feed a
 * synthetic {@link AgentEvent.ZoneBroadcast} carrying a structured
 * {@code task_completed} payload and assert the resulting
 * {@link CodingArtifact} has the right identity, files, and sibling
 * build artifact (when present).</p>
 */
class OpenCodeEventAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void namespace_is_opencode() {
        var adapter = new OpenCodeEventAdapter();
        assertThat(adapter.namespace()).isEqualTo("opencode");
        assertThat(adapter.namespace()).isEqualTo(OpenCodeBackend.NAME);
    }

    // ─── Event translation ────────────────────────────────────────

    @Test void translates_task_completed_into_source_artifact() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-abc-123");
        data.put("workspace", "/tmp/repo");
        data.put("model", "wyrdsekai-3.5-9b-vitality-v6");
        data.put("provider", "wyrd-local");
        data.put("status", "complete");
        var files = data.putArray("files");
        files.add("src/foo.java");
        files.add("src/bar.java");

        var event = broadcast(data);
        var artifact = new OpenCodeEventAdapter().translateEvent(event);

        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.backend()).isEqualTo("opencode");
        assertThat(src.taskId()).isEqualTo("task-abc-123");
        assertThat(src.workspacePath()).isEqualTo("/tmp/repo");
        assertThat(src.files()).containsExactly("src/foo.java", "src/bar.java");
        assertThat(src.backendMetadata()).containsEntry("source", "opencode");
        assertThat(src.backendMetadata()).containsEntry("model", "wyrdsekai-3.5-9b-vitality-v6");
    }

    @Test void translates_with_sibling_build_when_build_status_present() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-456");
        data.put("workspace", "/tmp/r2");
        data.put("buildStatus", "success");
        data.put("testsPassed", 17);
        data.put("testsFailed", 0);

        var artifact = new OpenCodeEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);

        var src = (SourceArtifact) artifact;
        var sibling = src.backendMetadata().get("__sibling_build");
        assertThat(sibling).isInstanceOf(BuildArtifact.class);
        var build = (BuildArtifact) sibling;
        assertThat(build.backend()).isEqualTo("opencode");
        assertThat(build.status()).isEqualTo("success");
        assertThat(build.testsPassed()).isEqualTo(17);
        assertThat(build.testsFailed()).isEqualTo(0);
        assertThat(build.taskId()).isEqualTo("task-456");
    }

    @Test void returns_null_when_namespace_does_not_match() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        var event = new AgentEvent.ZoneBroadcast("codezaiku",
            "workshop",
            new S2CMessage.ZoneResponse(0L, "req", "codezaiku",
                "ok", data, List.of()),
            Instant.now());

        assertThat(new OpenCodeEventAdapter().translateEvent(event)).isNull();
    }

    @Test void returns_null_when_event_is_not_task_completed() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_progress");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");

        assertThat(new OpenCodeEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_task_id() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("workspace", "/tmp");
        // no taskId

        assertThat(new OpenCodeEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_workspace() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "x");
        // no workspace

        assertThat(new OpenCodeEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_null_event() {
        assertThat(new OpenCodeEventAdapter().translateEvent(null)).isNull();
    }

    @Test void returns_null_when_message_is_not_zone_response() {
        var event = new AgentEvent.ZoneBroadcast("opencode",
            "workshop",
            new S2CMessage.Notification(0L, "info", "title", "body"),
            Instant.now());
        assertThat(new OpenCodeEventAdapter().translateEvent(event)).isNull();
    }

    // ─── parsePlayerCommand ──────────────────────────────────────

    @Test void parses_create_command_into_task_spec() {
        var spec = new OpenCodeEventAdapter().parsePlayerCommand("create", "fix bug X");

        assertThat(spec).isNotNull();
        assertThat(spec.taskType()).isEqualTo("code"); // create normalizes to code
        assertThat(spec.description()).isEqualTo("fix bug X");
        assertThat(spec.taskId()).isNotNull();
    }

    @Test void parses_review_verb_keeps_task_type() {
        var spec = new OpenCodeEventAdapter().parsePlayerCommand("review", "src/foo.java");
        assertThat(spec.taskType()).isEqualTo("review");
        assertThat(spec.description()).isEqualTo("src/foo.java");
    }

    @Test void parses_test_verb_keeps_task_type() {
        var spec = new OpenCodeEventAdapter().parsePlayerCommand("test", "TestX");
        assertThat(spec.taskType()).isEqualTo("test");
    }

    @Test void parse_returns_null_on_blank_command() {
        var adapter = new OpenCodeEventAdapter();
        assertThat(adapter.parsePlayerCommand("", "anything")).isNull();
        assertThat(adapter.parsePlayerCommand(null, "anything")).isNull();
        assertThat(adapter.parsePlayerCommand("   ", "anything")).isNull();
    }

    @Test void parse_handles_null_args() {
        var spec = new OpenCodeEventAdapter().parsePlayerCommand("code", null);
        assertThat(spec).isNotNull();
        assertThat(spec.description()).isEqualTo("");
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static AgentEvent.ZoneBroadcast broadcast(JsonNode data) {
        return new AgentEvent.ZoneBroadcast(
            "opencode",
            "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "opencode", "ok", data, List.of()),
            Instant.now()
        );
    }
}
