package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import static org.assertj.core.api.Assertions.assertThat;

class ActionSchemasTest {

    private static JsonNode parse(String json) {
        try {
            return Json.mapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- go_to_room ---

    @Test void valid_go_to_room_passes() {
        var node = parse("""
            {"action": "go_to_room", "target": "workshop", "reason": "check artifacts"}
            """);
        var errors = ActionSchemas.validate("go_to_room", node);
        assertThat(errors).isEmpty();
    }

    @Test void go_to_room_without_reason_passes() {
        var node = parse("""
            {"action": "go_to_room", "target": "north"}
            """);
        var errors = ActionSchemas.validate("go_to_room", node);
        assertThat(errors).isEmpty();
    }

    @Test void missing_required_field_fails() {
        var node = parse("""
            {"action": "go_to_room", "reason": "just because"}
            """);
        var errors = ActionSchemas.validate("go_to_room", node);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("target");
    }

    @Test void blank_required_field_fails() {
        var node = parse("""
            {"action": "go_to_room", "target": "  "}
            """);
        var errors = ActionSchemas.validate("go_to_room", node);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("target").contains("blank");
    }

    @Test void extra_fields_allowed() {
        var node = parse("""
            {"action": "go_to_room", "target": "nexus", "speed": "fast", "mood": "excited"}
            """);
        var errors = ActionSchemas.validate("go_to_room", node);
        assertThat(errors).isEmpty();
    }

    // --- library_search ---

    @Test void valid_library_search_passes() {
        var node = parse("""
            {"action": "library_search", "query": "quantum mechanics", "collections": ["physics"]}
            """);
        var errors = ActionSchemas.validate("library_search", node);
        assertThat(errors).isEmpty();
    }

    @Test void missing_query_in_library_search_fails() {
        var node = parse("""
            {"action": "library_search", "collections": ["physics"]}
            """);
        var errors = ActionSchemas.validate("library_search", node);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("query");
    }

    @Test void library_search_without_collections_passes() {
        var node = parse("""
            {"action": "library_search", "query": "relativity"}
            """);
        var errors = ActionSchemas.validate("library_search", node);
        assertThat(errors).isEmpty();
    }

    // --- calibration_feedback ---

    @Test void valid_calibration_feedback_passes() {
        var node = parse("""
            {"action": "calibration_feedback", "feedback_type": "timing", "direction": "sooner", "reason": "too slow"}
            """);
        var errors = ActionSchemas.validate("calibration_feedback", node);
        assertThat(errors).isEmpty();
    }

    @Test void calibration_feedback_missing_direction_fails() {
        var node = parse("""
            {"action": "calibration_feedback", "feedback_type": "timing"}
            """);
        var errors = ActionSchemas.validate("calibration_feedback", node);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("direction");
    }

    // --- unknown action ---

    @Test void unknown_action_skipped() {
        var node = parse("""
            {"action": "some_future_action", "data": "anything"}
            """);
        var errors = ActionSchemas.validate("some_future_action", node);
        assertThat(errors).isEmpty();
    }

    // --- tell_agent ---

    @Test void valid_tell_agent_passes() {
        var node = parse("""
            {"action": "tell_agent", "target": "Ember", "message": "Hello there"}
            """);
        var errors = ActionSchemas.validate("tell_agent", node);
        assertThat(errors).isEmpty();
    }

    @Test void tell_agent_missing_message_fails() {
        var node = parse("""
            {"action": "tell_agent", "target": "Ember"}
            """);
        var errors = ActionSchemas.validate("tell_agent", node);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("message");
    }

    @Test void tell_agent_missing_both_fields_fails() {
        var node = parse("""
            {"action": "tell_agent"}
            """);
        var errors = ActionSchemas.validate("tell_agent", node);
        assertThat(errors).hasSize(2);
    }

    // --- type mismatch ---

    @Test void wrong_type_for_required_field_fails() {
        var node = parse("""
            {"action": "delegate_chain", "goal": "deploy", "steps": "not-an-array"}
            """);
        var errors = ActionSchemas.validate("delegate_chain", node);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("steps").contains("array");
    }

    @Test void wrong_type_number_field_fails() {
        var node = parse("""
            {"action": "go_to_room", "target": 42}
            """);
        var errors = ActionSchemas.validate("go_to_room", node);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("target").contains("string");
    }

    // --- null inputs ---

    @Test void null_action_name_returns_empty() {
        var node = parse("""
            {"action": "go_to_room", "target": "north"}
            """);
        var errors = ActionSchemas.validate(null, node);
        assertThat(errors).isEmpty();
    }

    @Test void null_node_returns_empty() {
        var errors = ActionSchemas.validate("go_to_room", null);
        assertThat(errors).isEmpty();
    }

    // --- hasSchema ---

    @Test void hasSchema_for_known_action() {
        assertThat(ActionSchemas.hasSchema("go_to_room")).isTrue();
        assertThat(ActionSchemas.hasSchema("tell_agent")).isTrue();
        assertThat(ActionSchemas.hasSchema("library_search")).isTrue();
    }

    @Test void hasSchema_for_unknown_action() {
        assertThat(ActionSchemas.hasSchema("teleport_to_moon")).isFalse();
        assertThat(ActionSchemas.hasSchema(null)).isFalse();
    }

    // --- integration: validation rejects in ActionParser ---

    @Test void actionParser_skips_invalid_action() {
        // go_to_room without required "target" — should be rejected by schema validation
        var input = """
            Moving on.
            ```json
            {"action": "go_to_room", "reason": "just because"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isNull();
    }

    @Test void actionParser_accepts_valid_action() {
        var input = """
            Let me go.
            ```json
            {"action": "go_to_room", "target": "north"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.GoToRoom.class);
    }
}
