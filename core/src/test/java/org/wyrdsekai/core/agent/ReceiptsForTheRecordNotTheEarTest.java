package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Provenance travels out-of-band of the voice.
 *
 * <p>Live 2026-08-10, twice in one afternoon: a library answer went out and
 * the room heard, as its second line, {@code Sources: S1: …epub (part 92/387)
 * (doc:did:key:…)} — the machinery talking through her mouth. The same
 * doc-ids also poisoned the voice-polish fact-guard: a GOOD polish that
 * dropped them was rejected as unfaithful ("missing facts=[107, 387,
 * 6MktgdToPdRWMNYv…]"), forcing the raw-draft fallback that leaked them.
 * One root for both: the Sources suffix was appended to the findings BEFORE
 * the speech branches, so every downstream layer faced a false choice —
 * preserve the receipts (leak) or drop them (rejected).</p>
 *
 * <p>The split: working memory keeps findings + Sources — the model needs its
 * ground. Speech starts from the bare findings, minus inline [S1]-style
 * citation keys. The receipts still exist; they just aren't spoken.</p>
 */
class ReceiptsForTheRecordNotTheEarTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    /** THE case: speech is built from the bare findings, not the combined record. */
    @Test
    void speech_starts_from_the_bare_findings() throws Exception {
        assertThat(src()).contains(
            ".stripScaffolding(String.valueOf(result.get(\"findings\")))");
    }

    /** The record keeps the receipts — memory grounding is not sacrificed. */
    @Test
    void working_memory_still_carries_the_sources() throws Exception {
        assertThat(src()).contains("resultSummary += \"\\nSources: \"");
    }

    /** Inline [S1] citation keys are for the page, not the mouth. */
    @Test
    void citation_keys_are_stripped_from_speech() throws Exception {
        assertThat(src()).contains(".replaceAll(\"\\\\[S\\\\d+]\", \"\")");
    }

    /** The verbatim recitation path still speaks the cleaned text unchanged. */
    @Test
    void verbatim_recitation_is_untouched() throws Exception {
        var s = src();
        assertThat(s).contains("var parts = verbatimUtterances(cleanSummary);");
        assertThat(s).contains("QUOTED TEXT IS NOT HERS TO POLISH.");
    }
}
