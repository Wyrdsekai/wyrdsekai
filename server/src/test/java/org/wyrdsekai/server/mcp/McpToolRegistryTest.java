package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRegistryTest {

    private McpToolRegistry registry;
    private ObjectMapper mapper;

    @BeforeEach void setUp() {
        registry = new McpToolRegistry();
        mapper = new ObjectMapper();
    }

    @Test void listTools_returns_builtin_tools() {
        var tools = registry.listTools();
        assertThat(tools).isNotEmpty();
        assertThat(tools.stream().map(McpToolRegistry.ToolDef::name))
            .contains("room.look", "room.say", "room.move", "agent.status");
    }

    @Test void call_room_say_returns_text() {
        var args = mapper.createObjectNode();
        args.put("text", "hello world");
        var result = registry.call("room.say", args);
        assertThat(result.toString()).contains("hello world");
    }

    @Test void call_unknown_tool_returns_error() {
        var result = registry.call("nonexistent", mapper.createObjectNode());
        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test void tool_definitions_have_schemas() {
        for (var tool : registry.listTools()) {
            assertThat(tool.inputSchema()).isNotNull();
            assertThat(tool.inputSchema().path("type").asText()).isEqualTo("object");
        }
    }

    @Test void call_room_move_with_direction() {
        var args = mapper.createObjectNode();
        args.put("direction", "north");
        var result = registry.call("room.move", args);
        assertThat(result.toString()).contains("north");
    }

    @Test void register_custom_tool() {
        var schema = mapper.createObjectNode().put("type", "object");
        registry.register("custom.tool", "A custom tool", schema,
            args -> {
                var r = mapper.createObjectNode();
                r.putArray("content").addObject().put("type", "text").put("text", "custom result");
                return r;
            });
        assertThat(registry.listTools().stream().map(McpToolRegistry.ToolDef::name))
            .contains("custom.tool");
        var result = registry.call("custom.tool", mapper.createObjectNode());
        assertThat(result.toString()).contains("custom result");
    }
}
