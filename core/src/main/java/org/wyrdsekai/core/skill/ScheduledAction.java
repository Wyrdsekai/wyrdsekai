package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * A skill invocation that fires on a schedule. Persisted via Pekko Persistence.
 * Skills that fire once are useful. Skills that fire on a schedule are a household.
 *
 * @param id               Unique action ID
 * @param agentDid         Which agent owns this scheduled action
 * @param skillId          Skill to invoke (e.g., "hearth.medication.remind")
 * @param params           Skill parameters
 * @param schedule         When to fire
 * @param requiresApproval Human must approve before execution?
 * @param approvalPrompt   What to show the human (e.g., "Send grocery order?")
 * @param maxRetries       Retry on failure (0 = no retry)
 * @param status           Current status
 * @param createdAt        When this action was created
 * @param lastFiredAt      When this action last fired (null if never)
 * @param nextFireAt       When this action will next fire (null if completed/paused)
 */
public record ScheduledAction(
    String id,
    String agentDid,
    String skillId,
    Map<String, Object> params,
    Schedule schedule,
    boolean requiresApproval,
    String approvalPrompt,
    int maxRetries,
    ActionStatus status,
    Instant createdAt,
    Instant lastFiredAt,
    Instant nextFireAt
) {
    public ScheduledAction {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Action ID required");
        if (agentDid == null) throw new IllegalArgumentException("Agent DID required");
        if (skillId == null) throw new IllegalArgumentException("Skill ID required");
        if (schedule == null) throw new IllegalArgumentException("Schedule required");
        if (params == null) params = Map.of();
        if (status == null) status = ActionStatus.ACTIVE;
        if (createdAt == null) createdAt = Instant.now();
    }

    /** Create a simple one-shot scheduled action. */
    public static ScheduledAction once(String id, String agentDid, String skillId,
                                        Map<String, Object> params, Instant at) {
        return new ScheduledAction(id, agentDid, skillId, params,
            new Schedule.Once(at), false, null, 0,
            ActionStatus.ACTIVE, Instant.now(), null, at);
    }

    /** Create a recurring cron-based action. */
    public static ScheduledAction recurring(String id, String agentDid, String skillId,
                                             Map<String, Object> params, String cron,
                                             ZoneId timezone) {
        return new ScheduledAction(id, agentDid, skillId, params,
            new Schedule.Recurring(cron, timezone, null),
            false, null, 0,
            ActionStatus.ACTIVE, Instant.now(), null, null);
    }

    /** Create an event-triggered action. */
    public static ScheduledAction onEvent(String id, String agentDid, String skillId,
                                           Map<String, Object> params, String eventPattern) {
        return new ScheduledAction(id, agentDid, skillId, params,
            new Schedule.OnEvent(eventPattern),
            false, null, 0,
            ActionStatus.ACTIVE, Instant.now(), null, null);
    }

    /** Return a copy with updated status. */
    public ScheduledAction withStatus(ActionStatus newStatus) {
        return new ScheduledAction(id, agentDid, skillId, params, schedule,
            requiresApproval, approvalPrompt, maxRetries,
            newStatus, createdAt, lastFiredAt, nextFireAt);
    }

    /** Return a copy recording that the action just fired. */
    public ScheduledAction fired(Instant at, Instant nextFire) {
        return new ScheduledAction(id, agentDid, skillId, params, schedule,
            requiresApproval, approvalPrompt, maxRetries,
            status, createdAt, at, nextFire);
    }

    public enum ActionStatus {
        ACTIVE,             // Running on schedule
        PAUSED,             // Temporarily suspended
        AWAITING_APPROVAL,  // Fired, waiting for human approval
        COMPLETED,          // One-shot that has fired
        CANCELLED           // Permanently stopped
    }

    /**
     * Schedule types for when an action should fire.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Schedule.Once.class, name = "once"),
        @JsonSubTypes.Type(value = Schedule.Recurring.class, name = "recurring"),
        @JsonSubTypes.Type(value = Schedule.Interval.class, name = "interval"),
        @JsonSubTypes.Type(value = Schedule.OnEvent.class, name = "on_event"),
    })
    public sealed interface Schedule {

        /** Fire once at a specific time. */
        record Once(Instant at) implements Schedule {}

        /** Fire on a cron-like schedule. */
        record Recurring(
            String cron,        // e.g., "0 8 * * *" = every day at 8am
            ZoneId timezone,
            Instant until       // optional end date (null = forever)
        ) implements Schedule {}

        /** Fire at a fixed interval. */
        record Interval(
            Duration every,
            Instant startAfter
        ) implements Schedule {}

        /** Fire when a world event matches the pattern (reactive). */
        record OnEvent(
            String eventPattern  // e.g., "hearth.ha.event.doorbell"
        ) implements Schedule {}
    }
}
