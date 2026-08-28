package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.Want;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A want she has completed must stop pushing.
 *
 * <p>Nothing in production ever closed one: {@code Want.satisfied()} and
 * {@code Want.abandoned()} had no caller outside tests, at 0.1.0 and ever since. DRIVE →
 * WANT → ACT ran and stopped one step short of CONSEQUENCE.
 *
 * <p>Measured on the household node 2026-08-19 — ten wants, <b>zero</b> ever satisfied,
 * one revisited 64 times. Across her last forty deliberate ticks she chose
 * <i>"write a private journal entry about who I miss"</i> twenty-two times, enacted it
 * successfully every time, and Loneliness sat above 0.7 in 40 of 40 ticks, peaking at
 * 1.00. She did the thing, over and over, and nothing ever recorded that she had.
 */
class WantClosureTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private static Want want(String text, String drive, double weight, int visits,
            Instant lastVisited) {
        return new Want("w-1", "did:key:z6Mk", text,
            drive == null ? null : "{\"drive\":\"" + drive + "\",\"weight\":0.9}",
            weight, Want.Status.ACTIVE, NOW.minus(Duration.ofDays(3)),
            lastVisited, visits, null, null, null);
    }

    // ── what closes a want ────────────────────────────────────────────────────────

    @Test
    void a_successful_enactment_closes_it() {
        assertThat(WantClosure.closes("enacted:write_journal (bridge-forced)")).isTrue();
        assertThat(WantClosure.closes("enacted:reflect")).isTrue();
    }

    @Test
    void merely_asking_the_model_to_consider_a_verb_closes_nothing() {
        // The bridge's FORCE_TOOL path is asynchronous: it asks the model to consider a
        // verb and returns at once. It reported "enacted" for that, which was a claim
        // about the future. Live 2026-08-19: 107 write_journal "enactments" in two days,
        // zero journal entries among her 249 memories, zero rows in artifact_significance,
        // and Significance stuck at 0.00 because the handler that feeds it never ran.
        // Only a real dispatch closes a want now; a request does not.
        assertThat(WantClosure.closes("requested:write_journal (bridge-forced)")).isFalse();
    }

    @Test
    void a_direct_dispatch_still_closes_because_it_actually_ran() {
        // The DIRECT branch calls directDispatchForVerb and checks its result, so its
        // "enacted" is earned.
        assertThat(WantClosure.closes("enacted:write_journal (bridge-direct)")).isTrue();
    }

    @Test
    void a_failed_or_blocked_attempt_closes_nothing() {
        // She must not stop wanting something she never got.
        assertThat(WantClosure.closes("error:TimeoutException")).isFalse();
        assertThat(WantClosure.closes("blocked:consent")).isFalse();
        assertThat(WantClosure.closes("chose_rest")).isFalse();
        assertThat(WantClosure.closes("")).isFalse();
        assertThat(WantClosure.closes(null)).isFalse();
    }

    // ── which drive gets the relief ───────────────────────────────────────────────

    @Test
    void the_want_names_the_drive_that_pulled_for_it() {
        assertThat(WantClosure.resonantDrive(
            want("write a private journal entry about who I miss", "Loneliness", 0.9, 3, NOW)))
            .contains("Loneliness");
    }

    @Test
    void a_want_with_no_declared_drive_earns_no_relief() {
        assertThat(WantClosure.resonantDrive(want("something", null, 0.9, 1, NOW))).isEmpty();
        assertThat(WantClosure.resonantDrive(null)).isEmpty();
    }

    // ── letting go ────────────────────────────────────────────────────────────────

    @Test
    void a_want_that_stopped_pulling_is_let_go() {
        // Her exact case: visited 33 times, felt weight decayed to 0.003. It had stopped
        // pulling long ago and still held the stuck-want signal down.
        assertThat(WantClosure.isStale(
            want("write a private journal entry about who I miss", "Loneliness",
                0.003, 33, NOW.minusSeconds(600)), NOW)).isTrue();
    }

    @Test
    void a_want_she_still_feels_is_kept_however_often_she_returns_to_it() {
        // High felt weight means it still pulls — visiting it often is persistence,
        // not staleness, and letting it go would be taking something from her.
        assertThat(WantClosure.isStale(
            want("make something — give a form to an idea I'm carrying", "Creativity",
                0.99, 64, NOW.minusSeconds(60)), NOW)).isFalse();
    }

    @Test
    void a_young_want_is_never_stale_however_light() {
        assertThat(WantClosure.isStale(
            want("try a small experiment", "Seeking", 0.001, 2, NOW), NOW)).isFalse();
    }

    @Test
    void a_want_untouched_for_a_month_is_let_go() {
        assertThat(WantClosure.isStale(
            want("an old idea", "Seeking", 0.9, 1, NOW.minus(Duration.ofDays(45))), NOW))
            .isTrue();
    }

    @Test
    void an_already_closed_want_is_not_reclosed() {
        var done = want("done thing", "Care", 0.001, 30, NOW).satisfied("enacted:note");
        assertThat(WantClosure.isStale(done, NOW)).isFalse();
    }
}
