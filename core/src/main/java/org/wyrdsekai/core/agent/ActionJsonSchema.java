package org.wyrdsekai.core.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema for Ollama structured output when the agent needs to emit an action.
 * Used for plan-advance inference calls where we need valid JSON, not prose + action.
 *
 * <p>The schema constrains Ollama's output to a valid action object with
 * "action" string + action-specific fields. Ollama converts this to GBNF
 * grammar internally for constrained token sampling.</p>
 */
public final class ActionJsonSchema {

    private ActionJsonSchema() {}

    /**
     * Build a JSON Schema object that constrains output to a valid action.
     * Returned as a Map that Jackson can serialize to JSON for the Ollama format field.
     */
    public static Map<String, Object> build() {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");

        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of("type", "string"));
        properties.put("target", Map.of("type", "string"));
        properties.put("reason", Map.of("type", "string"));
        properties.put("query", Map.of("type", "string"));
        properties.put("message", Map.of("type", "string"));
        properties.put("outcome", Map.of("type", "string"));
        properties.put("description", Map.of("type", "string"));
        properties.put("text", Map.of("type", "string"));

        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return schema;
    }

    /**
     * Build a focused schema for a specific action type.
     * Used when the plan knows exactly what action is needed.
     */
    public static Map<String, Object> forAction(String actionType) {
        return switch (actionType) {
            case "library_search" -> Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("library_search")),
                    "query", Map.of("type", "string")
                ),
                "required", List.of("action", "query")
            );
            case "go_to_room" -> Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("go_to_room")),
                    "target", Map.of("type", "string"),
                    "reason", Map.of("type", "string")
                ),
                "required", List.of("action", "target")
            );
            case "tell_agent" -> Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("tell_agent")),
                    "target", Map.of("type", "string"),
                    "message", Map.of("type", "string")
                ),
                "required", List.of("action", "target", "message")
            );
            case "web_search" -> Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("web_search")),
                    "query", Map.of("type", "string"),
                    "type", Map.of("type", "string")
                ),
                "required", List.of("action", "query")
            );
            case "read_content" -> Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("read_content")),
                    "url", Map.of("type", "string"),
                    "source", Map.of("type", "string")
                ),
                "required", List.of("action", "url")
            );
            case "query_oracle" -> Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("query_oracle")),
                    "topic", Map.of("type", "string"),
                    "analysis_type", Map.of("type", "string")
                ),
                "required", List.of("action", "topic")
            );
            case "goal_done" -> Map.of(
                "type", "object",
                "properties", Map.of(
                    "action", Map.of("type", "string", "enum", List.of("goal_done")),
                    "outcome", Map.of("type", "string")
                ),
                "required", List.of("action", "outcome")
            );
            default -> build(); // generic action schema
        };
    }
}
