package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandParserTest {

    // --- Null/blank ---

    @Test void parse_null_returns_null() {
        assertThat(CommandParser.parse(null)).isNull();
    }

    @Test void parse_blank_returns_null() {
        assertThat(CommandParser.parse("   ")).isNull();
    }

    @Test void parse_empty_returns_null() {
        assertThat(CommandParser.parse("")).isNull();
    }

    // --- Say ---

    @Test void parse_say_explicit() {
        var cmd = CommandParser.parse("say hello world");
        assertThat(cmd).isEqualTo(new ParsedCommand.Say("hello world"));
    }

    @Test void parse_bare_text_as_unknown() {
        var cmd = CommandParser.parse("hello world");
        assertThat(cmd).isEqualTo(new ParsedCommand.Unknown("hello world"));
    }

    @Test void parse_say_preserves_case() {
        var cmd = CommandParser.parse("say Hello World");
        assertThat(cmd).isEqualTo(new ParsedCommand.Say("Hello World"));
    }

    // --- Direction navigation ---

    @Test void parse_direction_full_north() {
        var cmd = CommandParser.parse("north");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("north"));
    }

    @Test void parse_direction_full_southwest() {
        var cmd = CommandParser.parse("southwest");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("southwest"));
    }

    @Test void parse_direction_abbrev_n() {
        var cmd = CommandParser.parse("n");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("north"));
    }

    @Test void parse_direction_abbrev_ne() {
        var cmd = CommandParser.parse("ne");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("northeast"));
    }

    @Test void parse_direction_abbrev_sw() {
        var cmd = CommandParser.parse("sw");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("southwest"));
    }

    @Test void parse_go_command() {
        var cmd = CommandParser.parse("go north");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("north"));
    }

    @Test void parse_go_with_abbreviation() {
        var cmd = CommandParser.parse("go e");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("east"));
    }

    // --- Look ---

    @Test void parse_look() {
        var cmd = CommandParser.parse("look");
        assertThat(cmd).isInstanceOf(ParsedCommand.Look.class);
    }

    @Test void parse_l_alias() {
        var cmd = CommandParser.parse("l");
        assertThat(cmd).isInstanceOf(ParsedCommand.Look.class);
    }

    // --- Look at / Examine (→ Examine) ---
    // Semantics fix (2026-05-23): "look at X" / "examine X" parse to ParsedCommand.Examine,
    // not Use. The two verbs are distinct in the action-menu redesign — examine reveals
    // *what an object is*, use *invokes* it. See RoomActor.onSelectHint case "examine"
    // and. The Examine target is uninterpreted; resolution happens
    // in ExamineLookup against entities + objects + self.

    @Test void parse_look_at_object() {
        var cmd = CommandParser.parse("look at card catalog");
        assertThat(cmd).isEqualTo(new ParsedCommand.Examine("card catalog"));
    }

    @Test void parse_look_at_case_insensitive() {
        var cmd = CommandParser.parse("Look At sword");
        assertThat(cmd).isEqualTo(new ParsedCommand.Examine("sword"));
    }

    @Test void parse_l_at_shorthand() {
        var cmd = CommandParser.parse("l at card catalog");
        assertThat(cmd).isEqualTo(new ParsedCommand.Examine("card catalog"));
    }

    @Test void parse_examine_object() {
        var cmd = CommandParser.parse("examine card catalog");
        assertThat(cmd).isEqualTo(new ParsedCommand.Examine("card catalog"));
    }

    @Test void parse_examine_case_insensitive() {
        var cmd = CommandParser.parse("Examine lever");
        assertThat(cmd).isEqualTo(new ParsedCommand.Examine("lever"));
    }

    @Test void parse_ex_shorthand() {
        var cmd = CommandParser.parse("ex crystal");
        assertThat(cmd).isEqualTo(new ParsedCommand.Examine("crystal"));
    }

    @Test void parse_bare_look_still_returns_look() {
        // Ensure "look" without "at" still returns Look, not Use
        var cmd = CommandParser.parse("look");
        assertThat(cmd).isInstanceOf(ParsedCommand.Look.class);
    }

    @Test void parse_bare_l_still_returns_look() {
        var cmd = CommandParser.parse("l");
        assertThat(cmd).isInstanceOf(ParsedCommand.Look.class);
    }

    // --- Take / Get ---

    @Test void parse_take() {
        var cmd = CommandParser.parse("take golden key");
        assertThat(cmd).isEqualTo(new ParsedCommand.Take("golden key"));
    }

    @Test void parse_get_alias() {
        var cmd = CommandParser.parse("get sword");
        assertThat(cmd).isEqualTo(new ParsedCommand.Take("sword"));
    }

    // --- Drop ---

    @Test void parse_drop() {
        var cmd = CommandParser.parse("drop golden key");
        assertThat(cmd).isEqualTo(new ParsedCommand.Drop("golden key"));
    }

    // --- Use ---

    @Test void parse_use_simple() {
        var cmd = CommandParser.parse("use lever");
        assertThat(cmd).isEqualTo(new ParsedCommand.Use("lever", null));
    }

    @Test void parse_use_on_target() {
        var cmd = CommandParser.parse("use key on door");
        assertThat(cmd).isEqualTo(new ParsedCommand.Use("key", "door"));
    }

    // --- Hint selection ---

    @Test void parse_hint_select_single_digit() {
        var cmd = CommandParser.parse("1");
        assertThat(cmd).isEqualTo(new ParsedCommand.HintSelect(0));
    }

    @Test void parse_hint_select_double_digit() {
        var cmd = CommandParser.parse("12");
        assertThat(cmd).isEqualTo(new ParsedCommand.HintSelect(11));
    }

    // --- Slash commands ---

    @Test void parse_slash_command_no_args() {
        var cmd = CommandParser.parse("/help");
        assertThat(cmd).isEqualTo(new ParsedCommand.SlashCommand("help", List.of()));
    }

    @Test void parse_slash_command_with_args() {
        var cmd = CommandParser.parse("/grant alice enter");
        assertThat(cmd).isEqualTo(new ParsedCommand.SlashCommand("grant", List.of("alice", "enter")));
    }

    // --- Quit ---

    @Test void parse_quit() {
        var cmd = CommandParser.parse("/quit");
        assertThat(cmd).isInstanceOf(ParsedCommand.Quit.class);
    }

    @Test void parse_exit() {
        var cmd = CommandParser.parse("/exit");
        assertThat(cmd).isInstanceOf(ParsedCommand.Quit.class);
    }

    // --- Inventory alias ---

    @Test void parse_inventory_alias() {
        var cmd = CommandParser.parse("i");
        assertThat(cmd).isEqualTo(new ParsedCommand.SlashCommand("inventory", List.of()));
    }

    @Test void parse_inventory_full() {
        var cmd = CommandParser.parse("inventory");
        assertThat(cmd).isEqualTo(new ParsedCommand.SlashCommand("inventory", List.of()));
    }

    // --- Direction expansion ---

    @Test void expandDirection_all_abbreviations() {
        assertThat(CommandParser.expandDirection("n")).isEqualTo("north");
        assertThat(CommandParser.expandDirection("s")).isEqualTo("south");
        assertThat(CommandParser.expandDirection("e")).isEqualTo("east");
        assertThat(CommandParser.expandDirection("w")).isEqualTo("west");
        assertThat(CommandParser.expandDirection("u")).isEqualTo("up");
        assertThat(CommandParser.expandDirection("d")).isEqualTo("down");
        assertThat(CommandParser.expandDirection("ne")).isEqualTo("northeast");
        assertThat(CommandParser.expandDirection("nw")).isEqualTo("northwest");
        assertThat(CommandParser.expandDirection("se")).isEqualTo("southeast");
        assertThat(CommandParser.expandDirection("sw")).isEqualTo("southwest");
    }

    @Test void expandDirection_already_expanded() {
        assertThat(CommandParser.expandDirection("north")).isEqualTo("north");
    }

    @Test void expandDirection_unknown_passes_through() {
        assertThat(CommandParser.expandDirection("portal")).isEqualTo("portal");
    }

    // --- Home / world-center verbs ---
    //
    // `home`, `return`, `study`, `office` are the four aliases for "go to my
    // own Home from anywhere". All must parse identically — if any diverge
    // we'd end up with inconsistent UX between telnet/CLI/web.

    @Test void parse_home_verb_goes_to_office() {
        assertThat(CommandParser.parse("home"))
            .isInstanceOf(ParsedCommand.Office.class);
    }

    @Test void parse_return_verb_goes_to_office() {
        // `return` is the canonical "come back" alias from.
        // Today the parser only recognises home/study/office — that gap is a
        // bug that keeps telnet/CLI users from matching the web semantics.
        assertThat(CommandParser.parse("return"))
            .isInstanceOf(ParsedCommand.Office.class);
    }

    @Test void parse_study_verb_goes_to_office() {
        assertThat(CommandParser.parse("study"))
            .isInstanceOf(ParsedCommand.Office.class);
    }

    @Test void parse_office_verb_goes_to_office() {
        assertThat(CommandParser.parse("office"))
            .isInstanceOf(ParsedCommand.Office.class);
    }

    @Test void parse_home_is_case_insensitive() {
        assertThat(CommandParser.parse("HOME"))
            .isInstanceOf(ParsedCommand.Office.class);
        assertThat(CommandParser.parse("Home"))
            .isInstanceOf(ParsedCommand.Office.class);
    }
}
