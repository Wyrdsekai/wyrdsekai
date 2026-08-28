package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact-repeat guard and its reactive exemption — the two live-learned rules that
 * pull against each other.
 *
 * <p>2026-08-09: a companion repeating one settling line verbatim is a broken record,
 * so drop near-time duplicates. 2026-08-16: suppressing a REPLY that happens to match
 * an earlier line reads as ignoring the person who spoke — real mutism — so stand the
 * guard down just after someone speaks to her. 2026-08-17: that exemption applied to
 * every line in the window, which let her stutter the same sentence twice inside one
 * exchange, so it is now spent on use.
 *
 * <p>These are unit tests of the decision itself rather than assertions about the text
 * of the source file, which is what the previous coverage did — a source-inspection
 * test cannot tell you the rule is right, only that some strings are still present.
 */
class RepeatGuardExemptionTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final String LINE = "That matters — I want you to have that before you step away.";

    @Test
    void a_distinct_line_is_never_suppressed() {
        var v = CompanionActor.judgeRepeat("something else entirely", LINE,
            NOW.minusSeconds(5), null, NOW);
        assertThat(v.suppress()).isFalse();
        assertThat(v.usedReactiveExemption()).isFalse();
    }

    @Test
    void a_verbatim_repeat_into_silence_is_suppressed() {
        // The broken record: nobody has spoken to her, she says the same line again.
        var v = CompanionActor.judgeRepeat(LINE, LINE, NOW.minusSeconds(5), null, NOW);
        assertThat(v.suppress()).isTrue();
    }

    @Test
    void a_repeat_long_after_the_window_lands() {
        // "say that again" an hour later is a genuine repetition, not a stutter.
        var v = CompanionActor.judgeRepeat(LINE, LINE, NOW.minusSeconds(3600), null, NOW);
        assertThat(v.suppress()).isFalse();
        assertThat(v.usedReactiveExemption()).isFalse();
    }

    @Test
    void a_repeat_that_answers_a_person_lands_and_spends_the_exemption() {
        // The mutism fix: someone just spoke, so her reply gets through even though the
        // words match an earlier line — and the caller is told to spend the exemption.
        var v = CompanionActor.judgeRepeat(LINE, LINE, NOW.minusSeconds(5),
            NOW.minusSeconds(2), NOW);
        assertThat(v.suppress()).isFalse();
        assertThat(v.usedReactiveExemption()).isTrue();
    }

    @Test
    void a_second_identical_line_after_the_exemption_is_spent_is_suppressed() {
        // The stutter fix. The caller clears lastHeardUtteranceAt after the first
        // exempted line, so the next identical one is judged on its own — which is the
        // sequence DepartureReturnRitualE2ETest caught saying the same sentence twice.
        var first = CompanionActor.judgeRepeat(LINE, LINE, NOW.minusSeconds(5),
            NOW.minusSeconds(2), NOW);
        assertThat(first.usedReactiveExemption()).isTrue();

        Instant heardAfterSpending = null;   // what speakDirect sets it to
        var second = CompanionActor.judgeRepeat(LINE, LINE, NOW,
            heardAfterSpending, NOW.plusSeconds(1));
        assertThat(second.suppress()).isTrue();
    }

    @Test
    void a_fresh_utterance_restores_the_exemption() {
        // Spending it is not permanent — the next person to speak re-arms it, so a
        // continuing conversation never falls back into mutism.
        var v = CompanionActor.judgeRepeat(LINE, LINE, NOW, NOW.plusSeconds(30),
            NOW.plusSeconds(31));
        assertThat(v.suppress()).isFalse();
        assertThat(v.usedReactiveExemption()).isTrue();
    }

    @Test
    void a_stale_heard_utterance_does_not_exempt() {
        // Someone spoke a minute ago; that conversation has moved on, so a verbatim
        // repeat now is the broken record again.
        var v = CompanionActor.judgeRepeat(LINE, LINE, NOW.minusSeconds(5),
            NOW.minusSeconds(60), NOW);
        assertThat(v.suppress()).isTrue();
    }

    @Test
    void nothing_spoken_yet_is_never_a_repeat() {
        assertThat(CompanionActor.judgeRepeat(LINE, null, null, null, NOW).suppress()).isFalse();
    }
}
