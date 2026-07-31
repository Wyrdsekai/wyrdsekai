package org.wyrdsekai.core.protocol;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the full alias lifecycle:
 * define → use → redefine → unalias → verify gone.
 *
 * Simulates a session where a user defines aliases, uses them,
 * and manages them — same flow as telnet/SSH.
 */
class CommandParserAliasIntegrationTest {

    /**
     * Full session lifecycle: define aliases, use them, list them, remove them.
     */
    @Test
    void full_alias_lifecycle() {
        var aliases = new HashMap<String, String>();

        // 1. Define aliases
        var cmd1 = CommandParser.parse("alias la look at", "en", aliases);
        assertThat(cmd1).isInstanceOf(ParsedCommand.Alias.class);
        var alias1 = (ParsedCommand.Alias) cmd1;
        assertThat(alias1.name()).isEqualTo("la");
        assertThat(alias1.expansion()).isEqualTo("look at");
        aliases.put(alias1.name(), alias1.expansion()); // session stores it

        var cmd2 = CommandParser.parse("alias k go north", "en", aliases);
        aliases.put(((ParsedCommand.Alias) cmd2).name(), ((ParsedCommand.Alias) cmd2).expansion());

        var cmd3 = CommandParser.parse("alias j journal", "en", aliases);
        aliases.put(((ParsedCommand.Alias) cmd3).name(), ((ParsedCommand.Alias) cmd3).expansion());

        // 2. Use aliases — they expand before parsing
        var result1 = CommandParser.parse("la crystal", "en", aliases);
        // "look at crystal" → Examine (CommandParser line 215 routes look-at to examine).
        assertThat(result1).isInstanceOf(ParsedCommand.Examine.class);

        var result2 = CommandParser.parse("k", "en", aliases);
        assertThat(result2).isInstanceOf(ParsedCommand.Go.class);
        assertThat(((ParsedCommand.Go) result2).direction()).isEqualTo("north");

        var result3 = CommandParser.parse("j today was great", "en", aliases);
        assertThat(result3).isInstanceOf(ParsedCommand.Say.class); // "journal today was great" → room script

        // 3. List aliases
        var listCmd = CommandParser.parse("alias", "en", aliases);
        assertThat(listCmd).isInstanceOf(ParsedCommand.Alias.class);
        assertThat(((ParsedCommand.Alias) listCmd).name()).isNull(); // list mode

        // 4. Show specific alias
        var showCmd = CommandParser.parse("alias la", "en", aliases);
        assertThat(showCmd).isInstanceOf(ParsedCommand.Alias.class);
        assertThat(((ParsedCommand.Alias) showCmd).name()).isEqualTo("la");
        assertThat(((ParsedCommand.Alias) showCmd).expansion()).isNull(); // show mode

        // 5. Redefine alias
        var redefine = CommandParser.parse("alias la examine", "en", aliases);
        aliases.put(((ParsedCommand.Alias) redefine).name(), ((ParsedCommand.Alias) redefine).expansion());

        var result4 = CommandParser.parse("la crystal", "en", aliases);
        // After redefining `la` → `examine`, "examine crystal" → Examine.
        assertThat(result4).isInstanceOf(ParsedCommand.Examine.class);

        // 6. Remove alias
        var unalias = CommandParser.parse("unalias la", "en", aliases);
        assertThat(unalias).isInstanceOf(ParsedCommand.Unalias.class);
        aliases.remove(((ParsedCommand.Unalias) unalias).name());

        // 7. Alias no longer expands
        var result5 = CommandParser.parse("la crystal", "en", aliases);
        assertThat(result5).isInstanceOf(ParsedCommand.Unknown.class); // not a command anymore
    }

    /**
     * User aliases work alongside locale aliases without conflict.
     */
    @Test
    void user_and_locale_aliases_coexist() {
        var aliases = new HashMap<String, String>();
        aliases.put("x", "examine");

        // User alias works
        var result1 = CommandParser.parse("x crystal", "ja", aliases);
        // "examine crystal" → Examine (parser contract: examine routes to Examine).
        assertThat(result1).isInstanceOf(ParsedCommand.Examine.class);

        // Japanese locale alias still works
        var result2 = CommandParser.parse("見る", "ja", aliases);
        assertThat(result2).isInstanceOf(ParsedCommand.Look.class);

        // Japanese direction still works
        var result3 = CommandParser.parse("北", "ja", aliases);
        assertThat(result3).isInstanceOf(ParsedCommand.Go.class);
    }

    /**
     * User alias can override a locale alias.
     */
    @Test
    void user_alias_overrides_locale() {
        var aliases = new HashMap<String, String>();
        // Override Spanish "mirar" (normally → look) to mean "go north"
        aliases.put("mirar", "go north");

        var result = CommandParser.parse("mirar", "es", aliases);
        assertThat(result).isInstanceOf(ParsedCommand.Go.class);
        assertThat(((ParsedCommand.Go) result).direction()).isEqualTo("north");
    }

    /**
     * Alias expansion chains: alias expands, then the result is parsed.
     */
    @Test
    void alias_expansion_chains_correctly() {
        var aliases = new HashMap<String, String>();
        aliases.put("greet", "'hello everyone");

        var result = CommandParser.parse("greet", "en", aliases);
        assertThat(result).isInstanceOf(ParsedCommand.Say.class);
        assertThat(((ParsedCommand.Say) result).text()).isEqualTo("hello everyone");
    }

    /**
     * Alias with emote prefix.
     */
    @Test
    void alias_with_emote_expansion() {
        var aliases = new HashMap<String, String>();
        aliases.put("nod", ":nods thoughtfully");

        var result = CommandParser.parse("nod", "en", aliases);
        assertThat(result).isInstanceOf(ParsedCommand.Emote.class);
        assertThat(((ParsedCommand.Emote) result).text()).isEqualTo("nods thoughtfully");
    }

    /**
     * Empty alias map doesn't break anything.
     */
    @Test
    void empty_aliases_no_effect() {
        var result = CommandParser.parse("look", "en", Map.of());
        assertThat(result).isInstanceOf(ParsedCommand.Look.class);

        var result2 = CommandParser.parse("xyzzy", "en", Map.of());
        assertThat(result2).isInstanceOf(ParsedCommand.Unknown.class);
    }

    /**
     * Alias doesn't match partial words.
     */
    @Test
    void alias_only_matches_full_first_word() {
        var aliases = new HashMap<String, String>();
        aliases.put("lo", "look");

        // "lo" alone → expands to "look"
        var result1 = CommandParser.parse("lo", "en", aliases);
        assertThat(result1).isInstanceOf(ParsedCommand.Look.class);

        // "look" should NOT be affected by "lo" alias
        var result2 = CommandParser.parse("look", "en", aliases);
        assertThat(result2).isInstanceOf(ParsedCommand.Look.class);

        // "login" should NOT match "lo" alias (different first word)
        var result3 = CommandParser.parse("login", "en", aliases);
        assertThat(result3).isInstanceOf(ParsedCommand.Unknown.class);
    }

    /**
     * Multiple arguments preserved after alias expansion.
     */
    @Test
    void alias_preserves_all_arguments() {
        var aliases = new HashMap<String, String>();
        aliases.put("g", "give");

        var result = CommandParser.parse("g sword to ember", "en", aliases);
        assertThat(result).isInstanceOf(ParsedCommand.Give.class);
        assertThat(((ParsedCommand.Give) result).objectName()).isEqualTo("sword");
        assertThat(((ParsedCommand.Give) result).targetName()).isEqualTo("ember");
    }
}
