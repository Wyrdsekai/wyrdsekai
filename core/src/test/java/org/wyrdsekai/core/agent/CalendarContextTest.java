package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CalendarContext} -- calendar-to-context feeding for agents.
 */
class CalendarContextTest {

    private CalendarContext ctx;

    @BeforeEach
    void setUp() {
        CalendarContext.init();
        ctx = CalendarContext.get();
    }

    @AfterEach
    void tearDown() {
        CalendarContext.reset();
    }

    @Test
    void update_events_and_retrieve_upcoming() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Standup", now.plusSeconds(1800), now.plusSeconds(3600), null, false),
            new CalendarContext.CalendarEvent("Lunch", now.plusSeconds(7200), now.plusSeconds(10800), "Cafe", false)
        );

        ctx.updateEvents(events);

        assertThat(ctx.eventCount()).isEqualTo(2);
        assertThat(ctx.lastSynced()).isNotNull();

        var upcoming = ctx.upcoming(Duration.ofHours(4));
        assertThat(upcoming).hasSize(2);
        assertThat(upcoming.get(0).title()).isEqualTo("Standup");
        assertThat(upcoming.get(1).title()).isEqualTo("Lunch");
    }

    @Test
    void filter_by_time_window() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Soon", now.plusSeconds(600), now.plusSeconds(1200), null, false),
            new CalendarContext.CalendarEvent("Later", now.plusSeconds(7200), now.plusSeconds(10800), null, false),
            new CalendarContext.CalendarEvent("Much Later", now.plusSeconds(36000), now.plusSeconds(39600), null, false)
        );
        ctx.updateEvents(events);

        // 1-hour window should only include "Soon"
        var oneHour = ctx.upcoming(Duration.ofHours(1));
        assertThat(oneHour).hasSize(1);
        assertThat(oneHour.get(0).title()).isEqualTo("Soon");

        // 3-hour window should include "Soon" and "Later"
        var threeHours = ctx.upcoming(Duration.ofHours(3));
        assertThat(threeHours).hasSize(2);
    }

    @Test
    void isInMeeting_returns_true_during_event() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Current Meeting",
                now.minusSeconds(600), now.plusSeconds(1800), null, false)
        );
        ctx.updateEvents(events);

        assertThat(ctx.isInMeeting()).isTrue();
    }

    @Test
    void isInMeeting_returns_false_between_events() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Past Meeting",
                now.minusSeconds(3600), now.minusSeconds(1800), null, false),
            new CalendarContext.CalendarEvent("Future Meeting",
                now.plusSeconds(1800), now.plusSeconds(3600), null, false)
        );
        ctx.updateEvents(events);

        assertThat(ctx.isInMeeting()).isFalse();
    }

    @Test
    void allDay_events_excluded_from_isInMeeting() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Holiday",
                now.minusSeconds(3600), now.plusSeconds(86400), null, true)
        );
        ctx.updateEvents(events);

        assertThat(ctx.isInMeeting()).isFalse();
    }

    @Test
    void buildContext_formats_correctly() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Team Standup",
                now.plusSeconds(1200), now.plusSeconds(3000), "Room A", false)
        );
        ctx.updateEvents(events);

        String context = ctx.buildContext();
        assertThat(context).isNotNull();
        assertThat(context).contains("## Upcoming Schedule");
        assertThat(context).contains("Team Standup");
        assertThat(context).contains("@ Room A");
    }

    @Test
    void buildContext_shows_minutes_for_near_events() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Quick Sync",
                now.plusSeconds(900), now.plusSeconds(1800), null, false)
        );
        ctx.updateEvents(events);

        String context = ctx.buildContext();
        assertThat(context).isNotNull();
        // 900 seconds = 15 minutes, should show "in Xm"
        assertThat(context).contains("(in ");
        assertThat(context).contains("m)");
    }

    @Test
    void empty_calendar_returns_null_context() {
        assertThat(ctx.buildContext()).isNull();

        // Also null after setting empty list
        ctx.updateEvents(List.of());
        assertThat(ctx.buildContext()).isNull();
    }

    @Test
    void updateEvents_replaces_existing() {
        var now = Instant.now();
        ctx.updateEvents(List.of(
            new CalendarContext.CalendarEvent("Event A",
                now.plusSeconds(600), now.plusSeconds(1200), null, false)
        ));
        assertThat(ctx.eventCount()).isEqualTo(1);

        ctx.updateEvents(List.of(
            new CalendarContext.CalendarEvent("Event B",
                now.plusSeconds(600), now.plusSeconds(1200), null, false),
            new CalendarContext.CalendarEvent("Event C",
                now.plusSeconds(1800), now.plusSeconds(3000), null, false)
        ));
        assertThat(ctx.eventCount()).isEqualTo(2);

        var upcoming = ctx.upcoming(Duration.ofHours(4));
        assertThat(upcoming).extracting(CalendarContext.CalendarEvent::title)
            .containsExactly("Event B", "Event C");
    }

    @Test
    void past_events_excluded_from_upcoming() {
        var now = Instant.now();
        var events = List.of(
            new CalendarContext.CalendarEvent("Past",
                now.minusSeconds(3600), now.minusSeconds(1800), null, false),
            new CalendarContext.CalendarEvent("Future",
                now.plusSeconds(600), now.plusSeconds(1200), null, false)
        );
        ctx.updateEvents(events);

        var upcoming = ctx.upcoming(Duration.ofHours(4));
        assertThat(upcoming).hasSize(1);
        assertThat(upcoming.get(0).title()).isEqualTo("Future");
    }
}
