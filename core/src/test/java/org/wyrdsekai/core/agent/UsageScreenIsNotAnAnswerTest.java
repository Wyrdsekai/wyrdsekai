package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A usage screen is not a finding.
 *
 * <p>Live, 2026-08-07. An item called with no usable argument returns the
 * commands it accepts — a correct response to being asked nothing. The
 * never-silent guard read it as substance, could not parse it as prose, and the
 * companion told the bondholder <i>"it returned raw data I couldn't read as an
 * answer"</i>. Nothing had gone wrong with retrieval; the tool had simply been
 * handed nothing, and the person was told their library was broken.</p>
 */
class UsageScreenIsNotAnAnswerTest {

    /** THE case: the exact tool result that produced the bad line. */
    @Test
    void recognises_the_live_help_payload() {
        var payload = "[Tool completed] {help=true, text=The shelves hold what the "
            + "household Library has indexed — frameworks, conventions, accumulated "
            + "coding DNA.\n\nCommands:\n  use library shelves                  — this "
            + "overview\n  use library shelves search <query>   — search the household "
            + "Library}";

        assertThat(CompanionActor.looksLikeUsageScreen(payload)).isTrue();
    }

    /** The explicit marker alone is enough. */
    @Test
    void the_help_marker_alone_is_sufficient() {
        assertThat(CompanionActor.looksLikeUsageScreen("{help=true, text=anything}")).isTrue();
    }

    /**
     * The ~40 shipped items that predate the marker must still be caught, by the
     * shape of a usage screen — a Commands block listing the item's own verbs.
     */
    @Test
    void catches_unmarked_usage_screens_by_shape() {
        var unmarked = "{ok=true, text=The perch is where your bunshin rest.\n\n"
            + "Commands:\n  use familiar perch              — who is out working\n"
            + "  use familiar perch status <id>  — one worker, read closely}";

        assertThat(CompanionActor.looksLikeUsageScreen(unmarked)).isTrue();
    }

    /** A REAL answer must never be mistaken for a usage screen. */
    @Test
    void a_real_finding_is_not_a_usage_screen() {
        var answer = "{ok=true, text=From Glass Tide: The Librarian explained that a "
            + "vel-shara is a speech with power — an incantation that, spoken aloud, "
            + "reprograms the listener.}";

        assertThat(CompanionActor.looksLikeUsageScreen(answer)).isFalse();
    }

    /** Prose that merely mentions commands is not a usage screen. */
    @Test
    void prose_mentioning_commands_is_not_a_usage_screen() {
        assertThat(CompanionActor.looksLikeUsageScreen(
            "I ran the commands you asked for and they all passed.")).isFalse();
        assertThat(CompanionActor.looksLikeUsageScreen(
            "Commands: I'm not sure what you mean by that.")).isFalse();
    }

    /** An empty result is a different failure and must not be swallowed as help. */
    @Test
    void an_honest_empty_result_is_not_a_usage_screen() {
        assertThat(CompanionActor.looksLikeUsageScreen(
            "{ok=true, text=Nothing on the shelves matches 'vel-shara of Adrun'.}"))
            .isFalse();
    }

    /** Degenerate input must not throw. */
    @Test
    void handles_null_and_blank() {
        assertThat(CompanionActor.looksLikeUsageScreen(null)).isFalse();
        assertThat(CompanionActor.looksLikeUsageScreen("")).isFalse();
        assertThat(CompanionActor.looksLikeUsageScreen("   ")).isFalse();
    }
}
