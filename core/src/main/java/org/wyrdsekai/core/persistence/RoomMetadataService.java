package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Room metadata registry backed by SQLite or PostgreSQL.
 * Tracks all rooms for enumeration — Cluster Sharding cannot list entities.
 */
public final class RoomMetadataService {

    private static final Logger log = LoggerFactory.getLogger(RoomMetadataService.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public record RoomInfo(String roomId, String name, String zone,
                           String createdBy, long createdAt) {}

    public RoomMetadataService(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public RoomMetadataService(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    /**
     * Register a room (idempotent — INSERT OR IGNORE).
     */
    public void register(String roomId, String name, String zone, String createdBy) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = dialect.insertIgnore("rooms",
                "room_id, name, zone, created_by", "?, ?, ?, ?");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, roomId);
                stmt.setString(2, name);
                stmt.setString(3, zone);
                stmt.setString(4, createdBy);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Room registered: {} ({})", name, roomId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Room registration failed", e);
        }
    }

    /**
     * List all registered rooms, ordered by name.
     */
    /** Forget a room (demolition). Returns true when a row was removed. */
    public boolean delete(String roomId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("DELETE FROM rooms WHERE room_id = ?")) {
            stmt.setString(1, roomId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Room delete failed", e);
        }
    }

    /** Change the recorded name (steward rename, or the name repair that runs on upgrade). */
    public boolean rename(String roomId, String name) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("UPDATE rooms SET name = ? WHERE room_id = ?")) {
            stmt.setString(1, name);
            stmt.setString(2, roomId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Room rename failed", e);
        }
    }

    public List<RoomInfo> listRooms() {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = "SELECT room_id, name, zone, created_by, created_at FROM rooms ORDER BY name";
            try (var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery(sql);
                var rooms = new ArrayList<RoomInfo>();
                while (rs.next()) {
                    rooms.add(new RoomInfo(
                        rs.getString("room_id"),
                        rs.getString("name"),
                        rs.getString("zone"),
                        rs.getString("created_by"),
                        rs.getLong("created_at")
                    ));
                }
                return rooms;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Room listing failed", e);
        }
    }

    /**
     * When each room last had an event: the newest write in its journal, by room id. The one
     * record of a room's life the node already keeps; a room nobody has entered, spoken in or
     * placed anything in for weeks is a candidate for {@code wyrd rooms prune --stale}. Rooms
     * with no journal (never spoken in since the journal began) are absent from the map, and a
     * caller treats absence as "not known", never as "stale". Empty when the journal cannot be
     * read (a node whose events live elsewhere).
     */
    public Map<String, Long> lastActivityByRoom() {
        var out = new HashMap<String, Long>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT persistence_id, MAX(write_timestamp) AS t FROM event_journal "
                 + "WHERE persistence_id LIKE 'Room|%' GROUP BY persistence_id")) {
            while (rs.next()) {
                var pid = rs.getString(1);
                if (pid == null) continue;
                var parts = pid.split("\\|");
                if (parts.length < 2 || parts[1].isBlank()) continue;
                out.put(parts[1], rs.getLong(2));
            }
        } catch (SQLException e) {
            log.debug("room activity unavailable: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Get info for a single room.
     */
    public Optional<RoomInfo> getRoom(String roomId) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = "SELECT room_id, name, zone, created_by, created_at FROM rooms WHERE room_id = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, roomId);
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();
                return Optional.of(new RoomInfo(
                    rs.getString("room_id"),
                    rs.getString("name"),
                    rs.getString("zone"),
                    rs.getString("created_by"),
                    rs.getLong("created_at")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Room lookup failed", e);
        }
    }

    /**
     * Count total registered rooms.
     */
    public int countRooms() {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM rooms");
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Room count failed", e);
        }
    }
}
