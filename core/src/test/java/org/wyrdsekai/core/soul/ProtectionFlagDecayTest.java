package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B wiring time-decay.
 * SUSPECTED flags with no new signal for 90 days auto-clear.
 * NOTED flags with no new signal for 60 days auto-clear.
 * CONFIRMED and DISPUTED never auto-clear — explicit lift only.
 */
class ProtectionFlagDecayTest {

    private static final String SUBJECT = "did:key:subject";
    private static final String SETTER = "did:key:setter";

    @Test
    void SUSPECTED_no_signal_90_days_clears() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-01-01T00:00:00Z");
        tracker.setSuspected(SUBJECT, SETTER, "concern", t0);
        var future = t0.plus(Duration.ofDays(91));
        var cleared = tracker.decayStaleFlags(future);
        assertThat(cleared).contains(SUBJECT);
        assertThat(tracker.get(SUBJECT)).isEmpty();
    }

    @Test
    void SUSPECTED_89_days_does_not_clear() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-01-01T00:00:00Z");
        tracker.setSuspected(SUBJECT, SETTER, "concern", t0);
        var future = t0.plus(Duration.ofDays(89));
        var cleared = tracker.decayStaleFlags(future);
        assertThat(cleared).isEmpty();
        assertThat(tracker.get(SUBJECT)).isPresent();
        assertThat(tracker.get(SUBJECT).get().state())
            .isEqualTo(ProtectionFlag.State.SUSPECTED);
    }

    @Test
    void NOTED_60_days_clears() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-01-01T00:00:00Z");
        tracker.setNoted(SUBJECT, SETTER, "single observation", t0);
        var future = t0.plus(Duration.ofDays(61));
        var cleared = tracker.decayStaleFlags(future);
        assertThat(cleared).contains(SUBJECT);
    }

    @Test
    void NOTED_59_days_does_not_clear() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-01-01T00:00:00Z");
        tracker.setNoted(SUBJECT, SETTER, "single observation", t0);
        var future = t0.plus(Duration.ofDays(59));
        assertThat(tracker.decayStaleFlags(future)).isEmpty();
        assertThat(tracker.get(SUBJECT)).isPresent();
    }

    @Test
    void CONFIRMED_does_not_auto_clear_even_after_decade() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-01-01T00:00:00Z");
        // Promote to CONFIRMED via 2 setters
        tracker.setSuspected(SUBJECT, SETTER, "first", t0);
        tracker.setSuspected(SUBJECT, "did:key:setter-2", "second", t0.plusSeconds(60));
        assertThat(tracker.get(SUBJECT).get().state())
            .isEqualTo(ProtectionFlag.State.CONFIRMED);

        var distant = t0.plus(Duration.ofDays(3650));
        assertThat(tracker.decayStaleFlags(distant)).isEmpty();
        assertThat(tracker.get(SUBJECT).get().state())
            .isEqualTo(ProtectionFlag.State.CONFIRMED);
    }

    @Test
    void new_signal_resets_decay_clock() {
        var tracker = new ProtectionFlagTracker();
        var t0 = Instant.parse("2026-01-01T00:00:00Z");
        tracker.setSuspected(SUBJECT, SETTER, "first", t0);
        // Add a second signal at day 50 — same setter so won't escalate.
        tracker.setSuspected(SUBJECT, SETTER, "second", t0.plus(Duration.ofDays(50)));
        // Now at day 100: 100 - 50 = 50 days since most recent signal,
        // still under the 90-day threshold.
        var t100 = t0.plus(Duration.ofDays(100));
        var cleared = tracker.decayStaleFlags(t100);
        assertThat(cleared).isEmpty();
    }

    @Test
    void decay_idempotent_when_nothing_to_clear() {
        var tracker = new ProtectionFlagTracker();
        var now = Instant.now();
        assertThat(tracker.decayStaleFlags(now)).isEmpty();
        assertThat(tracker.decayStaleFlags(now)).isEmpty(); // again
    }

    @Test
    void null_now_uses_current_instant() {
        var tracker = new ProtectionFlagTracker();
        tracker.setSuspected(SUBJECT, SETTER, "fresh", Instant.now());
        var cleared = tracker.decayStaleFlags(null);
        assertThat(cleared).isEmpty(); // fresh flag stays
    }
}
