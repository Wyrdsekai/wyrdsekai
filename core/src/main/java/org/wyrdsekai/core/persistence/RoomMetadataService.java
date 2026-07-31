package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
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
