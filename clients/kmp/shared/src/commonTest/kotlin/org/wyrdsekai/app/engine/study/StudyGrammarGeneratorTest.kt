package org.wyrdsekai.app.engine.study

import org.wyrdsekai.app.protocol.Exit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class StudyGrammarGeneratorTest {

    @Test
    fun `grammar contains root rule`() {
        val grammar = StudyGrammarGenerator.generate()
        assertTrue(grammar.startsWith("root ::="), "Should start with root rule")
    }

    @Test
    fun `grammar always includes speech`() {
        val grammar = StudyGrammarGenerator.generate()
        assertTrue(grammar.contains("speech"), "Should include speech alternative")
        assertTrue(grammar.contains("\"say:\""), "Should include say: prefix")
        assertTrue(grammar.contains("freetext"), "Should include freetext rule")
    }

    @Test
    fun `grammar includes study verbs`() {
        val grammar = StudyGrammarGenerator.generate()
        assertTrue(grammar.contains("journal_write"), "Should include journal_write")
        assertTrue(grammar.contains("journal_search"), "Should include journal_search")
        assertTrue(grammar.contains("journal_private"), "Should include journal_private")
        assertTrue(grammar.contains("note_add"), "Should include note_add")
        assertTrue(grammar.contains("pin"), "Should include pin")
    }

    @Test
    fun `grammar includes study objects`() {
        val grammar = StudyGrammarGenerator.generate()
        assertTrue(grammar.contains("\"journal\""), "Should include journal object")
        assertTrue(grammar.contains("\"desk\""), "Should include desk object")
        assertTrue(grammar.contains("\"shelves\""), "Should include shelves object")
        assertTrue(grammar.contains("\"pinboard\""), "Should include pinboard object")
    }

    @Test
    fun `grammar includes navigation when exits provided`() {
        val exits = listOf(
            Exit("north", "home", "Back to Home"),
        )
        val grammar = StudyGrammarGenerator.generate(exits)
        assertTrue(grammar.contains("navigate"), "Should include navigate")
        assertTrue(grammar.contains("\"north\""), "Should include north direction")
    }

    @Test
    fun `grammar excludes navigation when no exits`() {
        val grammar = StudyGrammarGenerator.generate(emptyList())
        assertFalse(grammar.contains("navigate"), "Should not include navigate without exits")
    }

    @Test
    fun `grammar includes look`() {
        val grammar = StudyGrammarGenerator.generate()
        assertTrue(grammar.contains("look"), "Should include look")
    }

    @Test
    fun `grammar includes emote`() {
        val grammar = StudyGrammarGenerator.generate()
        assertTrue(grammar.contains("emote"), "Should include emote")
        assertTrue(grammar.contains("\"emote:\""), "Should include emote: prefix")
    }

    @Test
    fun `no-arg verbs have no colon`() {
        val grammar = StudyGrammarGenerator.generate()
        // summarize is a no-arg verb
        assertTrue(grammar.contains("summarize ::= \"summarize\""), "No-arg verb should not have colon")
    }
}
