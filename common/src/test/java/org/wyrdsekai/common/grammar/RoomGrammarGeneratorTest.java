package org.wyrdsekai.common.grammar;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomGrammarGeneratorTest {

    @Test
    void generates_grammar_with_exits_and_objects() {
        var exits = List.of(
            new Exit("north", "terminal", "The Terminal"),
            new Exit("south", "vault", "The Vault"));
        var objects = List.of(
            new RoomObject("crystal", "crystal", "A glowing crystal", false),
            new RoomObject("logbook", "logbook", "A leather logbook", true));

        var grammar = RoomGrammarGenerator.generate(exits, objects, null);

        assertNotNull(grammar);
        assertTrue(grammar.contains("speech"), "Should have speech rule");
        assertTrue(grammar.contains("say:"), "Should allow free speech");
        assertTrue(grammar.contains("navigate"), "Should have navigation");
        assertTrue(grammar.contains("\"north\""), "Should include north exit");
        assertTrue(grammar.contains("\"south\""), "Should include south exit");
        assertTrue(grammar.contains("use_object"), "Should have use");
        assertTrue(grammar.contains("\"crystal\""), "Should include crystal");
        assertTrue(grammar.contains("take_object"), "Should have take for takeable");
        assertTrue(grammar.contains("\"logbook\""), "Should include takeable logbook");
    }

    @Test
    void study_grammar_includes_journal_verbs() {
        var exits = List.of(new Exit("out", "nexus", "The Nexus"));
        var grammar = RoomGrammarGenerator.generateStudy(exits);

        assertNotNull(grammar);
        assertTrue(grammar.contains("journal_write"), "Should have journal_write");
        assertTrue(grammar.contains("journal_search"), "Should have journal_search");
        assertTrue(grammar.contains("note_add"), "Should have note_add");
        assertTrue(grammar.contains("pin"), "Should have pin");
        assertTrue(grammar.contains("remind"), "Should have remind");
        assertTrue(grammar.contains("summarize"), "Should have summarize");
        assertTrue(grammar.contains("say:"), "Speech should always be available");
    }

    @Test
    void empty_room_still_has_speech_and_look() {
        var grammar = RoomGrammarGenerator.generate(List.of(), List.of(), null);

        assertNotNull(grammar);
        assertTrue(grammar.contains("speech"), "Should have speech even in empty room");
        assertTrue(grammar.contains("look"), "Should have look even in empty room");
        assertTrue(grammar.contains("say:"), "Free speech always available");
    }

    @Test
    void grammar_is_valid_gbnf() {
        var exits = List.of(new Exit("north", "terminal", "The Terminal"));
        var grammar = RoomGrammarGenerator.generate(exits, List.of(), null);

        // Basic GBNF validity checks
        assertTrue(grammar.startsWith("root ::="), "Should start with root rule");
        assertTrue(grammar.contains("::="), "Should have production rules");
        assertFalse(grammar.contains("null"), "Should not contain null");
    }

    @Test
    void extra_verbs_included() {
        var verbs = List.of(
            new RoomGrammarGenerator.RoomVerb("forge_inspect", false),
            new RoomGrammarGenerator.RoomVerb("forge_soul", true));
        var grammar = RoomGrammarGenerator.generate(List.of(), List.of(), verbs);

        assertTrue(grammar.contains("forge_inspect"), "Should have forge_inspect");
        assertTrue(grammar.contains("forge_soul"), "Should have forge_soul with argument");
    }
}
