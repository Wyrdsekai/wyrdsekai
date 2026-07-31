package org.wyrdsekai.app.engine.study

import org.wyrdsekai.app.protocol.Exit
import org.wyrdsekai.app.protocol.RoomObject

/**
 * Generates GBNF grammars for the Study room on phone.
 *
 * Kotlin port of common/grammar/RoomGrammarGenerator.generateStudy().
 * Constrains 0.6B model output to valid Study actions.
 * Speech is always free. Actions constrained to room state.
 */
object StudyGrammarGenerator {

    /**
     * Generate a GBNF grammar for the Study room.
     * @param exits Available exits from the current room
     * @return GBNF grammar string
     */
    fun generate(exits: List<Exit> = emptyList()): String {
        val rules = mutableListOf<String>()
        val alts = mutableListOf<String>()

        // Free speech — always available, unconstrained
        rules.add("speech ::= \"say:\" freetext")
        rules.add("freetext ::= [^\\n]+")
        alts.add("speech")

        // Emote
        rules.add("emote ::= \"emote:\" freetext")
        alts.add("emote")

        // Navigation
        if (exits.isNotEmpty()) {
            val dirs = exits.joinToString(" | ") { "\"${escapeGbnf(it.direction)}\"" }
            rules.add("navigate ::= \"go:\" direction")
            rules.add("direction ::= $dirs")
            alts.add("navigate")
        }

        // Study objects (fixed set)
        rules.add("use_object ::= \"use:\" object_name")
        rules.add("object_name ::= \"journal\" | \"desk\" | \"shelves\" | \"pinboard\"")
        alts.add("use_object")

        // Look
        rules.add("look ::= \"look\" | \"look:\" freetext")
        alts.add("look")

        // Study-specific verbs
        val studyVerbs = listOf(
            "journal_write" to true,
            "journal_search" to true,
            "journal_private" to true,
            "note_add" to true,
            "note_search" to true,
            "pin" to true,
            "remind" to true,
            "summarize" to false,
            "digest" to false,
        )
        for ((name, hasArg) in studyVerbs) {
            if (hasArg) {
                rules.add("$name ::= \"$name:\" freetext")
            } else {
                rules.add("$name ::= \"$name\"")
            }
            alts.add(name)
        }

        // Root: pick one action
        val root = "root ::= ${alts.joinToString(" | ")}"
        return root + "\n\n" + rules.joinToString("\n") + "\n"
    }

    private fun escapeGbnf(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
