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

/** Phase 2e — unit tests for {@link GeminiCliEventAdapter}. */
class GeminiCliEventAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void namespace_is_gemini_cli() {
        assertThat(new GeminiCliEventAdapter().namespace()).isEqualTo("gemini-cli");
        assertThat(new GeminiCliEventAdapter().namespace()).isEqualTo(GeminiCliBackend.NAME);
    }

    @Test void translates_task_completed_into_source_artifact() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-1");
        data.put("workspace", "/tmp/repo");
        data.put("model", "gemini-2.5-flash");
        data.put("status", "complete");
        var files = data.putArray("files");
        files.add("src/foo.java");

        var src = (SourceArtifact) new GeminiCliEventAdapter().translateEvent(broadcast(data));
        assertThat(src.backend()).isEqualTo("gemini-cli");
        assertThat(src.files()).containsExactly("src/foo.java");
        assertThat(src.backendMetadata()).containsEntry("model", "gemini-2.5-flash");
    }

    @Test void translates_with_sibling_build_when_build_status_present() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-2");
        data.put("workspace", "/tmp");
        data.put("buildStatus", "failed");
        data.put("testsPassed", 0);
        data.put("testsFailed", 5);

        var src = (SourceArtifact) new GeminiCliEventAdapter().translateEvent(broadcast(data));
        var sibling = src.backendMetadata().get("__sibling_build");
        assertThat(sibling).isInstanceOf(BuildArtifact.class);
        var build = (BuildArtifact) sibling;
        assertThat(build.status()).isEqualTo("failed");
        assertThat(build.testsFailed()).isEqualTo(5);
    }

    @Test void returns_null_when_namespace_does_not_match() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        var event = new AgentEvent.ZoneBroadcast("opencode", "workshop",
            new S2CMessage.ZoneResponse(0L, "req", "opencode",
                "ok", data, List.of()), Instant.now());
        assertThat(new GeminiCliEventAdapter().translateEvent(event)).isNull();
    }

    @Test void returns_null_on_progress_event() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_progress");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        assertThat(new GeminiCliEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_task_id() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("workspace", "/tmp");
        assertThat(new GeminiCliEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_null_event() {
        assertThat(new GeminiCliEventAdapter().translateEvent(null)).isNull();
    }

    @Test void parses_create_command_into_code_task() {
        var spec = new GeminiCliEventAdapter().parsePlayerCommand("create", "fix bug X");
        assertThat(spec.taskType()).isEqualTo("code");
        assertThat(spec.description()).isEqualTo("fix bug X");
    }

    @Test void parses_refactor_keeps_task_type() {
        var spec = new GeminiCliEventAdapter().parsePlayerCommand("refactor", "src/foo.java");
        assertThat(spec.taskType()).isEqualTo("refactor");
    }

    @Test void parses_explore_keeps_task_type() {
        var spec = new GeminiCliEventAdapter().parsePlayerCommand("explore", "the foo subsystem");
        assertThat(spec.taskType()).isEqualTo("explore");
    }

    @Test void parse_returns_null_on_blank_command() {
        var a = new GeminiCliEventAdapter();
        assertThat(a.parsePlayerCommand("", "x")).isNull();
        assertThat(a.parsePlayerCommand(null, "x")).isNull();
    }

    private static AgentEvent.ZoneBroadcast broadcast(JsonNode data) {
        return new AgentEvent.ZoneBroadcast("gemini-cli", "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "gemini-cli", "ok", data, List.of()),
            Instant.now());
    }
}
