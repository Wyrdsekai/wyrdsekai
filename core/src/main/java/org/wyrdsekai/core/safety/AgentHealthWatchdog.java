package org.wyrdsekai.core.safety;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent health watchdog for cascading failure prevention (§96.1).
 * Tracks consecutive errors per agent and per room. If thresholds
 * are exceeded, agents enter forced rest and rooms enter maintenance mode.
 * <p>
 * This is a safety net, not a punishment.
 */
public class AgentHealthWatchdog {

    /** Health status for a monitored entity. */
    public record HealthStatus(
        String entityId,
        EntityType type,
        int consecutiveErrors,
        Instant lastError,
        Instant lastSuccess,
        WatchdogAction action
    ) {}

    public enum EntityType { AGENT, ROOM }

    public enum WatchdogAction {
        /** Entity is healthy. */
        HEALTHY,
        /** Errors detected but below threshold. */
        DEGRADED,
        /** Agent: forced rest. Room: maintenance mode. */
        QUARANTINED,
        /** Recovering from quarantine (half-open). */
        RECOVERING
    }

    /** Default: 5 consecutive errors → quarantine for agents. */
    private int agentErrorThreshold = 5;
    /** Default: 10 consecutive errors → maintenance for rooms. */
    private int roomErrorThreshold = 10;
    /** How long quarantine lasts before trying recovery (seconds). */
    private int quarantineDurationSeconds = 300;

    private final Map<String, ErrorTracker> trackers = new ConcurrentHashMap<>();

    private static class ErrorTracker {
        final String entityId;
        final EntityType type;
        int consecutiveErrors = 0;
        Instant lastError;
        Instant lastSuccess;
        Instant quarantinedAt;
        WatchdogAction action = WatchdogAction.HEALTHY;

        ErrorTracker(String entityId, EntityType type) {
            this.entityId = entityId;
            this.type = type;
        }
    }

    /** Record a successful operation for an entity. */
    public HealthStatus recordSuccess(String entityId, EntityType type) {
        var tracker = trackers.computeIfAbsent(entityId, id -> new ErrorTracker(id, type));
        synchronized (tracker) {
            tracker.consecutiveErrors = 0;
            tracker.lastSuccess = Instant.now();
            if (tracker.action == WatchdogAction.RECOVERING) {
                tracker.action = WatchdogAction.HEALTHY;
                tracker.quarantinedAt = null;
            }
            return toStatus(tracker);
        }
    }

    /** Record an error for an entity. Returns the new health status. */
    public HealthStatus recordError(String entityId, EntityType type) {
        var tracker = trackers.computeIfAbsent(entityId, id -> new ErrorTracker(id, type));
        int threshold = type == EntityType.AGENT ? agentErrorThreshold : roomErrorThreshold;

        synchronized (tracker) {
            tracker.consecutiveErrors++;
            tracker.lastError = Instant.now();

            if (tracker.consecutiveErrors >= threshold
                    && tracker.action != WatchdogAction.QUARANTINED) {
                tracker.action = WatchdogAction.QUARANTINED;
                tracker.quarantinedAt = Instant.now();
            } else if (tracker.action == WatchdogAction.HEALTHY
                    && tracker.consecutiveErrors > 0) {
                tracker.action = WatchdogAction.DEGRADED;
            }

            return toStatus(tracker);
        }
    }

    /** Get health status for an entity. */
    public Optional<HealthStatus> getStatus(String entityId) {
        var tracker = trackers.get(entityId);
        if (tracker == null) return Optional.empty();
        synchronized (tracker) {
            // Check if quarantine has expired
            if (tracker.action == WatchdogAction.QUARANTINED
                    && tracker.quarantinedAt != null) {
                var elapsed = Instant.now().getEpochSecond() - tracker.quarantinedAt.getEpochSecond();
                if (elapsed >= quarantineDurationSeconds) {
                    tracker.action = WatchdogAction.RECOVERING;
                }
            }
            return Optional.of(toStatus(tracker));
        }
    }

    /** Check if an entity is quarantined (should not process requests). */
    public boolean isQuarantined(String entityId) {
        return getStatus(entityId)
            .map(s -> s.action() == WatchdogAction.QUARANTINED)
            .orElse(false);
    }

    /** Get all quarantined entities. */
    public List<HealthStatus> quarantinedEntities() {
        return trackers.values().stream()
            .filter(t -> t.action == WatchdogAction.QUARANTINED)
            .map(this::toStatus)
            .toList();
    }

    /** Get all entities with any health issues. */
    public List<HealthStatus> unhealthyEntities() {
        return trackers.values().stream()
            .filter(t -> t.action != WatchdogAction.HEALTHY)
            .map(this::toStatus)
            .toList();
    }

    /** Manually release an entity from quarantine. */
    public boolean release(String entityId) {
        var tracker = trackers.get(entityId);
        if (tracker == null) return false;
        synchronized (tracker) {
            if (tracker.action == WatchdogAction.QUARANTINED
                    || tracker.action == WatchdogAction.RECOVERING) {
                tracker.action = WatchdogAction.RECOVERING;
                tracker.consecutiveErrors = 0;
                return true;
            }
            return false;
        }
    }

    /** Reset all tracking for an entity. */
    public void reset(String entityId) {
        trackers.remove(entityId);
    }

    /** Configure thresholds. */
    public void setAgentErrorThreshold(int threshold) {
        this.agentErrorThreshold = threshold;
    }

    public void setRoomErrorThreshold(int threshold) {
        this.roomErrorThreshold = threshold;
    }

    public void setQuarantineDurationSeconds(int seconds) {
        this.quarantineDurationSeconds = seconds;
    }

    /** Total tracked entities. */
    public int trackedCount() {
        return trackers.size();
    }

    /** Narrative for health status alerts. */
    public static String alertNarrative(HealthStatus status) {
        return switch (status.action()) {
            case HEALTHY -> status.entityId() + " is healthy.";
            case DEGRADED -> status.entityId() + " has encountered " +
                status.consecutiveErrors() + " consecutive errors. Monitoring.";
            case QUARANTINED -> status.type() == EntityType.AGENT
                ? status.entityId() + " has been placed in forced rest after " +
                  status.consecutiveErrors() + " consecutive errors. " +
                  "The household steward has been notified."
                : status.entityId() + " has entered maintenance mode after " +
                  status.consecutiveErrors() + " consecutive errors. " +
                  "Room access is temporarily suspended.";
            case RECOVERING -> status.entityId() + " is recovering from quarantine. " +
                "Next successful operation will restore full health.";
        };
    }

    private HealthStatus toStatus(ErrorTracker tracker) {
        return new HealthStatus(
            tracker.entityId, tracker.type,
            tracker.consecutiveErrors,
            tracker.lastError, tracker.lastSuccess,
            tracker.action
        );
    }
}
