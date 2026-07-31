package org.wyrdsekai.core.app.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FamilyHubServiceTest {

    private FamilyHubService service;

    @BeforeEach void setUp() {
        service = new FamilyHubService();
    }

    // ── Calendar Tests ──

    @Test void addEvent_creates_event() {
        var event = service.addEvent("Dinner", "Family dinner",
            Instant.now(), Instant.now().plus(Duration.ofHours(1)),
            "parent", Set.of("alice", "bob"),
            FamilyHubService.EventType.MEAL);
        assertThat(event.title()).isEqualTo("Dinner");
        assertThat(service.eventCount()).isEqualTo(1);
    }

    @Test void eventsForDate_returns_todays_events() {
        var now = Instant.now();
        service.addEvent("Morning Meeting", "standup",
            now, now.plus(Duration.ofMinutes(30)),
            "parent", Set.of(), FamilyHubService.EventType.APPOINTMENT);
        var today = LocalDate.now();
        assertThat(service.eventsForDate(today)).hasSize(1);
    }

    @Test void upcomingEvents_returns_future_events() {
        var future = Instant.now().plus(Duration.ofHours(2));
        service.addEvent("Later", "future event",
            future, future.plus(Duration.ofHours(1)),
            "parent", Set.of(), FamilyHubService.EventType.ACTIVITY);
        assertThat(service.upcomingEvents(24)).hasSize(1);
    }

    // ── Chore Tests ──

    @Test void assignChore_creates_pending() {
        var chore = service.assignChore("Dishes", "alice",
            Instant.now().plus(Duration.ofHours(4)), 10);
        assertThat(chore.status()).isEqualTo(FamilyHubService.ChoreStatus.PENDING);
        assertThat(chore.points()).isEqualTo(10);
    }

    @Test void completeChore_awards_points() {
        var chore = service.assignChore("Vacuum", "bob",
            Instant.now().plus(Duration.ofDays(1)), 15);
        var completed = service.completeChore(chore.choreId(), "bob");
        assertThat(completed).isPresent();
        assertThat(completed.get().status()).isEqualTo(FamilyHubService.ChoreStatus.COMPLETED);
        assertThat(service.pointsEarned("bob")).isEqualTo(15);
    }

    @Test void completeChore_rejects_wrong_person() {
        var chore = service.assignChore("Laundry", "alice",
            Instant.now().plus(Duration.ofDays(1)), 5);
        var result = service.completeChore(chore.choreId(), "bob");
        assertThat(result).isEmpty();
    }

    @Test void pendingChores_excludes_completed() {
        var chore = service.assignChore("Trash", "alice", Instant.now(), 5);
        assertThat(service.pendingChores()).hasSize(1);
        service.completeChore(chore.choreId(), "alice");
        assertThat(service.pendingChores()).isEmpty();
    }

    // ── Notice Board Tests ──

    @Test void postNotice_adds_to_board() {
        service.postNotice("WiFi Password Changed", "New: abc123",
            "parent", FamilyHubService.NoticePriority.HIGH, true);
        assertThat(service.noticeCount()).isEqualTo(1);
        assertThat(service.allNotices().get(0).pinned()).isTrue();
    }

    @Test void urgentNotices_filters_high_priority() {
        service.postNotice("Low priority", "not urgent", "parent",
            FamilyHubService.NoticePriority.LOW, false);
        service.postNotice("Urgent!", "very urgent", "parent",
            FamilyHubService.NoticePriority.URGENT, false);
        assertThat(service.urgentNotices()).hasSize(1);
    }

    // ── Screen Time Tests ──

    @Test void screenTimeBudget_tracks_usage() {
        service.setScreenTimeBudget("child", Duration.ofHours(2), true);
        service.recordScreenTime("child", Duration.ofMinutes(30));
        var budget = service.getScreenTimeBudget("child");
        assertThat(budget).isPresent();
        assertThat(budget.get().remaining()).isEqualTo(Duration.ofMinutes(90));
        assertThat(budget.get().isExceeded()).isFalse();
    }

    @Test void screenTimeBudget_detects_exceeded() {
        service.setScreenTimeBudget("child", Duration.ofHours(1), true);
        service.recordScreenTime("child", Duration.ofMinutes(70));
        var budget = service.getScreenTimeBudget("child");
        assertThat(budget.get().isExceeded()).isTrue();
    }

    @Test void resetDailyScreenTime_clears_usage() {
        service.setScreenTimeBudget("child", Duration.ofHours(2), true);
        service.recordScreenTime("child", Duration.ofMinutes(90));
        service.resetDailyScreenTime();
        var budget = service.getScreenTimeBudget("child");
        assertThat(budget.get().usedToday()).isEqualTo(Duration.ZERO);
    }

    @Test void describe_shows_summary() {
        service.postNotice("Test", "content", "parent",
            FamilyHubService.NoticePriority.NORMAL, false);
        assertThat(service.describe()).contains("Family Hub");
    }
}
