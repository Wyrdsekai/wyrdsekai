package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run-on rule is for her own words. The library's answers join their findings with semicolons,
 * so a 700-character answer read as one sentence of more than 60 words and was cut to its first
 * clause before anyone heard it (household node, 2026-09-30 evening: four of five cuts were the
 * library's text). A tool's text goes out whole; her own sentence that never ends is still cut.
 */
class ALibraryAnswerIsSpokenWholeTest {

    /** The library's shape: one sentence, findings joined with semicolons, well past 60 words. */
    private static final String ANSWER = "The sources say that somatic practices for emotional regulation and "
        + "nervous-system grounding fall into three families; the first is breath work, slow exhalation and "
        + "box breathing, which lowers heart rate within a few minutes; the second is movement, shaking, "
        + "walking and progressive muscle release, which discharges held tension; the third is attention, "
        + "the body scan and orienting to the room, which brings the person back to the present; the sources "
        + "add that pairing any of these with a named feeling makes the effect last longer than the practice alone.";

    @Test
    void the_librarys_answer_goes_out_whole() {
        assertThat(RunOn.hasRunOn(ANSWER)).as("the rule reads it as one long sentence").isTrue();
        assertThat(CompanionActor.heldToRunOn(ANSWER, ActivityLogger.AUTHORED_TOOL)).isSameAs(ANSWER);
    }

    @Test
    void her_own_sentence_that_never_ends_is_still_cut() {
        var hers = "I read a while tonight. " + ANSWER;
        assertThat(CompanionActor.heldToRunOn(hers, null)).isEqualTo("I read a while tonight.");
        assertThat(CompanionActor.heldToRunOn("Short. Fine.", null)).isEqualTo("Short. Fine.");
    }

    /**
     * Live 2026-10-01 06:36: "Taking that to the workshop: <her 61-word task>. I'll hand it to the
     * workshop and report back when it's done." was cut inside the task; the person never heard
     * that she would report back. A product line quotes content; it goes out whole.
     */
    @Test
    void a_product_line_quoting_a_long_task_goes_out_whole() {
        var task = "Create a clear, structured explanation of how the disease works, covering the mechanism, "
            + "the way it shows in children and in adults, how it is diagnosed with the three kinds of imaging, "
            + "and the treatments, direct and indirect surgery, with their outcomes, risks, and the follow-up "
            + "schedule, and finish with the three questions a patient should ask, the sources used, and a short "
            + "glossary of the terms a lay reader will meet";
        var line = "Taking that to the workshop: " + task + ". I'll hand it to the workshop and report back when it's done.";
        assertThat(RunOn.hasRunOn(line)).isTrue();
        assertThat(CompanionActor.heldToRunOn(line, ActivityLogger.AUTHORED_PRODUCT)).isSameAs(line);
    }

    @Test
    void both_ways_out_of_the_actor_pass_the_authorship_to_the_cut() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        var src = Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
        assertThat(src.split("text = cutRunOn\\(text, authoredBy\\);").length - 1)
            .as("speak() and speakDirect() both cut with the authorship known").isEqualTo(2);
        assertThat(src).doesNotContain("cutRunOn(text);");
    }
}
