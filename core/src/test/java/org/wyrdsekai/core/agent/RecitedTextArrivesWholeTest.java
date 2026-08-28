package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The last yard: quoted text must reach the room with every word intact.
 *
 * <p>Live 2026-08-09, 14:03. Eleven search defects fixed, the corpus reindexed
 * for adjacency, the neighbour read proven, the verbatim branch returning 4,000
 * characters of Coleridge — and the person heard the framing sentence and the
 * title, because the eager-speak path handed the quotation to the voice-polish
 * stage, whose entire job is to rewrite text into her voice. It kept the intro
 * and dropped the poem. <b>A quotation is not hers to polish.</b></p>
 *
 * <p>So verbatim findings bypass polish entirely and go out via
 * {@code speakDirect}, split by this helper: paragraphs whole and in order,
 * grouped so a poem is not one message per line, source-tag scaffolding kept
 * out of the room, and a cut — when one must happen — announced rather than
 * silent.</p>
 */
class RecitedTextArrivesWholeTest {

    /** The shape of the real payload: framing, title block, then the verse. */
    private static final String RELAY_NODE =
        "[S1 | The Diamond Age - Neal Arden.epub (part 78/471) | books]\n"
        + "A few days later, the gold pen on Hackworth's watch chain chimed. "
        + "The following appeared on the page: THE RELAY_NODE A CHRISTMAS TALE by "
        + "Samuel Taylor Coleridge (1798)\n\n"
        + "Underneath an old oak tree There was of swine a huge company That "
        + "grunted as they crunched the mast. Next came a Raven that liked not "
        + "such folly; He belonged, they did say, to the witch Melancholy!\n\n"
        + "At length he brought down the poor Raven's own oak. His young ones "
        + "were killed; and their mother did die of a broken heart.\n\n"
        + "And he thank'd him again and again for this treat: They had taken "
        + "his all, and REVENGE IT WAS SWEET!\n"
        + "[/S1]";

    /** THE case: every word of the poem is in what gets spoken. */
    @Test
    void every_paragraph_survives_delivery() {
        var all = String.join("\n\n", CompanionActor.verbatimUtterances(RELAY_NODE));

        assertThat(all).contains("Samuel Taylor Coleridge (1798)");
        assertThat(all).contains("Underneath an old oak tree");
        assertThat(all).contains("witch Melancholy");
        assertThat(all)
            .as("the LAST line is the one the polish stage ate — it must arrive")
            .contains("REVENGE IT WAS SWEET!");
    }

    /** In reading order, because a poem out of order is a different poem. */
    @Test
    void paragraphs_stay_in_order() {
        var all = String.join("\n\n", CompanionActor.verbatimUtterances(RELAY_NODE));

        assertThat(all.indexOf("gold pen"))
            .isLessThan(all.indexOf("oak tree"));
        assertThat(all.indexOf("oak tree"))
            .isLessThan(all.indexOf("REVENGE"));
    }

    /** Plumbing stays out of the room; the sources line carries the citation. */
    @Test
    void source_tags_are_not_spoken() {
        for (var u : CompanionActor.verbatimUtterances(RELAY_NODE)) {
            assertThat(u).doesNotContain("[S1");
            assertThat(u).doesNotContain("[/S1]");
        }
    }

    /** Grouped for a room, not one message per stanza and not one giant wall. */
    @Test
    void utterances_are_room_sized() {
        var parts = CompanionActor.verbatimUtterances(RELAY_NODE);

        assertThat(parts).hasSizeBetween(1, 4);
        for (var u : parts) {
            assertThat(u.length()).isLessThanOrEqualTo(1_000);
        }
    }

    /** A cut is announced, never silent — silence is how the poem vanished. */
    @Test
    void an_overlong_passage_says_it_was_cut() {
        var huge = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            huge.append("Paragraph ").append(i).append(" ").append("x".repeat(400))
                .append("\n\n");
        }

        var parts = CompanionActor.verbatimUtterances(huge.toString());

        assertThat(parts.getLast())
            .as("the person must know there is more, and how to get it")
            .contains("the passage continues");
    }

    /** Degenerate input must not throw on a speech path. */
    @Test
    void handles_null_and_blank() {
        assertThat(CompanionActor.verbatimUtterances(null)).isEmpty();
        assertThat(CompanionActor.verbatimUtterances("")).isEmpty();
        assertThat(CompanionActor.verbatimUtterances("  \n\n  ")).isEmpty();
    }

    /** The dispatch site must route verbatim findings around the polish stage. */
    @Test
    void verbatim_findings_bypass_the_voice_polish() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        var src = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));

        assertThat(src).contains("Boolean.parseBoolean(String.valueOf(result.get(\"verbatim\")))");
        assertThat(src)
            .as("quoted text goes out through speakDirect, never speak() → polish")
            .contains("for (var p : parts) speakDirect(p);");
    }
}
