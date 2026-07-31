package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §3 contract tests for WorldModelPromptRenderer. */
class WorldModelPromptRendererTest {

    @Test
    void empty_state_renders_cleanly() {
        var r = new WorldModelPromptRenderer();
        var out = r.render(new WorldModelPromptRenderer.Inputs(
            List.of(), Map.of(), List.of()));

        assertThat(out.text()).contains("ZONE STATE MAP");
        assertThat(out.text()).contains("(no rooms loaded yet)");
        assertThat(out.text()).contains("ACTION CONSEQUENCES");
        assertThat(out.text()).contains("(no transitions observed yet — companion is fresh)");
        assertThat(out.text()).contains("KNOWN PATTERNS");
        assertThat(out.text()).contains("loop antipattern");
    }

    @Test
    void renders_rooms_with_exits_and_objects() {
        var hearth = roomSnapshot("hearth", "The Hearth",
            List.of(new Exit("nexus", "nexus", "→ nexus")),
            List.of(roomObject("drives_mirror", "Drives Mirror"),
                    roomObject("journal", "Journal"),
                    roomObject("autonomy_console", "Autonomy Console")));
        var library = roomSnapshot("library", "The Library",
            List.of(new Exit("nexus", "nexus", "→ nexus")),
            List.of(roomObject("library_card", "Library Card")));
        var r = new WorldModelPromptRenderer();
        var out = r.render(new WorldModelPromptRenderer.Inputs(
            List.of(hearth, library), Map.of(), List.of()));

        assertThat(out.text()).contains("The Hearth (hearth)");
        assertThat(out.text()).contains("nexus → nexus");
        assertThat(out.text()).contains("Drives Mirror");
        assertThat(out.text()).contains("Journal");
        assertThat(out.text()).contains("The Library (library)");
        assertThat(out.text()).contains("Library Card");
    }

    @Test
    void aggregates_action_consequences_by_action_type() {
        var transitions = Map.of(
            "k1", List.of(
                txn("library_search", "amae", true, "12 results"),
                txn("library_search", "saudade", true, "8 results"),
                txn("library_search", "wabi-sabi", true, "5 results"),
                txn("library_search", "void", false, "0 results")),
            "k2", List.of(
                txn("examine", "room", true, "saw 3 objects"),
                txn("examine", "obj", true, "description"),
                txn("examine", "self", true, "introspect")),
            "k3", List.of(
                txn("rare_action", "x", true, "happened"))   // rare — < 3 obs, should be omitted/aggregated
        );
        var r = new WorldModelPromptRenderer();
        var out = r.render(new WorldModelPromptRenderer.Inputs(
            List.of(), transitions, List.of()));

        assertThat(out.text()).contains("library_search →");
        assertThat(out.text()).contains("(n=4)");
        assertThat(out.text()).contains("examine →");
        assertThat(out.text()).contains("(n=3)");
        // Rare action falls below floor and is summarized as omitted
        assertThat(out.text()).contains("rarely-observed action types omitted");
        assertThat(out.text()).doesNotContain("rare_action →");
    }

    @Test
    void caches_render_when_inputs_unchanged() {
        var rooms = List.of(roomSnapshot("hearth", "Hearth", List.of(), List.of()));
        var r = new WorldModelPromptRenderer();
        var first = r.render(new WorldModelPromptRenderer.Inputs(rooms, Map.of(), List.of()));
        var second = r.render(new WorldModelPromptRenderer.Inputs(rooms, Map.of(), List.of()));

        // Same instance returned from cache
        assertThat(second).isSameAs(first);
    }

    @Test
    void invalidate_drops_cache() {
        var rooms = List.of(roomSnapshot("hearth", "Hearth", List.of(), List.of()));
        var r = new WorldModelPromptRenderer();
        var first = r.render(new WorldModelPromptRenderer.Inputs(rooms, Map.of(), List.of()));
        r.invalidate();
        var second = r.render(new WorldModelPromptRenderer.Inputs(rooms, Map.of(), List.of()));

        // Different instance — text matches but the cache was rebuilt
        assertThat(second).isNotSameAs(first);
        assertThat(second.text()).isEqualTo(first.text());
    }

    @Test
    void cache_invalidates_on_room_topology_change() {
        var hearth = roomSnapshot("hearth", "Hearth", List.of(), List.of());
        var library = roomSnapshot("library", "Library", List.of(), List.of());
        var r = new WorldModelPromptRenderer();

        var first = r.render(new WorldModelPromptRenderer.Inputs(
            List.of(hearth), Map.of(), List.of()));
        var afterAdd = r.render(new WorldModelPromptRenderer.Inputs(
            List.of(hearth, library), Map.of(), List.of()));

        assertThat(afterAdd).isNotSameAs(first);
        assertThat(afterAdd.text()).contains("Library");
        assertThat(first.text()).doesNotContain("Library");
    }

    @Test
    void token_budget_within_4k_for_realistic_household() {
        // 20-room household with diverse transitions — spec target ≤ 4000 tokens
        var rooms = new ArrayList<RoomSnapshot>();
        for (int i = 0; i < 20; i++) {
            var objs = new ArrayList<RoomObject>();
            for (int j = 0; j < 5; j++) {
                objs.add(roomObject("obj" + i + "_" + j, "Object " + i + "/" + j));
            }
            rooms.add(roomSnapshot("room" + i, "Room " + i,
                List.of(new Exit("nexus", "nexus", "→ nexus")), objs));
        }
        var transitions = new LinkedHashMap<String, List<WorldModel.Transition>>();
        var actionTypes = List.of("library_search", "web_search", "examine", "tell_agent",
            "go_to_room", "introspect", "write_journal", "read_content",
            "summarize", "make_commitment", "query_oracle", "remember");
        int keyCounter = 0;
        for (var act : actionTypes) {
            var list = new ArrayList<WorldModel.Transition>();
            for (int i = 0; i < 5; i++) {
                list.add(txn(act, "target_" + i, true, "outcome text " + i));
            }
            transitions.put("k" + (keyCounter++), list);
        }

        var r = new WorldModelPromptRenderer();
        var out = r.render(new WorldModelPromptRenderer.Inputs(rooms, transitions, List.of()));

        assertThat(out.approxTokens())
            .as("Renderer must stay within 4k token budget for 20-room household")
            .isLessThanOrEqualTo(4000);
    }

    @Test
    void high_failure_action_emitted_in_known_patterns() {
        var transitions = Map.of(
            "k1", List.of(
                txn("flaky_action", "x", false, "failed"),
                txn("flaky_action", "x", false, "failed"),
                txn("flaky_action", "x", true, "ok"),
                txn("flaky_action", "x", false, "failed"),
                txn("flaky_action", "x", false, "failed"))   // 4/5 fail
        );
        var r = new WorldModelPromptRenderer();
        var out = r.render(new WorldModelPromptRenderer.Inputs(
            List.of(), transitions, List.of()));

        assertThat(out.text()).contains("high-failure: flaky_action(x)");
        assertThat(out.text()).contains("4/5");
    }

    // ── helpers ──

    private static RoomSnapshot roomSnapshot(String id, String name,
                                              List<Exit> exits, List<RoomObject> objects) {
        return new RoomSnapshot(id, name, "desc", "alpha", List.of(),
            exits, List.of(), objects, List.of());
    }

    private static RoomObject roomObject(String id, String name) {
        return new RoomObject(id, name, "obj description", true);
    }

    private static WorldModel.Transition txn(String action, String target,
                                              boolean success, String outcome) {
        return new WorldModel.Transition(
            "stateA", action, target,
            success ? "stateB" : null,
            success, outcome, Instant.now());
    }
}
