package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Trimming and ordering are different jobs.
 *
 * <p>{@code surfaceByAffordance} returned early for a menu at or under the
 * top-K cap — correct for trimming, wrong for ordering. Small models pick
 * heavily by position, so a short menu still needs to be ordered by what was
 * asked.</p>
 *
 * <p>Live, 2026-08-08: a reactive turn's permitted surface was already under the
 * cap, so the ranker never ran and there was no {@code Affordance surface} line
 * for the turn at all. Asked to look through the household's books, she chose
 * {@code examine} against a room object that does not exist. Every measurement
 * behind this ranker had been taken on the own-time path; the path a person
 * actually talks to was never covered by it.</p>
 */
class SmallSurfacesAreStillOrderedTest {

    private static String source() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        var p = Files.exists(fromCore)
            ? fromCore : Paths.get(rel);
        return Files.readString(p);
    }

    /** THE regression: a short menu must not bypass ranking entirely. */
    @Test
    void a_menu_under_the_cap_is_no_longer_returned_unranked() throws Exception {
        assertThat(source())
            .as("the early return skipped ordering as well as trimming")
            .doesNotContain("if (tools == null || tools.size() <= AFFORDANCE_TOPK) return tools;");
    }

    /** It must still bail on genuinely nothing, rather than rank an empty list. */
    @Test
    void an_empty_surface_still_returns_immediately() throws Exception {
        assertThat(source()).contains("if (tools == null || tools.isEmpty()) return tools;");
    }

    /** The trim only applies when there is something to trim. */
    @Test
    void the_cap_is_applied_only_when_the_menu_exceeds_it() throws Exception {
        var src = source();

        assertThat(src).contains("boolean trimNeeded = tools.size() > AFFORDANCE_TOPK;");
        assertThat(src)
            .as("under the cap, rank for order over the FULL list — never truncate it")
            .contains("trimNeeded ? AFFORDANCE_TOPK : names.size()");
    }

    /**
     * The log must name the whole menu. A three-name preview cannot answer the
     * question you actually have when she reaches for the wrong verb: was the
     * right one even on the list?
     */
    @Test
    void the_whole_offered_menu_is_logged() throws Exception {
        var src = source();
        int line = src.indexOf("Affordance surface for '{}'");

        assertThat(line).isGreaterThan(0);
        var stmt = src.substring(line, Math.min(src.length(), line + 500));
        assertThat(stmt)
            .as("must join the full ranked list, not a sublist preview")
            .contains("String.join(\",\", ranked)");
        assertThat(stmt)
            .as("the truncated three-name preview must be gone")
            .doesNotContain("ranked.subList(0, 3)");
    }
}
