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

/** Phase 2e — unit tests for {@link ClaudeSdkEventAdapter}. */
class ClaudeSdkEventAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void namespace_is_claude_sdk() {
        assertThat(new ClaudeSdkEventAdapter().namespace()).isEqualTo("claude-sdk");
        assertThat(new ClaudeSdkEventAdapter().namespace()).isEqualTo(ClaudeSdkBackend.NAME);
    }

    // ─── Event translation ────────────────────────────────────────

    @Test void translates_task_completed_into_source_artifact() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-1");
        data.put("workspace", "/tmp/repo");
        data.put("model", "sonnet");
        data.put("session_id", "sess-xyz");
        data.put("status", "complete");
        var files = data.putArray("files");
        files.add("src/foo.java");

        var artifact = new ClaudeSdkEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.backend()).isEqualTo("claude-sdk");
        assertThat(src.taskId()).isEqualTo("task-1");
        assertThat(src.files()).containsExactly("src/foo.java");
        assertThat(src.backendMetadata()).containsEntry("source", "claude-sdk");
        assertThat(src.backendMetadata()).containsEntry("model", "sonnet");
        assertThat(src.backendMetadata()).containsEntry("session_id", "sess-xyz");
    }

    @Test void translates_with_sibling_build_when_build_status_present() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-2");
        data.put("workspace", "/tmp/r2");
        data.put("buildStatus", "success");
        data.put("testsPassed", 5);
        data.put("testsFailed", 0);

        var src = (SourceArtifact) new ClaudeSdkEventAdapter().translateEvent(broadcast(data));
        var sibling = src.backendMetadata().get("__sibling_build");
        assertThat(sibling).isInstanceOf(BuildArtifact.class);
        var build = (BuildArtifact) sibling;
        assertThat(build.backend()).isEqualTo("claude-sdk");
        assertThat(build.testsPassed()).isEqualTo(5);
    }

    @Test void translates_with_total_cost_usd_when_present() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-3");
        data.put("workspace", "/tmp");
        data.put("total_cost_usd", 0.0125);

        var src = (SourceArtifact) new ClaudeSdkEventAdapter().translateEvent(broadcast(data));
        assertThat(src.backendMetadata()).containsEntry("total_cost_usd", 0.0125);
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
        assertThat(new ClaudeSdkEventAdapter().translateEvent(event)).isNull();
    }

    @Test void returns_null_on_progress_event() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_progress");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        assertThat(new ClaudeSdkEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_task_id() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("workspace", "/tmp");
        assertThat(new ClaudeSdkEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_null_event() {
        assertThat(new ClaudeSdkEventAdapter().translateEvent(null)).isNull();
    }

    // ─── parsePlayerCommand ──────────────────────────────────────

    @Test void parses_create_command_into_code_task() {
        var spec = new ClaudeSdkEventAdapter().parsePlayerCommand("create", "fix bug X");
        assertThat(spec).isNotNull();
        assertThat(spec.taskType()).isEqualTo("code");
        assertThat(spec.description()).isEqualTo("fix bug X");
    }

    @Test void parses_refactor_keeps_task_type() {
        var spec = new ClaudeSdkEventAdapter().parsePlayerCommand("refactor", "src/foo.java");
        assertThat(spec.taskType()).isEqualTo("refactor");
    }

    @Test void parses_explore_keeps_task_type() {
        var spec = new ClaudeSdkEventAdapter().parsePlayerCommand("explore", "the foo subsystem");
        assertThat(spec.taskType()).isEqualTo("explore");
    }

    @Test void parse_returns_null_on_blank_command() {
        var a = new ClaudeSdkEventAdapter();
        assertThat(a.parsePlayerCommand("", "anything")).isNull();
        assertThat(a.parsePlayerCommand(null, "anything")).isNull();
        assertThat(a.parsePlayerCommand("   ", "anything")).isNull();
    }

    @Test void parse_handles_null_args() {
        var spec = new ClaudeSdkEventAdapter().parsePlayerCommand("code", null);
        assertThat(spec).isNotNull();
        assertThat(spec.description()).isEqualTo("");
    }

    private static AgentEvent.ZoneBroadcast broadcast(JsonNode data) {
        return new AgentEvent.ZoneBroadcast("claude-sdk", "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "claude-sdk", "ok", data, List.of()),
            Instant.now());
    }
}
