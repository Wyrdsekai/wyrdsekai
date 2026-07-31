package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson-backed JSON helpers powering
 * {@code world.json.*}. Focus: parse round-trips, depth/size guards,
 * JSONPath traversal, deep merge, RFC-6902 diff.
 */
class ItemJsonHelperTest {

    @Test
    void parse_round_trips_typical_object() {
        Object parsed = ItemJsonHelper.parse("{\"name\":\"Ember\",\"age\":3,\"likes\":[\"fire\",\"books\"]}");
        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var m = (Map<String, Object>) parsed;
        assertThat(m.get("name")).isEqualTo("Ember");
        assertThat(m.get("age")).isEqualTo(3);
        assertThat(m.get("likes")).isEqualTo(List.of("fire", "books"));
    }

    @Test
    void parse_invalid_returns_error_map() {
        var parsed = ItemJsonHelper.parse("{not valid json");
        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var m = (Map<String, Object>) parsed;
        assertThat(m).containsKey("error");
        assertThat((String) m.get("error")).contains("json.parse failed");
    }

    @Test
    void parse_oversized_input_rejected() {
        var huge = "x".repeat(ItemJsonHelper.MAX_INPUT_BYTES + 1);
        var parsed = ItemJsonHelper.parse(huge);
        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var m = (Map<String, Object>) parsed;
        assertThat((String) m.get("error")).contains("exceeds");
    }

    @Test
    void parse_deep_nesting_rejected() {
        var sb = new StringBuilder();
        for (int i = 0; i < ItemJsonHelper.MAX_DEPTH + 5; i++) sb.append("[");
        sb.append("1");
        for (int i = 0; i < ItemJsonHelper.MAX_DEPTH + 5; i++) sb.append("]");
        var parsed = ItemJsonHelper.parse(sb.toString());
        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var m = (Map<String, Object>) parsed;
        assertThat(m).containsKey("error");
    }

    @Test
    void stringify_pretty_vs_compact() {
        var obj = Map.of("a", 1);
        var compact = ItemJsonHelper.stringify(obj, false);
        var pretty = ItemJsonHelper.stringify(obj, true);
        assertThat(compact).isEqualTo("{\"a\":1}");
        assertThat(pretty).contains("\n");
    }

    @Test
    void stringify_handles_null_gracefully() {
        assertThat(ItemJsonHelper.stringify(null, false)).isEqualTo("null");
    }

    @Test
    void path_traverses_nested_object() {
        var root = Map.of(
            "user", Map.of(
                "name", "Ember",
                "tags", List.of("fire", "study")));
        assertThat(ItemJsonHelper.path(root, "$.user.name")).isEqualTo("Ember");
        assertThat(ItemJsonHelper.path(root, "$.user.tags[0]")).isEqualTo("fire");
        assertThat(ItemJsonHelper.path(root, "user.tags[1]")).isEqualTo("study");
    }

    @Test
    void path_returns_null_for_missing() {
        assertThat(ItemJsonHelper.path(Map.of("a", 1), "$.b.c")).isNull();
        assertThat(ItemJsonHelper.path(Map.of("a", List.of(1, 2)), "$.a[5]")).isNull();
    }

    @Test
    void path_root_returns_root() {
        var root = Map.of("a", 1);
        assertThat(ItemJsonHelper.path(root, "$")).isEqualTo(root);
    }

    @Test
    void merge_deep_merges_objects() {
        var a = Map.of("x", 1, "nested", Map.of("p", 1, "q", 2));
        var b = Map.of("y", 2, "nested", Map.of("q", 99, "r", 3));
        @SuppressWarnings("unchecked")
        var merged = (Map<Object, Object>) ItemJsonHelper.merge(a, b);
        assertThat(merged.get("x")).isEqualTo(1);
        assertThat(merged.get("y")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        var nested = (Map<Object, Object>) merged.get("nested");
        assertThat(nested.get("p")).isEqualTo(1);
        assertThat(nested.get("q")).isEqualTo(99);  // b wins
        assertThat(nested.get("r")).isEqualTo(3);
    }

    @Test
    void merge_b_wins_on_scalar_conflicts() {
        assertThat(ItemJsonHelper.merge("a", "b")).isEqualTo("b");
        assertThat(ItemJsonHelper.merge(Map.of("k", 1), Map.of("k", 99)))
            .isEqualTo(Map.of("k", 99));
    }

    @Test
    void diff_emits_replace_for_changed_value() {
        var a = Map.of("x", 1, "y", 2);
        var b = Map.of("x", 1, "y", 99);
        var ops = ItemJsonHelper.diff(a, b);
        assertThat(ops).hasSize(1);
        var op = ops.getFirst();
        assertThat(op.get("op")).isEqualTo("replace");
        assertThat((String) op.get("path")).contains("y");
        assertThat(op.get("value")).isEqualTo(99);
    }

    @Test
    void diff_emits_add_for_new_key() {
        var a = Map.of("x", 1);
        var b = Map.of("x", 1, "y", 2);
        var ops = ItemJsonHelper.diff(a, b);
        assertThat(ops).hasSize(1);
        assertThat(ops.getFirst().get("op")).isEqualTo("add");
        assertThat((String) ops.getFirst().get("path")).contains("y");
    }

    @Test
    void diff_emits_remove_for_dropped_key() {
        var a = Map.of("x", 1, "y", 2);
        var b = Map.of("x", 1);
        var ops = ItemJsonHelper.diff(a, b);
        assertThat(ops).hasSize(1);
        assertThat(ops.getFirst().get("op")).isEqualTo("remove");
        assertThat((String) ops.getFirst().get("path")).contains("y");
    }

    @Test
    void diff_returns_empty_for_equal_inputs() {
        assertThat(ItemJsonHelper.diff(Map.of("a", 1), Map.of("a", 1))).isEmpty();
    }
}
