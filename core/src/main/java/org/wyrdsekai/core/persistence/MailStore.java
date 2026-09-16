package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mail on disk.
 *
 * <p>The mail service kept messages in a map and said so: "Persistence comes in Phase D."
 * That made it a notification queue, not mail — a restart lost every message, and nothing
 * you cannot come back to tomorrow is worth writing. Rows live in {@code world.db} beside
 * the rest of the household, so they are backed up and snapshotted before every upgrade.</p>
 *
 * <p>Filed under the recipient's IDENTITY, with the typed address kept beside it for display
 * and reply. The steward's view reads the header columns only; bodies are the recipient's.</p>
 */
public final class MailStore {

    private static final Logger log = LoggerFactory.getLogger(MailStore.class);

    private final String jdbcUrl;

    public MailStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** One message. {@code from}/{@code to} are identities; the {@code *Address} fields are display text. */
    public record Row(String id, String from, String fromAddress, String to, String toAddress,
                      String subject, String body, long ts, boolean read, boolean archived,
                      String priority, Long expiresAt, String attachmentsJson) {}

    /** Header-only view: what a steward may see of mail that is not theirs. */
    public record Header(String id, String from, String fromAddress, String to, String toAddress,
                         long ts, boolean read, boolean archived, int bodyBytes) {}

    public void insert(Row m) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("""
                 INSERT INTO mail (id, sender, sender_address, recipient, recipient_address,
                                   subject, body, ts, read_flag, archived, priority,
                                   expires_at, attachments)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            stmt.setString(1, m.id());
            stmt.setString(2, m.from());
            stmt.setString(3, m.fromAddress());
            stmt.setString(4, m.to());
            stmt.setString(5, m.toAddress());
            stmt.setString(6, m.subject());
            stmt.setString(7, m.body());
            stmt.setLong(8, m.ts());
            stmt.setInt(9, m.read() ? 1 : 0);
            stmt.setInt(10, m.archived() ? 1 : 0);
            stmt.setString(11, m.priority());
            if (m.expiresAt() == null) stmt.setNull(12, java.sql.Types.INTEGER);
            else stmt.setLong(12, m.expiresAt());
            stmt.setString(13, m.attachmentsJson());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("mail insert failed: " + e.getMessage(), e);
        }
    }

    /** Everything addressed to one identity, newest first. Expired mail is left out. */
    public List<Row> inbox(String recipient, boolean unreadOnly, boolean includeArchived,
                           String fromFilter, long now) {
        var out = new ArrayList<Row>();
        var sql = new StringBuilder("SELECT * FROM mail WHERE recipient = ?");
        if (unreadOnly) sql.append(" AND read_flag = 0");
        if (!includeArchived) sql.append(" AND archived = 0");
        if (fromFilter != null && !fromFilter.isBlank()) sql.append(" AND (sender = ? OR sender_address = ?)");
        sql.append(" AND (expires_at IS NULL OR expires_at > ?) ORDER BY ts DESC");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql.toString())) {
            int i = 1;
            stmt.setString(i++, recipient);
            if (fromFilter != null && !fromFilter.isBlank()) {
                stmt.setString(i++, fromFilter);
                stmt.setString(i++, fromFilter);
            }
            stmt.setLong(i, now);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException e) {
            log.error("mail inbox failed for {}: {}", recipient, e.getMessage());
        }
        return out;
    }

    /** One message, only if it is addressed to this identity. */
    public Row get(String recipient, String id) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("SELECT * FROM mail WHERE recipient = ? AND id = ?")) {
            stmt.setString(1, recipient);
            stmt.setString(2, id);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            log.error("mail get failed: {}", e.getMessage());
            return null;
        }
    }

    /** Set a flag on the recipient's own message. Returns false when there is no such message. */
    public boolean setFlag(String recipient, String id, String column, boolean value) {
        if (!"read_flag".equals(column) && !"archived".equals(column)) {
            throw new IllegalArgumentException("not a mail flag: " + column);
        }
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "UPDATE mail SET " + column + " = ? WHERE recipient = ? AND id = ?")) {
            stmt.setInt(1, value ? 1 : 0);
            stmt.setString(2, recipient);
            stmt.setString(3, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("mail flag failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Headers across the whole household — who wrote to whom, when, and whether it was read.
     * No subject and no body: the steward keeps the household, not its correspondence.
     */
    public List<Header> headers(int limit) {
        var out = new ArrayList<Header>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("""
                 SELECT id, sender, sender_address, recipient, recipient_address, ts,
                        read_flag, archived, LENGTH(body) AS body_bytes
                 FROM mail ORDER BY ts DESC LIMIT ?""")) {
            stmt.setInt(1, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new Header(rs.getString("id"), rs.getString("sender"),
                        rs.getString("sender_address"), rs.getString("recipient"),
                        rs.getString("recipient_address"), rs.getLong("ts"),
                        rs.getInt("read_flag") == 1, rs.getInt("archived") == 1,
                        rs.getInt("body_bytes")));
                }
            }
        } catch (SQLException e) {
            log.error("mail headers failed: {}", e.getMessage());
        }
        return out;
    }

    /** How many unread, for the arrival line and the item's summary. */
    public int unreadCount(String recipient) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM mail WHERE recipient = ? AND read_flag = 0 AND archived = 0")) {
            stmt.setString(1, recipient);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Drop expired mail. Called on the housekeeping pass; safe any time. */
    public int purgeExpired(long now) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "DELETE FROM mail WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            stmt.setLong(1, now);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Create the table on a data dir that predates it. Idempotent. */
    public static void ensureTable(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS mail (
                  id TEXT PRIMARY KEY,
                  sender TEXT NOT NULL,
                  sender_address TEXT,
                  recipient TEXT NOT NULL,
                  recipient_address TEXT,
                  subject TEXT,
                  body TEXT NOT NULL,
                  ts INTEGER NOT NULL,
                  read_flag INTEGER NOT NULL DEFAULT 0,
                  archived INTEGER NOT NULL DEFAULT 0,
                  priority TEXT,
                  expires_at INTEGER,
                  attachments TEXT)""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mail_recipient ON mail (recipient, ts)");
        }
    }

    private static Row read(ResultSet rs) throws SQLException {
        var expires = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() || expires == 0 ? null : expires;   // wasNull reads the LAST column
        return new Row(rs.getString("id"), rs.getString("sender"), rs.getString("sender_address"),
            rs.getString("recipient"), rs.getString("recipient_address"), rs.getString("subject"),
            rs.getString("body"), rs.getLong("ts"), rs.getInt("read_flag") == 1,
            rs.getInt("archived") == 1, rs.getString("priority"),
            expiresAt, rs.getString("attachments"));
    }
}
