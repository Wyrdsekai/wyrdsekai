package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "You did this" names an act whose outcome is known, never the verb a want was read as.
 *
 * <p>The own-time muse took its second anchor from {@code recentEnactedVerbs.peekLast()}, and
 * {@code enactInteriorityWant} fills that ring with the verb read off the chosen want's text,
 * before the tier gate, the state gates, the cooldown and the bridge. Household node,
 * 2026-09-22: a companion was told "you just did this: tell_agent" for a tell that was never
 * offered to her, and another "you just did this: library_search" two hours after her only
 * search; she described a book that was in no result.</p>
 */
class HerLastActIsWhatRanTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    /** The body of the method whose declaration starts with {@code signature}: up to the first
     *  closing brace at member indentation. */
    private static String body(String src, String signature) {
        var at = src.indexOf(signature);
        assertTrue(at > 0, signature + " was not found");
        var end = src.indexOf("\n    }\n", at);
        assertTrue(end > at, "the end of " + signature + " was not found");
        return src.substring(at, end);
    }

    @Test
    @DisplayName("the muse reads her last act, not the verb a want was read as")
    void museReadsTheLastAct() throws IOException {
        var src = Files.readString(ACTOR);
        var fresh = body(src, "private String freshMuseAnchor() {");
        assertFalse(fresh.contains("recentEnactedVerbs"), "the want-verb ring is not a record of acts");
        assertTrue(fresh.contains("lastAct,"), "the muse is handed her last act");
        assertFalse(src.contains("\"you just did this: \" +"), "an act is named with its age and outcome");
    }

    @Test
    @DisplayName("choosing a want records no act, and no act is recorded without an outcome")
    void noActWithoutAnOutcome() throws IOException {
        var src = Files.readString(ACTOR);
        assertFalse(body(src, "private String enactInteriorityWant(").contains("noteAct("),
            "a chosen want has not happened yet");
        assertFalse(Pattern.compile("noteAct\\([^;]*,\\s*null\\s*\\);").matcher(src).find(),
            "an act noted without an outcome would hand the model a bare verb");
    }

    @Test
    @DisplayName("a search notes what it found, or that it found nothing")
    void searchesNoteTheirResult() throws IOException {
        var src = Files.readString(ACTOR);
        var library = body(src, "private void handleLibrarySearch(");
        assertTrue(library.contains("noteAct(\"library_search\", searchOutcome(action.query(), hitNames(searchResults)));"),
            "a library search that found something names what it found");
        assertTrue(library.contains("noteAct(\"library_search\", searchOutcome(alt, hitNames(altHits)));"),
            "a rephrasing that found something names what it found");
        assertTrue(library.contains("noteAct(\"library_search\", searchOutcome(action.query(), List.of()));"),
            "a library search that found nothing says so");
        assertTrue(body(src, "private void handleWebSearch(").contains("noteAct(\"web_search\", searchOutcome(action.query(),"),
            "a web search names what it returned");
        assertTrue(body(src, "private Behavior<Command> onSubjectReadingDone(")
                .contains("readingOutcome(msg.part(), msg.result())"),
            "a reading on her subject names what it found");
    }

    @Test
    @DisplayName("an own-time act a gate held back is noted as held back")
    void gatesNoteWhatTheyHeldBack() throws IOException {
        var src = Files.readString(ACTOR);
        var policy = body(src, "private boolean enforceActionPolicy(");
        assertTrue(policy.indexOf("noteAct(actionType, NOT_HERS_YET);")
                != policy.lastIndexOf("noteAct(actionType, NOT_HERS_YET);"),
            "both the tier gate and the consent gate note what they held back");
        var dispatch = body(src, "private boolean tryDispatchScriptedToolCall(");
        var denial = dispatch.indexOf("if (authoringDenial != null) {");
        assertTrue(denial > 0, "the authoring gate was not found");
        var noted = dispatch.indexOf("noteAct(actionName, NOT_HERS_YET);", denial);
        assertTrue(noted > denial && noted < dispatch.indexOf("speak(authoringDenial);", denial),
            "the authoring gate notes what it held back");
    }
}
