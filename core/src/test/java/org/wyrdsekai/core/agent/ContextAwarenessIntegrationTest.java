package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for context awareness: location, calendar, watchers,
 * notifications, and salience scoring working together.
 *
 * <p>These tests verify that the context sources compose correctly when
 * wired into the agent's prompt via buildExternalEventContext().</p>
 *
 * @see LocationContext
 * @see CalendarContext
 * @see SalienceScorer
 * @see WatcherService
 * @see NotificationService
 */
class ContextAwarenessIntegrationTest {

    private LocationContext locationCtx;
    private CalendarContext calendarCtx;
    private NotificationService notificationService;
    private WatcherService watcherService;
    private List<String> deliveredMessages;

    @BeforeEach
    void setUp() {
        LocationContext.init();
        locationCtx = LocationContext.get();

        CalendarContext.init();
        calendarCtx = CalendarContext.get();

        notificationService = new NotificationService();
        deliveredMessages = new CopyOnWriteArrayList<>();
        notificationService.setDeliveryCallback((target, notif) ->
            deliveredMessages.add(notif.message()));

        watcherService = new WatcherService(notificationService, script -> {
            return switch (script.strip()) {
                case "true" -> true;
                case "false" -> false;
                default -> script;
            };
        });
    }

    @AfterEach
    void tearDown() {
        LocationContext.reset();
        CalendarContext.reset();
    }

    @Test
    void location_context_builds_for_prompt() {
        locationCtx.update(37.7749, -122.4194, "home");

        String context = locationCtx.buildContext();
        assertThat(context)
            .isNotNull()
            .contains("## Human Location")
            .contains("home")
            .contains("HOME");
    }

    @Test
    void calendar_context_builds_for_prompt() {
        var now = Instant.now();
        calendarCtx.updateEvents(List.of(
            new CalendarContext.CalendarEvent("Sprint Review",
                now.plusSeconds(1200), now.plusSeconds(3600), "Zoom", false)
        ));

        String context = calendarCtx.buildContext();
        assertThat(context)
            .isNotNull()
            .contains("## Upcoming Schedule")
            .contains("Sprint Review")
            .contains("@ Zoom");
    }

    @Test
    void in_meeting_raises_salience_threshold() {
        var now = Instant.now();
        calendarCtx.updateEvents(List.of(
            new CalendarContext.CalendarEvent("Board Meeting",
                now.minusSeconds(600), now.plusSeconds(3000), null, false)
        ));
        assertThat(calendarCtx.isInMeeting()).isTrue();

        var normalVitality = new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.5);

        double normalThreshold = SalienceScorer.calculateAttentionThreshold(normalVitality, false);
        double meetingThreshold = SalienceScorer.calculateAttentionThreshold(normalVitality, true);

        assertThat(meetingThreshold).isEqualTo(normalThreshold + 0.2);
        assertThat(meetingThreshold).isEqualTo(0.7);
    }

    @Test
    void location_change_creates_moderate_salience_event() {
        var event = new AgentEvent.LocationUpdate(
            37.7749, -122.4194, "office",
            LocationContext.LocationState.WORK, Instant.now());

        var normalVitality = new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.5);
        double score = SalienceScorer.score(event, normalVitality, GenomeProfile.defaults());

        // Location update with a known state scores 0.6 (moderate)
        assertThat(score).isGreaterThanOrEqualTo(0.5);
        assertThat(score).isLessThanOrEqualTo(0.7);

        // Passes the default threshold of 0.5
        double threshold = SalienceScorer.calculateAttentionThreshold(normalVitality);
        assertThat(score).isGreaterThanOrEqualTo(threshold);
    }

    @Test
    void watcher_context_alongside_location_and_calendar() {
        // Set up location
        locationCtx.update(35.6762, 139.6503, "office");

        // Set up calendar
        var now = Instant.now();
        calendarCtx.updateEvents(List.of(
            new CalendarContext.CalendarEvent("Team Sync",
                now.plusSeconds(1800), now.plusSeconds(3600), null, false)
        ));

        // Set up watcher
        watcherService.createWatcher("cpu-monitor", "agent-1",
            "true", "5m", "failure", "CPU high!", "critical");

        // Verify each produces context independently
        assertThat(locationCtx.buildContext()).contains("## Human Location");
        assertThat(calendarCtx.buildContext()).contains("## Upcoming Schedule");
        assertThat(watcherService.buildContext("agent-1")).contains("## Active Watchers");
    }

    @Test
    void notification_delivery_tracked_in_context() {
        notificationService.notify("steward", "Build completed", "normal", "agent-1");

        var recent = notificationService.recentForAgent("agent-1", 5);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).message()).isEqualTo("Build completed");
        assertThat(deliveredMessages).containsExactly("Build completed");
    }

    @Test
    void schedule_context_with_calendar_awareness() {
        var now = Instant.now();

        // Calendar says human is in a meeting
        calendarCtx.updateEvents(List.of(
            new CalendarContext.CalendarEvent("Important Meeting",
                now.minusSeconds(300), now.plusSeconds(3300), null, false)
        ));
        assertThat(calendarCtx.isInMeeting()).isTrue();

        // A routine zone broadcast should be filtered during a meeting
        var routine = new AgentEvent.ZoneBroadcast("codeplane", "room-1",
            new S2CMessage.Prose(1L, "zone", "Heartbeat: all nominal",
                List.of(), null, null, null, false, List.of()),
            now);

        var normalVitality = new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.5);
        double score = SalienceScorer.score(routine, normalVitality, GenomeProfile.defaults());
        double meetingThreshold = SalienceScorer.calculateAttentionThreshold(normalVitality, true);

        // Routine broadcast (0.3) should NOT pass meeting threshold (0.7)
        assertThat(score).isLessThan(meetingThreshold);
    }

    @Test
    void all_context_sources_combined() {
        var now = Instant.now();

        // Location
        locationCtx.update(37.7749, -122.4194, "home");

        // Calendar
        calendarCtx.updateEvents(List.of(
            new CalendarContext.CalendarEvent("Doctor Appt",
                now.plusSeconds(3600), now.plusSeconds(7200), "Clinic", false)
        ));

        // Watcher
        watcherService.createWatcher("server-check", "agent-1",
            "true", "1m", "failure", "Server down!", "critical");

        // Notification
        notificationService.notify("steward", "Reminder sent", "normal", "agent-1");

        // Verify all contexts are non-null and contain expected headers
        var locCtx = locationCtx.buildContext();
        var calCtx = calendarCtx.buildContext();
        var watchCtx = watcherService.buildContext("agent-1");
        var notifRecords = notificationService.recentForAgent("agent-1", 5);

        assertThat(locCtx).contains("## Human Location").contains("home").contains("HOME");
        assertThat(calCtx).contains("## Upcoming Schedule").contains("Doctor Appt").contains("@ Clinic");
        assertThat(watchCtx).contains("## Active Watchers").contains("server-check");
        assertThat(notifRecords).hasSize(1);

        // Combine them as buildExternalEventContext would
        var sb = new StringBuilder();
        if (locCtx != null) sb.append(locCtx);
        if (calCtx != null) sb.append(calCtx);
        if (watchCtx != null) sb.append(watchCtx);

        String combined = sb.toString();
        assertThat(combined)
            .contains("## Human Location")
            .contains("## Upcoming Schedule")
            .contains("## Active Watchers")
            .contains("home")
            .contains("Doctor Appt")
            .contains("server-check");
    }

    @Test
    void location_unknown_event_scores_low() {
        var event = new AgentEvent.LocationUpdate(
            0.0, 0.0, "",
            LocationContext.LocationState.UNKNOWN, Instant.now());

        var normalVitality = new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.5);
        double score = SalienceScorer.score(event, normalVitality, GenomeProfile.defaults());

        // UNKNOWN state scores low (routine GPS ping)
        assertThat(score).isLessThanOrEqualTo(0.4);
    }

    @Test
    void meeting_threshold_capped_at_1_0() {
        // Low energy already gives 0.7, plus 0.2 for meeting = 0.9 (still under cap)
        var tiredVitality = new VitalityState(0.5, 0.5, 0.2, 0.3, 0.0, 0.1, 0.3, 0.5);
        double threshold = SalienceScorer.calculateAttentionThreshold(tiredVitality, true);
        assertThat(threshold).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.01));
        assertThat(threshold).isLessThanOrEqualTo(1.0);
    }
}
