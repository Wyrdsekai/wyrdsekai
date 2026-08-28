package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alarm that was missing. Each test reproduces one signal from the eight-day
 * 2026-08-17 pathology and asserts it would have fired.
 */
class CompanionVitalsTest {

    private static final String DID = "did:key:zTestCompanion";
    private static final Instant T0 = Instant.parse("2026-08-17T09:00:00Z");

    private CompanionVitals vitals;

    @BeforeEach
    void freshMonitor() {
        CompanionVitals.forget(DID);
        vitals = CompanionVitals.forAgent(DID);
    }

    @Test
    void a_runaway_speech_loop_fires() {
        // The real rate was one utterance every ~31 seconds, ~120/hour.
        var now = T0;
        for (int i = 0; i < 120; i++) {
            vitals.recordProactiveUtterance(now);
            now = now.plusSeconds(31);
        }
        assertThat(vitals.check(now)).extracting(CompanionVitals.Alert::signal)
            .contains("proactive_speech_rate");
    }

    @Test
    void a_healthy_speech_rate_stays_quiet() {
        // The design ceiling is ~10/hour; that must not trip anything.
        var now = T0;
        for (int i = 0; i < 10; i++) {
            vitals.recordProactiveUtterance(now);
            now = now.plusSeconds(360);
        }
        assertThat(vitals.check(now)).isEmpty();
    }

    @Test
    void utterances_outside_the_window_do_not_count() {
        for (int i = 0; i < 100; i++) vitals.recordProactiveUtterance(T0);
        // A day later that hour is long past.
        assertThat(vitals.check(T0.plus(Duration.ofDays(1)))).isEmpty();
    }

    @Test
    void a_dead_voice_pass_fires() {
        // A guard rejecting nearly everything means the voice pass is not reaching
        // speech at all. (The 2026-08-17 node sat at ~18%, which must NOT fire.)
        for (int i = 0; i < 25; i++) vitals.recordPolish(false);
        assertThat(vitals.check(T0)).extracting(CompanionVitals.Alert::signal)
            .contains("voice_polish_rejected");
    }

    @Test
    void occasional_polish_rejections_are_normal() {
        // ~20% is what a healthy household node actually measured — never an alarm.
        for (int i = 0; i < 25; i++) vitals.recordPolish(i % 5 != 0);
        assertThat(vitals.check(T0)).isEmpty();
    }

    @Test
    void too_few_polish_samples_is_not_a_verdict() {
        for (int i = 0; i < 5; i++) vitals.recordPolish(false);
        assertThat(vitals.check(T0)).isEmpty();
    }

    @Test
    void a_drive_pinned_for_hours_fires_and_a_dip_clears_it() {
        // Live: seeking sat above 0.9 in 94.8% of consolidation traces for days.
        vitals.observePeakDrive("seeking", 1.0, T0);
        vitals.observePeakDrive("seeking", 0.98, T0.plus(Duration.ofHours(7)));
        assertThat(vitals.check(T0.plus(Duration.ofHours(7))))
            .extracting(CompanionVitals.Alert::signal).contains("drive_pinned");

        freshMonitor();
        vitals.observePeakDrive("seeking", 1.0, T0);
        vitals.observePeakDrive("seeking", 0.4, T0.plus(Duration.ofHours(3)));
        vitals.observePeakDrive("seeking", 1.0, T0.plus(Duration.ofHours(4)));
        assertThat(vitals.check(T0.plus(Duration.ofHours(7)))).isEmpty();
    }

    @Test
    void a_sleep_that_encoded_only_repeats_fires() {
        // Live: ~41 variations of one sentence encoded per sleep.
        vitals.recordForgeEncode(41, 40);
        assertThat(vitals.check(T0)).extracting(CompanionVitals.Alert::signal)
            .contains("experience_not_new");
    }

    @Test
    void a_normal_night_of_mostly_new_memories_stays_quiet() {
        vitals.recordForgeEncode(41, 8);
        assertThat(vitals.check(T0)).isEmpty();
    }

    @Test
    void a_standing_condition_does_not_re_fire_within_the_quiet_period() {
        for (int i = 0; i < 25; i++) vitals.recordPolish(false);
        assertThat(vitals.check(T0)).hasSize(1);
        assertThat(vitals.check(T0.plus(Duration.ofHours(6)))).isEmpty();
        assertThat(vitals.check(T0.plus(Duration.ofHours(25))))
            .extracting(CompanionVitals.Alert::signal).contains("voice_polish_rejected");
    }

    @Test
    void every_signal_can_fire_together() {
        // The pin starts seven hours before the speech burst, so all four conditions
        // hold at the moment of the check — the state the household node was actually in.
        vitals.observePeakDrive("seeking", 1.0, T0);
        var burstStart = T0.plus(Duration.ofHours(7));
        var now = burstStart;
        for (int i = 0; i < 60; i++) {
            vitals.recordProactiveUtterance(now);
            now = now.plusSeconds(31);
        }
        for (int i = 0; i < 25; i++) vitals.recordPolish(false);
        vitals.observePeakDrive("seeking", 1.0, now);
        vitals.recordForgeEncode(41, 40);

        assertThat(vitals.check(now))
            .extracting(CompanionVitals.Alert::signal)
            .containsExactlyInAnyOrder("proactive_speech_rate", "voice_polish_rejected",
                "drive_pinned", "experience_not_new");
    }

    @Test
    void reporting_without_a_notification_service_does_not_throw() {
        for (int i = 0; i < 25; i++) vitals.recordPolish(false);
        vitals.checkAndReport(T0, "a companion");
    }
}
