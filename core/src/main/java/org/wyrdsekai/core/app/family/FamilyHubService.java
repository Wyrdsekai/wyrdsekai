package org.wyrdsekai.core.app.family;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Family Hub service (§15).
 * Shared calendar, chore tracking, notice board, screen-time enforcement.
 *
 * M0 scope: core calendar, chores, notices.
 * M2+: screen-time enforcement, family governance, multi-household federation.
 */
public class FamilyHubService {

    /** A calendar event. */
    public record CalendarEvent(
        String eventId,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        String createdBy,
        Set<String> participants,
        boolean recurring,
        EventType type
    ) {}

    public enum EventType {
        APPOINTMENT, MEAL, ACTIVITY, SCHOOL, WORK, REMINDER, CUSTOM
    }

    /** A household chore. */
    public record Chore(
        String choreId,
        String title,
        String assignee,
        ChoreStatus status,
        Instant dueDate,
        Instant completedAt,
        int points   // reward points for completion
    ) {}

    public enum ChoreStatus { PENDING, IN_PROGRESS, COMPLETED, OVERDUE }

    /** A notice board posting. */
    public record Notice(
        String noticeId,
        String title,
        String content,
        String postedBy,
        Instant postedAt,
        NoticePriority priority,
        boolean pinned
    ) {}

    public enum NoticePriority { LOW, NORMAL, HIGH, URGENT }

    /** Screen-time budget for a household member. */
    public record ScreenTimeBudget(
        String memberId,
        Duration dailyLimit,
        Duration usedToday,
        boolean enforced
    ) {
        public Duration remaining() {
            var diff = dailyLimit.minus(usedToday);
            return diff.isNegative() ? Duration.ZERO : diff;
        }

        public boolean isExceeded() {
            return usedToday.compareTo(dailyLimit) >= 0;
        }
    }

    private final FamilyPersistence persistence; // nullable
    private final Map<String, CalendarEvent> events = new ConcurrentHashMap<>();
    private final Map<String, Chore> chores = new ConcurrentHashMap<>();
    private final Map<String, Notice> notices = new ConcurrentHashMap<>();
    private final Map<String, ScreenTimeBudget> screenTimeBudgets = new ConcurrentHashMap<>();
    private int nextEventId = 1;
    private int nextChoreId = 1;
    private int nextNoticeId = 1;

    public FamilyHubService() { this(null); }

    public FamilyHubService(FamilyPersistence persistence) {
        this.persistence = persistence;
    }

    // ── Calendar ──

    /** Add a calendar event. */
    public CalendarEvent addEvent(String title, String description,
                                    Instant start, Instant end, String createdBy,
                                    Set<String> participants, EventType type) {
        var id = "event-" + nextEventId++;
        var event = new CalendarEvent(id, title, description, start, end,
            createdBy, participants, false, type);
        events.put(id, event);
        if (persistence != null) persistence.saveEvent(event);
        return event;
    }

    /** Get events for a date. */
    public List<CalendarEvent> eventsForDate(LocalDate date) {
        var dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant();
        var dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        return events.values().stream()
            .filter(e -> !e.endTime().isBefore(dayStart) && e.startTime().isBefore(dayEnd))
            .sorted(Comparator.comparing(CalendarEvent::startTime))
            .toList();
    }

    /** Get upcoming events (next N hours). */
    public List<CalendarEvent> upcomingEvents(int hours) {
        var now = Instant.now();
        var cutoff = now.plus(Duration.ofHours(hours));
        return events.values().stream()
            .filter(e -> !e.startTime().isBefore(now) && e.startTime().isBefore(cutoff))
            .sorted(Comparator.comparing(CalendarEvent::startTime))
            .toList();
    }

    // ── Chores ──

    /** Assign a chore. */
    public Chore assignChore(String title, String assignee, Instant dueDate, int points) {
        var id = "chore-" + nextChoreId++;
        var chore = new Chore(id, title, assignee, ChoreStatus.PENDING,
            dueDate, null, points);
        chores.put(id, chore);
        if (persistence != null) persistence.saveChore(chore);
        return chore;
    }

    /** Complete a chore. */
    public Optional<Chore> completeChore(String choreId, String completedBy) {
        var chore = chores.get(choreId);
        if (chore == null) return Optional.empty();
        if (!chore.assignee().equals(completedBy)) return Optional.empty();
        var completed = new Chore(chore.choreId(), chore.title(), chore.assignee(),
            ChoreStatus.COMPLETED, chore.dueDate(), Instant.now(), chore.points());
        chores.put(choreId, completed);
        if (persistence != null) persistence.saveChore(completed);
        return Optional.of(completed);
    }

    /** Get chores for a member. */
    public List<Chore> choresFor(String assignee) {
        return chores.values().stream()
            .filter(c -> c.assignee().equals(assignee))
            .sorted(Comparator.comparing(Chore::dueDate))
            .toList();
    }

    /** Get pending chores. */
    public List<Chore> pendingChores() {
        return chores.values().stream()
            .filter(c -> c.status() == ChoreStatus.PENDING || c.status() == ChoreStatus.IN_PROGRESS)
            .sorted(Comparator.comparing(Chore::dueDate))
            .toList();
    }

    /** Calculate total points earned by a member. */
    public int pointsEarned(String memberId) {
        return chores.values().stream()
            .filter(c -> c.assignee().equals(memberId) && c.status() == ChoreStatus.COMPLETED)
            .mapToInt(Chore::points)
            .sum();
    }

    // ── Notice Board ──

    /** Post a notice. */
    public Notice postNotice(String title, String content, String postedBy,
                              NoticePriority priority, boolean pinned) {
        var id = "notice-" + nextNoticeId++;
        var notice = new Notice(id, title, content, postedBy, Instant.now(), priority, pinned);
        notices.put(id, notice);
        if (persistence != null) persistence.saveNotice(notice);
        return notice;
    }

    /** Get all notices (pinned first, then by recency). */
    public List<Notice> allNotices() {
        return notices.values().stream()
            .sorted(Comparator.comparing(Notice::pinned).reversed()
                .thenComparing(Comparator.comparing(Notice::postedAt).reversed()))
            .toList();
    }

    /** Get urgent notices. */
    public List<Notice> urgentNotices() {
        return notices.values().stream()
            .filter(n -> n.priority() == NoticePriority.URGENT || n.priority() == NoticePriority.HIGH)
            .sorted(Comparator.comparing(Notice::postedAt).reversed())
            .toList();
    }

    // ── Screen Time ──

    /** Set screen-time budget for a member. */
    public ScreenTimeBudget setScreenTimeBudget(String memberId, Duration dailyLimit,
                                                  boolean enforced) {
        var budget = new ScreenTimeBudget(memberId, dailyLimit, Duration.ZERO, enforced);
        screenTimeBudgets.put(memberId, budget);
        return budget;
    }

    /** Record screen time usage. */
    public Optional<ScreenTimeBudget> recordScreenTime(String memberId, Duration usage) {
        var budget = screenTimeBudgets.get(memberId);
        if (budget == null) return Optional.empty();
        var updated = new ScreenTimeBudget(memberId, budget.dailyLimit(),
            budget.usedToday().plus(usage), budget.enforced());
        screenTimeBudgets.put(memberId, updated);
        return Optional.of(updated);
    }

    /** Get screen-time budget for a member. */
    public Optional<ScreenTimeBudget> getScreenTimeBudget(String memberId) {
        return Optional.ofNullable(screenTimeBudgets.get(memberId));
    }

    /** Reset all daily screen-time counters. */
    public void resetDailyScreenTime() {
        screenTimeBudgets.replaceAll((id, budget) ->
            new ScreenTimeBudget(id, budget.dailyLimit(), Duration.ZERO, budget.enforced()));
    }

    // ── Stats ──

    public int eventCount() { return events.size(); }
    public int choreCount() { return chores.size(); }
    public int noticeCount() { return notices.size(); }

    /** Human-readable summary. */
    public String describe() {
        var sb = new StringBuilder("=== Family Hub ===\n\n");
        sb.append("Calendar events: ").append(events.size()).append("\n");
        sb.append("Active chores: ").append(pendingChores().size()).append("\n");
        sb.append("Notices: ").append(notices.size()).append("\n");

        var upcoming = upcomingEvents(24);
        if (!upcoming.isEmpty()) {
            sb.append("\nUpcoming (24h):\n");
            upcoming.stream().limit(5).forEach(e ->
                sb.append("  ").append(e.title())
                    .append(" — ").append(e.type())
                    .append("\n"));
        }

        var urgent = urgentNotices();
        if (!urgent.isEmpty()) {
            sb.append("\nUrgent notices:\n");
            urgent.stream().limit(3).forEach(n ->
                sb.append("  [!] ").append(n.title()).append("\n"));
        }

        return sb.toString().stripTrailing();
    }
}
