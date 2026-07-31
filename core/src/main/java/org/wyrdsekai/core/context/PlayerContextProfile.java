package org.wyrdsekai.core.context;

import org.wyrdsekai.core.agent.CalendarContext;
import org.wyrdsekai.core.agent.ContextAccessManager;
import org.wyrdsekai.core.agent.LocationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-player aggregated context -- a living model of who this person is, what they
 * care about, and what is happening in their life right now.
 *
 * <p>Combines all permitted data sources (calendar, location, desktop, conversations,
 * email subjects, recent files) into a coherent picture. The key method is
 * {@link #buildFilteredContext(String, ContextAccessManager)}, which returns only data
 * the requesting agent is permitted to see, and CONNECTS data sources when multiple are
 * available (e.g. "Preparing for [meeting] while editing [file]").</p>
 *
 * <p>Privacy model: nothing in Tier 3-4 is stored to disk. All state is in-memory only.
 * Each source is independently gated. Different agents see different views.</p>
 *
 * @see PersonalContextAggregator
 * @see ContextAccessManager
 */
public class PlayerContextProfile {

    /** Duration after which location data is considered stale. */
    private static final Duration LOCATION_STALE_THRESHOLD = Duration.ofMinutes(30);

    private final String playerDid;

    // Tier 2: Permission-gated
    private volatile LocationContext.LocationState locationState;
    private volatile String locationName;
    private volatile String currentApp;
    private volatile String appCategory;
    private volatile List<CalendarContext.CalendarEvent> upcomingEvents = List.of();
    private volatile boolean inMeeting;

    // Tier 1: Always available (conversation topics)
    private volatile List<String> recentTopics = List.of();

    // Tier 3: Sensitive
    private volatile List<String> recentEmailSubjects = List.of();
    private volatile List<String> recentFiles = List.of();

    // Activity tracking
    private volatile Instant lastActive;
    private volatile Instant lastUpdated;
    private volatile Instant locationUpdatedAt;

    public PlayerContextProfile(String playerDid) {
        this.playerDid = playerDid;
        this.lastActive = Instant.now();
        this.lastUpdated = Instant.now();
    }

    public String playerDid() { return playerDid; }

    // --- Update methods ---

    public void updateLocation(LocationContext.LocationState state, String name) {
        this.locationState = state;
        this.locationName = name;
        this.locationUpdatedAt = Instant.now();
        this.lastUpdated = Instant.now();
    }

    public void updateDesktop(String app, String category) {
        this.currentApp = app;
        this.appCategory = category;
        this.lastUpdated = Instant.now();
    }

    public void updateCalendar(List<CalendarContext.CalendarEvent> events) {
        this.upcomingEvents = events != null ? List.copyOf(events) : List.of();
        // Determine if currently in a meeting
        var now = Instant.now();
        this.inMeeting = this.upcomingEvents.stream()
            .anyMatch(e -> !e.isAllDay() && e.start().isBefore(now) && e.end().isAfter(now));
        this.lastUpdated = Instant.now();
    }

    public void updateTopics(List<String> topics) {
        this.recentTopics = topics != null ? List.copyOf(topics) : List.of();
        this.lastUpdated = Instant.now();
    }

    public void updateEmailSubjects(List<String> subjects) {
        this.recentEmailSubjects = subjects != null ? List.copyOf(subjects) : List.of();
        this.lastUpdated = Instant.now();
    }

    public void updateRecentFiles(List<String> files) {
        this.recentFiles = files != null ? List.copyOf(files) : List.of();
        this.lastUpdated = Instant.now();
    }

    public void markActive() {
        this.lastActive = Instant.now();
    }

    // --- Query methods ---

    public boolean isInMeeting() { return inMeeting; }
    public Instant lastActive() { return lastActive; }
    public Instant lastUpdated() { return lastUpdated; }
    public List<String> getRecentTopics() { return recentTopics; }
    public LocationContext.LocationState locationState() { return locationState; }
    public String locationName() { return locationName; }
    public String currentApp() { return currentApp; }
    public String appCategory() { return appCategory; }
    public List<CalendarContext.CalendarEvent> upcomingEvents() { return upcomingEvents; }
    public List<String> recentEmailSubjects() { return recentEmailSubjects; }
    public List<String> recentFiles() { return recentFiles; }

    /** Duration since last player activity. */
    public Duration idleDuration() {
        if (lastActive == null) return Duration.ZERO;
        return Duration.between(lastActive, Instant.now());
    }

    /** Whether the location data is stale (> 30 minutes old). */
    public boolean isLocationStale() {
        return locationUpdatedAt == null
            || Duration.between(locationUpdatedAt, Instant.now()).compareTo(LOCATION_STALE_THRESHOLD) > 0;
    }

    // --- Context building ---

    /**
     * Build full context with ALL data, no permission filtering.
     * Used for debugging / "show context" commands.
     *
     * @return Full context string, or null if profile is completely empty
     */
    public String buildFullContext() {
        return buildContextInternal(true, true, true, true, true);
    }

    /**
     * Build context filtered by what the given agent is permitted to see.
     * Connects dots across data sources when multiple are available.
     *
     * @param agentId       Agent requesting context
     * @param accessManager Permission manager (may be null -- returns Tier 1 only)
     * @return Filtered context string, or null if nothing to show
     */
    public String buildFilteredContext(String agentId, ContextAccessManager accessManager) {
        boolean hasLocation = accessManager != null && accessManager.isGranted(agentId, "location");
        boolean hasCalendar = accessManager != null && accessManager.isGranted(agentId, "calendar");
        boolean hasDesktop = accessManager != null && accessManager.isGranted(agentId, "active_window");
        boolean hasEmail = accessManager != null && accessManager.isGranted(agentId, "email_subjects");
        boolean hasFiles = accessManager != null && accessManager.isGranted(agentId, "files");

        return buildContextInternal(hasLocation, hasCalendar, hasDesktop, hasEmail, hasFiles);
    }

    /**
     * Internal context builder. Includes data sources based on flags and CONNECTS
     * data across sources to produce narrative context.
     */
    private String buildContextInternal(boolean includeLocation, boolean includeCalendar,
                                         boolean includeDesktop, boolean includeEmail,
                                         boolean includeFiles) {
        var sb = new StringBuilder();
        var connections = new ArrayList<String>();

        // --- Connected-dots analysis (cross-source narrative) ---
        String nextMeetingTitle = null;
        long minutesToNextMeeting = -1;
        if (includeCalendar && !upcomingEvents.isEmpty()) {
            var now = Instant.now();
            for (var event : upcomingEvents) {
                if (event.start().isAfter(now) && !event.isAllDay()) {
                    nextMeetingTitle = event.title();
                    minutesToNextMeeting = Duration.between(now, event.start()).toMinutes();
                    break;
                }
            }
        }

        // Calendar + Desktop connection
        if (includeCalendar && includeDesktop && nextMeetingTitle != null && currentApp != null) {
            String connection = "Preparing for " + nextMeetingTitle;
            if (minutesToNextMeeting >= 0 && minutesToNextMeeting <= 60) {
                connection += " in " + minutesToNextMeeting + " minutes";
            }
            connection += " (editing " + currentApp + ")";
            connections.add(connection);
        }

        // Location + Calendar connection
        if (includeLocation && includeCalendar
                && locationState == LocationContext.LocationState.COMMUTING
                && nextMeetingTitle != null) {
            String connection = "Commuting for " + nextMeetingTitle;
            if (minutesToNextMeeting >= 0) {
                connection += " in " + minutesToNextMeeting + " minutes";
            }
            connections.add(connection);
        }

        // Email + Conversation topic overlap
        if (includeEmail && !recentEmailSubjects.isEmpty() && !recentTopics.isEmpty()) {
            for (String topic : recentTopics) {
                String topicLower = topic.toLowerCase();
                for (String subject : recentEmailSubjects) {
                    if (subject.toLowerCase().contains(topicLower)
                            || topicLower.contains(subject.toLowerCase())) {
                        connections.add("Active topic: " + topic + " (email thread and recent discussion)");
                        break;
                    }
                }
            }
        }

        // --- Build the context block ---
        sb.append("## Personal Context\n");

        // Connected insights first (most valuable)
        if (!connections.isEmpty()) {
            for (String c : connections) {
                sb.append("- ").append(c).append("\n");
            }
        }

        // Location
        if (includeLocation && locationState != null
                && locationState != LocationContext.LocationState.UNKNOWN) {
            sb.append("- Location: ");
            if (locationName != null && !locationName.isBlank()) {
                sb.append(locationName);
            } else {
                sb.append(locationState.name().toLowerCase());
            }
            sb.append(" (").append(locationState).append(")");
            if (isLocationStale()) {
                sb.append(" [stale]");
            }
            sb.append("\n");
        }

        // Meeting status
        if (includeCalendar && inMeeting) {
            sb.append("- *Currently in a meeting -- be brief, only interrupt for critical matters.*\n");
        }

        // Upcoming events
        if (includeCalendar && !upcomingEvents.isEmpty()) {
            var now = Instant.now();
            var future = upcomingEvents.stream()
                .filter(e -> e.start().isAfter(now))
                .limit(3)
                .toList();
            if (!future.isEmpty()) {
                sb.append("- Upcoming: ");
                var parts = new ArrayList<String>();
                for (var event : future) {
                    long mins = Duration.between(now, event.start()).toMinutes();
                    String part = event.title();
                    if (mins <= 60) {
                        part += " (in " + mins + "m)";
                    }
                    parts.add(part);
                }
                sb.append(String.join(", ", parts)).append("\n");
            }
        }

        // Desktop activity
        if (includeDesktop && currentApp != null && !currentApp.isBlank()) {
            sb.append("- Activity: ").append(currentApp);
            if (appCategory != null && !appCategory.isBlank()) {
                sb.append(" (").append(appCategory).append(")");
            }
            sb.append("\n");
        }

        // Recent topics (Tier 1 -- always included)
        if (!recentTopics.isEmpty()) {
            sb.append("- Recent topics: ").append(String.join(", ", recentTopics)).append("\n");
        }

        // Email subjects
        if (includeEmail && !recentEmailSubjects.isEmpty()) {
            sb.append("- Email subjects: ").append(
                String.join(", ", recentEmailSubjects.subList(0, Math.min(3, recentEmailSubjects.size())))
            ).append("\n");
        }

        // Recent files
        if (includeFiles && !recentFiles.isEmpty()) {
            sb.append("- Recent files: ").append(
                String.join(", ", recentFiles.subList(0, Math.min(3, recentFiles.size())))
            ).append("\n");
        }

        // Idle duration
        if (lastActive != null) {
            long idleMinutes = idleDuration().toMinutes();
            if (idleMinutes > 0) {
                sb.append("- Last active: ").append(idleMinutes).append("m ago\n");
            } else {
                sb.append("- Last active: just now\n");
            }
        }

        // Return null if only the header was added (no actual data)
        String result = sb.toString();
        if (result.equals("## Personal Context\n")) {
            return null;
        }
        return result;
    }
}
