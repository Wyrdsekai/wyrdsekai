package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feeds upcoming calendar events into the agent's prompt context automatically.
 *
 * <p>Calendar events come from {@link org.wyrdsekai.core.skill.impl.GCalSkillExecutor}
 * or {@link org.wyrdsekai.core.skill.impl.CalDavSkillExecutor}. A periodic
 * CalendarSyncTick in CompanionActor refreshes events every 30 minutes.</p>
 *
 * <p>When the human is in a meeting, the SalienceScorer raises the attention
 * threshold by 0.2 to avoid interrupting with non-critical notifications.</p>
 *
 * <p>Follows the same singleton pattern as {@link WatcherService} and
 * {@link NotificationService}: initialized at startup, accessed via {@link #get()}.</p>
 *
 * @see org.wyrdsekai.core.skill.impl.GCalSkillExecutor
 * @see org.wyrdsekai.core.skill.impl.CalDavSkillExecutor
 * @see SalienceScorer
 */
public class CalendarContext {

    /** A single calendar event. */
    public record CalendarEvent(String title, Instant start, Instant end,
                                String location, boolean isAllDay) {}

    private final List<CalendarEvent> upcomingEvents = new CopyOnWriteArrayList<>();
    private volatile Instant lastSynced;

    /** Global instance -- initialized by Main.java at startup. */
    private static volatile CalendarContext instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() { instance = new CalendarContext(); }

    /** Get the global instance. May be null if not initialized. */
    public static CalendarContext get() { return instance; }

    /** Reset for testing. */
    static void reset() { instance = null; }

    /**
     * Update from calendar skill result.
     * Replaces the entire event list (full refresh model).
     */
    public void updateEvents(List<CalendarEvent> events) {
        upcomingEvents.clear();
        if (events != null) {
            upcomingEvents.addAll(events);
        }
        lastSynced = Instant.now();
    }

    /**
     * Get events starting in the next {@code window} duration.
     * Sorted by start time.
     */
    public List<CalendarEvent> upcoming(Duration window) {
        var now = Instant.now();
        var cutoff = now.plus(window);
        return upcomingEvents.stream()
            .filter(e -> e.start().isBefore(cutoff) && e.start().isAfter(now))
            .sorted(Comparator.comparing(CalendarEvent::start))
            .toList();
    }

    /**
     * Build context string for the agent's prompt.
     * Returns null if no upcoming events (gracefully absent).
     */
    public String buildContext() {
        var now = Instant.now();
        var upcoming = upcoming(Duration.ofHours(4));
        if (upcoming.isEmpty()) return null;
        var sb = new StringBuilder("## Upcoming Schedule\n");
        for (var event : upcoming) {
            long minutesUntil = Duration.between(now, event.start()).toMinutes();
            sb.append("- ").append(event.title());
            if (minutesUntil <= 60) {
                sb.append(" (in ").append(minutesUntil).append("m)");
            } else {
                // Show time as HH:mm
                String isoTime = event.start().toString();
                if (isoTime.length() >= 16) {
                    sb.append(" (at ").append(isoTime, 11, 16).append(")");
                }
            }
            if (event.location() != null && !event.location().isBlank()) {
                sb.append(" @ ").append(event.location());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Is the human currently in a non-all-day meeting?
     * Used by SalienceScorer to raise the attention threshold.
     */
    public boolean isInMeeting() {
        var now = Instant.now();
        return upcomingEvents.stream()
            .anyMatch(e -> !e.isAllDay()
                        && e.start().isBefore(now)
                        && e.end().isAfter(now));
    }

    /** When the calendar was last synced. Null if never synced. */
    public Instant lastSynced() { return lastSynced; }

    /** How many events are currently tracked. */
    public int eventCount() { return upcomingEvents.size(); }
}
