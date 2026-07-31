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

/** Phase 2e — unit tests for {@link DevinEventAdapter}. */
class DevinEventAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void namespace_is_devin() {
        assertThat(new DevinEventAdapter().namespace()).isEqualTo("devin");
        assertThat(new DevinEventAdapter().namespace()).isEqualTo(DevinBackend.NAME);
    }

    @Test void translates_task_completed_with_pr_url() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "task-1");
        data.put("workspace", "/tmp/repo");
        data.put("session_id", "sess-xyz");
        data.put("pull_request_url", "https://github.com/owner/repo/pull/42");
        data.put("pull_request_title", "Fix bug X");
        data.put("status_enum", "stopped");

        var src = (SourceArtifact) new DevinEventAdapter().translateEvent(broadcast(data));
        assertThat(src.backend()).isEqualTo("devin");
        assertThat(src.backendMetadata())
            .containsEntry("session_id", "sess-xyz")
            .containsEntry("pull_request_url", "https://github.com/owner/repo/pull/42")
            .containsEntry("pull_request_title", "Fix bug X")
            .containsEntry("status_enum", "stopped");
    }

    @Test void returns_null_when_namespace_does_not_match() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        var event = new AgentEvent.ZoneBroadcast("opencode", "workshop",
            new S2CMessage.ZoneResponse(0L, "req", "opencode",
                "ok", data, List.of()), Instant.now());
        assertThat(new DevinEventAdapter().translateEvent(event)).isNull();
    }

    @Test void returns_null_on_progress_event() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_progress");
        data.put("taskId", "x");
        data.put("workspace", "/tmp");
        assertThat(new DevinEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_missing_task_id() {
        var data = MAPPER.createObjectNode();
        data.put("event", "task_completed");
        data.put("workspace", "/tmp");
        assertThat(new DevinEventAdapter().translateEvent(broadcast(data))).isNull();
    }

    @Test void returns_null_on_null_event() {
        assertThat(new DevinEventAdapter().translateEvent(null)).isNull();
    }

    @Test void parses_create_command_into_code_task() {
        var spec = new DevinEventAdapter().parsePlayerCommand("create", "implement feature Y");
        assertThat(spec.taskType()).isEqualTo("code");
        assertThat(spec.description()).isEqualTo("implement feature Y");
    }

    @Test void parse_returns_null_on_blank_command() {
        var a = new DevinEventAdapter();
        assertThat(a.parsePlayerCommand("", "x")).isNull();
        assertThat(a.parsePlayerCommand(null, "x")).isNull();
    }

    private static AgentEvent.ZoneBroadcast broadcast(JsonNode data) {
        return new AgentEvent.ZoneBroadcast("devin", "workshop",
            new S2CMessage.ZoneResponse(0L, UUID.randomUUID().toString(),
                "devin", "ok", data, List.of()),
            Instant.now());
    }
}
