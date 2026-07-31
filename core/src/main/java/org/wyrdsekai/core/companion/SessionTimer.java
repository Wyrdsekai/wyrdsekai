package org.wyrdsekai.core.companion;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Session time tracking for child companions (§100.5).
 * Break reminders, daily session limits, configurable by bracket.
 */
public class SessionTimer {

    /** Session state. */
    public record SessionState(
        String childDid,
        Instant sessionStart,
        Duration elapsed,
        Duration limit,
        int breaksTaken,
        boolean limitReached,
        boolean breakNeeded
    ) {}

    /** Break reminder. */
    public record BreakReminder(
        String childDid,
        Duration sessionDuration,
        String message,
        Instant remindedAt
    ) {}

    private final Map<String, Instant> sessionStarts = new HashMap<>();
    private final Map<String, Integer> breakCounts = new HashMap<>();
    private final Map<String, Duration> dailyTotals = new HashMap<>();
    private final Duration breakInterval;

    public SessionTimer() {
        this(Duration.ofMinutes(30));
    }

    public SessionTimer(Duration breakInterval) {
        this.breakInterval = breakInterval;
    }

    /** Start tracking a session. */
    public SessionState startSession(String childDid, Duration sessionLimit) {
        sessionStarts.put(childDid, Instant.now());
        breakCounts.put(childDid, 0);
        return checkSession(childDid, sessionLimit);
    }

    /** Check current session state. */
    public SessionState checkSession(String childDid, Duration sessionLimit) {
        var start = sessionStarts.get(childDid);
        if (start == null) return null;

        var elapsed = Duration.between(start, Instant.now());
        var breaks = breakCounts.getOrDefault(childDid, 0);
        boolean limitReached = elapsed.compareTo(sessionLimit) >= 0;

        // Break needed every breakInterval of continuous use
        var sinceLastBreak = Duration.ofMinutes(
            elapsed.toMinutes() - (breaks * breakInterval.toMinutes()));
        boolean breakNeeded = sinceLastBreak.compareTo(breakInterval) >= 0;

        return new SessionState(childDid, start, elapsed, sessionLimit,
            breaks, limitReached, breakNeeded);
    }

    /** Record that a break was taken. */
    public void recordBreak(String childDid) {
        breakCounts.merge(childDid, 1, Integer::sum);
    }

    /** End a session. Returns total duration. */
    public Duration endSession(String childDid) {
        var start = sessionStarts.remove(childDid);
        if (start == null) return Duration.ZERO;
        var duration = Duration.between(start, Instant.now());
        dailyTotals.merge(childDid, duration, Duration::plus);
        return duration;
    }

    /** Get total time used today. */
    public Duration dailyTotal(String childDid) {
        return dailyTotals.getOrDefault(childDid, Duration.ZERO);
    }

    /** Reset daily totals (call at midnight). */
    public void resetDaily() {
        dailyTotals.clear();
    }

    /** Generate a break reminder message. */
    public BreakReminder breakReminder(String childDid) {
        var elapsed = sessionStarts.containsKey(childDid)
            ? Duration.between(sessionStarts.get(childDid), Instant.now())
            : Duration.ZERO;

        return new BreakReminder(childDid, elapsed,
            "You've been here for a while. How about a break? " +
            "Go stretch, get some water, or look out the window!",
            Instant.now());
    }

    /** Generate a session limit message. */
    public String limitMessage() {
        return "Time's up for today! You've done great. " +
            "Let's pick up again tomorrow. Go enjoy something in the real world!";
    }

    public boolean isSessionActive(String childDid) {
        return sessionStarts.containsKey(childDid);
    }
}
