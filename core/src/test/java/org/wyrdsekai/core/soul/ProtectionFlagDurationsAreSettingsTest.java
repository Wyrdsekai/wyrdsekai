package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The flag durations are household settings; the numbers the code used to fix are the defaults. */
class ProtectionFlagDurationsAreSettingsTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final String SUBJECT = "did:key:zSubject";
    private static final String SETTER = "did:key:zSetter";

    @Test
    void theDefaultsAreTheOldNumbers() {
        assertThat(ProtectionFlagTracker.SUSPECTED_TIME_DECAY).isEqualTo(Duration.ofDays(14));
        assertThat(ProtectionFlagTracker.SUSPECTED_NO_SIGNAL_DECAY).isEqualTo(Duration.ofDays(90));
        assertThat(ProtectionFlagTracker.NOTED_NO_SIGNAL_DECAY).isEqualTo(Duration.ofDays(60));
    }

    @Test
    void aShorterEscalationWindowIsHonoured() {
        var t = new ProtectionFlagTracker(Duration.ofDays(3), Duration.ofDays(90), Duration.ofDays(60));
        t.setSuspected(SUBJECT, SETTER, "a sign", T0);
        assertThat(t.setSuspected(SUBJECT, SETTER, "again", T0.plus(Duration.ofDays(2))).state())
            .isEqualTo(ProtectionFlag.State.SUSPECTED);
        assertThat(t.setSuspected(SUBJECT, SETTER, "still", T0.plus(Duration.ofDays(3))).state())
            .isEqualTo(ProtectionFlag.State.CONFIRMED);
    }

    @Test
    void shorterLiftsAreHonoured() {
        var t = new ProtectionFlagTracker(Duration.ofDays(14), Duration.ofDays(10), Duration.ofDays(5));
        t.setNoted(SUBJECT, SETTER, "a sign", T0);
        assertThat(t.decayStaleFlags(T0.plus(Duration.ofDays(4)))).isEmpty();
        assertThat(t.decayStaleFlags(T0.plus(Duration.ofDays(6)))).containsExactly(SUBJECT);

        var s = new ProtectionFlagTracker(Duration.ofDays(14), Duration.ofDays(10), Duration.ofDays(5));
        s.setSuspected(SUBJECT, SETTER, "a sign", T0);
        assertThat(s.decayStaleFlags(T0.plus(Duration.ofDays(9)))).isEmpty();
        assertThat(s.decayStaleFlags(T0.plus(Duration.ofDays(11)))).containsExactly(SUBJECT);
    }
}
