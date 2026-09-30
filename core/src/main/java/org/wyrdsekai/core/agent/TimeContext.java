package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.inference.NowLine;

import java.time.*;

/**
 * Builds the elapsed-time context for agent prompts: how long since the human
 * last spoke, how long the agent has been awake. Injected at Layer 3 in
 * PromptAssembler.
 *
 * <p>The date and the time are not here any more. They were, from 2026-03-28,
 * but Layer 3 is trimmable and sits in the middle of the merged system prompt,
 * and every path built outside PromptAssembler never had them. Every request
 * now says what it knows about today and the router stamps it
 * ({@link NowLine}).</p>
 */
public final class TimeContext {

    private TimeContext() {}

    /**
     * Build the elapsed-time context string for prompt injection.
     *
     * @param lastHumanSaid When the human last spoke (null if never)
     * @param awokeSince    When the agent last woke from sleep (null if never slept)
     * @return A compact context string, or empty when there is nothing to say
     */
    public static String build(Instant lastHumanSaid, Instant awokeSince) {
        var sb = new StringBuilder();

        // Time since human last spoke
        if (lastHumanSaid != null && lastHumanSaid.isAfter(Instant.EPOCH)) {
            var elapsed = Duration.between(lastHumanSaid, Instant.now());
            if (elapsed.toMinutes() >= 1) {
                sb.append("Last heard from you: ");
                sb.append(formatDuration(elapsed));
                sb.append(" ago.");
            }
        }

        // Time awake
        if (awokeSince != null && awokeSince.isAfter(Instant.EPOCH)) {
            var awake = Duration.between(awokeSince, Instant.now());
            if (awake.toMinutes() >= 5) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append("Awake for ");
                sb.append(formatDuration(awake));
                sb.append(".");
            }
        }

        return sb.toString();
    }

    static String timeOfDay(int hour) {
        return NowLine.partOfDay(hour);
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
