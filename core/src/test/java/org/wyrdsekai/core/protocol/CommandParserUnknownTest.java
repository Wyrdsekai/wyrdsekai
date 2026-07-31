package org.wyrdsekai.core.protocol;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CommandParser auto-say removal and MUD prefix conventions.
 *
 * Standard MUD behavior:
 * - 'text → say
 * - "text → say
 * - :text → emote
 * - ;text → emote
 * - bare unrecognized text → Unknown (NOT say)
 */
class CommandParserUnknownTest {

    @Test
    void apostrophe_prefix_is_say() {
        var result = CommandParser.parse("'hello everyone");
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
        assertThat(((ParsedCommand.Say) result).text()).isEqualTo("hello everyone");
    }

    @Test
    void double_quote_prefix_is_say() {
        var result = CommandParser.parse("\"good morning");
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
        assertThat(((ParsedCommand.Say) result).text()).isEqualTo("good morning");
    }

    @Test
    void say_word_prefix_is_say() {
        var result = CommandParser.parse("say hello there");
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
        assertThat(((ParsedCommand.Say) result).text()).isEqualTo("hello there");
    }

    @Test
    void colon_prefix_is_emote() {
        var result = CommandParser.parse(":waves");
        assertThat(result).isInstanceOf(ParsedCommand.Emote.class);
        assertThat(((ParsedCommand.Emote) result).text()).isEqualTo("waves");
    }

    @Test
    void semicolon_prefix_is_emote() {
        var result = CommandParser.parse(";nods thoughtfully");
        assertThat(result).isInstanceOf(ParsedCommand.Emote.class);
        assertThat(((ParsedCommand.Emote) result).text()).isEqualTo("nods thoughtfully");
    }

    @Test
    void unrecognized_text_is_unknown_not_say() {
        var result = CommandParser.parse("xyzzy");
        assertThat(result).isInstanceOf(ParsedCommand.Unknown.class);
        assertThat(((ParsedCommand.Unknown) result).text()).isEqualTo("xyzzy");
    }

    @Test
    void random_sentence_is_unknown_not_say() {
        var result = CommandParser.parse("i want to say something");
        assertThat(result).isInstanceOf(ParsedCommand.Unknown.class);
    }

    @Test
    void known_commands_still_work() {
        assertThat(CommandParser.parse("look")).isInstanceOf(ParsedCommand.Look.class);
        assertThat(CommandParser.parse("north")).isInstanceOf(ParsedCommand.Go.class);
        assertThat(CommandParser.parse("go east")).isInstanceOf(ParsedCommand.Go.class);
        assertThat(CommandParser.parse("take sword")).isInstanceOf(ParsedCommand.Take.class);
        assertThat(CommandParser.parse("use crystal")).isInstanceOf(ParsedCommand.Use.class);
        // "help" works with or without / prefix
        assertThat(CommandParser.parse("help")).isInstanceOf(ParsedCommand.SlashCommand.class);
        assertThat(CommandParser.parse("/help")).isInstanceOf(ParsedCommand.SlashCommand.class);
    }

    @Test
    void empty_apostrophe_is_not_say() {
        // Just "'" with nothing after should fall through
        var result = CommandParser.parse("'");
        // Empty say prefix — should fall through to unknown
        assertThat(result).isInstanceOf(ParsedCommand.Unknown.class);
    }

    @Test
    void journal_command_routes_to_room_script() {
        // "journal" is routed as Say so room scripts can handle it
        var result = CommandParser.parse("journal had a good day");
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
        assertThat(((ParsedCommand.Say) result).text()).isEqualTo("journal had a good day");
    }

    @Test
    void search_command_routes_to_room_script() {
        var result = CommandParser.parse("search kubernetes");
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
    }

    @Test
    void note_command_routes_to_room_script() {
        var result = CommandParser.parse("note buy groceries");
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
    }

    @Test
    void help_recognized_without_slash() {
        var result = CommandParser.parse("help");
        assertThat(result).isInstanceOf(ParsedCommand.SlashCommand.class);
    }

    @Test
    void help_with_topic() {
        var result = CommandParser.parse("help say");
        assertThat(result).isInstanceOf(ParsedCommand.SlashCommand.class);
    }

    @Test
    void quit_recognized() {
        assertThat(CommandParser.parse("quit")).isInstanceOf(ParsedCommand.Quit.class);
        assertThat(CommandParser.parse("exit")).isInstanceOf(ParsedCommand.Quit.class);
    }

    // ── Locale alias tests ──────────────────────────────────────────

    @Test
    void japanese_look_alias() {
        var result = CommandParser.parse("見る", "ja");
        assertThat(result).isInstanceOf(ParsedCommand.Look.class);
    }

    @Test
    void japanese_go_north_alias() {
        var result = CommandParser.parse("行く 北", "ja");
        assertThat(result).isInstanceOf(ParsedCommand.Go.class);
    }

    @Test
    void japanese_direction_alias() {
        var result = CommandParser.parse("北", "ja");
        assertThat(result).isInstanceOf(ParsedCommand.Go.class);
        assertThat(((ParsedCommand.Go) result).direction()).isEqualTo("north");
    }

    @Test
    void japanese_journal_alias() {
        var result = CommandParser.parse("日記 今日は良い日だった", "ja");
        assertThat(result).isInstanceOf(ParsedCommand.Say.class); // routes to room script
        assertThat(((ParsedCommand.Say) result).text()).contains("journal");
    }

    @Test
    void japanese_help_alias() {
        var result = CommandParser.parse("ヘルプ", "ja");
        assertThat(result).isInstanceOf(ParsedCommand.SlashCommand.class);
    }

    @Test
    void spanish_look_alias() {
        var result = CommandParser.parse("mirar", "es");
        assertThat(result).isInstanceOf(ParsedCommand.Look.class);
    }

    @Test
    void spanish_go_direction() {
        var result = CommandParser.parse("ir norte", "es");
        assertThat(result).isInstanceOf(ParsedCommand.Go.class);
    }

    @Test
    void spanish_help_alias() {
        var result = CommandParser.parse("ayuda", "es");
        assertThat(result).isInstanceOf(ParsedCommand.SlashCommand.class);
    }

    @Test
    void english_locale_no_expansion() {
        // English input with "en" locale should not expand
        var result = CommandParser.parse("北", "en");
        assertThat(result).isInstanceOf(ParsedCommand.Unknown.class);
    }

    @Test
    void null_locale_defaults_to_english() {
        var result = CommandParser.parse("look", null);
        assertThat(result).isInstanceOf(ParsedCommand.Look.class);
    }

    // ── User-defined alias tests ─────────────────────────────────

    @Test
    void alias_command_define() {
        var result = CommandParser.parse("alias la look at");
        assertThat(result).isInstanceOf(ParsedCommand.Alias.class);
        var alias = (ParsedCommand.Alias) result;
        assertThat(alias.name()).isEqualTo("la");
        assertThat(alias.expansion()).isEqualTo("look at");
    }

    @Test
    void alias_command_list_all() {
        var result = CommandParser.parse("alias");
        assertThat(result).isInstanceOf(ParsedCommand.Alias.class);
        assertThat(((ParsedCommand.Alias) result).name()).isNull();
    }

    @Test
    void unalias_command() {
        var result = CommandParser.parse("unalias la");
        assertThat(result).isInstanceOf(ParsedCommand.Unalias.class);
        assertThat(((ParsedCommand.Unalias) result).name()).isEqualTo("la");
    }

    @Test
    void user_alias_expands() {
        var userAliases = Map.of("la", "look at", "k", "go north");

        var result = CommandParser.parse("la crystal", "en", userAliases);
        // "look at <target>" parses as Examine (line 215 of CommandParser.java
        // routes look-at to examine — this contract changed from Use to Examine).
        assertThat(result).isInstanceOf(ParsedCommand.Examine.class);

        var result2 = CommandParser.parse("k", "en", userAliases);
        assertThat(result2).isInstanceOf(ParsedCommand.Go.class);
        assertThat(((ParsedCommand.Go) result2).direction()).isEqualTo("north");
    }

    @Test
    void user_alias_priority_over_locale() {
        var userAliases = Map.of("norte", "go south");
        var result = CommandParser.parse("norte", "es", userAliases);
        assertThat(result).isInstanceOf(ParsedCommand.Go.class);
        assertThat(((ParsedCommand.Go) result).direction()).isEqualTo("south");
    }

    @Test
    void user_alias_with_arguments() {
        var userAliases = Map.of("j", "journal");
        var result = CommandParser.parse("j had a good day", "en", userAliases);
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
        assertThat(((ParsedCommand.Say) result).text()).contains("journal had a good day");
    }

    // ── Original tests ────────────────────────────────────────────

    @Test
    void emote_word_prefix_works() {
        var result = CommandParser.parse("emote stretches and yawns");
        assertThat(result).isInstanceOf(ParsedCommand.Emote.class);
        assertThat(((ParsedCommand.Emote) result).text()).isEqualTo("stretches and yawns");
    }

    @Test
    void tell_still_works() {
        var result = CommandParser.parse("tell ember hello there");
        assertThat(result).isInstanceOf(ParsedCommand.Tell.class);
        assertThat(((ParsedCommand.Tell) result).targetName()).isEqualTo("ember");
    }
}
