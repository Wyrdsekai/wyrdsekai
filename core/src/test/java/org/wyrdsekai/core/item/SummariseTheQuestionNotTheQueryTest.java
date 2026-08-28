package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A retrieval string and a question are not the same text.
 *
 * <p>{@code query} exists to match documents: it is the companion's paraphrase
 * with the person's words merged back in, garbles and all, because for BM25 a
 * wrong extra term costs nothing while a missing right one costs everything.
 * Reasoning from that same string inherits every slip in the paraphrase.</p>
 *
 * <p>Live, 2026-08-09. Her rewrite said <b>"velhara"</b>. The merge restored
 * "velsharas" → "velshara" and "tide", and the search returned the correct
 * Librarian passages — {@code promptLen=3003}, the summariser had the material.
 * Then the instruction was built from {@code params.query}, so she was asked
 * about "velhara", and answered: <i>"The source material does not contain a
 * record of a librarian named Velhara."</i> She had turned her own typo into a
 * person and then, correctly and uselessly, reported that he does not exist.</p>
 *
 * <p>Two strings, two jobs. {@code askedFor} is what the person said.</p>
 */
class SummariseTheQuestionNotTheQueryTest {

    private static String script(String constantName) throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java";
        var fromCore = Paths.get("..", rel);
        var src = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
        int start = src.indexOf("private static final String " + constantName);
        assertThat(start).as(constantName + " must exist").isGreaterThan(0);
        int end = src.indexOf("\"\"\";", start);
        return src.substring(start, end);
    }

    /** THE case: the summariser is asked the person's question. */
    @Test
    void the_library_summariser_uses_what_was_asked() throws Exception {
        var s = script("LIBRARY_CARD_SCRIPT");

        assertThat(s).contains("var question = params.askedFor || params.query;");
        assertThat(s)
            .as("the instruction must be built from the question, not the search string")
            .contains("\"Synthesize a concise answer to: \" + question");
        assertThat(s).doesNotContain("\"Synthesize a concise answer to: \" + params.query");
    }

    /** The web summariser had the identical line and the identical exposure. */
    @Test
    void the_web_summariser_does_too() throws Exception {
        var s = script("SEARCHING_GLASS_SCRIPT");

        assertThat(s).contains("var question = params.askedFor || params.query;");
        assertThat(s).doesNotContain("\"Synthesize a concise answer to: \" + params.query");
    }

    /** Retrieval must still use the merged string — that is what finds the passages. */
    @Test
    void the_search_still_uses_the_retrieval_string() throws Exception {
        var s = script("LIBRARY_CARD_SCRIPT");

        assertThat(s)
            .as("swapping the SEARCH to askedFor would undo the term-merge entirely")
            .contains("world.library.search(params.query)");
    }

    /**
     * The instruction says what to do, never what to avoid.
     *
     * <p>An earlier version of this file asserted the presence of "treat any
     * misspelling as a misspelling, NOT as the name of a person" — a negative
     * instruction, and the wrong kind of fix. A model under pressure declines
     * those. The working repair here is structural: {@code askedFor} carries the
     * person's real words, so the misspelling the model would have reified is
     * not in front of it in the first place.</p>
     */
    @Test
    void the_instruction_is_positive() throws Exception {
        var s = script("LIBRARY_CARD_SCRIPT");

        assertThat(s).contains("Answer from the source text");
        assertThat(s).doesNotContain("not as the name");
        assertThat(s).doesNotContain("Don't introduce facts");
    }

    /** Falls back cleanly for callers that pass no question — CLI, tests, own-time. */
    @Test
    void it_falls_back_to_the_query_when_nobody_asked() throws Exception {
        assertThat(script("LIBRARY_CARD_SCRIPT"))
            .as("|| params.query keeps every non-conversational caller working")
            .contains("params.askedFor || params.query");
    }

    /** The dispatcher must supply it, as context, the way agentDid is supplied. */
    @Test
    void the_dispatcher_injects_what_was_asked() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        var src = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));

        assertThat(src).contains("params.putIfAbsent(\"askedFor\", userRequest)");
        assertThat(src)
            .as("never overwrite a value an item was given explicitly")
            .doesNotContain("params.put(\"askedFor\", userRequest)");
    }
}
