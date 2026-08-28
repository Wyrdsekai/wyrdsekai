package org.wyrdsekai.core.codezaiku;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;

import static org.assertj.core.api.Assertions.assertThat;

class CodexActionParserTest {

    @Test void parse_codex_action_commit() {
        var llmOutput = """
            I'll commit those changes now.
            ```json
            {"action": "codex_action", "operation": "commit", "itemId": "codex-abc123", "params": {"message": "Add error handling"}}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.CodexAction.class);

        var codexAction = (AgentAction.CodexAction) action;
        assertThat(codexAction.operation()).isEqualTo("commit");
        assertThat(codexAction.itemId()).isEqualTo("codex-abc123");
        assertThat(codexAction.params()).containsEntry("message", "Add error handling");
    }

    @Test void parse_codex_action_deploy() {
        var llmOutput = """
            Deploying the artifact to the boiler room.
            ```json
            {"action": "codex_action", "operation": "deploy", "itemId": "artifact-xyz789", "params": {"target": "boiler-room"}}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.CodexAction.class);

        var codexAction = (AgentAction.CodexAction) action;
        assertThat(codexAction.operation()).isEqualTo("deploy");
        assertThat(codexAction.itemId()).isEqualTo("artifact-xyz789");
        assertThat(codexAction.params()).containsEntry("target", "boiler-room");
    }

    @Test void parse_codex_action_examine_with_file_param() {
        var llmOutput = """
            Let me look at that file.
            ```json
            {"action": "codex_action", "operation": "examine", "itemId": "codex-abc123", "params": {"file": "src/Main.java"}}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.CodexAction.class);

        var codexAction = (AgentAction.CodexAction) action;
        assertThat(codexAction.operation()).isEqualTo("examine");
        assertThat(codexAction.params()).containsEntry("file", "src/Main.java");
    }

    @Test void unknown_operation_still_parses() {
        var llmOutput = """
            ```json
            {"action": "codex_action", "operation": "revert", "itemId": "codex-abc123", "params": {"ref": "abc1234"}}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.CodexAction.class);

        var codexAction = (AgentAction.CodexAction) action;
        assertThat(codexAction.operation()).isEqualTo("revert");
    }

    @Test void missing_itemId_rejected_by_schema() {
        var llmOutput = """
            ```json
            {"action": "codex_action", "operation": "build", "params": {}}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isNull(); // schema requires non-blank itemId
    }
}
