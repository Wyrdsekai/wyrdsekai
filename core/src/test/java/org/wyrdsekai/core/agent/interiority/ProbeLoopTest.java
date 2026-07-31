package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verdict-logic coverage for the closed action-loop. The drive deltas live in
 * the actor; here we pin the timing/verdict state machine: within window → AWAITING; past window
 * under the cap → RETRY (sharpen + reach again); past window at the cap → DISENGAGE (release).
 */
class ProbeLoopTest {

    @Test void within_window_keeps_awaiting() {
        assertThat(ProbeLoop.onWindowCheck(ProbeLoop.WINDOW_SECONDS - 1, 1))
            .isEqualTo(ProbeLoop.Verdict.AWAITING);
    }

    @Test void past_window_under_cap_retries() {
        // streak-if-unmet below MAX → the unmet reach sharpens the want and reaches again
        assertThat(ProbeLoop.onWindowCheck(ProbeLoop.WINDOW_SECONDS + 1, ProbeLoop.MAX_ATTEMPTS - 1))
            .isEqualTo(ProbeLoop.Verdict.UNANSWERED_RETRY);
    }

    @Test void past_window_at_cap_disengages() {
        // streak-if-unmet reaches MAX → release the want to rest (the healthy close, not a grind)
        assertThat(ProbeLoop.onWindowCheck(ProbeLoop.WINDOW_SECONDS + 1, ProbeLoop.MAX_ATTEMPTS))
            .isEqualTo(ProbeLoop.Verdict.UNANSWERED_DISENGAGE);
    }

    @Test void retry_then_disengage_progression() {
        // The persist→retry→disengage arc: streak 1,2 retry; streak 3 (=MAX) disengages.
        var elapsed = ProbeLoop.WINDOW_SECONDS + 5;
        assertThat(ProbeLoop.onWindowCheck(elapsed, 1)).isEqualTo(ProbeLoop.Verdict.UNANSWERED_RETRY);
        assertThat(ProbeLoop.onWindowCheck(elapsed, 2)).isEqualTo(ProbeLoop.Verdict.UNANSWERED_RETRY);
        assertThat(ProbeLoop.onWindowCheck(elapsed, 3)).isEqualTo(ProbeLoop.Verdict.UNANSWERED_DISENGAGE);
    }

    // ── isAnswer matching (does an inbound reach close the pending probe) ──────────

    @Test void inbound_from_awaited_peer_by_name_is_an_answer() {
        var pending = new ProbeLoop.PendingProbe("Affiliation", "Vesna", Instant.EPOCH, 1);
        assertThat(ProbeLoop.isAnswer(pending, "vesna", "did:abc")).isTrue();   // name match, case-insensitive
    }

    @Test void inbound_from_awaited_peer_by_id_is_an_answer() {
        var pending = new ProbeLoop.PendingProbe("Care", "did:vesna", Instant.EPOCH, 1);
        assertThat(ProbeLoop.isAnswer(pending, "SomeoneElse", "did:vesna")).isTrue();
    }

    @Test void inbound_from_a_different_peer_is_not_an_answer() {
        var pending = new ProbeLoop.PendingProbe("Affiliation", "Vesna", Instant.EPOCH, 1);
        assertThat(ProbeLoop.isAnswer(pending, "Wyrd", "did:wyrd")).isFalse();
    }

    @Test void no_pending_probe_is_never_an_answer() {
        assertThat(ProbeLoop.isAnswer(null, "Vesna", "did:vesna")).isFalse();
    }

    // ── social vs epistemic clock (which drives await on the REAL wall-clock) ─────────

    @Test void affiliation_and_care_await_on_the_real_clock() {
        // Social returns are an inter-agent inference round-trip (real, un-compressible) — their
        // await runs on the wall clock, not the soak-compressible sim clock.
        assertThat(ProbeLoop.isSocialDrive("Affiliation")).isTrue();
        assertThat(ProbeLoop.isSocialDrive("Care")).isTrue();
    }

    @Test void seeking_is_not_a_social_drive() {
        // SEEKING's return is a synchronous query result → sim clock (compressible retries).
        assertThat(ProbeLoop.isSocialDrive("Seeking")).isFalse();
    }

    // ── answer-buffer (credit a close when the target answered after the probe was sent) ─

    @Test void target_answered_after_send_is_a_buffered_answer() {
        // The synchronous onAgentMessage close can miss when two symmetric agents don't coincide;
        // the buffer credits the close at the next window check. Keyed by name (lowercased).
        var pending = new ProbeLoop.PendingProbe("Affiliation", "Vesna", Instant.EPOCH, 1);
        var reached = Map.of("vesna", Instant.EPOCH.plusSeconds(2));   // answered AFTER sentAt
        assertThat(ProbeLoop.answeredSince(pending, reached)).isTrue();
    }

    @Test void target_answered_by_id_is_a_buffered_answer() {
        var pending = new ProbeLoop.PendingProbe("Care", "did:vesna", Instant.EPOCH, 1);
        var reached = Map.of("did:vesna", Instant.EPOCH.plusSeconds(1));
        assertThat(ProbeLoop.answeredSince(pending, reached)).isTrue();
    }

    @Test void a_reach_that_arrived_before_the_probe_was_sent_is_not_an_answer() {
        // A reach BEFORE you reached isn't an answer to YOUR reach (must be strictly after sentAt).
        var pending = new ProbeLoop.PendingProbe("Affiliation", "Vesna", Instant.EPOCH.plusSeconds(10), 1);
        var reached = Map.of("vesna", Instant.EPOCH.plusSeconds(2));   // before sentAt
        assertThat(ProbeLoop.answeredSince(pending, reached)).isFalse();
    }

    @Test void a_reach_from_a_different_peer_is_not_a_buffered_answer() {
        var pending = new ProbeLoop.PendingProbe("Affiliation", "Vesna", Instant.EPOCH, 1);
        var reached = Map.of("wyrd", Instant.EPOCH.plusSeconds(5));    // not the awaited peer
        assertThat(ProbeLoop.answeredSince(pending, reached)).isFalse();
    }

    @Test void no_buffered_reaches_is_not_an_answer() {
        var pending = new ProbeLoop.PendingProbe("Affiliation", "Vesna", Instant.EPOCH, 1);
        assertThat(ProbeLoop.answeredSince(pending, Map.of())).isFalse();
        assertThat(ProbeLoop.answeredSince(null, Map.of("vesna", Instant.now()))).isFalse();
    }
}
