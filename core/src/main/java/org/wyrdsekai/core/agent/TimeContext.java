package org.wyrdsekai.core.agent;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Builds a time-awareness context string for agent prompts.
 *
 * Gives the agent a sense of:
 * - Current wall-clock time and date
 * - Time of day (morning, afternoon, evening, night, late night)
 * - Day of week
 * - How long since the human last spoke
 * - How long the agent has been awake (since last sleep)
 *
 * Injected at Layer 3 (context scope) in PromptAssembler.
 * Compact format — ~30-40 tokens total.
 */
public final class TimeContext {

    private TimeContext() {}

    /**
     * Build the time context string for prompt injection.
     *
     * @param zone          The timezone to display (e.g. ZoneId.systemDefault())
     * @param lastHumanSaid When the human last spoke (null if never)
     * @param awokeSince    When the agent last woke from sleep (null if never slept)
     * @return A compact context string, or empty if zone is null
     */
    public static String build(ZoneId zone, Instant lastHumanSaid, Instant awokeSince) {
        if (zone == null) zone = ZoneId.systemDefault();

        var now = ZonedDateTime.now(zone);
        var sb = new StringBuilder();

        // Current time + date
        sb.append("Current time: ");
        sb.append(now.format(DateTimeFormatter.ofPattern("HH:mm")));
        sb.append(", ");
        sb.append(now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        sb.append(" ");
        sb.append(now.format(DateTimeFormatter.ofPattern("MMMM d, yyyy")));

        // Time of day label
        sb.append(" (");
        sb.append(timeOfDay(now.getHour()));
        sb.append(").");

        // Time since human last spoke
        if (lastHumanSaid != null && lastHumanSaid.isAfter(Instant.EPOCH)) {
            var elapsed = Duration.between(lastHumanSaid, Instant.now());
            if (elapsed.toMinutes() >= 1) {
                sb.append(" Last heard from you: ");
                sb.append(formatDuration(elapsed));
                sb.append(" ago.");
            }
        }

        // Time awake
        if (awokeSince != null && awokeSince.isAfter(Instant.EPOCH)) {
            var awake = Duration.between(awokeSince, Instant.now());
            if (awake.toMinutes() >= 5) {
                sb.append(" Awake for ");
                sb.append(formatDuration(awake));
                sb.append(".");
            }
        }

        return sb.toString();
    }

    /**
     * Build with system default timezone.
     */
    public static String build(Instant lastHumanSaid, Instant awokeSince) {
        return build(ZoneId.systemDefault(), lastHumanSaid, awokeSince);
    }

    static String timeOfDay(int hour) {
        if (hour >= 5 && hour < 12) return "morning";
        if (hour >= 12 && hour < 17) return "afternoon";
        if (hour >= 17 && hour < 21) return "evening";
        if (hour >= 21 || hour < 2) return "night";
        return "late night";
    }

    static String formatDuration(Duration d) {
        long totalMinutes = d.toMinutes();
        if (totalMinutes < 2) return "a moment";
        if (totalMinutes < 60) return totalMinutes + " minutes";
        long hours = d.toHours();
        long remainingMinutes = totalMinutes - (hours * 60);
        if (hours < 24) {
            if (remainingMinutes == 0) return hours + (hours == 1 ? " hour" : " hours");
            return hours + "h " + remainingMinutes + "m";
        }
        long days = d.toDays();
        long remainingHours = hours - (days * 24);
        if (remainingHours == 0) return days + (days == 1 ? " day" : " days");
        return days + (days == 1 ? " day " : " days ") + remainingHours + "h";
    }
}
