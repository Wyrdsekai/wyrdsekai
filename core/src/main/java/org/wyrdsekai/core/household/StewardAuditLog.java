package org.wyrdsekai.core.household;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit log for steward (admin) actions (§101).
 * All consequential steward actions are logged for transparency.
 *
 * <p>PERSISTENT when constructed with a JDBC url + dialect (the production
 * path): entries survive restarts — an audit ledger that forgot on reboot
 * would be no audit ledger at all ("the ink is permanent" must be true).
 * The no-arg constructor keeps the legacy in-memory behavior for tests and
 * bare boots.</p>
 */
public class StewardAuditLog {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger(StewardAuditLog.class);

    /** An auditable steward action. */
    public record StewardAction(
        long entryId,
        Instant timestamp,
        String actorDid,
        String actorName,
        ActionType type,
        String targetId,
        String description,
        boolean approved
    ) {}

    public enum ActionType {
        MEMBER_ADD, MEMBER_REMOVE, MEMBER_PROMOTE, MEMBER_DEACTIVATE,
        AGENT_CREATE, AGENT_DELETE, AGENT_CONFIG,
        BUDGET_CHANGE, SAFETY_CHANGE, TRUST_CHANGE,
        SPENDING_FREEZE, SPENDING_UNFREEZE,
        TOPOLOGY_CHANGE, EXPORT, IMPORT,
        DELEGATION_GRANT, DELEGATION_REVOKE
    }

    private final Deque<StewardAction> log = new ConcurrentLinkedDeque<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private int maxEntries = 10_000;

    // Persistence: set when built with (jdbcUrl, dialect). Null → in-memory.
    private final String jdbcUrl;
    private final SqlDialect dialect;

    /** In-memory audit log (tests / bare boots). */
    public StewardAuditLog() {
        this.jdbcUrl = null;
        this.dialect = null;
    }

    /** Persistent audit log backed by the world DB. */
    public StewardAuditLog(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
        try (var conn = getConnection()) {
            ensureTable(conn);
        } catch (SQLException e) {
            AUDIT_LOG.error("StewardAuditLog: could not ensure steward_audit table: {}",
                e.getMessage());
        }
    }

    private boolean persistent() { return jdbcUrl != null; }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void ensureTable(Connection conn) throws SQLException {
        var idCol = dialect instanceof SqlDialect.PostgreSQL
            ? "entry_id BIGSERIAL PRIMARY KEY"
            : "entry_id INTEGER PRIMARY KEY AUTOINCREMENT";
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS steward_audit("
                + idCol + ", "
                + "ts BIGINT NOT NULL, "
                + "actor_did TEXT, "
                + "actor_name TEXT, "
                + "type TEXT NOT NULL, "
                + "target_id TEXT, "
                + "description TEXT, "
                + "approved INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_steward_audit_ts "
                + "ON steward_audit(ts)");
        }
    }

    // Process-global handle so login/bootstrap paths (WyrdShellCommand, telnet)
    // that don't carry the injected instance can still record events. Main
    // registers the one it builds; unset → callers no-op. Mirrors
    // PairingService/ParentalControlService.
    private static volatile StewardAuditLog instance;

    /** Server boot: publish the canonical instance. */
    public static void register(StewardAuditLog l) { instance = l; }

    /** The registered instance, or null if none (tests / bare boots). */
    public static StewardAuditLog get() { return instance; }

    /** Test seam. */
    public static void resetForTests() { instance = null; }

    /** Log a steward action. */
    public StewardAction log(String actorDid, String actorName, ActionType type,
                              String targetId, String description, boolean approved) {
        var now = Instant.now();
        if (persistent()) {
            try (var conn = getConnection();
                 var ps = conn.prepareStatement(
                     "INSERT INTO steward_audit"
                     + "(ts, actor_did, actor_name, type, target_id, description, approved)"
                     + " VALUES (?,?,?,?,?,?,?)")) {
                ps.setLong(1, now.toEpochMilli());
                ps.setString(2, actorDid);
                ps.setString(3, actorName);
                ps.setString(4, type != null ? type.name() : "UNKNOWN");
                ps.setString(5, targetId);
                ps.setString(6, description);
                ps.setInt(7, approved ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                AUDIT_LOG.error("StewardAuditLog write failed: {}", e.getMessage());
            }
            return new StewardAction(0, now, actorDid, actorName, type,
                targetId, description, approved);
        }
        var entry = new StewardAction(nextId.getAndIncrement(), now,
            actorDid, actorName, type, targetId, description, approved);
        log.addLast(entry);
        while (log.size() > maxEntries) log.pollFirst();
        return entry;
    }

    /** Get recent actions. */
    public List<StewardAction> recent(int limit) {
        if (persistent()) return query(null, null, false, limit);
        return log.stream()
            .sorted(Comparator.comparing(StewardAction::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get actions by a specific steward. */
    public List<StewardAction> byActor(String actorDid, int limit) {
        if (persistent()) return query("actor_did", actorDid, false, limit);
        return log.stream()
            .filter(a -> a.actorDid().equals(actorDid))
            .sorted(Comparator.comparing(StewardAction::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get actions targeting a specific entity. */
    public List<StewardAction> byTarget(String targetId, int limit) {
        if (persistent()) return query("target_id", targetId, false, limit);
        return log.stream()
            .filter(a -> a.targetId().equals(targetId))
            .sorted(Comparator.comparing(StewardAction::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get denied actions. */
    public List<StewardAction> denied(int limit) {
        if (persistent()) return query(null, null, true, limit);
        return log.stream()
            .filter(a -> !a.approved())
            .sorted(Comparator.comparing(StewardAction::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /**
     * Parameterized read from the persistent table, newest-first. {@code col}
     * (nullable) adds a {@code col = ?} filter; {@code deniedOnly} adds
     * {@code approved = 0}.
     */
    private List<StewardAction> query(String col, String val, boolean deniedOnly, int limit) {
        var where = new ArrayList<String>();
        if (col != null) where.add(col + " = ?");
        if (deniedOnly) where.add("approved = 0");
        var sql = "SELECT entry_id, ts, actor_did, actor_name, type, target_id, "
            + "description, approved FROM steward_audit"
            + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
            + " ORDER BY ts DESC, entry_id DESC LIMIT ?";
        var out = new ArrayList<StewardAction>();
        try (var conn = getConnection();
             var ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (col != null) ps.setString(i++, val);
            ps.setInt(i, Math.max(1, limit));
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    ActionType type;
                    try { type = ActionType.valueOf(rs.getString("type")); }
                    catch (IllegalArgumentException | NullPointerException ex) { type = null; }
                    out.add(new StewardAction(
                        rs.getLong("entry_id"),
                        Instant.ofEpochMilli(rs.getLong("ts")),
                        rs.getString("actor_did"), rs.getString("actor_name"),
                        type, rs.getString("target_id"), rs.getString("description"),
                        rs.getInt("approved") != 0));
                }
            }
        } catch (SQLException e) {
            AUDIT_LOG.error("StewardAuditLog read failed: {}", e.getMessage());
        }
        return out;
    }

    public int entryCount() {
        if (persistent()) return query(null, null, false, Integer.MAX_VALUE).size();
        return log.size();
    }

    public void setMaxEntries(int max) { this.maxEntries = max; }
}
