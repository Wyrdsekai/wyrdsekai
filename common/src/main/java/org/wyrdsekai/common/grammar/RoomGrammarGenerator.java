package org.wyrdsekai.common.grammar;

import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates GBNF grammars from room state.
 *
 * The grammar constrains agent ACTIONS to what the room supports.
 * Speech is always free — the agent can say anything.
 * Internal experience (soul, vitality, dreams) is not affected.
 *
 * Grammar changes as the room changes: different objects, different exits,
 * different verbs. The model can only output valid actions for THIS room
 * in THIS state.
 *
 * Used by:
 * - Server: passed as `grammar` field to llama-server HTTP API
 * - Phone: passed to llama.cpp JNI via llama_sampler_init_grammar()
 * - Cloud: converted to JSON schema for structured outputs
 */
public final class RoomGrammarGenerator {

    private RoomGrammarGenerator() {}

    /**
     * Generate a GBNF grammar from the current room state.
     *
     * @param exits      Available exits (directions the agent can go)
     * @param objects    Objects in the room (things the agent can use/take/examine)
     * @param extraVerbs Additional room-specific verbs (e.g., "journal_write" in Study)
     * @return GBNF grammar string
     */
    public static String generate(List<Exit> exits, List<RoomObject> objects,
                                   List<RoomVerb> extraVerbs) {
        var sb = new StringBuilder();

        // Root: the agent picks ONE action or speaks freely
        sb.append("root ::= action | speech\n\n");

        // Free speech — always available, unconstrained content
        sb.append("speech ::= \"say:\" freetext\n");
        sb.append("freetext ::= [^\\n]+\n\n");

        // Emote — always available
        sb.append("emote ::= \"emote:\" freetext\n\n");

        // Build action alternatives
        var actionAlts = new ArrayList<String>();
        actionAlts.add("speech");
        actionAlts.add("emote");

        // Navigation — from room exits
        if (exits != null && !exits.isEmpty()) {
            sb.append("navigate ::= \"go:\" direction\n");
            var directions = exits.stream()
                .map(Exit::direction)
                .map(RoomGrammarGenerator::escapeGbnf)
                .toList();
            sb.append("direction ::= ")
                .append(String.join(" | ", directions.stream()
                    .map(d -> "\"" + d + "\"").toList()))
                .append("\n\n");
            actionAlts.add("navigate");
        }

        // Object interactions — from room objects
        if (objects != null && !objects.isEmpty()) {
            var useTargets = new ArrayList<String>();
            var takeTargets = new ArrayList<String>();

            for (var obj : objects) {
                var escaped = escapeGbnf(obj.name());
                useTargets.add("\"" + escaped + "\"");
                if (obj.takeable()) {
                    takeTargets.add("\"" + escaped + "\"");
                }
            }

            sb.append("use_object ::= \"use:\" object_name\n");
            sb.append("object_name ::= ").append(String.join(" | ", useTargets)).append("\n\n");
            actionAlts.add("use_object");

            if (!takeTargets.isEmpty()) {
                sb.append("take_object ::= \"take:\" takeable_name\n");
                sb.append("takeable_name ::= ").append(String.join(" | ", takeTargets)).append("\n\n");
                actionAlts.add("take_object");

                sb.append("drop_object ::= \"drop:\" takeable_name\n\n");
                actionAlts.add("drop_object");
            }
        }

        // Look — always available
        sb.append("look ::= \"look\" | \"look:\" freetext\n\n");
        actionAlts.add("look");

        // Extra verbs — room-specific (Study: journal_write, journal_search, etc.)
        if (extraVerbs != null) {
            for (var verb : extraVerbs) {
                var ruleName = verb.name().replace("-", "_").replace(" ", "_");
                if (verb.hasArgument()) {
                    sb.append(ruleName).append(" ::= \"").append(verb.name()).append(":\" freetext\n");
                } else {
                    sb.append(ruleName).append(" ::= \"").append(verb.name()).append("\"\n");
                }
                actionAlts.add(ruleName);
            }
            sb.append("\n");
        }

        // Assemble action rule
        sb.insert(sb.indexOf("action | speech"),
            "");  // action alternatives built below
        var actionLine = "action ::= " + String.join(" | ", actionAlts) + "\n";

        // Replace the root line with proper alternatives
        return "root ::= " + String.join(" | ", actionAlts) + "\n\n" + sb.toString()
            .replace("root ::= action | speech\n\n", "");
    }

    /**
     * Generate a Study-specific grammar with journal, notes, search, etc.
     */
    public static String generateStudy(List<Exit> exits) {
        var studyVerbs = List.of(
            new RoomVerb("journal_write", true),
            new RoomVerb("journal_search", true),
            new RoomVerb("journal_private", true),
            new RoomVerb("note_add", true),
            new RoomVerb("note_search", true),
            new RoomVerb("pin", true),
            new RoomVerb("remind", true),
            new RoomVerb("summarize", false),
            new RoomVerb("digest", false)
        );

        var studyObjects = List.of(
            new RoomObject("journal", "journal", "A leather-bound journal", false),
            new RoomObject("desk", "desk", "A writing desk", false),
            new RoomObject("shelves", "shelves", "Bookshelves", false),
            new RoomObject("pinboard", "pinboard", "A corkboard with pins", false)
        );

        return generate(exits, studyObjects, studyVerbs);
    }

    /**
     * Room-specific verb definition.
     */
    public record RoomVerb(String name, boolean hasArgument) {}

    /**
     * Escape special GBNF characters in a string.
     */
    private static String escapeGbnf(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
