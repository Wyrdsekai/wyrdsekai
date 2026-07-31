package org.wyrdsekai.core.inference;

import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ToolInferRequest — the tool inference message type.
 *-§17.
 */
class ToolInferRequestTest {

    private static ActorSystem<Void> system;

    @BeforeAll static void setup() {
        system = ActorSystem.create(Behaviors.empty(), "tool-infer-test");
    }

    @AfterAll static void teardown() {
        system.terminate();
    }

    @Test void record_construction_with_all_fields() {
        var probe = TestProbe.<InferenceRouter.InferResponse>create(system);
        var req = new InferenceRouter.ToolInferRequest(
            "req-1", "agent-ma", "reasoning", "qwen2.5:72b",
            "You are a code reviewer.", "Analyze this code for bugs.",
            2048, probe.ref());

        assertThat(req.requestId()).isEqualTo("req-1");
        assertThat(req.agentId()).isEqualTo("agent-ma");
        assertThat(req.capability()).isEqualTo("reasoning");
        assertThat(req.model()).isEqualTo("qwen2.5:72b");
        assertThat(req.systemPrompt()).isEqualTo("You are a code reviewer.");
        assertThat(req.prompt()).isEqualTo("Analyze this code for bugs.");
        assertThat(req.maxTokens()).isEqualTo(2048);
        assertThat(req.replyTo()).isEqualTo(probe.ref());
    }

    @Test void record_construction_with_nullable_fields() {
        var probe = TestProbe.<InferenceRouter.InferResponse>create(system);
        var req = new InferenceRouter.ToolInferRequest(
            "req-2", "agent-ma", null, null,
            null, "Just think about this.", 512, probe.ref());

        assertThat(req.capability()).isNull();
        assertThat(req.model()).isNull();
        assertThat(req.systemPrompt()).isNull();
        assertThat(req.prompt()).isEqualTo("Just think about this.");
    }

    @Test void implements_command_interface() {
        var probe = TestProbe.<InferenceRouter.InferResponse>create(system);
        var req = new InferenceRouter.ToolInferRequest(
            "req-3", "agent-chief", "coding", null,
            null, "Review this PR.", 1024, probe.ref());

        // ToolInferRequest must implement InferenceRouter.Command
        assertThat(req).isInstanceOf(InferenceRouter.Command.class);
    }

    @Test void record_equality() {
        var probe = TestProbe.<InferenceRouter.InferResponse>create(system);
        var a = new InferenceRouter.ToolInferRequest(
            "req-4", "agent-ma", "analysis", null,
            null, "Analyze this.", 1024, probe.ref());
        var b = new InferenceRouter.ToolInferRequest(
            "req-4", "agent-ma", "analysis", null,
            null, "Analyze this.", 1024, probe.ref());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test void different_capabilities_are_not_equal() {
        var probe = TestProbe.<InferenceRouter.InferResponse>create(system);
        var a = new InferenceRouter.ToolInferRequest(
            "req-5", "agent-ma", "reasoning", null,
            null, "Think.", 512, probe.ref());
        var b = new InferenceRouter.ToolInferRequest(
            "req-5", "agent-ma", "coding", null,
            null, "Think.", 512, probe.ref());

        assertThat(a).isNotEqualTo(b);
    }
}
