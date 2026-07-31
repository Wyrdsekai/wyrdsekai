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
 * Phase 2d — unit tests for {@link GooseEventAdapter}.
 */
class GooseEventAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void namespace_is_goose() {
        assertThat(new GooseEventAdapter().namespace()).isEqualTo("goose");
        assertThat(new GooseEventAdapter().namespace()).isEqualTo(GooseBackend.NAME);
    }

    // ─── Event translation ────────────────────────────────────────

    @Test void translates_task_completed_into_source_artifact() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-abc-123");
        data.put("workspace", "/tmp/repo");
        data.put("provider", "anthropic");
        data.put("status", "complete");
        var files = data.putArray("files");
        files.add("src/foo.java");
        files.add("src/bar.java");

        var artifact = new GooseEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.backend()).isEqualTo("goose");
        assertThat(src.taskId()).isEqualTo("task-abc-123");
        assertThat(src.workspacePath()).isEqualTo("/tmp/repo");
        assertThat(src.files()).containsExactly("src/foo.java", "src/bar.java");
        assertThat(src.backendMetadata()).containsEntry("source", "goose");
        assertThat(src.backendMetadata()).containsEntry("provider", "anthropic");
    }

    @Test void translates_with_sibling_build_when_build_status_present() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-456");
        data.put("workspace", "/tmp/r2");
        data.put("buildStatus", "success");
        data.put("testsPassed", 17);
        data.put("testsFailed", 0);

        var artifact = new GooseEventAdapter().translateEvent(broadcast(data));
        var src = (SourceArtifact) artifact;
        var sibling = src.backendMetadata().get("__sibling_build");
        assertThat(sibling).isInstanceOf(BuildArtifact.class);
        var build = (BuildArtifact) sibling;
        assertThat(build.backend()).isEqualTo("goose");
        assertThat(build.testsPassed()).isEqualTo(17);
        assertThat(build.testsFailed()).isEqualTo(0);
    }

    @Test void returns_null_when_namespace_does_not_match() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        var event = new AgentEvent.ZoneBroadcast("opencode",
            "workshop",
            new S2CMessage.ZoneResponse(0L, "req", "opencode",
                "ok", data, List.of()),
            Instant.now());
        assertThat(new GooseEventAdapter().translateEvent(event)).isNull();
    }

    @Test void returns_null_on_progress_event() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_progress");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        assertThat(new GooseEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_task_id() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("workspace", "/tmp");
        assertThat(new GooseEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_null_event() {
        assertThat(new GooseEventAdapter().translateEvent(null)).isNull();
    }

    // ─── parsePlayerCommand ──────────────────────────────────────

    @Test void parses_create_command_into_code_task() {
        var spec = new GooseEventAdapter().parsePlayerCommand("create", "fix bug X");
        assertThat(spec).isNotNull();
        assertThat(spec.taskType()).isEqualTo("code");
        assertThat(spec.description()).isEqualTo("fix bug X");
    }

    @Test void parses_refactor_keeps_task_type() {
        var spec = new GooseEventAdapter().parsePlayerCommand("refactor", "src/foo.java");
        assertThat(spec.taskType()).isEqualTo("refactor");
    }

    @Test void parses_explore_keeps_task_type() {
        var spec = new GooseEventAdapter().parsePlayerCommand("explore", "the foo subsystem");
        assertThat(spec.taskType()).isEqualTo("explore");
    }

    @Test void parse_returns_null_on_blank_command() {
        var a = new GooseEventAdapter();
        assertThat(a.parsePlayerCommand("", "anything")).isNull();
        assertThat(a.parsePlayerCommand(null, "anything")).isNull();
        assertThat(a.parsePlayerCommand("   ", "anything")).isNull();
    }

    @Test void parse_handles_null_args() {
        var spec = new GooseEventAdapter().parsePlayerCommand("code", null);
        assertThat(spec).isNotNull();
        assertThat(spec.description()).isEqualTo("");
    }

    private static AgentEvent.ZoneBroadcast broadcast(JsonNode data) {
        return new AgentEvent.ZoneBroadcast("goose", "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "goose", "ok", data, List.of()),
            Instant.now());
    }
}
