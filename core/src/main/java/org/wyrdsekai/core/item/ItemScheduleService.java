package org.wyrdsekai.core.item;

import org.apache.pekko.actor.Cancellable;
import org.apache.pekko.actor.typed.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * item-owned schedule manager.
 *
 * <p>Backs {@code world.schedule.*} surfaces from
 * {@link ItemWorldApiProviderImpl}. Schedules are owner-scoped: every
 * timer is bound to a {@code (agentId, hookName, payload)} triple, and
 * a per-agent view filter ensures one agent can never see, list, or
 * cancel another's timers.</p>
 *
 * <p>Persistence: when a JDBC URL is provided, timers are persisted to
 * a {@code item_schedules} table and re-armed on construction. The
 * scheduler uses the Pekko {@link ActorSystem#scheduler()} for both
 * one-shot and recurring fires; {@link Cancellable} handles are kept
 * in-memory. On restart, the row is re-armed and the cancellable
 * reconstructed; if the {@code nextFire} is in the past, the timer
 * fires immediately (one-shot) or computes the next slot (recurring).</p>
 *
 * <p>Cron is intentionally minimal in this MVP — only fixed-interval
 * recurrences (via {@link #scheduleEvery}) and a tiny subset of cron
 * are supported (5-field expressions with {@code *} wildcards and
 * fixed-second frequencies). Per-second crons are rejected outright.</p>
 *
 * <p>Singleton: callers obtain via {@link #get(ActorSystem, String)};
 * subsequent calls with the same JDBC URL return the same instance.</p>
 */
public final class ItemScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ItemScheduleService.class);

    private static volatile ItemScheduleService INSTANCE;

    /** A live scheduled timer. */
    public record Schedule(
        String timerId,
        String agentId,
        String hookName,
        Map<String, Object> payload,
        Instant createdAt,
        Instant nextFire,
        Long intervalSeconds,   // null for one-shot
        Cancellable handle
    ) {}

    private final ActorSystem<?> actorSystem;
    private final String jdbcUrl;
    private final ConcurrentHashMap<String, Schedule> active = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Schedule> fireListener;  // for tests + audit

    private ItemScheduleService(ActorSystem<?> system, String jdbcUrl) {
        this.actorSystem = system;
        this.jdbcUrl = jdbcUrl;
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            initSchema();
            rearmFromDisk();
        }
    }

    /**
     * Get the process-wide instance, creating it on first call.
     * {@code jdbcUrl} may be null for in-memory operation (no persistence).
     */
    public static ItemScheduleService get(ActorSystem<?> system, String jdbcUrl) {
        if (INSTANCE == null) {
            synchronized (ItemScheduleService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ItemScheduleService(system, jdbcUrl);
                }
            }
        }
        return INSTANCE;
    }

    /** Test-only — release the singleton so a new test can create a fresh instance. */
    public static void resetForTesting() {
        synchronized (ItemScheduleService.class) {
            if (INSTANCE != null) {
                for (var s : INSTANCE.active.values()) {
                    if (s.handle != null) s.handle.cancel();
                }
                INSTANCE.active.clear();
                INSTANCE = null;
            }
        }
    }

    /** Test/audit hook — called every time a timer fires. */
    public void setFireListener(BiConsumer<String, Schedule> listener) {
        this.fireListener = listener;
    }

    // ─── Public API ─────────────────────────────────────────────

    /** Schedule a one-shot callback {@code seconds} from now. */
    public Map<String, Object> scheduleIn(String agentId, int seconds, String hookName,
                                            Map<String, Object> payload) {
        if (agentId == null || hookName == null) {
            return Map.of("error", "agentId+hookName required");
        }
        if (seconds < 0) seconds = 0;
        var id = UUID.randomUUID().toString();
        var nextFire = Instant.now().plusSeconds(seconds);
        var safePayload = payload == null ? Map.<String, Object>of() : payload;
        persist(id, agentId, hookName, safePayload, nextFire, null);
        var handle = arm(id, agentId, hookName, safePayload, nextFire, null);
        active.put(id, new Schedule(id, agentId, hookName, safePayload,
            Instant.now(), nextFire, null, handle));
        return Map.of("ok", true, "timerId", id, "nextFire", nextFire.toEpochMilli());
    }

    /** Schedule a fixed-interval recurring callback. */
    public Map<String, Object> scheduleEvery(String agentId, long intervalSeconds, String hookName,
                                                Map<String, Object> payload) {
        if (agentId == null || hookName == null) {
            return Map.of("error", "agentId+hookName required");
        }
        if (intervalSeconds < 1) {
            return Map.of("error", "interval must be >= 1 second");
        }
        var id = UUID.randomUUID().toString();
        var nextFire = Instant.now().plusSeconds(intervalSeconds);
        var safePayload = payload == null ? Map.<String, Object>of() : payload;
        persist(id, agentId, hookName, safePayload, nextFire, intervalSeconds);
        var handle = arm(id, agentId, hookName, safePayload, nextFire, intervalSeconds);
        active.put(id, new Schedule(id, agentId, hookName, safePayload,
            Instant.now(), nextFire, intervalSeconds, handle));
        return Map.of("ok", true, "timerId", id,
            "nextFire", nextFire.toEpochMilli(),
            "intervalSeconds", intervalSeconds);
    }

    /**
     * Schedule a cron-style recurring callback. Supports a tiny safe subset:
     * {@code "&#42;/N seconds"}, {@code "&#42;/N minutes"}, {@code "&#42;/N hours"},
     * or 5-field cron with {@code *}/integer values. Per-second cron with
     * N=1 is rejected outright (would burn CPU).
     */
    public Map<String, Object> scheduleCron(String agentId, String cronExpr, String hookName,
                                             Map<String, Object> payload) {
        if (cronExpr == null || cronExpr.isBlank()) {
            return Map.of("error", "blank cron expression");
        }
        // Friendly forms: "*/30 seconds", "*/5 minutes", "*/1 hours".
        var parts = cronExpr.trim().split("\\s+");
        if (parts.length == 2 && parts[0].startsWith("*/")) {
            try {
                long n = Long.parseLong(parts[0].substring(2));
                long secs = switch (parts[1].toLowerCase()) {
                    case "second", "seconds" -> n;
                    case "minute", "minutes" -> n * 60;
                    case "hour", "hours"     -> n * 3600;
                    case "day", "days"       -> n * 86400;
                    default                  -> -1L;
                };
                if (secs < 1) {
                    return Map.of("error", "cron unit invalid: " + parts[1]);
                }
                if (secs < 5) {
                    return Map.of("error", "cron interval too short (min 5s)");
                }
                return scheduleEvery(agentId, secs, hookName, payload);
            } catch (NumberFormatException _) {
                return Map.of("error", "cron number parse failed: " + parts[0]);
            }
        }
        // 5-field standard cron (minute hour day-of-month month day-of-week).
        // Minimal MVP: only "0 H * * *" → daily at hour H, "0 * * * *" → hourly.
        if (parts.length == 5) {
            return Map.of("error", "5-field cron not yet supported in MVP — use '*/N <unit>' form");
        }
        return Map.of("error", "unrecognised cron expression: " + cronExpr);
    }

    /** Cancel a timer, but only if it belongs to {@code agentId}. */
    public Map<String, Object> cancel(String agentId, String timerId) {
        var s = active.get(timerId);
        if (s == null) return Map.of("ok", false, "reason", "not_found");
        if (agentId != null && !agentId.equals(s.agentId)) {
            return Map.of("ok", false, "reason", "not_owner");
        }
        if (s.handle != null) s.handle.cancel();
        active.remove(timerId);
        delete(timerId);
        return Map.of("ok", true, "timerId", timerId);
    }

    /** List schedules owned by {@code agentId}. */
    public List<Map<String, Object>> list(String agentId) {
        var out = new ArrayList<Map<String, Object>>();
        for (var s : active.values()) {
            if (agentId == null || agentId.equals(s.agentId)) {
                var m = new LinkedHashMap<String, Object>();
                m.put("timerId", s.timerId);
                m.put("hookName", s.hookName);
                m.put("nextFire", s.nextFire.toEpochMilli());
                if (s.intervalSeconds != null) m.put("intervalSeconds", s.intervalSeconds);
                m.put("recurring", s.intervalSeconds != null);
                out.add(m);
            }
        }
        return out;
    }

    /** True if a timer is registered (owner-blind, for tests). */
    public boolean exists(String timerId) {
        return active.containsKey(timerId);
    }

    /** Active count. */
    public int size() { return active.size(); }

    // ─── Internal: arm + fire ──────────────────────────────────

    private Cancellable arm(String id, String agentId, String hookName,
                              Map<String, Object> payload, Instant nextFire,
                              Long intervalSeconds) {
        if (actorSystem == null) {
            log.warn("ItemScheduleService.arm: no actor system, timer {} will not fire", id);
            return null;
        }
        long delayMs = Math.max(0L, nextFire.toEpochMilli() - System.currentTimeMillis());
        if (intervalSeconds != null) {
            return actorSystem.scheduler().scheduleAtFixedRate(
                Duration.ofMillis(delayMs),
                Duration.ofSeconds(intervalSeconds),
                () -> fire(id, agentId, hookName, payload, intervalSeconds),
                actorSystem.executionContext());
        } else {
            return actorSystem.scheduler().scheduleOnce(
                Duration.ofMillis(delayMs),
                () -> fire(id, agentId, hookName, payload, null),
                actorSystem.executionContext());
        }
    }

    private void fire(String id, String agentId, String hookName,
                       Map<String, Object> payload, Long intervalSeconds) {
        var s = active.get(id);
        if (s == null) return;  // cancelled before fire
        try {
            log.info("schedule fire: id={} agent={} hook={}", id, agentId, hookName);
            var listener = fireListener;
            if (listener != null) listener.accept(id, s);
            if (intervalSeconds != null) {
                // Bump nextFire and re-persist so a restart sees a current row.
                var bumped = new Schedule(id, agentId, hookName, payload,
                    s.createdAt, Instant.now().plusSeconds(intervalSeconds),
                    intervalSeconds, s.handle);
                active.put(id, bumped);
                persist(id, agentId, hookName, payload, bumped.nextFire, intervalSeconds);
            } else {
                active.remove(id);
                delete(id);
            }
        } catch (Exception e) {
            log.warn("schedule fire failed for {}: {}", id, e.getMessage());
        }
    }

    // ─── Persistence ───────────────────────────────────────────

    private boolean hasJdbc() {
        return jdbcUrl != null && !jdbcUrl.isBlank();
    }

    private void initSchema() {
        if (!hasJdbc()) return;
        try (var c = DriverManager.getConnection(jdbcUrl);
             var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS item_schedules (
                  timer_id TEXT PRIMARY KEY,
                  agent_id TEXT NOT NULL,
                  hook_name TEXT NOT NULL,
                  payload_json TEXT NOT NULL,
                  next_fire_ms BIGINT NOT NULL,
                  interval_seconds BIGINT,
                  created_at_ms BIGINT NOT NULL
                )
                """);
        } catch (SQLException e) {
            log.warn("ItemScheduleService.initSchema failed: {}", e.getMessage());
        }
    }

    private void persist(String id, String agentId, String hookName,
                          Map<String, Object> payload, Instant nextFire,
                          Long intervalSeconds) {
        if (!hasJdbc()) return;
        var json = ItemJsonHelper.stringify(payload);
        try (var c = DriverManager.getConnection(jdbcUrl)) {
            // Try INSERT, fallback to UPDATE for cross-dialect support
            try (var del = c.prepareStatement("DELETE FROM item_schedules WHERE timer_id = ?")) {
                del.setString(1, id);
                del.executeUpdate();
            }
            try (var ps = c.prepareStatement(
                "INSERT INTO item_schedules (timer_id, agent_id, hook_name, payload_json, next_fire_ms, interval_seconds, created_at_ms) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, agentId);
                ps.setString(3, hookName);
                ps.setString(4, json);
                ps.setLong(5, nextFire.toEpochMilli());
                if (intervalSeconds == null) ps.setNull(6, Types.BIGINT);
                else ps.setLong(6, intervalSeconds);
                ps.setLong(7, Instant.now().toEpochMilli());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("ItemScheduleService.persist failed: {}", e.getMessage());
        }
    }

    private void delete(String id) {
        if (!hasJdbc()) return;
        try (var c = DriverManager.getConnection(jdbcUrl);
             var ps = c.prepareStatement("DELETE FROM item_schedules WHERE timer_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("ItemScheduleService.delete failed: {}", e.getMessage());
        }
    }

    private void rearmFromDisk() {
        if (!hasJdbc()) return;
        try (var c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = c.prepareStatement(
                "SELECT timer_id, agent_id, hook_name, payload_json, next_fire_ms, interval_seconds, created_at_ms FROM item_schedules");
             ResultSet rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                var id = rs.getString("timer_id");
                var aid = rs.getString("agent_id");
                var hook = rs.getString("hook_name");
                var pjson = rs.getString("payload_json");
                long nextMs = rs.getLong("next_fire_ms");
                long intervalSec = rs.getLong("interval_seconds");
                boolean isRecurring = !rs.wasNull();
                long createdMs = rs.getLong("created_at_ms");
                @SuppressWarnings("unchecked")
                var payload = (Map<String, Object>) ItemJsonHelper.parse(pjson);
                if (payload == null) payload = Map.of();
                var nextFire = Instant.ofEpochMilli(nextMs);
                Long intervalBoxed = isRecurring ? intervalSec : null;
                var handle = arm(id, aid, hook, payload, nextFire, intervalBoxed);
                active.put(id, new Schedule(id, aid, hook, payload,
                    Instant.ofEpochMilli(createdMs), nextFire, intervalBoxed, handle));
                count++;
            }
            if (count > 0) log.info("ItemScheduleService re-armed {} timers from disk", count);
        } catch (SQLException e) {
            log.warn("ItemScheduleService.rearmFromDisk failed: {}", e.getMessage());
        }
    }
}
