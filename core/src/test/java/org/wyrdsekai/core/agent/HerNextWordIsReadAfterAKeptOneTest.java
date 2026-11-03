package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A turn that keeps her word must not start another from its own words, or she loops. The guard
 * was a three-minute window cleared by whatever she said next, so the first words of the NEXT
 * own-time turn were dropped instead: on the household node she read part 147 of a book on a kept
 * word, and two minutes later, on a new reading turn, said "I'll keep reading where we are — the
 * book is open, part 147" and nothing came of it (2026-11-01). Now the mark belongs to the kept
 * turn itself: set as it starts, cleared as any other own-time turn starts.
 */
class HerNextWordIsReadAfterAKeptOneTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    private static String body(String src, String declaration) {
        int at = src.indexOf(declaration);
        assertThat(at).as(declaration + " not found").isGreaterThan(0);
        int end = src.indexOf("\n    }\n", at);
        return src.substring(at, end > at ? end : src.length());
    }

    @Test
    void the_mark_is_the_kept_turns_and_every_other_own_time_turn_clears_it() throws Exception {
        var s = src();
        assertThat(s).as("no window of time stands in for the turn").doesNotContain("HER_WORD_TURN_WINDOW")
            .doesNotContain("herWordTurnStartedAt");

        var keep = body(s, "private Behavior<Command> onKeepHerWord(KeepHerWord msg) {");
        int set = keep.indexOf("startingHerWordTurn = true;");
        int start = keep.indexOf("triggerAutonomousInference(prompt, msg.tool());");
        int unset = keep.indexOf("startingHerWordTurn = false;");
        assertThat(set).as("marked as the kept turn starts").isGreaterThan(0);
        assertThat(start).isGreaterThan(set);
        assertThat(unset).as("and only for that one start").isGreaterThan(start);

        var turn = body(s, "private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance) {");
        int heldReturn = turn.indexOf("return false;\n        }\n        boolean planStep");
        int which = turn.indexOf("herWordTurnInFlight = startingHerWordTurn;");
        assertThat(which).as("every own-time turn that starts says which it is").isGreaterThan(0);
        assertThat(which).as("after the held turns return: a turn that does not start changes nothing")
            .isGreaterThan(turn.indexOf("if (held) {"));

        var notice = body(s, "private void noticeHerWord(String prose) {");
        assertThat(notice).contains("if (herWordTurnInFlight) {\n            herWordTurnInFlight = false;\n"
            + "            herWordToolInFlight = null;\n            return;\n        }");
    }
}
