package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** {@code call <companion>}: the bondholder calls her to them, on every client the same way. */
class CallCommandTest {

    @Test
    @DisplayName("call and summon parse to the same command; a trailing 'here' is not a name")
    void parses() {
        assertEquals("Ada", assertInstanceOf(ParsedCommand.Call.class, CommandParser.parse("call Ada")).target());
        assertEquals("Ada", assertInstanceOf(ParsedCommand.Call.class, CommandParser.parse("summon Ada")).target());
        assertEquals("Ada", assertInstanceOf(ParsedCommand.Call.class, CommandParser.parse("call Ada here")).target());
        assertEquals("Ada", assertInstanceOf(ParsedCommand.Call.class, CommandParser.parse("call Ada to me")).target());
        assertEquals("Ada the Quiet", assertInstanceOf(ParsedCommand.Call.class, CommandParser.parse("call Ada the Quiet")).target());
        assertFalse(CommandParser.parse("call") instanceof ParsedCommand.Call, "call whom?");
    }

    @Test
    @DisplayName("the mapper sends a typed Call frame carrying the caller's room")
    void maps() {
        var msg = ClientCommandMapper.toWorldC2S(CommandParser.parse("call Ada"), "id-1", "study-x");
        var call = assertInstanceOf(C2SMessage.Call.class, msg);
        assertEquals("Ada", call.target());
        assertEquals("study-x", call.roomId());
        assertEquals("id-1", call.id());
    }
}
