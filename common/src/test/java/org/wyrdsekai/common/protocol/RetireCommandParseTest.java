package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * There has to be a way to get rid of a thing.
 *
 * <p>{@code drop} leaves the item in the room, so nothing could ever be removed — the
 * Nexus ended up holding two objects called {@code codex} with no way to be rid of either
 * (household node, 2026-08-20). {@code retire} is the counterpart, and it accepts the
 * words a person actually reaches for: a command you cannot guess the name of is a command
 * that does not exist.
 */
class RetireCommandParseTest {

    private static String retired(String input) {
        var cmd = CommandParser.parse(input);
        assertThat(cmd).as(input).isInstanceOf(CommandParser.ParsedCommand.Retire.class);
        return ((CommandParser.ParsedCommand.Retire) cmd).objectName();
    }

    @Test
    void retire_names_the_thing_to_remove() {
        assertThat(retired("retire librarian")).isEqualTo("librarian");
    }

    @Test
    void the_words_a_person_actually_reaches_for_all_work() {
        assertThat(retired("destroy codex")).isEqualTo("codex");
        assertThat(retired("discard codex")).isEqualTo("codex");
    }

    @Test
    void a_multi_word_name_survives_intact() {
        // Room objects legitimately have names like "stone water vessel".
        assertThat(retired("retire stone water vessel")).isEqualTo("stone water vessel");
    }

    @Test
    void the_bare_verb_is_not_a_retire_command() {
        // "retire" alone must not parse as retiring nothing — it should fall through to
        // whatever the parser does with an unknown line, not silently target an empty name.
        assertThat(CommandParser.parse("retire"))
            .isNotInstanceOf(CommandParser.ParsedCommand.Retire.class);
    }

    @Test
    void drop_still_means_drop() {
        // Retiring must not swallow the command it complements.
        assertThat(CommandParser.parse("drop codex"))
            .isInstanceOf(CommandParser.ParsedCommand.Drop.class);
    }
}
