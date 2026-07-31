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
 * Phase 2d — unit tests for {@link ClineEventAdapter}.
 */
class ClineEventAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void namespace_is_cline() {
        assertThat(new ClineEventAdapter().namespace()).isEqualTo("cline");
        assertThat(new ClineEventAdapter().namespace()).isEqualTo(ClineBackend.NAME);
    }

    @Test void translates_task_completed_into_source_artifact() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-abc-123");
        data.put("workspace", "/tmp/repo");
        data.put("provider", "anthropic");
        data.put("status", "complete");
        var files = data.putArray("files");
        files.add("src/foo.java");

        var artifact = new ClineEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.backend()).isEqualTo("cline");
        assertThat(src.files()).containsExactly("src/foo.java");
        assertThat(src.backendMetadata()).containsEntry("source", "cline");
        assertThat(src.backendMetadata()).containsEntry("provider", "anthropic");
    }

    @Test void translates_with_sibling_build() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-456");
        data.put("workspace", "/tmp/r2");
        data.put("buildStatus", "success");
        data.put("testsPassed", 5);
        data.put("testsFailed", 1);

        var artifact = new ClineEventAdapter().translateEvent(broadcast(data));
        var src = (SourceArtifact) artifact;
        var sibling = (BuildArtifact) src.backendMetadata().get("__sibling_build");
        assertThat(sibling.backend()).isEqualTo("cline");
        assertThat(sibling.testsPassed()).isEqualTo(5);
        assertThat(sibling.testsFailed()).isEqualTo(1);
    }

    @Test void returns_null_when_namespace_does_not_match() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        var event = new AgentEvent.ZoneBroadcast("opencode", "workshop",
            new S2CMessage.ZoneResponse(0L, "req", "opencode", "ok", data, List.of()),
            Instant.now());
        assertThat(new ClineEventAdapter().translateEvent(event)).isNull();
    }

    @Test void returns_null_on_progress_event() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_progress");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        assertThat(new ClineEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_task_id() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("workspace", "/tmp");
        assertThat(new ClineEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_null_event() {
        assertThat(new ClineEventAdapter().translateEvent(null)).isNull();
    }

    // ─── parsePlayerCommand ──────────────────────────────────────

    @Test void parses_create_command_into_code_task() {
        var spec = new ClineEventAdapter().parsePlayerCommand("create", "fix bug X");
        assertThat(spec.taskType()).isEqualTo("code");
        assertThat(spec.description()).isEqualTo("fix bug X");
    }

    @Test void parses_refactor_keeps_task_type() {
        var spec = new ClineEventAdapter().parsePlayerCommand("refactor", "src/foo.java");
        assertThat(spec.taskType()).isEqualTo("refactor");
    }

    @Test void parses_explore_keeps_task_type() {
        var spec = new ClineEventAdapter().parsePlayerCommand("explore", "the foo subsystem");
        assertThat(spec.taskType()).isEqualTo("explore");
    }

    @Test void parse_returns_null_on_blank_command() {
        var a = new ClineEventAdapter();
        assertThat(a.parsePlayerCommand("", "x")).isNull();
        assertThat(a.parsePlayerCommand(null, "x")).isNull();
        assertThat(a.parsePlayerCommand("   ", "x")).isNull();
    }

    @Test void parse_handles_null_args() {
        var spec = new ClineEventAdapter().parsePlayerCommand("code", null);
        assertThat(spec.description()).isEqualTo("");
    }

    private static AgentEvent.ZoneBroadcast broadcast(JsonNode data) {
        return new AgentEvent.ZoneBroadcast("cline", "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "cline", "ok", data, List.of()),
            Instant.now());
    }
}
