package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Asked for the words, she must be given the words.
 *
 * <p>Live 2026-08-09, asked for the poem Finkle-McGraw sent Hackworth:</p>
 *
 * <blockquote>"The poem is called 'The Space Between.' It opens with lines about
 * silence and the weight of things left unsaid, and it closes with the image of
 * two people holding a space together without needing to fill it."</blockquote>
 *
 * <p>There is no such poem. It is fluent, it is in her voice, it is thematically
 * perfect, and it is invented whole — the failure mode that looks exactly like
 * success. Coleridge's <i>The Raven</i>, the poem actually quoted in the novel,
 * was in the library the entire time.</p>
 *
 * <p><b>The repair is mechanical, not instructional.</b> Telling a model not to
 * invent is a request it can decline under pressure; giving it nothing to invent
 * is not. When the request asks for the text, the item returns the retrieved
 * passages and no model is asked to produce prose at all — so whatever she says
 * afterwards is anchored to words that exist on a page.</p>
 */
class RecitationHandsBackTheTextTest {

    private static String librarySection() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java";
        var fromCore = Paths.get("..", rel);
        var src = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
        int start = src.indexOf("private static final String LIBRARY_CARD_SCRIPT");
        return src.substring(start, src.indexOf("\"\"\";", start));
    }

    /** THE case: a recitation request returns source text, not a generated answer. */
    @Test
    void a_request_for_the_words_returns_the_words() throws Exception {
        var s = librarySection();

        assertThat(s).contains("if (wantsTheWords) {");
        assertThat(s)
            .as("the verbatim branch must return blocks, never a model's prose")
            .contains("findings: blocks.slice(0, 3).join");
    }

    /** And it must return BEFORE any model is asked to compose. */
    @Test
    void the_model_is_never_asked_to_produce_the_text() throws Exception {
        var s = librarySection();
        int verbatimReturn = s.indexOf("if (wantsTheWords) {");
        int llmCall = s.indexOf("world.llm.analyze");

        assertThat(verbatimReturn).isGreaterThan(0);
        assertThat(llmCall).isGreaterThan(0);
        assertThat(verbatimReturn)
            .as("returning after the call would still have generated a poem first")
            .isLessThan(llmCall);
    }

    /** The words people actually use when they want the text itself. */
    @Test
    void the_cues_cover_how_people_ask() throws Exception {
        var s = librarySection();

        for (var cue : new String[]{"recite", "verbatim", "word for word",
                "exact words", "quote the", "read it to me", "full text", "in full"}) {
            assertThat(s).as("missing cue: " + cue).contains("\"" + cue + "\"");
        }
    }

    /** It reads the person's question, not the retrieval string. */
    @Test
    void the_cue_is_matched_against_what_the_person_said() throws Exception {
        assertThat(librarySection())
            .as("params.query carries her paraphrase; askedFor is the person's own words")
            .contains("(params.askedFor || params.query || \"\").toLowerCase()");
    }

    /** An ordinary question must still be answered, not dumped as raw passages. */
    @Test
    void an_ordinary_question_still_gets_a_synthesised_answer() throws Exception {
        var s = librarySection();

        assertThat(s).contains("var summary = world.llm.analyze(combined, instruction);");
        assertThat(s).contains("return { findings: summary, sources: sources };");
    }

    /** Sources travel with the text either way, so a quotation stays attributable. */
    @Test
    void the_verbatim_answer_is_still_sourced() throws Exception {
        assertThat(librarySection()).contains("sources: sources, verbatim: true");
    }

    /**
     * No negative instruction crept into the prompt. The guard is the control
     * flow above; a line telling the model what not to do is not the mechanism
     * and must not be mistaken for one.
     */
    @Test
    void the_fix_is_not_an_instruction_not_to_invent() throws Exception {
        var s = librarySection();

        assertThat(s).doesNotContain("Do not invent");
        assertThat(s).doesNotContain("Don't invent");
        assertThat(s).doesNotContain("never fabricate");
        assertThat(s).doesNotContain("Don't introduce facts");
    }
}
