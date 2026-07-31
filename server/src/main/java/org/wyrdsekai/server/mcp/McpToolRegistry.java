package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of MCP tools available to clients.
 * Each tool has a name, description, input schema, and handler.
 */
public class McpToolRegistry {

    public record ToolDef(String name, String description, JsonNode inputSchema) {}

    @FunctionalInterface
    public interface ToolHandler {
        JsonNode call(JsonNode arguments);
    }

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    public McpToolRegistry() {
        registerBuiltinTools();
    }

    /** Register a tool. */
    public void register(String name, String description, JsonNode inputSchema, ToolHandler handler) {
        tools.put(name, new ToolDef(name, description, inputSchema));
        handlers.put(name, handler);
    }

    /** List all registered tools. */
    public List<ToolDef> listTools() {
        return new ArrayList<>(tools.values());
    }

    /** Call a tool by name. */
    public JsonNode call(String name, JsonNode arguments) {
        var handler = handlers.get(name);
        if (handler == null) {
            var error = mapper.createObjectNode();
            error.put("isError", true);
            error.putArray("content").addObject()
                .put("type", "text")
                .put("text", "Unknown tool: " + name);
            return error;
        }
        return handler.call(arguments);
    }

    private void registerBuiltinTools() {
        // room.look — describe the current room
        register("room.look", "Look at the current room, describing its contents and exits",
            schemaOf(Map.of("room_id", "string")),
            args -> textResult("You are in a room. Use this tool with a connected MCP session for live room data."));

        // room.say — speak in the current room
        register("room.say", "Say something in the current room",
            schemaOf(Map.of("text", "string")),
            args -> {
                var text = args.path("text").asText("[silence]");
                return textResult("Said: " + text);
            });

        // room.move — move to an adjacent room
        register("room.move", "Move to an adjacent room via an exit direction",
            schemaOf(Map.of("direction", "string")),
            args -> {
                var dir = args.path("direction").asText("north");
                return textResult("Moving " + dir + ". Use this tool with a connected MCP session for live navigation.");
            });

        // agent.status — get agent vitality status
        register("agent.status", "Get the status of an agent including vitality and location",
            schemaOf(Map.of("agent_id", "string")),
            args -> textResult("Agent status requires a live MCP session."));

        // world.rooms — list available rooms
        register("world.rooms", "List all rooms in the current zone",
            schemaOf(Map.of()),
            args -> textResult("Room listing requires a live MCP session."));

        // world.economy — get economy status
        register("world.economy", "Get the current economy and credit ledger status",
            schemaOf(Map.of()),
            args -> textResult("Economy status requires a live MCP session."));

        // world.reputation — get reputation info
        register("world.reputation", "Get reputation scores for entities in the zone",
            schemaOf(Map.of("entity_id", "string")),
            args -> textResult("Reputation data requires a live MCP session."));

        // room.create — create a new room (async)
        register("room.create", "Create a new room in the current zone",
            schemaOf(Map.of("name", "string", "description", "string")),
            args -> {
                var name = args.path("name").asText("new-room");
                return textResult("Room creation for '" + name + "' requires a live MCP session.");
            });

        // world.topology — get network topology info
        register("world.topology", "Get the network topology and node connectivity status",
            schemaOf(Map.of()),
            args -> textResult("Topology data requires a live MCP session."));

        // world.federation — get federation status
        register("world.federation", "Get federation status including bilateral agreements and transit tokens",
            schemaOf(Map.of()),
            args -> textResult("Federation data requires a live MCP session."));

        // moderation.report — file a moderation report
        register("moderation.report", "File a moderation report against an entity",
            schemaOf(Map.of("target_entity", "string", "reason", "string")),
            args -> {
                var target = args.path("target_entity").asText();
                var reason = args.path("reason").asText();
                return textResult("Report against '" + target + "' for: " + reason + ". Requires a live MCP session.");
            });

        // translation.translate — translate text
        register("translation.translate", "Translate text to a target language",
            schemaOf(Map.of("text", "string", "target_lang", "string", "source_lang", "string")),
            args -> {
                var text = args.path("text").asText();
                var targetLang = args.path("target_lang").asText("es");
                return textResult("Translation of '" + text + "' to " + targetLang + " requires a live MCP session with TranslationActor.");
            });

        // translation.detect — detect language
        register("translation.detect", "Detect the language of text",
            schemaOf(Map.of("text", "string")),
            args -> {
                var text = args.path("text").asText();
                return textResult("Language detection for '" + text + "' requires a live MCP session with TranslationActor.");
            });
    }

    private JsonNode schemaOf(Map<String, String> properties) {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        for (var entry : properties.entrySet()) {
            props.putObject(entry.getKey()).put("type", entry.getValue());
        }
        return schema;
    }

    private JsonNode textResult(String text) {
        var result = mapper.createObjectNode();
        result.putArray("content").addObject()
            .put("type", "text")
            .put("text", text);
        return result;
    }
}
