package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Manages persistent watchers: condition monitors that check on a schedule
 * and trigger notifications when conditions are met.
 *
 * <p>A watcher combines scheduler + script check + notification into a single
 * agent concept. "Watch the GPU temperature and tell me if it exceeds 80C."</p>
 *
 * <p>Script evaluation is delegated via a {@link Function} callback to avoid
 * coupling core to the scripting module. Main.java wires the real GraalJS
 * sandbox; tests can provide a simple lambda.</p>
 *
 * <p>Follows the same singleton pattern as {@link AgentEventStream}:
 * initialized by Main.java at startup, accessed via {@link #get()}.</p>
 *
 * @see NotificationService
 */
public class WatcherService {

    private static final Logger log = LoggerFactory.getLogger(WatcherService.class);

    /** Global instance -- initialized by Main.java. */
    private static volatile WatcherService instance;

    private final NotificationService notifications;
    private final Function<String, Object> scriptEvaluator;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Watcher> watchers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    /** Default debounce: alert after this many consecutive failures. */
    static final int DEFAULT_DEBOUNCE = 2;

    /** Maximum watchers per agent. */
    static final int MAX_WATCHERS_PER_AGENT = 20;

    /** Minimum check interval. */
    static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    /** Maximum check interval. */
    static final Duration MAX_INTERVAL = Duration.ofDays(7);

    /** Consecutive failures before entering ERROR status. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * Represents a persistent watcher.
     */
    public record Watcher(
        String id,
        String name,
        String agentId,
        String checkScript,
        Duration interval,
        WatchCondition alertOn,
        String message,
        String priority,
        Instant createdAt,
        WatcherStatus status,
        String lastResult,
        Instant lastChecked,
        int consecutiveFailures,
        int consecutiveAlertConditions
    ) {
        public Watcher withStatus(WatcherStatus newStatus) {
            return new Watcher(id, name, agentId, checkScript, interval, alertOn,
                message, priority, createdAt, newStatus, lastResult, lastChecked,
                consecutiveFailures, consecutiveAlertConditions);
        }

        public Watcher withCheckResult(String result, Instant checked,
                                        int failures, int alertConditions) {
            return new Watcher(id, name, agentId, checkScript, interval, alertOn,
                message, priority, createdAt, status, result, checked,
                failures, alertConditions);
        }
    }

    /** Condition that triggers an alert. */
    public enum WatchCondition {
        /** Alert when the check script returns false/falsy. */
        FAILURE,
        /** Alert when the result changes from the previous check. */
        CHANGE,
        /** Alert on every check (e.g. logging). */
        ALWAYS
    }

    /** Watcher lifecycle status. */
    public enum WatcherStatus {
        ACTIVE,
        PAUSED,
        TRIGGERED,
        ERROR,
        CANCELLED
    }

    /**
     * Create a WatcherService.
     *
     * @param notifications   notification delivery service
     * @param scriptEvaluator callback that evaluates a JS expression and returns the result.
     *                        The function receives the script string and returns an Object.
     *                        May be null (checks will fail gracefully).
     */
    public WatcherService(NotificationService notifications,
                           Function<String, Object> scriptEvaluator) {
        this.notifications = Objects.requireNonNull(notifications, "notifications required");
        this.scriptEvaluator = scriptEvaluator;
        this.scheduler = Executors.newScheduledThreadPool(2,
            Thread.ofVirtual().name("watcher-", 0).factory());
    }

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init(NotificationService notifications,
                             Function<String, Object> scriptEvaluator) {
        instance = new WatcherService(notifications, scriptEvaluator);
    }

    /** Get the global instance (null if not initialized). */
    public static WatcherService get() {
        return instance;
    }

    /**
     * Create a new watcher.
     *
     * @param name        human-readable name for the watcher
     * @param agentId     the agent creating this watcher
     * @param checkScript GraalJS expression that returns truthy/falsy
     * @param interval    check interval string (e.g. "30s", "5m", "1h", "1d")
     * @param alertOn     "failure", "change", or "always"
     * @param message     notification message when triggered
     * @param priority    "ambient", "normal", or "critical"
     * @return the watcher ID, or null if validation fails
     */
    public String createWatcher(String name, String agentId, String checkScript,
                                 String interval, String alertOn, String message,
                                 String priority) {
        if (name == null || name.isBlank()) {
            log.warn("Watcher creation failed: blank name");
            return null;
        }
        if (checkScript == null || checkScript.isBlank()) {
            log.warn("Watcher creation failed: blank checkScript");
            return null;
        }

        var duration = parseInterval(interval);
        if (duration == null) {
            log.warn("Watcher creation failed: invalid interval '{}'", interval);
            return null;
        }
        if (duration.compareTo(MIN_INTERVAL) < 0) {
            log.warn("Watcher interval too short: {} (min {})", interval, MIN_INTERVAL);
            return null;
        }
        if (duration.compareTo(MAX_INTERVAL) > 0) {
            log.warn("Watcher interval too long: {} (max {})", interval, MAX_INTERVAL);
            return null;
        }

        // Check per-agent limit
        long agentCount = watchers.values().stream()
            .filter(w -> agentId.equals(w.agentId()) && w.status() != WatcherStatus.CANCELLED)
            .count();
        if (agentCount >= MAX_WATCHERS_PER_AGENT) {
            log.warn("Agent '{}' has reached max watchers ({})", agentId, MAX_WATCHERS_PER_AGENT);
            return null;
        }

        var condition = parseCondition(alertOn);
        var watcherId = UUID.randomUUID().toString();

        var watcher = new Watcher(
            watcherId, name, agentId, checkScript, duration, condition,
            message != null ? message : "Watcher triggered: " + name,
            priority != null ? priority : "normal",
            Instant.now(), WatcherStatus.ACTIVE,
            null, null, 0, 0);

        watchers.put(watcherId, watcher);
        scheduleCheck(watcher);

        log.info("Watcher created: id={} name='{}' agent='{}' interval={} alertOn={}",
            watcherId, name, agentId, duration, condition);
        return watcherId;
    }

    /**
     * Cancel a watcher.
     *
     * @param watcherId the watcher ID
     * @return true if the watcher was found and cancelled
     */
    public boolean cancelWatcher(String watcherId) {
        var watcher = watchers.get(watcherId);
        if (watcher == null) return false;

        watchers.put(watcherId, watcher.withStatus(WatcherStatus.CANCELLED));
        cancelTimer(watcherId);
        log.info("Watcher cancelled: id={} name='{}'", watcherId, watcher.name());
        return true;
    }

    /**
     * Pause a watcher.
     *
     * @param watcherId the watcher ID
     * @return true if the watcher was found and paused
     */
    public boolean pauseWatcher(String watcherId) {
        var watcher = watchers.get(watcherId);
        if (watcher == null || watcher.status() != WatcherStatus.ACTIVE) return false;

        watchers.put(watcherId, watcher.withStatus(WatcherStatus.PAUSED));
        cancelTimer(watcherId);
        return true;
    }

    /**
     * Resume a paused watcher.
     *
     * @param watcherId the watcher ID
     * @return true if the watcher was found and resumed
     */
    public boolean resumeWatcher(String watcherId) {
        var watcher = watchers.get(watcherId);
        if (watcher == null || watcher.status() != WatcherStatus.PAUSED) return false;

        var resumed = watcher.withStatus(WatcherStatus.ACTIVE);
        watchers.put(watcherId, resumed);
        scheduleCheck(resumed);
        return true;
    }

    /**
     * List all watchers for a specific agent.
     *
     * @param agentId the agent to query
     * @return list of non-cancelled watchers for this agent
     */
    public List<Watcher> listWatchers(String agentId) {
        return watchers.values().stream()
            .filter(w -> agentId.equals(w.agentId()))
            .filter(w -> w.status() != WatcherStatus.CANCELLED)
            .toList();
    }

    /**
     * Get a specific watcher by ID.
     *
     * @param watcherId the watcher ID
     * @return the watcher, or null if not found
     */
    public Watcher getWatcher(String watcherId) {
        return watchers.get(watcherId);
    }

    /**
     * Total number of tracked watchers (including cancelled).
     */
    public int size() {
        return watchers.size();
    }

    /**
     * Number of currently active watchers.
     */
    public int activeCount() {
        return (int) watchers.values().stream()
            .filter(w -> w.status() == WatcherStatus.ACTIVE)
            .count();
    }

    /**
     * Execute a single check for a watcher. Called by the scheduler timer.
     * Package-visible for testing.
     *
     * @param watcherId the watcher to check
     */
    void executeCheck(String watcherId) {
        var watcher = watchers.get(watcherId);
        if (watcher == null || watcher.status() != WatcherStatus.ACTIVE) return;

        Object result;
        try {
            if (scriptEvaluator == null) {
                throw new UnsupportedOperationException("No script evaluator configured");
            }
            result = scriptEvaluator.apply(watcher.checkScript());
        } catch (Exception e) {
            // Script execution failed
            int newFailures = watcher.consecutiveFailures() + 1;
            if (newFailures >= MAX_CONSECUTIVE_FAILURES) {
                // Too many failures — pause the watcher and notify
                var errored = watcher.withCheckResult(
                    "ERROR: " + e.getMessage(), Instant.now(), newFailures, 0)
                    .withStatus(WatcherStatus.ERROR);
                watchers.put(watcherId, errored);
                cancelTimer(watcherId);
                notifications.notify("steward",
                    "Watcher '" + watcher.name() + "' stopped: " + e.getMessage(),
                    "normal", watcher.agentId());
                log.warn("Watcher '{}' entered ERROR after {} failures: {}",
                    watcher.name(), newFailures, e.getMessage());
            } else {
                var updated = watcher.withCheckResult(
                    "ERROR: " + e.getMessage(), Instant.now(), newFailures,
                    watcher.consecutiveAlertConditions());
                watchers.put(watcherId, updated);
                log.debug("Watcher '{}' check failed ({}/{}): {}",
                    watcher.name(), newFailures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            }
            return;
        }

        String resultStr = String.valueOf(result);
        boolean shouldAlert = evaluateCondition(watcher, result, resultStr);

        int alertConditions = shouldAlert ? watcher.consecutiveAlertConditions() + 1 : 0;
        var updated = watcher.withCheckResult(resultStr, Instant.now(), 0, alertConditions);
        watchers.put(watcherId, updated);

        // Debounce: only alert after DEFAULT_DEBOUNCE consecutive alert conditions
        if (shouldAlert && alertConditions >= DEFAULT_DEBOUNCE) {
            notifications.notify("steward", watcher.message(), watcher.priority(), watcher.agentId());
            // Reset alert conditions after sending notification
            var triggered = updated.withCheckResult(resultStr, Instant.now(), 0, 0);
            watchers.put(watcherId, triggered);
            log.info("Watcher '{}' triggered: result='{}', sending notification", watcher.name(), resultStr);
        }

        // Recovery notification: if previous was in alert state and now resolved
        if (!shouldAlert && watcher.consecutiveAlertConditions() >= DEFAULT_DEBOUNCE) {
            notifications.notify("steward",
                "Watcher '" + watcher.name() + "' recovered: " + resultStr,
                "ambient", watcher.agentId());
            log.info("Watcher '{}' recovered: result='{}'", watcher.name(), resultStr);
        }
    }

    /**
     * Build context string for agent prompt assembly.
     *
     * @param agentId the agent to build context for
     * @return context string, or null if no watchers
     */
    public String buildContext(String agentId) {
        var active = listWatchers(agentId);
        if (active.isEmpty()) return null;

        var sb = new StringBuilder("## Active Watchers\n");
        for (var w : active) {
            sb.append("- ").append(w.name()).append(": checking every ")
              .append(formatDuration(w.interval()));
            if (w.lastChecked() != null) {
                var ago = Duration.between(w.lastChecked(), Instant.now());
                sb.append(" (last: ").append(w.lastResult() != null ? w.lastResult() : "pending")
                  .append(", ").append(formatDuration(ago)).append(" ago)");
            } else {
                sb.append(" (not yet checked)");
            }
            if (w.status() == WatcherStatus.ERROR) {
                sb.append(" **[ERROR]**");
            } else if (w.status() == WatcherStatus.PAUSED) {
                sb.append(" [paused]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Shutdown the scheduler. */
    public void shutdown() {
        scheduler.shutdown();
    }

    // --- Internal ---

    private void scheduleCheck(Watcher watcher) {
        var future = scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    executeCheck(watcher.id());
                } catch (Exception e) {
                    log.error("Unexpected error in watcher check '{}': {}",
                        watcher.id(), e.getMessage(), e);
                }
            },
            watcher.interval().toMillis(),
            watcher.interval().toMillis(),
            TimeUnit.MILLISECONDS);
        timers.put(watcher.id(), future);
    }

    private void cancelTimer(String watcherId) {
        var future = timers.remove(watcherId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private boolean evaluateCondition(Watcher watcher, Object result, String resultStr) {
        return switch (watcher.alertOn()) {
            case FAILURE -> !isTruthy(result);
            case CHANGE -> watcher.lastResult() != null && !resultStr.equals(watcher.lastResult());
            case ALWAYS -> true;
        };
    }

    static boolean isTruthy(Object result) {
        if (result == null) return false;
        if (result instanceof Boolean b) return b;
        if (result instanceof Number n) return n.doubleValue() != 0;
        if (result instanceof String s) return !s.isEmpty() && !"false".equalsIgnoreCase(s);
        return true;
    }

    /**
     * Parse an interval string like "30s", "5m", "1h", "6h", "1d" to a Duration.
     *
     * @param interval the interval string
     * @return the parsed Duration, or null if invalid
     */
    static Duration parseInterval(String interval) {
        if (interval == null || interval.isBlank()) return null;
        interval = interval.strip().toLowerCase();
        try {
            if (interval.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(interval.substring(0, interval.length() - 1)));
            } else if (interval.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(interval.substring(0, interval.length() - 1)));
            } else if (interval.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(interval.substring(0, interval.length() - 1)));
            } else if (interval.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(interval.substring(0, interval.length() - 1)));
            }
            // Try parsing as seconds
            return Duration.ofSeconds(Long.parseLong(interval));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static WatchCondition parseCondition(String alertOn) {
        if (alertOn == null) return WatchCondition.FAILURE;
        return switch (alertOn.toLowerCase()) {
            case "failure" -> WatchCondition.FAILURE;
            case "change" -> WatchCondition.CHANGE;
            case "always" -> WatchCondition.ALWAYS;
            default -> WatchCondition.FAILURE;
        };
    }

    private static String formatDuration(Duration d) {
        if (d.toDays() > 0) return d.toDays() + "d";
        if (d.toHours() > 0) return d.toHours() + "h";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return d.toSeconds() + "s";
    }
}
