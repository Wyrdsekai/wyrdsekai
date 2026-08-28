package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The bunshin exists in the world, not just in her sentences.
 *
 * <p>The bondholder's exact words (2026-08-11): "why do i not see the
 * bunshin happen or come back.. its like it happens but i dont see an
 * effect." He was right: the whole lifecycle was two speech lines. Nothing
 * entered Present, nothing could be examined, nothing visibly returned.
 * Concepts need to exist somewhere; the split now has a body — a room
 * presence that enters when she splits and folds back before she narrates
 * what the copy brought.</p>
 */
class TheSplitHasABodyTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    /** Dispatch gives the copy a presence and a visible departure-into-work. */
    @Test
    void the_split_enters_the_room() throws Exception {
        var s = src();
        assertThat(s).contains(":bunshin:\" + slotId;");
        assertThat(s).contains("presenceRegistry.enter(bunshinEntityId,");
        assertThat(s).contains("splits in two — a translucent copy settles nearby");
    }

    /** The return is seen BEFORE it is narrated — the room gets the moment. */
    @Test
    void the_return_is_visible_before_the_findings() throws Exception {
        var s = src();
        int dissolve = s.indexOf("dissolveBunshinBody(msg.slotId(), true);");
        int buffer = s.indexOf("bunshinReportsSinceLastSleep.add(report);",
            s.indexOf("received bunshin report"));
        assertThat(dissolve).isGreaterThan(0);
        assertThat(buffer).as("fold-back precedes narration/ingestion").isGreaterThan(dissolve);
        assertThat(s).contains("absorbs the returning copy");
    }

    /** Every terminal path dissolves the body — no ghosts in Present. */
    @Test
    void stale_drops_dissolve_silently() throws Exception {
        var s = src();
        assertThat(s).contains("dissolveBunshinBody(msg.slotId(), false);");
        assertThat(s)
            .as("stale drops must not show a return the reset erased")
            .contains("skipped for stale");
    }
}
