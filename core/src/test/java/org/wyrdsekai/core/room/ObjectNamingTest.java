package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A room is addressed by name, so a name has to be typeable and mean one thing.
 *
 * <p>Live on the household node, 2026-08-20. Two backend-authored artifacts were both
 * placed as "codex". The steward's own session, verbatim: {@code get codex} took one,
 * {@code use codex} worked, a second {@code get codex} left two in hand, and then
 * {@code use codex} answered <i>"Error [not_found]: No such object: codex"</i>. He had
 * done nothing wrong — the world had given two different things the same handle.
 */
class ObjectNamingTest {

    @Test
    void a_free_name_is_kept_exactly_as_it_is() {
        assertThat(ObjectNaming.unique("library_storyteller", List.of("crystal"), "codex"))
            .isEqualTo("library_storyteller");
    }

    @Test
    void a_second_one_gets_a_suffix_a_person_can_type() {
        // Not a hash. "library_storyteller-2" is obvious and typeable;
        // "codex-41e7c871" is neither, and that is what shipped.
        assertThat(ObjectNaming.unique(
                "library_storyteller", List.of("library_storyteller"), "codex"))
            .isEqualTo("library_storyteller-2");
    }

    @Test
    void it_keeps_counting_rather_than_colliding_again() {
        assertThat(ObjectNaming.unique("codex",
                List.of("codex", "codex-2", "codex-3"), "x"))
            .isEqualTo("codex-4");
    }

    @Test
    void matching_ignores_case_because_a_person_typing_it_will() {
        assertThat(ObjectNaming.unique("Codex", List.of("codex"), "x"))
            .isEqualTo("Codex-2");
    }

    @Test
    void a_path_or_filename_becomes_a_plain_name() {
        assertThat(ObjectNaming.normalise("/workspace/library_storyteller.js"))
            .isEqualTo("library_storyteller");
        assertThat(ObjectNaming.normalise("My Great Tool!.js")).isEqualTo("My_Great_Tool");
    }

    @Test
    void a_multi_word_name_survives_untouched() {
        // The world is addressed by exactly these strings. An earlier cut ran every name
        // through normalise(), turning "mode dial" into "mode_dial" — which broke
        // `use mode dial set on loud`, because the split between name and args stopped
        // matching anything. normalise() is for turning a PATH into a name; a name that
        // is already a name must be left alone.
        assertThat(ObjectNaming.unique("mode dial", List.of("crystal"), "x"))
            .isEqualTo("mode dial");
        assertThat(ObjectNaming.unique("stone water vessel", List.of(), "x"))
            .isEqualTo("stone water vessel");
        assertThat(ObjectNaming.unique("test console", List.of("test console"), "x"))
            .as("and a collision still suffixes without mangling")
            .isEqualTo("test console-2");
    }

    @Test
    void a_missing_name_falls_back_rather_than_producing_an_empty_handle() {
        // An object with no name cannot be picked up at all.
        assertThat(ObjectNaming.unique(null, List.of(), "codex-41e7c871"))
            .isEqualTo("codex-41e7c871");
        assertThat(ObjectNaming.unique("   ", List.of(), null)).isEqualTo("thing");
    }
}
