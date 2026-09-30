package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool call becomes {"action": <tool>, ...its arguments}. A connected service's tool with an
 * argument called "action" overwrote the tool's name that way, and the call went nowhere
 * (2026-09-29). Its arguments are kept apart under "arguments" instead.
 */
class AnMcpToolKeepsItsNameTest {

    private static String fold(String tool, Map<String, Object> args) {
        var msg = new InferenceClient.ChatMessage("assistant", "",
            List.of(new InferenceClient.ToolCall("c1", "function", new InferenceClient.ToolCallFunction(tool, args))), null);
        return InferenceRouter.foldedContent(new InferenceClient.ChatResponse("id", "chat.completion", 0L, "m",
            List.of(new InferenceClient.Choice(0, msg, "stop")), null));
    }

    @Test
    void anMcpToolsOwnActionArgumentDoesNotReplaceTheToolsName() throws Exception {
        var node = Json.mapper().readTree(fold("mcp__calendar__events", Map.of("action", "list", "day", "today")));
        assertThat(node.path("action").asText()).isEqualTo("mcp__calendar__events");
        assertThat(node.path(InferenceRouter.MCP_ARGUMENTS).path("action").asText()).isEqualTo("list");
        assertThat(node.path(InferenceRouter.MCP_ARGUMENTS).path("day").asText()).isEqualTo("today");
    }

    @Test
    void otherCallsAreSpreadAsBefore() throws Exception {
        var node = Json.mapper().readTree(fold("mcp__svc__lookup", Map.of("word", "saudade")));
        assertThat(node.path("action").asText()).isEqualTo("mcp__svc__lookup");
        assertThat(node.path("word").asText()).isEqualTo("saudade");
        assertThat(node.has(InferenceRouter.MCP_ARGUMENTS)).isFalse();
    }
}
