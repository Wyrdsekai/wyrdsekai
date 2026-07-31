package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for parsing the make_commitment action from LLM output.
 */
class MakeCommitmentParserTest {

    @Test void parse_with_deadline() {
        var input = """
            I'll check on the training results tomorrow morning.
            ```json
            {"action": "make_commitment", "description": "Check training results", "deadline": "2026-03-17T09:00:00Z"}
            ```
            """;
        var action = ActionParser.parse(input);

        assertThat(action).isInstanceOf(ActionParser.AgentAction.MakeCommitment.class);
        var commit = (ActionParser.AgentAction.MakeCommitment) action;
        assertThat(commit.description()).isEqualTo("Check training results");
        assertThat(commit.deadline()).isEqualTo("2026-03-17T09:00:00Z");
    }

    @Test void parse_without_deadline() {
        var input = """
            I'll look into that when I get a chance.
            ```json
            {"action": "make_commitment", "description": "Research the new MCP tools"}
            ```
            """;
        var action = ActionParser.parse(input);

        assertThat(action).isInstanceOf(ActionParser.AgentAction.MakeCommitment.class);
        var commit = (ActionParser.AgentAction.MakeCommitment) action;
        assertThat(commit.description()).isEqualTo("Research the new MCP tools");
        assertThat(commit.deadline()).isNull();
    }

    @Test void parse_empty_description_returns_null() {
        var input = """
            ```json
            {"action": "make_commitment", "description": ""}
            ```
            """;
        var action = ActionParser.parse(input);

        // Empty description should not produce a MakeCommitment action
        assertThat(action).isNull();
    }

    @Test void parse_with_null_deadline() {
        var input = """
            I'll handle it when convenient.
            ```json
            {"action": "make_commitment", "description": "Review the deployment logs", "deadline": null}
            ```
            """;
        var action = ActionParser.parse(input);

        assertThat(action).isInstanceOf(ActionParser.AgentAction.MakeCommitment.class);
        var commit = (ActionParser.AgentAction.MakeCommitment) action;
        assertThat(commit.description()).isEqualTo("Review the deployment logs");
        assertThat(commit.deadline()).isNull();
    }
}
