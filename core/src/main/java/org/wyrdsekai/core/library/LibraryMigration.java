package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Migrates old capabilities table (8 columns) to new LibraryStore schema (20 columns).
 * Run before LibraryStore initialization. Idempotent — no-op if old table doesn't exist
 * or migration already completed.
 */
public final class LibraryMigration {

    private static final Logger log = LoggerFactory.getLogger(LibraryMigration.class);

    /** Run migration if needed. Returns true if migration was performed. */
    public static boolean migrate(String jdbcUrl) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            if (!hasOldCapabilitiesTable(conn)) {
                return false;
            }
            if (hasNewLibraryTable(conn)) {
                log.info("Library migration: new table already exists, skipping");
                return false;
            }

            var oldRecords = readOldRecords(conn);
            log.info("Library migration: found {} records in old capabilities table", oldRecords.size());

            if (oldRecords.isEmpty()) {
                // Just drop the old table, LibraryStore will create the new one
                dropOldTable(conn);
                return true;
            }

            // LibraryStore.initSchema() will create the new table.
            // We insert migrated records after LibraryStore is created.
            // For now, just drop the old table so it doesn't conflict.
            dropOldTable(conn);
            log.info("Library migration: dropped old capabilities table, {} records staged for import", oldRecords.size());

            // Store migrated records in a temp table for LibraryStore to pick up
            createMigrationStagingTable(conn, oldRecords);
            return true;

        } catch (Exception e) {
            log.error("Library migration failed: {}", e.getMessage());
            return false;
        }
    }

    /** Import staged records into LibraryStore after it's initialized. */
    public static void importStaged(LibraryStore store, String jdbcUrl) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            if (!tableExists(conn, "library_migration_staging")) {
                return;
            }

            var records = readStagedRecords(conn);
            if (records.isEmpty()) {
                dropTable(conn, "library_migration_staging");
                return;
            }

            store.upsertCapabilities(records);
            dropTable(conn, "library_migration_staging");
            log.info("Library migration: imported {} records from staging", records.size());

        } catch (Exception e) {
            log.error("Library migration import failed: {}", e.getMessage());
        }
    }

    private static boolean hasOldCapabilitiesTable(Connection conn) throws SQLException {
        // Check if 'capabilities' table exists with old schema (has 'capability_id' column)
        try (var rs = conn.getMetaData().getColumns(null, null, "capabilities", "capability_id")) {
            return rs.next();
        }
    }

    private static boolean hasNewLibraryTable(Connection conn) throws SQLException {
        return tableExists(conn, "library_capabilities");
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private static record OldRecord(String id, String name, String description,
                                     String category, String provider, String version,
                                     String status, long registeredAt) {}

    private static List<OldRecord> readOldRecords(Connection conn) throws SQLException {
        var result = new ArrayList<OldRecord>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT * FROM capabilities")) {
            while (rs.next()) {
                result.add(new OldRecord(
                    rs.getString("capability_id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("category"),
                    rs.getString("provider"),
                    rs.getString("version"),
                    rs.getString("status"),
                    rs.getLong("registered_at")
                ));
            }
        }
        return result;
    }

    private static void dropOldTable(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS capabilities");
            stmt.execute("DROP INDEX IF EXISTS capabilities_name");
            stmt.execute("DROP INDEX IF EXISTS capabilities_category");
        }
    }

    private static void dropTable(Connection conn, String tableName) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + tableName);
        }
    }

    private static void createMigrationStagingTable(Connection conn, List<OldRecord> records) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_migration_staging (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    version TEXT,
                    description TEXT,
                    cognitive_layer TEXT,
                    tags TEXT,
                    source TEXT NOT NULL,
                    protocol TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    registered_at TEXT
                )
                """);
        }

        try (var ps = conn.prepareStatement("""
            INSERT INTO library_migration_staging
            (id, name, version, description, cognitive_layer, tags, source, protocol, provider, registered_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            for (var old : records) {
                ps.setString(1, old.id() != null ? old.id() : UUID.randomUUID().toString());
                ps.setString(2, old.name());
                ps.setString(3, old.version());
                ps.setString(4, old.description());
                ps.setString(5, mapCategoryToLayer(old.category()));
                ps.setString(6, old.category()); // old category becomes a tag
                ps.setString(7, "SEED"); // migrated records are treated as seed
                ps.setString(8, mapCategoryToProtocol(old.category()));
                ps.setString(9, old.provider());
                ps.setString(10, old.registeredAt() > 0
                    ? Instant.ofEpochSecond(old.registeredAt()).toString() : null);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static List<CapabilityRecord> readStagedRecords(Connection conn) throws SQLException {
        var result = new ArrayList<CapabilityRecord>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT * FROM library_migration_staging")) {
            while (rs.next()) {
                var tagsStr = rs.getString("tags");
                result.add(new CapabilityRecord(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("version"),
                    rs.getString("description"),
                    parseLayer(rs.getString("cognitive_layer")),
                    tagsStr != null ? List.of(tagsStr) : List.of(),
                    parseSource(rs.getString("source")),
                    parseProtocol(rs.getString("protocol")),
                    -1.0f,
                    CapabilityRecord.VerificationStatus.UNVERIFIED,
                    null, null, null,
                    rs.getString("provider"),
                    null, 0,
                    false, null,
                    parseInstant(rs.getString("registered_at")),
                    null
                ));
            }
        }
        return result;
    }

    /** Map old category names to cognitive layers. */
    private static String mapCategoryToLayer(String category) {
        if (category == null) return "EXECUTE";
        return switch (category.toLowerCase()) {
            case "tool" -> "EXECUTE";
            case "agent" -> "COORDINATE";
            case "skill" -> "SYNTHESIZE";
            case "service" -> "PERCEIVE";
            default -> "EXECUTE";
        };
    }

    /** Map old category names to protocols. */
    private static String mapCategoryToProtocol(String category) {
        if (category == null) return "ROOM_SCRIPT";
        return switch (category.toLowerCase()) {
            case "tool" -> "ROOM_SCRIPT";
            case "agent" -> "AGENT";
            case "skill" -> "INFERENCE";
            case "service" -> "SERVICE";
            default -> "ROOM_SCRIPT";
        };
    }

    private static CapabilityRecord.CognitiveLayer parseLayer(String s) {
        if (s == null) return null;
        try { return CapabilityRecord.CognitiveLayer.valueOf(s); } catch (Exception e) { return null; }
    }

    private static CapabilityRecord.CapabilitySource parseSource(String s) {
        if (s == null) return CapabilityRecord.CapabilitySource.SEED;
        try { return CapabilityRecord.CapabilitySource.valueOf(s); } catch (Exception e) { return CapabilityRecord.CapabilitySource.SEED; }
    }

    private static CapabilityRecord.CapabilityProtocol parseProtocol(String s) {
        if (s == null) return CapabilityRecord.CapabilityProtocol.ROOM_SCRIPT;
        try { return CapabilityRecord.CapabilityProtocol.valueOf(s); } catch (Exception e) { return CapabilityRecord.CapabilityProtocol.ROOM_SCRIPT; }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
