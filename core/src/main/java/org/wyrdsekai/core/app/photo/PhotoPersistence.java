package org.wyrdsekai.core.app.photo;

import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * JDBC persistence for PhotoService (§14).
 * Stores photo metadata, face groups, and memory lanes.
 * Uses SqlDialect for cross-database portability (libSQL/PostgreSQL).
 */
public class PhotoPersistence {

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public PhotoPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
    }

    // --- Photo CRUD ---

    public void savePhoto(PhotoService.Photo photo) {
        var sql = dialect.upsert("photos",
            "photo_id, filename, owner_entity, taken_at, imported_at, location, tags, faces, perceptual_hash, status",
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?",
            "photo_id",
            "filename = EXCLUDED.filename, tags = EXCLUDED.tags, faces = EXCLUDED.faces, status = EXCLUDED.status");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, photo.photoId());
            ps.setString(2, photo.filename());
            ps.setString(3, photo.ownerEntity());
            ps.setLong(4, photo.takenAt() != null ? photo.takenAt().getEpochSecond() : 0);
            ps.setLong(5, photo.importedAt().getEpochSecond());
            ps.setString(6, photo.location());
            ps.setString(7, String.join(",", photo.tags()));
            ps.setString(8, String.join(",", photo.faces()));
            ps.setString(9, photo.perceptualHash());
            ps.setString(10, photo.status().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save photo: " + photo.photoId(), e);
        }
    }

    public Optional<PhotoService.Photo> loadPhoto(String photoId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM photos WHERE photo_id = ?")) {
            ps.setString(1, photoId);
            var rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapPhoto(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load photo: " + photoId, e);
        }
    }

    public List<PhotoService.Photo> photosByOwner(String ownerEntity) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM photos WHERE owner_entity = ? ORDER BY imported_at DESC")) {
            ps.setString(1, ownerEntity);
            return mapPhotos(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query photos for owner: " + ownerEntity, e);
        }
    }

    public List<PhotoService.Photo> photosByTag(String tag) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM photos WHERE tags LIKE ? ORDER BY imported_at DESC")) {
            ps.setString(1, "%" + tag + "%");
            return mapPhotos(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query photos by tag: " + tag, e);
        }
    }

    public int photoCount() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM photos")) {
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count photos", e);
        }
    }

    // --- Face Group CRUD ---

    public void saveFaceGroup(PhotoService.FaceGroup group) {
        var sql = dialect.upsert("face_groups",
            "face_id, name, photo_ids, created_at",
            "?, ?, ?, ?",
            "face_id",
            "name = EXCLUDED.name, photo_ids = EXCLUDED.photo_ids");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, group.faceId());
            ps.setString(2, group.name());
            ps.setString(3, String.join(",", group.photoIds()));
            ps.setLong(4, group.createdAt().getEpochSecond());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save face group: " + group.faceId(), e);
        }
    }

    public Optional<PhotoService.FaceGroup> loadFaceGroup(String faceId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM face_groups WHERE face_id = ?")) {
            ps.setString(1, faceId);
            var rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(new PhotoService.FaceGroup(
                    rs.getString("face_id"),
                    rs.getString("name"),
                    parseSet(rs.getString("photo_ids")),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load face group: " + faceId, e);
        }
    }

    // --- Memory Lane CRUD ---

    public void saveMemoryLane(PhotoService.MemoryLane lane) {
        var sql = dialect.upsert("memory_lanes",
            "lane_id, title, description, photo_ids, created_at, created_by",
            "?, ?, ?, ?, ?, ?",
            "lane_id",
            "title = EXCLUDED.title, description = EXCLUDED.description, photo_ids = EXCLUDED.photo_ids");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, lane.laneId());
            ps.setString(2, lane.title());
            ps.setString(3, lane.description());
            ps.setString(4, String.join(",", lane.photoIds()));
            ps.setLong(5, lane.createdAt().getEpochSecond());
            ps.setString(6, lane.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save memory lane: " + lane.laneId(), e);
        }
    }

    public List<PhotoService.MemoryLane> allMemoryLanes() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM memory_lanes ORDER BY created_at DESC")) {
            var rs = ps.executeQuery();
            var lanes = new ArrayList<PhotoService.MemoryLane>();
            while (rs.next()) {
                lanes.add(new PhotoService.MemoryLane(
                    rs.getString("lane_id"),
                    rs.getString("title"),
                    rs.getString("description"),
                    parseList(rs.getString("photo_ids")),
                    Instant.ofEpochSecond(rs.getLong("created_at")),
                    rs.getString("created_by")
                ));
            }
            return lanes;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list memory lanes", e);
        }
    }

    // --- Helpers ---

    private PhotoService.Photo mapPhoto(ResultSet rs) throws SQLException {
        return new PhotoService.Photo(
            rs.getString("photo_id"),
            rs.getString("filename"),
            rs.getString("owner_entity"),
            Instant.ofEpochSecond(rs.getLong("taken_at")),
            Instant.ofEpochSecond(rs.getLong("imported_at")),
            rs.getString("location"),
            parseSet(rs.getString("tags")),
            parseSet(rs.getString("faces")),
            rs.getString("perceptual_hash"),
            PhotoService.PhotoStatus.valueOf(rs.getString("status"))
        );
    }

    private List<PhotoService.Photo> mapPhotos(ResultSet rs) throws SQLException {
        var photos = new ArrayList<PhotoService.Photo>();
        while (rs.next()) photos.add(mapPhoto(rs));
        return photos;
    }

    private static Set<String> parseSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }

    private static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.asList(csv.split(","));
    }
}
