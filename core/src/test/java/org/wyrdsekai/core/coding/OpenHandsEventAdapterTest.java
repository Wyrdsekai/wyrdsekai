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
 * Phase 2c — unit tests for {@link OpenHandsEventAdapter}.
 *
 * <p>The adapter accepts two terminal-event shapes after the 2026-05-05
 * reconciliation:</p>
 * <ul>
 *   <li><b>V1 Agent Server</b> shape:
 *       {@code {kind: "ConversationStateUpdateEvent", value: "finished"|"error"|"stuck", ...}}
 *       — the live shape emitted by
 *       {@code ghcr.io/openhands/agent-server:1.19.1-python}.</li>
 *   <li><b>Legacy MCP-era</b> shape: {@code {event: "complete"|"task_completed", ...}}
 *       — kept for back-compat with traces produced by the pre-2026-05
 *       fabricated MCP path.</li>
 * </ul>
 *
 * <p>Mirrors the OpenCode adapter tests in shape.</p>
 */
class OpenHandsEventAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void namespace_is_openhands() {
        var adapter = new OpenHandsEventAdapter();
        assertThat(adapter.namespace()).isEqualTo("openhands");
        assertThat(adapter.namespace()).isEqualTo(OpenHandsBackend.NAME);
    }

    // ─── V1 shape: ConversationStateUpdateEvent ────────────────────

    @Test void translates_v1_finished_state_event_into_source_artifact() {
        var data = MAPPER.createObjectNode();
        data.put("kind", "ConversationStateUpdateEvent");
        data.put("value", "finished");
        data.put("taskId", "task-abc-123");
        data.put("workspace", "/tmp/repo");
        data.put("status", "success");
        data.put("agentVersion", "1.19.1");
        var files = data.putArray("files");
        files.add("src/foo.java");
        files.add("src/bar.java");

        var event = broadcast(data);
        var artifact = new OpenHandsEventAdapter().translateEvent(event);

        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.backend()).isEqualTo("openhands");
        assertThat(src.taskId()).isEqualTo("task-abc-123");
        assertThat(src.workspacePath()).isEqualTo("/tmp/repo");
        assertThat(src.files()).containsExactly("src/foo.java", "src/bar.java");
        assertThat(src.backendMetadata()).containsEntry("source", "openhands");
        assertThat(src.backendMetadata()).containsEntry("status", "success");
        assertThat(src.backendMetadata()).containsEntry("agent_version", "1.19.1");
    }

    @Test void translates_v1_error_state_event_too() {
        var data = MAPPER.createObjectNode();
        data.put("kind", "ConversationStateUpdateEvent");
        data.put("value", "error");
        data.put("taskId", "task-err");
        data.put("workspace", "/tmp/r");

        var artifact = new OpenHandsEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);
    }

    @Test void translates_v1_stuck_state_event_too() {
        var data = MAPPER.createObjectNode();
        data.put("kind", "ConversationStateUpdateEvent");
        data.put("value", "stuck");
        data.put("taskId", "task-stuck");
        data.put("workspace", "/tmp/r");

        var artifact = new OpenHandsEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);
    }

    @Test void v1_state_update_with_non_terminal_value_returns_null() {
        // ConversationStateUpdateEvent with value="running" or
        // value="idle" is a status ping, not a terminal event.
        for (var value : List.of("running", "idle", "paused", "waiting_for_confirmation")) {
            var data = MAPPER.createObjectNode();
            data.put("kind", "ConversationStateUpdateEvent");
            data.put("value", value);
            data.put("taskId", "x");
            data.put("workspace", "/tmp");

            assertThat(new OpenHandsEventAdapter().translateEvent(broadcast(data)))
                .as("non-terminal status '%s' must not produce an artifact", value)
                .isNull();
        }
    }

    // ─── Legacy MCP-era shape ──────────────────────────────────────

    @Test void translates_legacy_complete_event_into_source_artifact() {
        var data = MAPPER.createObjectNode();
        data.put("event", "complete");
        data.put("taskId", "task-abc-123");
        data.put("workspace", "/tmp/repo");
        data.put("status", "success");
        data.put("agentVersion", "0.99.0");
        var files = data.putArray("files");
        files.add("src/foo.java");
        files.add("src/bar.java");

        var artifact = new OpenHandsEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        var src = (SourceArtifact) artifact;
        assertThat(src.taskId()).isEqualTo("task-abc-123");
        assertThat(src.files()).containsExactly("src/foo.java", "src/bar.java");
    }

    @Test void accepts_legacy_task_completed_event_name() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-456");
        data.put("workspace", "/tmp/r2");

        var artifact = new OpenHandsEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);
        assertThat(((SourceArtifact) artifact).taskId()).isEqualTo("task-456");
    }

    @Test void translates_with_sibling_build_when_build_block_present() {
        // Build artifact may appear on either V1 or legacy shape; pin
        // both. Use V1 here so the assertion exercises the live path.
        var data = MAPPER.createObjectNode();
        data.put("kind", "ConversationStateUpdateEvent");
        data.put("value", "finished");
        data.put("taskId", "task-789");
        data.put("workspace", "/tmp/r3");
        var build = data.putObject("build");
        build.put("status", "success");
        build.put("testsPassed", 42);
        build.put("testsFailed", 0);

        var artifact = new OpenHandsEventAdapter().translateEvent(broadcast(data));
        assertThat(artifact).isInstanceOf(SourceArtifact.class);

        var src = (SourceArtifact) artifact;
        var sibling = src.backendMetadata().get("__sibling_build");
        assertThat(sibling).isInstanceOf(BuildArtifact.class);
        var buildArtifact = (BuildArtifact) sibling;
        assertThat(buildArtifact.backend()).isEqualTo("openhands");
        assertThat(buildArtifact.status()).isEqualTo("success");
        assertThat(buildArtifact.testsPassed()).isEqualTo(42);
        assertThat(buildArtifact.testsFailed()).isEqualTo(0);
        assertThat(buildArtifact.taskId()).isEqualTo("task-789");
    }

    @Test void returns_null_when_namespace_does_not_match() {
        var data = MAPPER.createObjectNode();
        data.put("kind", "ConversationStateUpdateEvent");
        data.put("value", "finished");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        var event = new AgentEvent.ZoneBroadcast("opencode",
            "workshop",
            new S2CMessage.ZoneResponse(0L, "req", "opencode",
                "ok", data, List.of()),
            Instant.now());

        assertThat(new OpenHandsEventAdapter().translateEvent(event)).isNull();
    }

    @Test void returns_null_for_v1_progress_events() {
        // V1 emits ActionEvent / MessageEvent / SystemPromptEvent /
        // ObservationEvent during the run; none of those are terminal.
        for (var kind : List.of("ActionEvent", "MessageEvent",
                                  "SystemPromptEvent", "ObservationEvent",
                                  "LLMCompletionLogEvent")) {
            var data = MAPPER.createObjectNode();
            data.put("kind", kind);
            data.put("taskId", "x");
            data.put("workspace", "/tmp");

            assertThat(new OpenHandsEventAdapter().translateEvent(broadcast(data)))
                .as("event kind '%s' is a progress ping; should not produce an artifact",
                    kind)
                .isNull();
        }
    }

    @Test void returns_null_for_legacy_progress_events() {
        for (var eventName : List.of("agent_action", "file_changed",
                                       "command_run", "task_started")) {
            var data = MAPPER.createObjectNode();
            data.put("event", eventName);
            data.put("taskId", "x");
            data.put("workspace", "/tmp");

            assertThat(new OpenHandsEventAdapter().translateEvent(broadcast(data)))
                .as("event '%s' is a progress ping; should not produce an artifact",
                    eventName)
                .isNull();
        }
    }

    @Test void returns_null_on_missing_task_id() {
        var data = MAPPER.createObjectNode();
        data.put("kind", "ConversationStateUpdateEvent");
        data.put("value", "finished");
        data.put("workspace", "/tmp");

        assertThat(new OpenHandsEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_workspace() {
        var data = MAPPER.createObjectNode();
        data.put("kind", "ConversationStateUpdateEvent");
        data.put("value", "finished");
        data.put("taskId", "x");

        assertThat(new OpenHandsEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_null_event() {
        assertThat(new OpenHandsEventAdapter().translateEvent(null)).isNull();
    }

    @Test void returns_null_when_message_is_not_zone_response() {
        var event = new AgentEvent.ZoneBroadcast("openhands",
            "workshop",
            new S2CMessage.Notification(0L, "info", "title", "body"),
            Instant.now());
        assertThat(new OpenHandsEventAdapter().translateEvent(event)).isNull();
    }

    // ─── parsePlayerCommand ──────────────────────────────────────

    @Test void parses_create_command_into_code_task() {
        var spec = new OpenHandsEventAdapter().parsePlayerCommand("create", "fix bug X");

        assertThat(spec).isNotNull();
        assertThat(spec.taskType()).isEqualTo("code");
        assertThat(spec.description()).isEqualTo("fix bug X");
    }

    @Test void parses_explore_verb_keeps_task_type() {
        var spec = new OpenHandsEventAdapter().parsePlayerCommand("explore",
            "the foo subsystem");
        assertThat(spec.taskType()).isEqualTo("explore");
        assertThat(spec.description()).isEqualTo("the foo subsystem");
    }

    @Test void parses_survey_alias_into_explore() {
        var spec = new OpenHandsEventAdapter().parsePlayerCommand("survey",
            "the dependency graph");
        assertThat(spec.taskType()).isEqualTo("explore");
    }

    @Test void parses_research_alias_into_explore() {
        var spec = new OpenHandsEventAdapter().parsePlayerCommand("research",
            "the codebase");
        assertThat(spec.taskType()).isEqualTo("explore");
    }

    @Test void parses_refactor_verb_keeps_task_type() {
        var spec = new OpenHandsEventAdapter().parsePlayerCommand("refactor",
            "src/foo.java");
        assertThat(spec.taskType()).isEqualTo("refactor");
    }

    @Test void parse_returns_null_on_blank_command() {
        var adapter = new OpenHandsEventAdapter();
        assertThat(adapter.parsePlayerCommand("", "anything")).isNull();
        assertThat(adapter.parsePlayerCommand(null, "anything")).isNull();
        assertThat(adapter.parsePlayerCommand("   ", "anything")).isNull();
    }

    @Test void parse_handles_null_args() {
        var spec = new OpenHandsEventAdapter().parsePlayerCommand("explore", null);
        assertThat(spec).isNotNull();
        assertThat(spec.description()).isEqualTo("");
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static AgentEvent.ZoneBroadcast broadcast(JsonNode data) {
        return new AgentEvent.ZoneBroadcast(
            "openhands",
            "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "openhands", "ok", data, List.of()),
            Instant.now()
        );
    }
}
