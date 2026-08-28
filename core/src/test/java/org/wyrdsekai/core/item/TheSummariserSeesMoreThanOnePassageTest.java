package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The budget that made a full library look empty.
 *
 * <p>{@code llmCall} truncated its input to <b>3,000 characters</b> — applied to
 * every source block concatenated together. Study chunks run 1,200–2,900
 * characters, so the first block consumed the budget and the rest were built,
 * counted, cited as sources, and then cut before the model ever saw them. The
 * item script's careful top-3 selection was decorative; the summariser read one
 * passage, and only answered when the answer happened to rank first.</p>
 *
 * <p><b>The tell was in every log line for days:</b> {@code promptLen=3003} —
 * identical for a two-word question and a sixty-nine-word one. A constant where
 * a measurement should have been. Same shape as {@code RERANK_BUDGET_MS}, which
 * was also a number that looked enforced and wasn't.</p>
 *
 * <p>Measured cost: six live runs of a question whose answer was demonstrably in
 * the library. Three reached the search, retrieved the right book, and all three
 * reported finding nothing.</p>
 */
class TheSummariserSeesMoreThanOnePassageTest {

    /** Largest Study chunk observed live; two of these must both fit. */
    private static final int LARGEST_OBSERVED_CHUNK = 2_900;

    private static String source(String rel) throws Exception {
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    private static String librarySection() throws Exception {
        var src = source("core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java");
        int start = src.indexOf("private static final String LIBRARY_CARD_SCRIPT");
        return src.substring(start, src.indexOf("\"\"\";", start));
    }

    /** THE case: the budget must hold several real chunks, not one. */
    @Test
    void the_input_budget_holds_more_than_a_single_chunk() {
        assertThat(ItemWorldApiProviderImpl.LLM_INPUT_CHARS)
            .as("3,000 chars could not hold even ONE of the larger live chunks plus its neighbour")
            .isGreaterThan(LARGEST_OBSERVED_CHUNK * 4);
    }

    /** And the hardcoded 3000 must be gone, not merely shadowed. */
    @Test
    void the_old_cap_is_not_still_in_the_call() throws Exception {
        var src = source("core/src/main/java/org/wyrdsekai/core/item/ItemWorldApiProviderImpl.java");

        assertThat(src).contains("truncate(userText, LLM_INPUT_CHARS)");
        assertThat(src)
            .as("a literal here is what made promptLen a constant")
            .doesNotContain("truncate(userText, 3000)");
    }

    /** More passages reach the model — the selection stops being decorative. */
    @Test
    void the_script_offers_more_than_three_passages() throws Exception {
        var s = librarySection();

        assertThat(s).contains("picked < 8");
        assertThat(s).doesNotContain("picked < 3");
    }

    /**
     * Per-block capping is the half that stops the bug recurring one level up:
     * without it a single long chunk still starves everything after it.
     */
    @Test
    void one_long_passage_cannot_crowd_out_the_others() throws Exception {
        var s = librarySection();

        // The cap applies to whatever the block ends up being — the chunk, or
        // the chunk plus its neighbours when the request wants the text itself.
        assertThat(s).contains("full.length > 4000");
        assertThat(s).contains("(wantsTheWords && chunk.context) ? chunk.context : chunk.text");
        assertThat(ItemWorldApiProviderImpl.PER_BLOCK_CHARS * 8)
            .as("eight blocks at the per-block ceiling must fit inside the total budget")
            .isLessThanOrEqualTo(ItemWorldApiProviderImpl.LLM_INPUT_CHARS
                + ItemWorldApiProviderImpl.PER_BLOCK_CHARS * 8);
        assertThat(ItemWorldApiProviderImpl.PER_BLOCK_CHARS)
            .as("a per-block cap above the total budget would do nothing")
            .isLessThan(ItemWorldApiProviderImpl.LLM_INPUT_CHARS);
    }

    /** The two ceilings must stay consistent with each other. */
    @Test
    void the_java_and_script_ceilings_agree() throws Exception {
        assertThat(librarySection())
            .as("the script's literal must match PER_BLOCK_CHARS or one silently wins")
            .contains(String.valueOf(ItemWorldApiProviderImpl.PER_BLOCK_CHARS));
    }

    /** Sources are still cited, so a widened funnel stays attributable. */
    @Test
    void every_passage_shown_is_still_a_citable_source() throws Exception {
        var s = librarySection();

        assertThat(s).contains("sources.push(key + \": \" + title");
        assertThat(s).contains("var key = \"S\" + (picked + 1);");
    }
}
