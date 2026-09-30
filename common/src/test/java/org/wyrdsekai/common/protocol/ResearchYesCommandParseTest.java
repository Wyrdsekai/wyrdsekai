package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code research yes} is a person's command, parsed deterministically: their own yes to a
 * question the household library asked them about. It must never be speech (speech reaches the
 * companion's model), and nothing but the exact line is a yes.
 */
class ResearchYesCommandParseTest {

    @Test
    void the_exact_line_is_the_command() {
        assertInstanceOf(ParsedCommand.ResearchYes.class, CommandParser.parse("research yes"));
        assertInstanceOf(ParsedCommand.ResearchYes.class, CommandParser.parse("  Research   YES  "));
        assertInstanceOf(ParsedCommand.ResearchYes.class, CommandParser.parse("research yes", "ja"));
    }

    @Test
    void anything_else_is_not_a_yes() {
        for (var line : new String[]{"research", "research yes please", "research: yes", "yes",
                                     "research no", "say research yes", "'research yes"}) {
            assertFalse(CommandParser.parse(line) instanceof ParsedCommand.ResearchYes, line);
        }
    }
}
