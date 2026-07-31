package org.wyrdsekai.core.external.t;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * (Phase T) — process-wide registry of inbound
 * subscriptions across all listener kinds (webhook, RSS, IMAP, MQTT, MQTT-like
 * brokers, FileWatcher, scheduled, SSE, WebSocket).
 *
 * <p>The registry is shared infrastructure — every listener adapter writes into
 * it on subscribe and reads from it on dispatch. State persisted to the
 * {@code item_listener_subscriptions} JDBC table (mirrors the
 * {@code item_schedules} pattern from §4.5) so subscriptions survive a restart
 * and the per-subscription rate-limit window is visible to the registry but the
 * actual transport-level connection (broker session, IMAP IDLE, ...) is held
 * by the listener adapter.</p>
 *
 * <p>{@link #dispatch} is a hot path — it reads from {@link #active} only,
 * never a JDBC connection — so listeners can flood without blocking on disk.
 * Writes (add/remove/pause/resume) hit JDBC synchronously so the next process
 * boot is consistent.</p>
 *
 * <p>Per-subscription cap: §4.34 states "default 1000 events/hour, configurable
 * per manifest". The registry tracks a sliding-window count per subscription;
 * when exceeded, dispatch returns {@link DeliveryDecision#RATE_LIMITED} and
 * the listener should drop the event with an audit entry.</p>
 */
public final class InboundSubscriptionRegistry {

    private static final Logger log = LoggerFactory.getLogger(InboundSubscriptionRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default delivery cap when the manifest doesn't override (§4.34). */
    public static final int DEFAULT_CAP_PER_HOUR = 1000;

    private static volatile InboundSubscriptionRegistry INSTANCE;

    /** A live subscription. */
    public record Subscription(
        String subscriptionId,
        String itemId,
        String agentId,
        String kind,           // webhook|rss|email|mqtt|file_watch|...
        String hookName,       // e.g. "onWebhook" — JS function in item script
        String target,         // path / topic / url depending on kind
        Map<String, Object> opts,
        String secret,         // HMAC secret for webhook; null otherwise
        int capPerHour,
        boolean paused,
        Instant createdAt
    ) {
        public Map<String, Object> toListShape() {
            var m = new LinkedHashMap<String, Object>();
            m.put("subscriptionId", subscriptionId);
            m.put("kind", kind);
            m.put("target", target);
            m.put("hook", hookName);
            m.put("paused", paused);
            m.put("createdAt", createdAt.toEpochMilli());
            return m;
        }
    }

    /** Per-subscription rate-limit window (sliding-hour count + reset clock). */
    private static final class RateLimitState {
        private final AtomicLong windowStartMs = new AtomicLong();
        private final AtomicLong count = new AtomicLong();

        boolean tryRecord(int capPerHour, long nowMs) {
            var start = windowStartMs.get();
            // First call OR window has elapsed: reset.
            if (start == 0L || nowMs - start >= 3600_000L) {
                if (windowStartMs.compareAndSet(start, nowMs)) {
                    count.set(1);
                    return true;
                }
                // Lost the race; fall through.
            }
            var c = count.incrementAndGet();
            return c <= capPerHour;
        }

        long currentCount() { return count.get(); }
    }

    /** Outcome the dispatch service uses to decide. */
    public enum DeliveryDecision {
        DELIVER, NOT_FOUND, PAUSED, RATE_LIMITED
    }

    private final String jdbcUrl;
    private final ConcurrentHashMap<String, Subscription> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimitState> rateLimits = new ConcurrentHashMap<>();

    private InboundSubscriptionRegistry(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        if (hasJdbc()) {
            initSchema();
            loadFromDisk();
        }
    }

    /**
     * Process-wide instance; first call wins. Subsequent calls return the
     * established singleton regardless of {@code jdbcUrl}, matching the
     * {@link org.wyrdsekai.core.item.ItemScheduleService} convention.
     */
    public static InboundSubscriptionRegistry get(String jdbcUrl) {
        if (INSTANCE == null) {
            synchronized (InboundSubscriptionRegistry.class) {
                if (INSTANCE == null) {
                    INSTANCE = new InboundSubscriptionRegistry(jdbcUrl);
                }
            }
        }
        return INSTANCE;
    }

    /** Test-only — drop the singleton so a fresh test can build a new one. */
    public static synchronized void resetForTesting() {
        INSTANCE = null;
    }

    // ─── Public API ─────────────────────────────────────────────

    /** Add a subscription. Returns the assigned id. */
    public String add(String itemId, String agentId, String kind, String hookName,
                      String target, Map<String, Object> opts, String secret,
                      Integer capPerHour) {
        var id = UUID.randomUUID().toString();
        var safeOpts = opts == null ? Map.<String, Object>of() : Map.copyOf(opts);
        int cap = capPerHour == null || capPerHour <= 0 ? DEFAULT_CAP_PER_HOUR : capPerHour;
        var sub = new Subscription(id, itemId, agentId, kind, hookName,
            target == null ? "" : target, safeOpts, secret, cap, false, Instant.now());
        active.put(id, sub);
        persist(sub);
        log.info("inbound subscription added: id={} kind={} item={} hook={}",
            id, kind, itemId, hookName);
        return id;
    }

    /** Look up by id. */
    public Optional<Subscription> find(String subscriptionId) {
        if (subscriptionId == null) return Optional.empty();
        return Optional.ofNullable(active.get(subscriptionId));
    }

    /** Cancel — owner-scoped (call site verifies the agent owns the sub). */
    public boolean cancel(String agentId, String subscriptionId) {
        var s = active.get(subscriptionId);
        if (s == null) return false;
        if (agentId != null && !agentId.equals(s.agentId)) return false;
        active.remove(subscriptionId);
        rateLimits.remove(subscriptionId);
        deleteRow(subscriptionId);
        return true;
    }

    /** Pause delivery — keeps the row, sets {@code paused=true}. */
    public boolean pause(String agentId, String subscriptionId) {
        return setPaused(agentId, subscriptionId, true);
    }

    /** Resume delivery — clears {@code paused}. */
    public boolean resume(String agentId, String subscriptionId) {
        return setPaused(agentId, subscriptionId, false);
    }

    private boolean setPaused(String agentId, String subscriptionId, boolean paused) {
        var s = active.get(subscriptionId);
        if (s == null) return false;
        if (agentId != null && !agentId.equals(s.agentId)) return false;
        var updated = new Subscription(s.subscriptionId, s.itemId, s.agentId, s.kind,
            s.hookName, s.target, s.opts, s.secret, s.capPerHour, paused, s.createdAt);
        active.put(subscriptionId, updated);
        persist(updated);
        return true;
    }

    /** List subscriptions for one agent. */
    public List<Map<String, Object>> list(String agentId) {
        var out = new ArrayList<Map<String, Object>>();
        for (var s : active.values()) {
            if (agentId == null || agentId.equals(s.agentId)) {
                out.add(s.toListShape());
            }
        }
        return out;
    }

    /** List by listener kind (e.g. used by adapters when restoring state). */
    public List<Subscription> byKind(String kind) {
        var out = new ArrayList<Subscription>();
        for (var s : active.values()) {
            if (kind.equals(s.kind)) out.add(s);
        }
        return out;
    }

    /** All subscriptions snapshot — for diagnostics. */
    public List<Subscription> all() {
        return List.copyOf(active.values());
    }

    public int size() { return active.size(); }

    /**
     * Decide whether this subscription accepts the next event. Increments the
     * rate-limit counter as a side effect when the answer is
     * {@link DeliveryDecision#DELIVER}.
     */
    public DeliveryDecision evaluate(String subscriptionId) {
        var s = active.get(subscriptionId);
        if (s == null) return DeliveryDecision.NOT_FOUND;
        if (s.paused) return DeliveryDecision.PAUSED;
        var rl = rateLimits.computeIfAbsent(subscriptionId, _ -> new RateLimitState());
        var ok = rl.tryRecord(s.capPerHour, System.currentTimeMillis());
        return ok ? DeliveryDecision.DELIVER : DeliveryDecision.RATE_LIMITED;
    }

    /** Diagnostic-only: current count for a subscription's window. */
    public long currentCount(String subscriptionId) {
        var rl = rateLimits.get(subscriptionId);
        return rl == null ? 0L : rl.currentCount();
    }

    // ─── Persistence ────────────────────────────────────────────

    private boolean hasJdbc() {
        return jdbcUrl != null && !jdbcUrl.isBlank();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() {
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS item_listener_subscriptions (
                  subscription_id TEXT PRIMARY KEY,
                  item_id TEXT NOT NULL,
                  agent_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  hook_name TEXT NOT NULL,
                  target TEXT NOT NULL,
                  opts_json TEXT NOT NULL,
                  secret TEXT,
                  cap_per_hour INTEGER NOT NULL,
                  paused INTEGER NOT NULL,
                  created_at_ms BIGINT NOT NULL
                )
                """);
        } catch (SQLException e) {
            log.warn("InboundSubscriptionRegistry.initSchema failed: {}", e.getMessage());
        }
    }

    private void persist(Subscription s) {
        if (!hasJdbc()) return;
        try (var c = conn()) {
            try (var del = c.prepareStatement(
                    "DELETE FROM item_listener_subscriptions WHERE subscription_id = ?")) {
                del.setString(1, s.subscriptionId);
                del.executeUpdate();
            }
            try (var ins = c.prepareStatement("""
                INSERT INTO item_listener_subscriptions
                  (subscription_id, item_id, agent_id, kind, hook_name, target,
                   opts_json, secret, cap_per_hour, paused, created_at_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """)) {
                ins.setString(1, s.subscriptionId);
                ins.setString(2, s.itemId);
                ins.setString(3, s.agentId);
                ins.setString(4, s.kind);
                ins.setString(5, s.hookName);
                ins.setString(6, s.target);
                ins.setString(7, MAPPER.writeValueAsString(s.opts));
                ins.setString(8, s.secret);
                ins.setInt(9, s.capPerHour);
                ins.setInt(10, s.paused ? 1 : 0);
                ins.setLong(11, s.createdAt.toEpochMilli());
                ins.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("InboundSubscriptionRegistry.persist failed: {}", e.getMessage());
        }
    }

    private void deleteRow(String id) {
        if (!hasJdbc()) return;
        try (var c = conn();
             var ps = c.prepareStatement(
                "DELETE FROM item_listener_subscriptions WHERE subscription_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("InboundSubscriptionRegistry.deleteRow failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        try (var c = conn();
             var ps = c.prepareStatement("""
                 SELECT subscription_id, item_id, agent_id, kind, hook_name, target,
                        opts_json, secret, cap_per_hour, paused, created_at_ms
                 FROM item_listener_subscriptions
                 """);
             var rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                Map<String, Object> opts;
                try {
                    var j = rs.getString("opts_json");
                    opts = j == null || j.isBlank()
                        ? Map.of()
                        : MAPPER.readValue(j, Map.class);
                } catch (Exception e) {
                    opts = Map.of();
                }
                var s = new Subscription(
                    rs.getString("subscription_id"),
                    rs.getString("item_id"),
                    rs.getString("agent_id"),
                    rs.getString("kind"),
                    rs.getString("hook_name"),
                    rs.getString("target"),
                    opts,
                    rs.getString("secret"),
                    rs.getInt("cap_per_hour"),
                    rs.getInt("paused") == 1,
                    Instant.ofEpochMilli(rs.getLong("created_at_ms")));
                active.put(s.subscriptionId, s);
                count++;
            }
            if (count > 0) {
                log.info("InboundSubscriptionRegistry loaded {} subscriptions from disk", count);
            }
        } catch (SQLException e) {
            log.warn("InboundSubscriptionRegistry.loadFromDisk failed: {}", e.getMessage());
        }
    }
}
