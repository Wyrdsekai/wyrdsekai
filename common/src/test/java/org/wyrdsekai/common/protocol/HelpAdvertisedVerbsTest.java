package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * `help` is a contract. Every verb it advertises must reach a handler.
 *
 * <p>It stopped being one for `passwd`: the handler existed, but only inside the SLASH
 * command switch, so the bare form that both `help` ("passwd - Change your password")
 * and tab-completion offered parsed to {@link ParsedCommand.Unknown} and answered
 * "Didn't catch that." The command was advertised, implemented, and unreachable.</p>
 */
class HelpAdvertisedVerbsTest {

    @Test
    @DisplayName("bare `passwd` reaches the password handler, as help promises")
    void barePasswdParses() {
        var parsed = CommandParser.parse("passwd oldpass newpass");
        var slash = assertInstanceOf(ParsedCommand.SlashCommand.class, parsed,
            "bare `passwd` must reach the handler, not fall through to Unknown");
        assertEquals("passwd", slash.command());
        assertEquals(List.of("oldpass", "newpass"), slash.args());
    }

    @Test
    @DisplayName("`password` alias and the multi-word setup passphrase both survive")
    void passwordAliasAndMultiWordCurrent() {
        var slash = assertInstanceOf(ParsedCommand.SlashCommand.class,
            CommandParser.parse("password correct horse battery staple newpass"));
        assertEquals("passwd", slash.command());
        // The handler joins all-but-last as the current password — a multi-word setup
        // passphrase must arrive intact rather than being truncated to one token.
        assertEquals(5, slash.args().size());
        assertEquals("newpass", slash.args().getLast());
    }

    @Test
    @DisplayName("bare `passwd` with no args still reaches the handler (it prints usage)")
    void barePasswdNoArgs() {
        var slash = assertInstanceOf(ParsedCommand.SlashCommand.class,
            CommandParser.parse("passwd"));
        assertEquals("passwd", slash.command());
        assertEquals(List.of(), slash.args());
    }

    @Test
    @DisplayName("the slash form keeps working — this adds a path, it does not move one")
    void slashFormStillWorks() {
        var slash = assertInstanceOf(ParsedCommand.SlashCommand.class,
            CommandParser.parse("/passwd old new"));
        assertEquals("passwd", slash.command());
    }

    @Test
    @DisplayName("no verb that `help` advertises falls through to Unknown")
    void helpAdvertisedVerbsAllDispatch() {
        // Exactly as printed by `help` on the SSH surface.
        for (var verb : new String[]{"look", "exits", "inventory", "actions", "who",
                                      "help", "passwd", "map"}) {
            assertFalse(CommandParser.parse(verb) instanceof ParsedCommand.Unknown,
                "`help` advertises '" + verb + "' but it parses to Unknown — "
                    + "an advertised command that answers \"Didn't catch that\"");
        }
    }

    @Test
    @DisplayName("speech is not swallowed — a sentence merely mentioning a password stays speech")
    void doesNotSwallowSpeech() {
        // The bare-verb rule keys on the FIRST word only, so ordinary talk is untouched.
        assertFalse(CommandParser.parse("say my password is on the pinboard")
            instanceof ParsedCommand.SlashCommand);
    }
}
