package org.wyrdsekai.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Protection-flag and deep-sleep timings are settings, defaulting to the numbers the code used to fix. */
class SleepAndFlagSettingsTest {

    @Test
    void defaults() {
        var c = WyrdConfig.forProfile(Map.of());
        assertThat(c.flagSuspectedEscalateDays()).isEqualTo(14);
        assertThat(c.flagSuspectedLiftDays()).isEqualTo(90);
        assertThat(c.flagNotedLiftDays()).isEqualTo(60);
        assertThat(c.deepSleepDeadlineMinutes()).isEqualTo(15);
        assertThat(c.voiceAlignTimeoutMinutes()).isEqualTo(60);
    }

    @Test
    void theProfileSetsThem() {
        var c = WyrdConfig.forProfile(Map.of(
            "protection.suspected_escalate_days", "21",
            "protection.suspected_lift_days", "120",
            "protection.noted_lift_days", "30",
            "sleep.deep_deadline_minutes", "45",
            "sleep.voice_align_timeout_minutes", "40"));
        assertThat(c.flagSuspectedEscalateDays()).isEqualTo(21);
        assertThat(c.flagSuspectedLiftDays()).isEqualTo(120);
        assertThat(c.flagNotedLiftDays()).isEqualTo(30);
        assertThat(c.deepSleepDeadlineMinutes()).isEqualTo(45);
        assertThat(c.voiceAlignTimeoutMinutes()).isEqualTo(40);
    }

    @Test
    void nonsenseFallsBackToTheDefault() {
        var c = WyrdConfig.forProfile(Map.of("sleep.deep_deadline_minutes", "soon"));
        assertThat(c.deepSleepDeadlineMinutes()).isEqualTo(15);
    }
}
