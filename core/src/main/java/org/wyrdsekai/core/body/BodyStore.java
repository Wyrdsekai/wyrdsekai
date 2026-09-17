package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The map and the marks on disk, beside the rest of the record in {@code world.db}.
 *
 * <p>Two tables. {@code body_parts} is one row per attached part: its descriptor and where it
 * is in its life. {@code body_marks} is the told-afterwards ledger: what the body did without
 * her, kept until she has read it and after. Both are plain SQL that reads the same on sqlite
 * and postgres, the mail table's pattern.</p>
 */
public final class BodyStore {

    private static final Logger log = LoggerFactory.getLogger(BodyStore.class);

    private final String jdbcUrl;

    public BodyStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public static void ensureTables(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS body_parts (
                  part_id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  name TEXT NOT NULL,
                  owner TEXT,
                  transport TEXT,
                  heartbeat_ms INTEGER NOT NULL,
                  felt_weight TEXT NOT NULL,
                  numb_behaviour TEXT,
                  shed_tier TEXT,
                  state TEXT NOT NULL,
                  first_attached INTEGER NOT NULL,
                  last_heartbeat INTEGER,
                  last_detail TEXT,
                  numb_since INTEGER,
                  gone_at INTEGER,
                  gone_by TEXT,
                  last_used INTEGER)""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS body_marks (
                  id TEXT PRIMARY KEY,
                  ts INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  subject TEXT,
                  audience TEXT,
                  text TEXT NOT NULL,
                  detail TEXT,
                  read_by TEXT)""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_body_marks_ts ON body_marks (ts)");
        }
    }

    // ── parts ──

    public void upsert(BodyPart p) {
        var d = p.descriptor();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var del = conn.prepareStatement("DELETE FROM body_parts WHERE part_id = ?")) {
                del.setString(1, d.id());
                del.executeUpdate();
            }
            try (var ins = conn.prepareStatement("""
                    INSERT INTO body_parts (part_id, kind, name, owner, transport, heartbeat_ms,
                      felt_weight, numb_behaviour, shed_tier, state, first_attached,
                      last_heartbeat, last_detail, numb_since, gone_at, gone_by, last_used)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ins.setString(1, d.id());
                ins.setString(2, d.kind().name());
                ins.setString(3, d.name());
                ins.setString(4, d.owner());
                ins.setString(5, d.transport());
                ins.setLong(6, d.heartbeatEvery().toMillis());
                ins.setString(7, d.feltWeight().name());
                ins.setString(8, d.numbBehaviour());
                ins.setString(9, d.shedTier());
                ins.setString(10, p.state().name());
                ins.setLong(11, p.firstAttached().toEpochMilli());
                setInstant(ins, 12, p.lastHeartbeat());
                ins.setString(13, p.lastDetail());
                setInstant(ins, 14, p.numbSince());
                setInstant(ins, 15, p.goneAt());
                ins.setString(16, p.goneBy());
                setInstant(ins, 17, p.lastUsed());
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("body map: could not save part {}: {}", d.id(), e.getMessage());
        }
    }

    public List<BodyPart> loadParts() {
        var out = new ArrayList<BodyPart>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("SELECT * FROM body_parts ORDER BY kind, name");
             var rs = stmt.executeQuery()) {
            while (rs.next()) out.add(readPart(rs));
        } catch (SQLException e) {
            log.warn("body map: could not load parts: {}", e.getMessage());
        }
        return out;
    }

    private static BodyPart readPart(ResultSet rs) throws SQLException {
        var d = new LimbDescriptor(
            rs.getString("part_id"),
            BodyKind.valueOf(rs.getString("kind")),
            rs.getString("name"),
            rs.getString("owner"),
            rs.getString("transport"),
            Duration.ofMillis(Math.max(1, rs.getLong("heartbeat_ms"))),
            FeltWeight.valueOf(rs.getString("felt_weight")),
            rs.getString("numb_behaviour"),
            rs.getString("shed_tier"));
        return new BodyPart(d,
            PartState.valueOf(rs.getString("state")),
            Instant.ofEpochMilli(rs.getLong("first_attached")),
            instant(rs, "last_heartbeat"),
            rs.getString("last_detail"),
            instant(rs, "numb_since"),
            instant(rs, "gone_at"),
            rs.getString("gone_by"),
            instant(rs, "last_used"));
    }

    // ── marks ──

    public void insert(BodyMark m) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ins = conn.prepareStatement("""
                 INSERT INTO body_marks (id, ts, kind, subject, audience, text, detail, read_by)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ins.setString(1, m.id());
            ins.setLong(2, m.at().toEpochMilli());
            ins.setString(3, m.kind());
            ins.setString(4, m.subject());
            ins.setString(5, m.audience());
            ins.setString(6, m.text());
            ins.setString(7, m.detail());
            ins.setString(8, joinReaders(m.readBy()));
            ins.executeUpdate();
        } catch (SQLException e) {
            log.warn("body map: could not save mark {}: {}", m.kind(), e.getMessage());
        }
    }

    public void setReadBy(String id, List<String> readBy) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var up = conn.prepareStatement("UPDATE body_marks SET read_by = ? WHERE id = ?")) {
            up.setString(1, joinReaders(readBy));
            up.setString(2, id);
            up.executeUpdate();
        } catch (SQLException e) {
            log.warn("body map: could not record a mark as read: {}", e.getMessage());
        }
    }

    /** Newest first. */
    public List<BodyMark> loadMarks(int limit) {
        var out = new ArrayList<BodyMark>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("SELECT * FROM body_marks ORDER BY ts DESC LIMIT ?")) {
            stmt.setInt(1, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) out.add(readMark(rs));
            }
        } catch (SQLException e) {
            log.warn("body map: could not load marks: {}", e.getMessage());
        }
        return out;
    }

    public void pruneMarksOlderThan(Instant cutoff) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var del = conn.prepareStatement("DELETE FROM body_marks WHERE ts < ? AND read_by IS NOT NULL AND read_by <> ''")) {
            del.setLong(1, cutoff.toEpochMilli());
            del.executeUpdate();
        } catch (SQLException e) {
            log.debug("body map: prune skipped: {}", e.getMessage());
        }
    }

    private static BodyMark readMark(ResultSet rs) throws SQLException {
        var readBy = rs.getString("read_by");
        return new BodyMark(rs.getString("id"), Instant.ofEpochMilli(rs.getLong("ts")),
            rs.getString("kind"), rs.getString("subject"), rs.getString("audience"),
            rs.getString("text"), rs.getString("detail"),
            readBy == null || readBy.isBlank() ? List.of()
                : Arrays.stream(readBy.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    private static String joinReaders(List<String> readBy) {
        return readBy == null || readBy.isEmpty() ? "" : String.join(",", readBy);
    }

    private static void setInstant(java.sql.PreparedStatement ps, int idx, Instant t) throws SQLException {
        if (t == null) ps.setNull(idx, Types.INTEGER);
        else ps.setLong(idx, t.toEpochMilli());
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() || v == 0 ? null : Instant.ofEpochMilli(v);
    }
}
