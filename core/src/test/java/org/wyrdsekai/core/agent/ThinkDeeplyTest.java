package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the think_deeply action type (Phase G: Tool Inference).
 */
class ThinkDeeplyTest {

    @Test void parse_think_deeply_with_capability() {
        var input = """
            I need to analyze this more carefully.
            ```json
            {"action": "think_deeply", "capability": "reasoning", "prompt": "Analyze this deployment for risks: no rollback, API change."}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ThinkDeeply.class);
        var td = (AgentAction.ThinkDeeply) action;
        assertThat(td.capability()).isEqualTo("reasoning");
        assertThat(td.delegationPrompt()).isEqualTo(
            "Analyze this deployment for risks: no rollback, API change.");
    }

    @Test void parse_think_deeply_without_capability() {
        var input = """
            Let me think about this...
            ```json
            {"action": "think_deeply", "prompt": "What are the implications of this config change?"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ThinkDeeply.class);
        var td = (AgentAction.ThinkDeeply) action;
        assertThat(td.capability()).isNull();
        assertThat(td.delegationPrompt()).isEqualTo(
            "What are the implications of this config change?");
    }

    @Test void parse_think_deeply_coding_capability() {
        var input = """
            I should review this code more thoroughly.
            ```json
            {"action": "think_deeply", "capability": "coding",
             "prompt": "Review this Java class for thread-safety issues:\\nclass Foo { int x; void set(int v) { x = v; } }"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ThinkDeeply.class);
        var td = (AgentAction.ThinkDeeply) action;
        assertThat(td.capability()).isEqualTo("coding");
        assertThat(td.delegationPrompt()).contains("thread-safety");
    }

    @Test void parse_think_deeply_with_complex_prompt() {
        var input = """
            This requires deep analysis.
            ```json
            {"action": "think_deeply", "capability": "analysis",
             "prompt": "Given the following metrics:\\n- CPU: 85%\\n- Memory: 72%\\n- Disk I/O: high\\n- Network: normal\\n\\nIs this server overloaded? What should we prioritize?"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ThinkDeeply.class);
        var td = (AgentAction.ThinkDeeply) action;
        assertThat(td.capability()).isEqualTo("analysis");
        assertThat(td.delegationPrompt()).contains("CPU: 85%");
        assertThat(td.delegationPrompt()).contains("prioritize");
    }

    @Test void parse_think_deeply_empty_prompt_rejected_by_schema() {
        var input = """
            ```json
            {"action": "think_deeply"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isNull(); // schema requires non-blank prompt
    }

    @Test void parse_think_deeply_with_hints() {
        var input = """
            Let me analyze this.
            ```json
            {"action": "think_deeply", "capability": "reasoning",
             "prompt": "Evaluate the risk of this change."}
            ```
            ```json
            {"action": "suggest_hints", "hints": [
              {"label": "Show details", "intent": "detail", "action": "say:show me the full analysis"}
            ]}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.ThinkDeeply.class);
        assertThat(result.hasHints()).isTrue();
        assertThat(result.hints()).hasSize(1);
    }

    @Test void parse_think_deeply_does_not_override_earlier_action() {
        var input = """
            ```json
            {"action": "zone_command", "command": "codezaiku.status", "payload": {}}
            ```
            ```json
            {"action": "think_deeply", "capability": "reasoning", "prompt": "Analyze this."}
            ```
            """;
        var result = ActionParser.parseAll(input);
        // zone_command came first — think_deeply is ignored
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.ZoneCommand.class);
    }

    @Test void extractProse_before_think_deeply() {
        var input = """
            I need to think about this more carefully.
            ```json
            {"action": "think_deeply", "capability": "reasoning", "prompt": "Deep analysis needed."}
            ```
            """;
        var prose = ActionParser.extractProse(input);
        assertThat(prose).isEqualTo("I need to think about this more carefully.");
    }

    @Test void think_deeply_record_equality() {
        var a = new AgentAction.ThinkDeeply("reasoning", "Analyze this.");
        var b = new AgentAction.ThinkDeeply("reasoning", "Analyze this.");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test void think_deeply_null_capability_is_valid() {
        var td = new AgentAction.ThinkDeeply(null, "Just think about it.");
        assertThat(td.capability()).isNull();
        assertThat(td.delegationPrompt()).isEqualTo("Just think about it.");
    }
}
