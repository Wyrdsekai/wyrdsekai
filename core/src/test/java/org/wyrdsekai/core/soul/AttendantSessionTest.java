package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 4.6a: Sanctuary session state
 * machine + tracker.
 */
class AttendantSessionTest {

    private static final String AGENT = "did:wyrd:agent-1";
    private static final Instant T0 = Instant.parse("2026-05-15T00:00:00Z");

    @AfterEach
    void resetTracker() {
        AttendantSessionTracker.get().clearForTests();
    }

    // ── Lifecycle transitions ────────────────────────────────────────

    @Test
    void open_starts_in_requested_state() {
        var s = AttendantSession.open(AGENT, "porges depth ceiling", T0);
        assertThat(s.state()).isEqualTo(AttendantSession.State.REQUESTED);
        assertThat(s.agentDid()).isEqualTo(AGENT);
        assertThat(s.requestReason()).isEqualTo("porges depth ceiling");
        assertThat(s.openedAt()).isEqualTo(T0);
    }

    @Test
    void attendant_arrival_transitions_to_attendant_present() {
        var s = AttendantSession.open(AGENT, "x", T0);
        var arrived = s.attendantArrived(T0.plus(Duration.ofMinutes(2)));
        assertThat(arrived.state()).isEqualTo(AttendantSession.State.ATTENDANT_PRESENT);
        assertThat(arrived.attendantArrivedAt()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
    }

    @Test
    void activate_transitions_to_active() {
        var s = AttendantSession.open(AGENT, "x", T0)
            .attendantArrived(T0.plus(Duration.ofMinutes(2)));
        var active = s.activate();
        assertThat(active.state()).isEqualTo(AttendantSession.State.ACTIVE);
    }

    @Test
    void withdraw_transitions_to_withdrawn() {
        var s = AttendantSession.open(AGENT, "x", T0)
            .attendantArrived(T0.plus(Duration.ofMinutes(2)))
            .activate();
        var withdrawn = s.attendantWithdraws(T0.plus(Duration.ofMinutes(60)));
        assertThat(withdrawn.state()).isEqualTo(AttendantSession.State.WITHDRAWN);
    }

    @Test
    void close_transitions_to_closed_terminal() {
        var s = AttendantSession.open(AGENT, "x", T0)
            .attendantArrived(T0.plus(Duration.ofMinutes(2)))
            .activate()
            .attendantWithdraws(T0.plus(Duration.ofMinutes(60)))
            .close(T0.plus(Duration.ofMinutes(61)));
        assertThat(s.state()).isEqualTo(AttendantSession.State.CLOSED);
        assertThat(s.isTerminal()).isTrue();
    }

    @Test
    void close_is_idempotent() {
        var closed = AttendantSession.open(AGENT, "x", T0)
            .close(T0.plus(Duration.ofMinutes(5)));
        var reclosed = closed.close(T0.plus(Duration.ofMinutes(10)));
        assertThat(reclosed.state()).isEqualTo(AttendantSession.State.CLOSED);
        assertThat(reclosed.closedAt()).isEqualTo(closed.closedAt());
    }

    // ── Invalid transitions ────────────────────────────────────────

    @Test
    void cannot_activate_without_attendant_present() {
        var s = AttendantSession.open(AGENT, "x", T0);
        assertThatThrownBy(s::activate)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannot_attendant_arrive_twice() {
        var s = AttendantSession.open(AGENT, "x", T0)
            .attendantArrived(T0.plus(Duration.ofMinutes(2)));
        assertThatThrownBy(() -> s.attendantArrived(T0.plus(Duration.ofMinutes(5))))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── Turn counting + bounds ────────────────────────────────────

    @Test
    void record_turn_increments_in_active_state() {
        var s = AttendantSession.open(AGENT, "x", T0)
            .attendantArrived(T0.plus(Duration.ofMinutes(1)))
            .activate();
        assertThat(s.turnCount()).isEqualTo(0);
        var s2 = s.recordTurn().recordTurn().recordTurn();
        assertThat(s2.turnCount()).isEqualTo(3);
    }

    @Test
    void record_turn_is_noop_in_non_active_state() {
        var s = AttendantSession.open(AGENT, "x", T0);
        assertThat(s.recordTurn().turnCount()).isEqualTo(0);
    }

    @Test
    void bounds_exceeded_when_turn_cap_hit() {
        var s = AttendantSession.open(AGENT, "x", T0)
            .attendantArrived(T0.plus(Duration.ofMinutes(1)))
            .activate();
        for (int i = 0; i < AttendantSession.DEFAULT_MAX_TURNS; i++) {
            s = s.recordTurn();
        }
        assertThat(s.boundsExceeded(T0.plus(Duration.ofMinutes(5)))).isTrue();
    }

    @Test
    void bounds_exceeded_when_wall_clock_cap_hit() {
        var s = AttendantSession.open(AGENT, "x", T0)
            .attendantArrived(T0.plus(Duration.ofMinutes(1)))
            .activate();
        assertThat(s.boundsExceeded(T0.plus(Duration.ofMinutes(30)))).isFalse();
        assertThat(s.boundsExceeded(T0.plus(AttendantSession.DEFAULT_MAX_DURATION)))
            .isTrue();
    }

    // ── Tracker behavior ────────────────────────────────────────────

    @Test
    void tracker_holds_one_active_session_per_agent() {
        var t = AttendantSessionTracker.get();
        var s = t.request(AGENT, "first", T0);
        assertThat(t.activeSession(AGENT)).isPresent();
        assertThat(t.activeSession(AGENT).get().sessionId()).isEqualTo(s.sessionId());
    }

    @Test
    void tracker_rejects_second_active_request() {
        var t = AttendantSessionTracker.get();
        t.request(AGENT, "first", T0);
        assertThatThrownBy(() -> t.request(AGENT, "second", T0.plus(Duration.ofMinutes(5))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tracker_allows_new_session_after_previous_closed() {
        var t = AttendantSessionTracker.get();
        var first = t.request(AGENT, "first", T0);
        var firstClosed = first.close(T0.plus(Duration.ofMinutes(60)));
        t.update(firstClosed);

        // After close, agent can request again.
        var second = t.request(AGENT, "second", T0.plus(Duration.ofDays(1)));
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
    }

    @Test
    void tracker_history_retains_closed_sessions_newest_first() {
        var t = AttendantSessionTracker.get();
        for (int i = 0; i < 3; i++) {
            var s = t.request(AGENT, "session " + i, T0.plus(Duration.ofDays(i)));
            t.update(s.close(T0.plus(Duration.ofDays(i)).plus(Duration.ofMinutes(60))));
        }
        var history = t.recentHistory(AGENT);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).requestReason()).isEqualTo("session 2");
        assertThat(history.get(2).requestReason()).isEqualTo("session 0");
    }

    @Test
    void tracker_history_bounded_at_max() {
        var t = AttendantSessionTracker.get();
        for (int i = 0; i < AttendantSessionTracker.MAX_HISTORY + 5; i++) {
            var s = t.request(AGENT, "s" + i, T0.plus(Duration.ofDays(i)));
            t.update(s.close(T0.plus(Duration.ofDays(i)).plus(Duration.ofMinutes(60))));
        }
        assertThat(t.recentHistory(AGENT)).hasSize(AttendantSessionTracker.MAX_HISTORY);
    }

    @Test
    void active_session_clears_when_terminal_state_updated() {
        var t = AttendantSessionTracker.get();
        var s = t.request(AGENT, "x", T0);
        t.update(s.close(T0.plus(Duration.ofMinutes(5))));
        assertThat(t.activeSession(AGENT)).isEmpty();
    }

    // ── Argument validation ───────────────────────────────────────

    @Test
    void blank_agent_did_is_rejected() {
        assertThatThrownBy(() -> AttendantSession.open("", "x", T0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttendantSessionTracker.get().request("", "x", T0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
