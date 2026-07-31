package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for navigation commands added in §N2:
 * map, where, nearby, rooms, exits, path.
 */
class NavigationCommandTest {

    // --- Map ---

    @Test void map_default_radius() {
        var cmd = CommandParser.parse("map");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(2));
    }

    @Test void map_custom_radius() {
        var cmd = CommandParser.parse("map 3");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(3));
    }

    @Test void map_radius_clamped_low() {
        var cmd = CommandParser.parse("map 0");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(1));
    }

    @Test void map_radius_clamped_high() {
        var cmd = CommandParser.parse("map 10");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(5));
    }

    @Test void map_non_numeric_radius_defaults_to_2() {
        var cmd = CommandParser.parse("map foo");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(2));
    }

    @Test void map_case_insensitive() {
        var cmd = CommandParser.parse("MAP 4");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(4));
    }

    @Test void bare_m_is_unknown_not_map() {
        // "m" alone is not a direction and the map guard skips bare "m",
        // so it falls through to Unknown (standard MUD behavior — bare text is not auto-say).
        var cmd = CommandParser.parse("m");
        assertThat(cmd).isEqualTo(new ParsedCommand.Unknown("m"));
    }

    @Test void m_with_radius_is_map() {
        var cmd = CommandParser.parse("m 3");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(3));
    }

    // --- Where ---

    @Test void where_command() {
        var cmd = CommandParser.parse("where");
        assertThat(cmd).isInstanceOf(ParsedCommand.Where.class);
    }

    @Test void where_case_insensitive() {
        var cmd = CommandParser.parse("WHERE");
        assertThat(cmd).isInstanceOf(ParsedCommand.Where.class);
    }

    @Test void w_is_go_west_not_where() {
        // "w" is a direction abbreviation, must NOT become Where.
        var cmd = CommandParser.parse("w");
        assertThat(cmd).isEqualTo(new ParsedCommand.Go("west"));
    }

    // --- Nearby ---

    @Test void nearby_command() {
        var cmd = CommandParser.parse("nearby");
        assertThat(cmd).isInstanceOf(ParsedCommand.Nearby.class);
    }

    // --- Rooms ---

    @Test void rooms_command() {
        var cmd = CommandParser.parse("rooms");
        assertThat(cmd).isInstanceOf(ParsedCommand.Rooms.class);
    }

    // --- Exits ---

    @Test void exits_command() {
        var cmd = CommandParser.parse("exits");
        assertThat(cmd).isInstanceOf(ParsedCommand.Exits.class);
    }

    @Test void x_alias_for_exits() {
        var cmd = CommandParser.parse("x");
        assertThat(cmd).isInstanceOf(ParsedCommand.Exits.class);
    }

    // --- Path ---

    @Test void path_to_room() {
        var cmd = CommandParser.parse("path The Library");
        assertThat(cmd).isEqualTo(new ParsedCommand.Path("The Library"));
    }

    @Test void path_to_keyword_stripped() {
        var cmd = CommandParser.parse("path to The Library");
        assertThat(cmd).isEqualTo(new ParsedCommand.Path("The Library"));
    }

    @Test void path_preserves_room_name_case() {
        var cmd = CommandParser.parse("path to Grand Hall of Mirrors");
        assertThat(cmd).isEqualTo(new ParsedCommand.Path("Grand Hall of Mirrors"));
    }

    // --- Extra whitespace ---

    @Test void map_extra_whitespace() {
        var cmd = CommandParser.parse("  map   4  ");
        assertThat(cmd).isEqualTo(new ParsedCommand.MapCommand(4));
    }
}
