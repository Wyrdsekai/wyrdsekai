package org.wyrdsekai.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** `go <query>` exit resolution — exact direction first, fuzzy destination fallback
 *  (second-node 2026-07-09: `go greenhouse` failed against `to-greenhouse-7772 → Greenhouse`). */
class ExitResolveTest {

    private final List<Exit> exits = List.of(
        new Exit("north", "nexus", "→ The Nexus"),
        new Exit("out", "nexus", "→ The Nexus"),
        new Exit("to-greenhouse-7772", "greenhouse-7772", "A path to Greenhouse"));

    @Test
    void exact_direction_wins() {
        assertThat(Exit.resolve(exits, "north")).hasValueSatisfying(
            e -> assertThat(e.direction()).isEqualTo("north"));
        assertThat(Exit.resolve(exits, "OUT")).hasValueSatisfying(
            e -> assertThat(e.direction()).isEqualTo("out"));
    }

    @Test
    void destination_name_matches_generated_exit() {
        assertThat(Exit.resolve(exits, "greenhouse")).hasValueSatisfying(
            e -> assertThat(e.targetRoom()).isEqualTo("greenhouse-7772"));
        assertThat(Exit.resolve(exits, "Greenhouse")).isPresent();
    }

    @Test
    void short_queries_do_not_fuzzy_match() {
        assertThat(Exit.resolve(exits, "gr")).isEmpty();
        assertThat(Exit.resolve(exits, "n")).isEmpty();   // no exact 'n' direction, no fuzzy
    }

    @Test
    void label_matches_too() {
        assertThat(Exit.resolve(exits, "path to")).isPresent();
    }

    @Test
    void no_match_and_null_safety() {
        assertThat(Exit.resolve(exits, "observatory")).isEmpty();
        assertThat(Exit.resolve(exits, null)).isEmpty();
        assertThat(Exit.resolve(null, "x")).isEmpty();
    }
}
