package org.wyrdsekai.core.household;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Parental controls — per-member time limits, room restrictions, inference
 * quotas, and content filters (the promises of the Study's parental-controls
 * scroll, now enforced by the substrate).
 * <p>
 * Uses the same JDBC pattern as {@link AuthService} / PairingService:
 * one table of steward-set controls ({@code parental_controls}) plus a
 * per-day usage counter table ({@code parental_usage}). Writes are
 * steward-gated at the service (caller's role verified via the injected
 * {@link AuthService}); reads and enforcement checks are open.
 * <p>
 * Enforcement points consult the {@link #get()} singleton and MUST no-op
 * (allow) when it is {@code null} — tests and bare boots that never call
 * {@link #init} see no behavior change.
 */
public final class ParentalControlService {

    private static final Logger log = LoggerFactory.getLogger(ParentalControlService.class);

    /** Content-filter modes. Anything other than {@code strict} means off. */
    public static final String FILTER_OFF = "off";
    public static final String FILTER_STRICT = "strict";

    /** Cached controls stay fresh this long — enforcement checks run per
     *  room-entry / per-prose-line and must not hammer the DB. */
    private static final long CACHE_TTL_MILLIS = 15_000;

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private final AuthService authService;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService usageTicker;

    private record CacheEntry(Optional<Controls> controls, long expiresAt) {}

    /**
     * Steward-set controls for one member. {@code null} numeric limits mean
     * unlimited; an empty {@code blockedRooms} list means no room is barred.
     */
    public record Controls(String memberUserId, Integer dailyMinutes,
                           List<String> blockedRooms, Integer dailyInference,
                           String contentFilter, String setBy, Instant updatedAt) {}

    /** One member's usage counters for a single day (YYYY-MM-DD). */
    public record Usage(String memberUserId, String day,
                        int minutesUsed, int inferencesUsed) {}

    public ParentalControlService(String jdbcUrl, SqlDialect dialect, AuthService authService) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
        this.authService = authService;
    }

    // ─── Singleton accessor (same pattern as PairingService) ─────────────

    private static volatile ParentalControlService INSTANCE;

    /**
     * Build, initialize schema, and register the singleton. Called once at
     * boot from Main. Enforcement points that see {@code get() == null}
     * treat every check as ALLOW.
     */
    public static ParentalControlService init(String jdbcUrl, SqlDialect dialect,
                                              AuthService authService) {
        var svc = new ParentalControlService(jdbcUrl, dialect, authService);
        svc.initSchema();
        INSTANCE = svc;
        return svc;
    }

    /** Returns the registered instance, or {@code null} when parental controls aren't wired. */
    public static ParentalControlService get() {
        return INSTANCE;
    }

    /** Test hook — drop the singleton (and its ticker) so other tests see a bare world. */
    public static void resetForTests() {
        var svc = INSTANCE;
        INSTANCE = null;
        if (svc != null) svc.stopUsageTicker();
    }

    /** Initialize tables. Idempotent (CREATE TABLE IF NOT EXISTS). */
    public void initSchema() {
        var intType = dialect instanceof SqlDialect.PostgreSQL ? "BIGINT" : "INTEGER";
        try (var conn = getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS parental_controls("
                + "member_user_id TEXT PRIMARY KEY, "
                + "daily_minutes INTEGER, "
                + "blocked_rooms TEXT NOT NULL DEFAULT '[]', "
                + "daily_inference INTEGER, "
                + "content_filter TEXT NOT NULL DEFAULT 'off', "
                + "set_by TEXT, "
                + "updated_at " + intType + " NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS parental_usage("
                + "member_user_id TEXT NOT NULL, "
                + "day TEXT NOT NULL, "
                + "minutes_used INTEGER NOT NULL DEFAULT 0, "
                + "inferences_used INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(member_user_id, day))");
            log.debug("Parental-controls schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize parental-controls schema", e);
        }
    }

    // ─── Controls CRUD (writes steward-gated) ────────────────────────────

    /**
     * Create or replace a member's controls. Steward-only: returns false
     * when {@code callerUserId} does not hold the steward role.
     */
    public boolean setControls(String callerUserId, String memberUserId,
                               Integer dailyMinutes, List<String> blockedRooms,
                               Integer dailyInference, String contentFilter) {
        if (!isSteward(callerUserId)) {
            log.warn("parental setControls denied — caller {} is not steward", callerUserId);
            return false;
        }
        if (memberUserId == null || memberUserId.isBlank()) return false;
        var filter = FILTER_STRICT.equalsIgnoreCase(contentFilter) ? FILTER_STRICT : FILTER_OFF;
        var rooms = blockedRooms == null ? List.<String>of() : List.copyOf(blockedRooms);
        try (var conn = getConnection()) {
            var sql = dialect.upsert("parental_controls",
                "member_user_id, daily_minutes, blocked_rooms, daily_inference, content_filter, set_by, updated_at",
                "?,?,?,?,?,?,?",
                "member_user_id",
                "daily_minutes = EXCLUDED.daily_minutes, blocked_rooms = EXCLUDED.blocked_rooms, "
                    + "daily_inference = EXCLUDED.daily_inference, content_filter = EXCLUDED.content_filter, "
                    + "set_by = EXCLUDED.set_by, updated_at = EXCLUDED.updated_at");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, memberUserId);
                if (dailyMinutes != null) stmt.setInt(2, Math.max(0, dailyMinutes));
                else stmt.setNull(2, Types.INTEGER);
                stmt.setString(3, Json.mapper().writeValueAsString(rooms));
                if (dailyInference != null) stmt.setInt(4, Math.max(0, dailyInference));
                else stmt.setNull(4, Types.INTEGER);
                stmt.setString(5, filter);
                stmt.setString(6, callerUserId);
                stmt.setLong(7, Instant.now().getEpochSecond());
                stmt.executeUpdate();
            }
            cache.remove(memberUserId);
            log.info("Parental controls set for {} by {} (minutes={}, inference={}, filter={}, blockedRooms={})",
                memberUserId, callerUserId, dailyMinutes, dailyInference, filter, rooms);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to set parental controls for " + memberUserId, e);
        }
    }

    /** Remove a member's controls entirely. Steward-only. */
    public boolean clearControls(String callerUserId, String memberUserId) {
        if (!isSteward(callerUserId)) {
            log.warn("parental clearControls denied — caller {} is not steward", callerUserId);
            return false;
        }
        if (memberUserId == null) return false;
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "DELETE FROM parental_controls WHERE member_user_id = ?")) {
            stmt.setString(1, memberUserId);
            var removed = stmt.executeUpdate() > 0;
            cache.remove(memberUserId);
            if (removed) log.info("Parental controls cleared for {} by {}", memberUserId, callerUserId);
            return removed;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear parental controls for " + memberUserId, e);
        }
    }

    /** The member's controls, or empty when none are set. Cached ~15s. */
    public Optional<Controls> controlsFor(String userId) {
        if (userId == null) return Optional.empty();
        var now = System.currentTimeMillis();
        var cached = cache.get(userId);
        if (cached != null && cached.expiresAt() > now) return cached.controls();
        var loaded = loadControls(userId);
        cache.put(userId, new CacheEntry(loaded, now + CACHE_TTL_MILLIS));
        return loaded;
    }

    /** Every member with controls set. Uncached (steward panel read). */
    public List<Controls> listControls() {
        var out = new ArrayList<Controls>();
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT member_user_id, daily_minutes, blocked_rooms, daily_inference,"
                     + " content_filter, set_by, updated_at FROM parental_controls"
                     + " ORDER BY member_user_id")) {
            var rs = stmt.executeQuery();
            while (rs.next()) out.add(readControls(rs));
        } catch (SQLException e) {
            log.warn("listControls failed: {}", e.getMessage());
        }
        return out;
    }

    private Optional<Controls> loadControls(String userId) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT member_user_id, daily_minutes, blocked_rooms, daily_inference,"
                     + " content_filter, set_by, updated_at FROM parental_controls"
                     + " WHERE member_user_id = ?")) {
            stmt.setString(1, userId);
            var rs = stmt.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(readControls(rs));
        } catch (SQLException e) {
            // Fail open: a broken DB must not lock the household out of rooms.
            log.warn("controlsFor({}) failed — treating as no controls: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    private Controls readControls(ResultSet rs) throws SQLException {
        Integer minutes = rs.getInt("daily_minutes");
        if (rs.wasNull()) minutes = null;
        Integer inference = rs.getInt("daily_inference");
        if (rs.wasNull()) inference = null;
        return new Controls(
            rs.getString("member_user_id"),
            minutes,
            parseRooms(rs.getString("blocked_rooms")),
            inference,
            rs.getString("content_filter"),
            rs.getString("set_by"),
            Instant.ofEpochSecond(rs.getLong("updated_at")));
    }

    private static List<String> parseRooms(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return Json.mapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Unparseable blocked_rooms JSON '{}' — treating as none", json);
            return List.of();
        }
    }

    // ─── Usage counters ──────────────────────────────────────────────────

    /** Add spent minutes to today's counter for the member. */
    public void recordMinutes(String userId, int minutes) {
        recordMinutes(userId, minutes, today());
    }

    /** Day-explicit variant (tests / backfill). {@code day} is YYYY-MM-DD. */
    public void recordMinutes(String userId, int minutes, String day) {
        bumpUsage(userId, day, minutes, 0);
    }

    /** Count one inference against today's quota for the member. */
    public void recordInference(String userId) {
        recordInference(userId, today());
    }

    /** Day-explicit variant (tests / backfill). */
    public void recordInference(String userId, String day) {
        bumpUsage(userId, day, 0, 1);
    }

    /** Today's counters for a member (zeros when nothing recorded). */
    public Usage usageToday(String userId) {
        return usageFor(userId, today());
    }

    /** Counters for a member on an explicit day. */
    public Usage usageFor(String userId, String day) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT minutes_used, inferences_used FROM parental_usage"
                     + " WHERE member_user_id = ? AND day = ?")) {
            stmt.setString(1, userId);
            stmt.setString(2, day);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return new Usage(userId, day, rs.getInt("minutes_used"), rs.getInt("inferences_used"));
            }
        } catch (SQLException e) {
            log.warn("usageFor({}, {}) failed: {}", userId, day, e.getMessage());
        }
        return new Usage(userId, day, 0, 0);
    }

    private void bumpUsage(String userId, String day, int minutes, int inferences) {
        if (userId == null || day == null) return;
        try (var conn = getConnection()) {
            // UPDATE first; INSERT the day row when absent. Portable across
            // SQLite and PostgreSQL without dialect-specific upsert-increment.
            try (var upd = conn.prepareStatement(
                    "UPDATE parental_usage SET minutes_used = minutes_used + ?,"
                        + " inferences_used = inferences_used + ?"
                        + " WHERE member_user_id = ? AND day = ?")) {
                upd.setInt(1, minutes);
                upd.setInt(2, inferences);
                upd.setString(3, userId);
                upd.setString(4, day);
                if (upd.executeUpdate() > 0) return;
            }
            try (var ins = conn.prepareStatement(dialect.insertIgnore("parental_usage",
                    "member_user_id, day, minutes_used, inferences_used", "?,?,?,?"))) {
                ins.setString(1, userId);
                ins.setString(2, day);
                ins.setInt(3, minutes);
                ins.setInt(4, inferences);
                if (ins.executeUpdate() > 0) return;
            }
            // Lost the insert race — another writer created the row; increment it.
            try (var upd = conn.prepareStatement(
                    "UPDATE parental_usage SET minutes_used = minutes_used + ?,"
                        + " inferences_used = inferences_used + ?"
                        + " WHERE member_user_id = ? AND day = ?")) {
                upd.setInt(1, minutes);
                upd.setInt(2, inferences);
                upd.setString(3, userId);
                upd.setString(4, day);
                upd.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("bumpUsage({}, {}) failed: {}", userId, day, e.getMessage());
        }
    }

    // ─── Enforcement queries (null = unlimited; errors fail OPEN) ────────

    /**
     * Minutes the member may still spend today, or {@code null} when
     * unlimited (no controls / no daily_minutes limit). Never negative.
     */
    public Integer minutesRemaining(String userId) {
        var controls = controlsFor(userId);
        if (controls.isEmpty() || controls.get().dailyMinutes() == null) return null;
        return Math.max(0, controls.get().dailyMinutes() - usageToday(userId).minutesUsed());
    }

    /**
     * Inferences the member may still trigger today, or {@code null} when
     * unlimited. Never negative.
     */
    public Integer inferencesRemaining(String userId) {
        var controls = controlsFor(userId);
        if (controls.isEmpty() || controls.get().dailyInference() == null) return null;
        return Math.max(0, controls.get().dailyInference() - usageToday(userId).inferencesUsed());
    }

    /**
     * True unless a blocked-room glob matches {@code roomId}. Globs support
     * {@code *} (any run) and {@code ?} (single char): {@code "gpu-chamber"},
     * {@code "study-*"}. Unknown users / no controls → allowed.
     */
    public boolean canEnterRoom(String userId, String roomId) {
        if (userId == null || roomId == null) return true;
        var controls = controlsFor(userId);
        if (controls.isEmpty()) return true;
        for (var glob : controls.get().blockedRooms()) {
            if (globMatches(glob, roomId)) return false;
        }
        return true;
    }

    /** The member's content-filter mode: {@code strict} or {@code off}. */
    public String contentFilter(String userId) {
        return controlsFor(userId)
            .map(Controls::contentFilter)
            .filter(FILTER_STRICT::equals)
            .orElse(FILTER_OFF);
    }

    /** Glob match: {@code *} = any run, {@code ?} = one char, rest literal. */
    static boolean globMatches(String glob, String value) {
        if (glob == null || value == null) return false;
        var regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return value.matches(regex.toString());
    }

    // ─── Minutes-accrual ticker (server wires at boot) ──────────────────

    /**
     * Start the 60s usage ticker (daemon thread, same shape as
     * EmbeddingService's recycle scheduler). Each tick charges one minute to
     * every live, controlled member and — when that crosses the daily limit —
     * hands the member's id to {@code onLimitExceeded} so the server side can
     * close their sessions politely.
     *
     * @param liveUserIds     snapshot of playerIds with a live session
     *                        (ClientConnectionRegistry-backed on the server)
     * @param onLimitExceeded invoked at most once per tick per over-limit member
     */
    public synchronized void startUsageTicker(Supplier<Set<String>> liveUserIds,
                                              Consumer<String> onLimitExceeded) {
        if (usageTicker != null) return;
        var exec = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "parental-usage-ticker");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(() -> tick(liveUserIds, onLimitExceeded),
            60, 60, TimeUnit.SECONDS);
        usageTicker = exec;
        log.info("Parental usage ticker started (60s)");
    }

    /** Stop the ticker (shutdown / tests). */
    public synchronized void stopUsageTicker() {
        if (usageTicker != null) {
            usageTicker.shutdownNow();
            usageTicker = null;
        }
    }

    /** One ticker pass — package-visible so tests can drive it without the clock. */
    void tick(Supplier<Set<String>> liveUserIds, Consumer<String> onLimitExceeded) {
        try {
            var live = liveUserIds.get();
            if (live == null) return;
            for (var userId : live) {
                if (userId == null) continue;
                // Only members with a controls row accrue counters — anonymous
                // sessions, agents, and unrestricted members stay untracked.
                if (controlsFor(userId).isEmpty()) continue;
                recordMinutes(userId, 1);
                var remaining = minutesRemaining(userId);
                if (remaining != null && remaining <= 0) {
                    try {
                        onLimitExceeded.accept(userId);
                    } catch (RuntimeException e) {
                        log.warn("parental limit-exceeded handler failed for {}: {}",
                            userId, e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("parental usage tick failed: {}", e.getMessage());
        }
    }

    // ─── internals ───────────────────────────────────────────────────────

    private boolean isSteward(String callerUserId) {
        if (callerUserId == null || authService == null) return false;
        try {
            return authService.findUser(callerUserId)
                .map(u -> "steward".equals(u.role()))
                .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
