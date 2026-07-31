package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

class TimeContextTest {

    @Test
    void build_includes_current_time_and_date() {
        String ctx = TimeContext.build(null, null);
        assertThat(ctx).startsWith("Current time: ");
        assertThat(ctx).containsPattern("\\d{2}:\\d{2}");  // HH:mm
        assertThat(ctx).contains("2026"); // current year
    }

    @Test
    void build_includes_time_of_day() {
        String ctx = TimeContext.build(null, null);
        assertThat(ctx).containsAnyOf("morning", "afternoon", "evening", "night", "late night");
    }

    @Test
    void build_includes_elapsed_when_last_human_said() {
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        String ctx = TimeContext.build(tenMinutesAgo, null);
        assertThat(ctx).contains("Last heard from you: 10 minutes ago.");
    }

    @Test
    void build_omits_elapsed_when_recent() {
        Instant justNow = Instant.now().minus(Duration.ofSeconds(30));
        String ctx = TimeContext.build(justNow, null);
        assertThat(ctx).doesNotContain("Last heard from you");
    }

    @Test
    void build_includes_awake_duration() {
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        String ctx = TimeContext.build(null, twoHoursAgo);
        assertThat(ctx).contains("Awake for 2 hours.");
    }

    @Test
    void time_of_day_labels() {
        assertThat(TimeContext.timeOfDay(6)).isEqualTo("morning");
        assertThat(TimeContext.timeOfDay(14)).isEqualTo("afternoon");
        assertThat(TimeContext.timeOfDay(19)).isEqualTo("evening");
        assertThat(TimeContext.timeOfDay(23)).isEqualTo("night");
        assertThat(TimeContext.timeOfDay(3)).isEqualTo("late night");
    }

    @Test
    void format_duration_minutes() {
        assertThat(TimeContext.formatDuration(Duration.ofMinutes(45))).isEqualTo("45 minutes");
    }

    @Test
    void format_duration_hours() {
        assertThat(TimeContext.formatDuration(Duration.ofHours(3))).isEqualTo("3 hours");
    }

    @Test
    void format_duration_hours_and_minutes() {
        assertThat(TimeContext.formatDuration(Duration.ofMinutes(150))).isEqualTo("2h 30m");
    }

    @Test
    void format_duration_days() {
        assertThat(TimeContext.formatDuration(Duration.ofDays(2))).isEqualTo("2 days");
    }
}
