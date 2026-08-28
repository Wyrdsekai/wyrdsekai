package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The ranker was built for a measured failure and then wired into one caller.
 *
 * <p>{@code surfaceByAffordance} exists because of an ablation:
 * <em>"for 4B/9B models a focused, need-ranked menu is load-bearing: a flat
 * 18-tool menu makes them pick whatever ranks high in the abstract
 * (2026-05-30)"</em>. It was applied only in {@code triggerAutonomousInference}.
 * Every own-time turn therefore got a ranked menu of 8; every turn where a
 * <b>person asked for something</b> got {@code buildScopedTools()} flat — 131
 * tools, in construction order.</p>
 *
 * <p>Live, 2026-08-08: asked to look through the household's books, she was
 * handed all 131 and chose {@code examine} against a room object that does not
 * exist. {@code library_card} and {@code library_shelves} were both on the list
 * and both would have worked.</p>
 *
 * <p>The relevance text on this path has to be the PERSON'S words. Scoring the
 * autonomy prompt — which is what the other caller passes — would rank the menu
 * against a sentence the person never said.</p>
 */
class ReactiveTurnRanksItsToolsTest {

    private static String source() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        var p = Files.exists(fromCore)
            ? fromCore : Paths.get(rel);
        return Files.readString(p);
    }

    /** THE fix: the reactive turn must rank, not hand over the flat list. */
    @Test
    void the_reactive_turn_ranks_its_tool_surface() throws Exception {
        var src = source();
        int build = src.indexOf("var allTools = buildScopedTools();");
        assertThat(build).as("the reactive assembly must still exist").isGreaterThan(0);

        // 4000, not 2000: the ranker's rationale comment lives between the
        // assembly and the call — prose growth must not fail the wiring pin.
        var after = src.substring(build, Math.min(src.length(), build + 4000));
        assertThat(after)
            .as("a flat 131-tool menu is the documented small-model failure mode")
            .contains("surfaceByAffordance(");
    }

    /** It must rank against what the PERSON said, not the autonomy prompt. */
    @Test
    void it_ranks_against_the_persons_own_words() throws Exception {
        var src = source();
        int build = src.indexOf("var allTools = buildScopedTools();");
        var after = src.substring(build, Math.min(src.length(), build + 2000));

        assertThat(after)
            .as("the person's utterance is the relevance signal on this path")
            .contains("pendingTrigger.text()");
        assertThat(after)
            .as("must not score the autonomy prompt here — she never said it")
            .doesNotContain("apLower");
    }

    /** Only a real human turn — never rank against our own scaffold. */
    @Test
    void only_a_human_request_drives_the_ranking() throws Exception {
        var src = source();
        int build = src.indexOf("var allTools = buildScopedTools();");
        var after = src.substring(build, Math.min(src.length(), build + 2000));

        assertThat(after)
            .as("an own-time synthetic trigger must not be treated as a request")
            .contains("isHumanRequest(pendingTrigger)");
    }

    /** Both callers must reach the same ranker, so they cannot drift apart. */
    @Test
    void both_paths_use_the_same_ranker() throws Exception {
        var src = source();
        int occurrences = src.split("surfaceByAffordance\\(", -1).length - 1;

        assertThat(occurrences)
            .as("one definition plus two call sites — autonomous and reactive")
            .isGreaterThanOrEqualTo(3);
    }
}
