package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A person's fact-question reaches the library even when she is tired,
 * and even when the turn authors outside the ReAct loop.
 *
 * <p>Observed live, 2026-08-10, first question to a fresh install with the
 * full corpus indexed: "Library-first armed" logged, then the SkillCost
 * filter at energy=0.25 culled every library tool, the affordance surface
 * arrived all mood and no library, the turn authored on the DIRECT-RESPONSE
 * path (no active plan) which never consulted the armed flag, and `recall`
 * against an hours-old memory answered "I don't have any memory of that" —
 * a question 13.7 million indexed chunks could answer. Three gaps, one
 * outcome; these tests hold all three closed.</p>
 */
class TheQuestionSurvivesLowEnergyTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    // ── 1. The cost filter honors the person's question ──────────────────

    /** Low energy must not sever the pathway a direct question travels. */
    @Test
    void the_skillcost_filter_restores_library_tools_for_a_pending_question() throws Exception {
        var s = src();
        assertThat(s)
            .as("the cull branch needs a consent restore, like the posture filter's")
            .contains("} else if (libraryFirstPending && LIBRARY_FIRST_TOOLS.contains(actionName)) {");
        assertThat(s)
            .as("a silent restore is invisible in the journal next time this breaks")
            .contains("restored by the person's pending request");
    }

    /**
     * The same rule, the other door.
     *
     * <p>The library half of this was protected on 2026-08-10; the workbench half was not.
     * Live on staging 2026-08-22 build-first armed correctly for "please build me an item
     * called venture_scout" and this filter culled {@code dispatch_task} at energy=0.22.
     * She answered about wanting the request properly heard — evasion to read, and in fact
     * a companion whose hands were emptied between the routing and the menu. Asking her to
     * build is consent to spend what building costs, exactly as a question is consent to
     * spend what a lookup costs.
     */
    @Test
    void the_skillcost_filter_restores_build_tools_for_a_pending_build_request() throws Exception {
        var s = src();
        assertThat(s)
            .as("a build request must survive low energy the way a question does")
            .contains("} else if (buildFirstPending && BUILD_FIRST_TOOLS.contains(actionName)) {");
    }

    // ── 2. The ranker cannot drop what the force will need ────────────────

    /** The pin rides the same rail as the WantActBridge FORCE verb. */
    @Test
    void a_pending_question_pins_a_library_tool_through_the_topk_trim() throws Exception {
        var s = src();
        // Renamed libraryPin → forcedPin when build-first gained the same
        // rail (2026-08-11) — the pin mechanism is now shared.
        assertThat(s).contains("String forcedPin = null;");
        assertThat(s)
            .as("the pin must reach surfaceByAffordance as its forced argument")
            .contains("askedFor.toLowerCase(Locale.ROOT), forcedPin);");
        assertThat(s)
            .as("library_card is the full search→read→summarize chain; prefer it")
            .contains("if (\"library_card\".equals(nm)) break;");
    }

    // ── 3. The direct-response path carries the force ─────────────────────

    /** The path a person actually talks to must host the same force as ReAct. */
    @Test
    void the_direct_response_branch_consults_the_armed_flag() throws Exception {
        var s = src();
        assertThat(s).contains("Library-first FORCE (direct):");
        assertThat(s)
            .as("consumed whether or not it applies — it must never leak forward")
            .contains("skipped (direct)");
        assertThat(s)
            .as("the narrowed choice must reach the wire, not a hardcoded auto")
            .contains("roomGrammar, null, wireTools, directToolChoice,");
    }

    /** Both consumption sites remain: ReAct keeps its force, direct gains one. */
    @Test
    void both_authoring_paths_consume_the_flag() throws Exception {
        var s = src();
        int react = s.indexOf("Library-first FORCE: narrowed");
        int direct = s.indexOf("Library-first FORCE (direct):");
        assertThat(react).as("ReAct force must survive this change").isGreaterThan(0);
        assertThat(direct).isGreaterThan(0);
        assertThat(react).isNotEqualTo(direct);
    }
}
