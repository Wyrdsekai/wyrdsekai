package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Phase D: Inter-Agent Communication (tell_agent).
 *
 * Covers:
 * - ActionParser parsing of tell_agent action
 * - AgentMessage event construction and targeted delivery
 * - AgentEventStream.publishAgentMessage targeted routing
 * - Edge cases (empty target, empty message, unknown target)
 */
class TellAgentTest {

    // ---- ActionParser: tell_agent ----

    @Test void parse_tell_agent() {
        var input = """
            I'll check with the Chief about that.
            ```json
            {"action": "tell_agent", "target": "Chief", "message": "How's the Boiler Room pressure?"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.TellAgent.class);
        var tell = (AgentAction.TellAgent) action;
        assertThat(tell.targetName()).isEqualTo("Chief");
        assertThat(tell.message()).isEqualTo("How's the Boiler Room pressure?");
    }

    @Test void parse_tell_agent_with_long_message() {
        var longMsg = "A".repeat(500);
        var input = """
            ```json
            {"action": "tell_agent", "target": "Warden", "message": "%s"}
            ```
            """.formatted(longMsg);
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.TellAgent.class);
        var tell = (AgentAction.TellAgent) action;
        assertThat(tell.targetName()).isEqualTo("Warden");
        assertThat(tell.message()).isEqualTo(longMsg);
    }

    @Test void parse_tell_agent_empty_target_rejected_by_schema() {
        var input = """
            ```json
            {"action": "tell_agent", "message": "hello"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isNull(); // schema requires non-blank target
    }

    @Test void parse_tell_agent_empty_message_rejected_by_schema() {
        var input = """
            ```json
            {"action": "tell_agent", "target": "Chief"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isNull(); // schema requires non-blank message
    }

    @Test void parse_tell_agent_extracts_prose() {
        var input = """
            Let me ask the Chief about that.
            ```json
            {"action": "tell_agent", "target": "Chief", "message": "Status report?"}
            ```
            """;
        var prose = ActionParser.extractProse(input);
        assertThat(prose).isEqualTo("Let me ask the Chief about that.");
    }

    @Test void parse_tell_agent_does_not_conflict_with_other_actions() {
        // tell_agent should not be parsed if another action was already found
        var input = """
            ```json
            {"action": "create_room", "name": "Test"}
            ```
            Some text
            ```json
            {"action": "tell_agent", "target": "Chief", "message": "hello"}
            ```
            """;
        var action = ActionParser.parse(input);
        // First action wins — create_room, not tell_agent
        assertThat(action).isInstanceOf(AgentAction.CreateRoom.class);
    }

    // ---- AgentEvent.AgentMessage ----

    @Test void agent_message_event_construction() {
        var now = Instant.now();
        var msg = new AgentEvent.AgentMessage(
            "agent-1", "Lain", "agent-2", "Hello from the Nexus!", now);

        assertThat(msg.fromAgentId()).isEqualTo("agent-1");
        assertThat(msg.fromAgentName()).isEqualTo("Lain");
        assertThat(msg.toAgentId()).isEqualTo("agent-2");
        assertThat(msg.message()).isEqualTo("Hello from the Nexus!");
        assertThat(msg.timestamp()).isEqualTo(now);
    }

    @Test void agent_message_is_an_agent_event() {
        var msg = new AgentEvent.AgentMessage(
            "a1", "Lain", "a2", "test", Instant.now());
        assertThat(msg).isInstanceOf(AgentEvent.class);
    }

    // ---- AgentEventStream: targeted delivery ----

    private AgentEventStream stream;
    private final List<String> subscribedAgents = new ArrayList<>();

    @BeforeEach
    void setup() {
        stream = new AgentEventStream();
    }

    @AfterEach
    void teardown() {
        for (var id : subscribedAgents) {
            stream.unsubscribe(id);
        }
    }

    private void subscribe(String agentId, Consumer<AgentEvent> listener) {
        stream.subscribe(agentId, listener);
        subscribedAgents.add(agentId);
    }

    @Test void publishAgentMessage_delivers_only_to_target() throws InterruptedException {
        List<AgentEvent> received1 = Collections.synchronizedList(new ArrayList<>());
        List<AgentEvent> received2 = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(1);

        subscribe("agent-1", received1::add);
        subscribe("agent-2", event -> {
            received2.add(event);
            latch.countDown();
        });

        boolean delivered = stream.publishAgentMessage(
            "agent-1", "Lain", "agent-2", "Hello Chief!");

        assertThat(delivered).isTrue();
        // Wait for async delivery via drain thread
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        // Only agent-2 should receive the message
        assertThat(received1).isEmpty();
        assertThat(received2).hasSize(1);

        var msg = (AgentEvent.AgentMessage) received2.get(0);
        assertThat(msg.fromAgentId()).isEqualTo("agent-1");
        assertThat(msg.fromAgentName()).isEqualTo("Lain");
        assertThat(msg.toAgentId()).isEqualTo("agent-2");
        assertThat(msg.message()).isEqualTo("Hello Chief!");
        assertThat(msg.timestamp()).isNotNull();
    }

    @Test void publishAgentMessage_returns_false_for_unknown_target() {
        subscribe("agent-1", event -> {});

        boolean delivered = stream.publishAgentMessage(
            "agent-1", "Lain", "agent-999", "Anyone there?");

        assertThat(delivered).isFalse();
    }

    @Test void publishAgentMessage_returns_false_with_no_subscribers() {
        boolean delivered = stream.publishAgentMessage(
            "agent-1", "Lain", "agent-2", "Hello?");

        assertThat(delivered).isFalse();
    }

    @Test void publishAgentMessage_handles_listener_exception() {
        subscribe("bad-agent", event -> {
            throw new RuntimeException("boom");
        });

        // Should not throw, just log the warning and return true (delivery attempted)
        boolean delivered = stream.publishAgentMessage(
            "agent-1", "Lain", "bad-agent", "This will fail gracefully");

        assertThat(delivered).isTrue();
    }
}
