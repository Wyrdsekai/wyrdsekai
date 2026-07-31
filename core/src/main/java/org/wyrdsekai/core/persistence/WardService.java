package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.home.WardGrantSync;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ward system — room-level access control backed by SQLite or PostgreSQL.
 *
 * Policy:
 * - Rooms with NO wards are OPEN (all operations allowed to everyone).
 * - Once ANY ward exists for a room, it becomes WARDED (only explicit grants allowed).
 * - Principal "*" is a wildcard grant (applies to everyone).
 * - The "admin" permission implies all other permissions.
 *
 * Permissions: look (always allowed), enter, speak, take, drop, use, build, admin.
 */
public final class WardService {

    private static final Logger log = LoggerFactory.getLogger(WardService.class);

    /** Well-known principal: wildcard (everyone). */
    public static final String WILDCARD = "*";

    /** Well-known principal: system (zone guardian, seeding). */
    public static final String SYSTEM = "system";

    private final String jdbcUrl;
    private final SqlDialect dialect;

    /**
     * Optional mirror: when set, writes to Home rooms are also materialized as
     * Grants on {@code home://{owner}/home-room}.
     */
    private volatile WardGrantSync grantSync;

    public record Ward(String roomId, String principal, String permission,
                       String grantedBy, long createdAt) {}

    public WardService(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public WardService(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    /** Attach the Home-room Grant mirror. Idempotent. */
    public void setGrantSync(WardGrantSync sync) {
        this.grantSync = sync;
        log.info("WardService: Home-room grant mirror attached");
    }

    /**
     * Check if a principal is allowed to perform an action in a room.
     *
     * @param roomId    the room to check
     * @param principal the user/agent ID (or null for anonymous)
     * @param permission the permission to check (enter, speak, take, drop, use, build, admin)
     * @return true if allowed
     */
    public boolean isAllowed(String roomId, String principal, String permission) {
        // look is always allowed — no ward check
        if ("look".equals(permission)) return true;

        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            // First: does this room have ANY wards?
            try (var stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM wards WHERE room_id = ?")) {
                stmt.setString(1, roomId);
                var rs = stmt.executeQuery();
                if (rs.next() && rs.getInt(1) == 0) {
                    // No wards = open room, everything allowed
                    return true;
                }
            }

            // Room is warded — check for explicit grant
            // Check both the specific principal and the wildcard
            var sql = """
                SELECT COUNT(*) FROM wards
                WHERE room_id = ?
                  AND principal IN (?, ?)
                  AND permission IN (?, 'admin')
                """;
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, roomId);
                stmt.setString(2, principal != null ? principal : "");
                stmt.setString(3, WILDCARD);
                stmt.setString(4, permission);
                var rs = stmt.executeQuery();
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("Ward check failed for room={}, principal={}, perm={}: {}",
                roomId, principal, permission, e.getMessage());
            // Fail open for M0 — revisit for production
            return true;
        }
    }

    /**
     * Grant a ward permission WITHOUT firing the home-room grant mirror.
     * Used by flows that have already issued the underlying Grant (e.g.
     * §10 approve) and want to write only the ward row.
     */
    public boolean grantSilent(String roomId, String principal, String permission,
                                String grantedBy) {
        var savedSync = this.grantSync;
        this.grantSync = null;
        try {
            return grant(roomId, principal, permission, grantedBy);
        } finally {
            this.grantSync = savedSync;
        }
    }

    /**
     * Grant a permission to a principal in a room.
     *
     * @return true if the ward was created (false if it already existed)
     */
    public boolean grant(String roomId, String principal, String permission, String grantedBy) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = dialect.insertIgnore("wards",
                "room_id, principal, permission, granted_by",
                "?, ?, ?, ?");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, roomId);
                stmt.setString(2, principal);
                stmt.setString(3, permission);
                stmt.setString(4, grantedBy);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Ward granted: {} can {} in {} (by {})",
                        principal, permission, roomId, grantedBy);
                    var sync = grantSync;
                    if (sync != null) sync.onGranted(roomId, principal, permission, grantedBy);
                }
                return rows > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Ward grant failed", e);
        }
    }

    /** Revoke without firing the Home-room grant mirror (§108 eject path). */
    public boolean revokeSilent(String roomId, String principal, String permission) {
        var savedSync = this.grantSync;
        this.grantSync = null;
        try {
            return revoke(roomId, principal, permission);
        } finally {
            this.grantSync = savedSync;
        }
    }

    /**
     * Revoke a permission from a principal in a room.
     *
     * @return true if the ward was removed
     */
    public boolean revoke(String roomId, String principal, String permission) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = "DELETE FROM wards WHERE room_id = ? AND principal = ? AND permission = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, roomId);
                stmt.setString(2, principal);
                stmt.setString(3, permission);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Ward revoked: {} lost {} in {}", principal, permission, roomId);
                    var sync = grantSync;
                    if (sync != null) sync.onRevoked(roomId, principal, permission);
                }
                return rows > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Ward revoke failed", e);
        }
    }

    /**
     * List all wards for a room.
     */
    public List<Ward> listWards(String roomId) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = "SELECT room_id, principal, permission, granted_by, created_at FROM wards WHERE room_id = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, roomId);
                var rs = stmt.executeQuery();
                var wards = new ArrayList<Ward>();
                while (rs.next()) {
                    wards.add(new Ward(
                        rs.getString("room_id"),
                        rs.getString("principal"),
                        rs.getString("permission"),
                        rs.getString("granted_by"),
                        rs.getLong("created_at")
                    ));
                }
                return wards;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Ward list failed", e);
        }
    }

    /**
     * Check if a principal has admin permission on a room.
     */
    public boolean isAdmin(String roomId, String principal) {
        return isAllowed(roomId, principal, "admin");
    }

    /**
     * Seed default wards for a Foundation room.
     * Everyone can enter, speak, look. Only system has admin.
     */
    public void seedFoundationWards(String roomId) {
        grant(roomId, WILDCARD, "enter", SYSTEM);
        grant(roomId, WILDCARD, "speak", SYSTEM);
        grant(roomId, WILDCARD, "take", SYSTEM);
        grant(roomId, WILDCARD, "drop", SYSTEM);
        grant(roomId, WILDCARD, "use", SYSTEM);
        grant(roomId, WILDCARD, "use", SYSTEM);
        grant(roomId, SYSTEM, "admin", SYSTEM);
    }

    /**
     * Count total ward entries across all rooms.
     */
    public int countWards() {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM wards");
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Ward count failed", e);
        }
    }

    /**
     * Remove all wards for a room (makes it open again).
     */
    public void clearWards(String roomId) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.prepareStatement("DELETE FROM wards WHERE room_id = ?")) {
                stmt.setString(1, roomId);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("All wards cleared for room {} ({} removed)", roomId, rows);
                    var sync = grantSync;
                    if (sync != null) sync.onCleared(roomId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Ward clear failed", e);
        }
    }
}
