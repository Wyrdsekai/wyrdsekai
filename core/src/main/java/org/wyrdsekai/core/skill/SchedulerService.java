package org.wyrdsekai.core.skill;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages scheduled skill invocations. Skills that fire on a schedule are a household.
 *
 * Persistence: In production, backed by Pekko Persistence (EventSourced).
 * This implementation provides the scheduling logic; the Pekko actor wrapper
 * handles persistence and recovery.
 *
 * Cross-node: Scheduled actions run on the node where the agent lives.
 * If the skill is LOCAL and the agent is on a phone, the SkillRegistry
 * routes via BetweenSkillBridge automatically.
 */
public class SchedulerService {

    /** Global instance -- initialized by Main.java. */
    private static volatile SchedulerService instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init(SkillRegistry skillRegistry) {
        instance = new SchedulerService(skillRegistry);
    }

    /** Get the global instance (null if not initialized). */
    public static SchedulerService get() {
        return instance;
    }

    private final SkillRegistry skillRegistry;
    private final Map<String, ScheduledAction> actions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private Consumer<ScheduledAction> approvalCallback;
    private Consumer<SkillResult> resultCallback;

    public SchedulerService(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        this.scheduler = Executors.newScheduledThreadPool(2,
            Thread.ofVirtual().name("scheduler-", 0).factory());
    }

    /** Set callback for when a scheduled action needs human approval. */
    public void onApprovalNeeded(Consumer<ScheduledAction> callback) {
        this.approvalCallback = callback;
    }

    /** Set callback for when a scheduled action produces a result. */
    public void onResult(Consumer<SkillResult> callback) {
        this.resultCallback = callback;
    }

    /** Create and schedule a new action. */
    public ScheduledAction create(ScheduledAction action) {
        actions.put(action.id(), action);
        scheduleNext(action);
        return action;
    }

    /** Cancel a scheduled action. */
    public void cancel(String actionId) {
        ScheduledAction action = actions.get(actionId);
        if (action != null) {
            actions.put(actionId, action.withStatus(ScheduledAction.ActionStatus.CANCELLED));
            cancelTimer(actionId);
        }
    }

    /** Pause a scheduled action (e.g., vacation mode). */
    public void pause(String actionId) {
        ScheduledAction action = actions.get(actionId);
        if (action != null && action.status() == ScheduledAction.ActionStatus.ACTIVE) {
            actions.put(actionId, action.withStatus(ScheduledAction.ActionStatus.PAUSED));
            cancelTimer(actionId);
        }
    }

    /** Resume a paused action. */
    public void resume(String actionId) {
        ScheduledAction action = actions.get(actionId);
        if (action != null && action.status() == ScheduledAction.ActionStatus.PAUSED) {
            var resumed = action.withStatus(ScheduledAction.ActionStatus.ACTIVE);
            actions.put(actionId, resumed);
            scheduleNext(resumed);
        }
    }

    /** Approve a pending action (human approved). */
    public void approve(String actionId) {
        ScheduledAction action = actions.get(actionId);
        if (action != null && action.status() == ScheduledAction.ActionStatus.AWAITING_APPROVAL) {
            fireAction(action);
        }
    }

    /** Deny a pending action (human rejected). */
    public void deny(String actionId) {
        ScheduledAction action = actions.get(actionId);
        if (action != null && action.status() == ScheduledAction.ActionStatus.AWAITING_APPROVAL) {
            // Reschedule for NEXT occurrence without firing.
            // Set lastFiredAt=now so the next interval fires after the full interval,
            // not immediately (which would re-trigger AWAITING_APPROVAL).
            Instant now = Instant.now();
            Instant nextFire = computeNextFireTime(action, now);
            var updated = new ScheduledAction(action.id(), action.agentDid(), action.skillId(),
                action.params(), action.schedule(), action.requiresApproval(),
                action.approvalPrompt(), action.maxRetries(),
                ScheduledAction.ActionStatus.ACTIVE,
                action.createdAt(), now, nextFire);
            actions.put(actionId, updated);
            scheduleNext(updated);
        }
    }

    /** List all scheduled actions for an agent. */
    public List<ScheduledAction> listForAgent(String agentDid) {
        return actions.values().stream()
            .filter(a -> agentDid.equals(a.agentDid()))
            .filter(a -> a.status() != ScheduledAction.ActionStatus.CANCELLED)
            .toList();
    }

    /** List all active actions. */
    public List<ScheduledAction> allActive() {
        return actions.values().stream()
            .filter(a -> a.status() == ScheduledAction.ActionStatus.ACTIVE
                      || a.status() == ScheduledAction.ActionStatus.AWAITING_APPROVAL)
            .toList();
    }

    /** Get a specific action. */
    public Optional<ScheduledAction> get(String actionId) {
        return Optional.ofNullable(actions.get(actionId));
    }

    /** Total number of tracked actions (including cancelled). */
    public int size() {
        return actions.size();
    }

    /** Number of currently active actions. */
    public int activeCount() {
        return (int) actions.values().stream()
            .filter(a -> a.status() == ScheduledAction.ActionStatus.ACTIVE)
            .count();
    }

    /** Shutdown the scheduler. */
    public void shutdown() {
        scheduler.shutdown();
    }

    // --- Internal scheduling logic ---

    private void scheduleNext(ScheduledAction action) {
        if (action.status() != ScheduledAction.ActionStatus.ACTIVE) return;

        Duration delay = computeDelay(action);
        if (delay == null) return; // No next fire time (e.g., OnEvent, or completed Once)

        ScheduledFuture<?> future = scheduler.schedule(
            () -> onTimerFired(action.id()),
            delay.toMillis(), TimeUnit.MILLISECONDS);
        timers.put(action.id(), future);
    }

    private void onTimerFired(String actionId) {
        ScheduledAction action = actions.get(actionId);
        if (action == null || action.status() != ScheduledAction.ActionStatus.ACTIVE) return;

        if (action.requiresApproval()) {
            var awaiting = action.withStatus(ScheduledAction.ActionStatus.AWAITING_APPROVAL);
            actions.put(actionId, awaiting);
            if (approvalCallback != null) {
                approvalCallback.accept(awaiting);
            }
        } else {
            fireAction(action);
        }
    }

    private void fireAction(ScheduledAction action) {
        // Build context (simplified — real impl gets credentials from TheSafe)
        SkillContext context = SkillContext.forAgent(action.agentDid(), "scheduled",
            Map.of(), Long.MAX_VALUE);

        SkillResult result = skillRegistry.execute(action.skillId(), action.params(), context);

        if (resultCallback != null) {
            resultCallback.accept(result);
        }

        // Update fired time and schedule next
        Instant now = Instant.now();
        Instant nextFire = computeNextFireTime(action, now);

        ScheduledAction.ActionStatus newStatus = (action.schedule() instanceof ScheduledAction.Schedule.Once)
            ? ScheduledAction.ActionStatus.COMPLETED
            : ScheduledAction.ActionStatus.ACTIVE;

        var updated = new ScheduledAction(action.id(), action.agentDid(), action.skillId(),
            action.params(), action.schedule(), action.requiresApproval(),
            action.approvalPrompt(), action.maxRetries(),
            newStatus, action.createdAt(), now, nextFire);

        actions.put(action.id(), updated);

        if (newStatus == ScheduledAction.ActionStatus.ACTIVE) {
            scheduleNext(updated);
        }
    }

    private Duration computeDelay(ScheduledAction action) {
        Instant now = Instant.now();
        return switch (action.schedule()) {
            case ScheduledAction.Schedule.Once once -> {
                if (once.at().isAfter(now)) {
                    yield Duration.between(now, once.at());
                }
                yield null; // Already past
            }
            case ScheduledAction.Schedule.Interval interval -> {
                Instant start = interval.startAfter() != null ? interval.startAfter() : now;
                if (action.lastFiredAt() != null) {
                    Instant next = action.lastFiredAt().plus(interval.every());
                    yield next.isAfter(now) ? Duration.between(now, next) : Duration.ZERO;
                }
                yield start.isAfter(now) ? Duration.between(now, start) : Duration.ZERO;
            }
            case ScheduledAction.Schedule.Recurring recurring -> {
                // Simplified: for real impl, parse cron expression
                // For now, default to 24h if we can't parse
                yield Duration.ofHours(24);
            }
            case ScheduledAction.Schedule.OnEvent onEvent -> null; // Event-driven, no timer
        };
    }

    private Instant computeNextFireTime(ScheduledAction action, Instant after) {
        return switch (action.schedule()) {
            case ScheduledAction.Schedule.Once once -> null;
            case ScheduledAction.Schedule.Interval interval -> after.plus(interval.every());
            case ScheduledAction.Schedule.Recurring recurring -> after.plus(Duration.ofHours(24));
            case ScheduledAction.Schedule.OnEvent onEvent -> null;
        };
    }

    private void cancelTimer(String actionId) {
        ScheduledFuture<?> future = timers.remove(actionId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
