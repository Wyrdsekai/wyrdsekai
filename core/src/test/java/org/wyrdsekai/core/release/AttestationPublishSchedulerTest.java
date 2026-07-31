package org.wyrdsekai.core.release;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationPublishSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-05-17T12:00:00Z");

    @Test
    void null_input_skips() {
        var d = AttestationPublishScheduler.decide(null, NOW);
        assertThat(d.shouldPublish()).isFalse();
    }

    @Test
    void nostr_disabled_skips() {
        var in = new AttestationPublishScheduler.Input(
            Optional.empty(), Optional.empty(), Optional.empty(), false, false);
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isFalse();
        assertThat(d.reason()).isEqualTo(AttestationPublishScheduler.Reason.NO_PUBLISH_NEEDED);
    }

    @Test
    void first_session_publishes() {
        var in = AttestationPublishScheduler.Input.empty();
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isTrue();
        assertThat(d.reason()).isEqualTo(AttestationPublishScheduler.Reason.FIRST_THIS_SESSION);
    }

    @Test
    void significant_transition_publishes() {
        var in = new AttestationPublishScheduler.Input(
            Optional.of(NOW.minus(Duration.ofMinutes(10))),
            Optional.of("h1"), Optional.of("h1"), true, true);
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isTrue();
        assertThat(d.reason()).isEqualTo(AttestationPublishScheduler.Reason.SIGNIFICANT_TRANSITION);
    }

    @Test
    void config_changed_publishes() {
        var in = new AttestationPublishScheduler.Input(
            Optional.of(NOW.minus(Duration.ofMinutes(10))),
            Optional.of("h2"), Optional.of("h1"), false, true);
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isTrue();
        assertThat(d.reason()).isEqualTo(AttestationPublishScheduler.Reason.CONFIGURATION_CHANGED);
    }

    @Test
    void same_manifest_within_7_days_skips() {
        var in = new AttestationPublishScheduler.Input(
            Optional.of(NOW.minus(Duration.ofDays(3))),
            Optional.of("h1"), Optional.of("h1"), false, true);
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isFalse();
    }

    @Test
    void periodic_refresh_at_7_days_publishes() {
        var in = new AttestationPublishScheduler.Input(
            Optional.of(NOW.minus(Duration.ofDays(7))),
            Optional.of("h1"), Optional.of("h1"), false, true);
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isTrue();
        assertThat(d.reason()).isEqualTo(AttestationPublishScheduler.Reason.PERIODIC_REFRESH);
    }

    @Test
    void periodic_refresh_after_8_days_publishes() {
        var in = new AttestationPublishScheduler.Input(
            Optional.of(NOW.minus(Duration.ofDays(8))),
            Optional.of("h1"), Optional.of("h1"), false, true);
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isTrue();
    }

    @Test
    void current_manifest_set_no_prior_publish_treats_as_config_changed() {
        var in = new AttestationPublishScheduler.Input(
            Optional.of(NOW.minus(Duration.ofMinutes(10))),
            Optional.of("h1"), Optional.empty(), false, true);
        var d = AttestationPublishScheduler.decide(in, NOW);
        assertThat(d.shouldPublish()).isTrue();
        assertThat(d.reason()).isEqualTo(AttestationPublishScheduler.Reason.CONFIGURATION_CHANGED);
    }
}
