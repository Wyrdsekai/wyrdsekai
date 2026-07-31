package org.wyrdsekai.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for memory_entities (and memory_edges).
 *
 * <p>Append-only. Contradictions resolved at query time via ORDER BY timestamp DESC.
 * Staleness handled by Forge consolidation, not here.</p>
 *
 * <p>Schema defined in schema/sqlite-create-schema.sql + schema/postgresql-create-schema.sql.
 * Tables are created by SchemaInitializer at server startup.</p>
 */
public final class MemoryEntityStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryEntityStore.class);

    public record EntityRow(
            String did,
            String memoryId,
            String entityType,
            String entityRole,
            String entityValue,
            long timestamp) {
    }

    public record EdgeRow(
            String did,
            String subject,
            String predicate,
            String object,
            String memoryId,
            double confidence) {
    }

    private final String jdbcUrl;

    public MemoryEntityStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    // -----------------------------------------------------------------
    //  Insert
    // -----------------------------------------------------------------

    /** Insert a single entity row. Returns true on success. */
    public boolean insertEntity(EntityRow row) {
        var sql = "INSERT INTO memory_entities "
                + "(did, memory_id, entity_type, entity_role, entity_value, timestamp) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, row.did());
            stmt.setString(2, row.memoryId());
            stmt.setString(3, row.entityType());
            stmt.setString(4, row.entityRole());
            stmt.setString(5, row.entityValue());
            stmt.setLong(6, row.timestamp());
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.warn("Failed to insert entity row for did={} type={}: {}",
                    row.did(), row.entityType(), e.getMessage());
            return false;
        }
    }

    /** Insert many entities in one transaction. */
    public int insertEntities(List<EntityRow> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        var sql = "INSERT INTO memory_entities "
                + "(did, memory_id, entity_type, entity_role, entity_value, timestamp) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        int count = 0;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement(sql)) {
                for (var r : rows) {
                    stmt.setString(1, r.did());
                    stmt.setString(2, r.memoryId());
                    stmt.setString(3, r.entityType());
                    stmt.setString(4, r.entityRole());
                    stmt.setString(5, r.entityValue());
                    stmt.setLong(6, r.timestamp());
                    stmt.addBatch();
                }
                var results = stmt.executeBatch();
                for (var r : results) if (r >= 0 || r == Statement.SUCCESS_NO_INFO) count++;
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                log.warn("Batch entity insert failed: {}", e.getMessage());
            }
        } catch (SQLException e) {
            log.warn("Connection failed for batch entity insert: {}", e.getMessage());
        }
        return count;
    }

    /** Insert a single edge row. Returns true on success. */
    public boolean insertEdge(EdgeRow row) {
        var sql = "INSERT INTO memory_edges "
                + "(did, subject, predicate, object, memory_id, confidence) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, row.did());
            stmt.setString(2, row.subject());
            stmt.setString(3, row.predicate());
            stmt.setString(4, row.object());
            stmt.setString(5, row.memoryId());
            stmt.setDouble(6, row.confidence());
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.warn("Failed to insert edge row for did={} {}→{}→{}: {}",
                    row.did(), row.subject(), row.predicate(), row.object(), e.getMessage());
            return false;
        }
    }

    /** Insert many edges in one transaction. */
    public int insertEdges(List<EdgeRow> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        var sql = "INSERT INTO memory_edges "
                + "(did, subject, predicate, object, memory_id, confidence) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        int count = 0;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try (var stmt = conn.prepareStatement(sql)) {
                for (var r : rows) {
                    stmt.setString(1, r.did());
                    stmt.setString(2, r.subject());
                    stmt.setString(3, r.predicate());
                    stmt.setString(4, r.object());
                    stmt.setString(5, r.memoryId());
                    stmt.setDouble(6, r.confidence());
                    stmt.addBatch();
                }
                var results = stmt.executeBatch();
                for (var r : results) if (r >= 0 || r == Statement.SUCCESS_NO_INFO) count++;
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                log.warn("Batch edge insert failed: {}", e.getMessage());
            }
        } catch (SQLException e) {
            log.warn("Connection failed for batch edge insert: {}", e.getMessage());
        }
        return count;
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /**
     * Find the latest entity for (did, type[, role]).
     * Role may be null — then any role matches.
     */
    public Optional<EntityRow> findLatest(String did, String entityType, String entityRole) {
        var sql = entityRole == null
                ? "SELECT memory_id, entity_type, entity_role, entity_value, timestamp "
                + "FROM memory_entities "
                + "WHERE did = ? AND entity_type = ? "
                + "ORDER BY timestamp DESC LIMIT 1"
                : "SELECT memory_id, entity_type, entity_role, entity_value, timestamp "
                + "FROM memory_entities "
                + "WHERE did = ? AND entity_type = ? AND entity_role = ? "
                + "ORDER BY timestamp DESC LIMIT 1";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, entityType);
            if (entityRole != null) stmt.setString(3, entityRole);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new EntityRow(
                            did,
                            rs.getString("memory_id"),
                            rs.getString("entity_type"),
                            rs.getString("entity_role"),
                            rs.getString("entity_value"),
                            rs.getLong("timestamp")));
                }
            }
        } catch (SQLException e) {
            log.debug("findLatest query failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Latest entity per (entity_type, entity_role) for a DID, ordered newest-first,
     * capped at {@code limit}. Used to render a "What I know about &lt;sender&gt;"
     * structured block above retrieved memories so the model sees prior facts as
     * a flat table rather than scattered prose. The append-only schema means we
     * pick the most recent timestamp per key — supersession resolves naturally.
     *
     * <p>The SQLite/PostgreSQL query uses a correlated subquery rather than
     * window functions for portability across both dialects we support.</p>
     */
    public List<EntityRow> findAllForDid(String did, int limit) {
        var out = new ArrayList<EntityRow>();
        var sql = "SELECT memory_id, entity_type, entity_role, entity_value, timestamp "
                + "FROM memory_entities e1 "
                + "WHERE did = ? AND timestamp = ("
                + "  SELECT MAX(timestamp) FROM memory_entities e2 "
                + "  WHERE e2.did = e1.did AND e2.entity_type = e1.entity_type "
                + "    AND ((e2.entity_role IS NULL AND e1.entity_role IS NULL) "
                + "         OR e2.entity_role = e1.entity_role)"
                + ") "
                + "ORDER BY timestamp DESC LIMIT ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setInt(2, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new EntityRow(
                            did,
                            rs.getString("memory_id"),
                            rs.getString("entity_type"),
                            rs.getString("entity_role"),
                            rs.getString("entity_value"),
                            rs.getLong("timestamp")));
                }
            }
        } catch (SQLException e) {
            log.debug("findAllForDid query failed: {}", e.getMessage());
        }
        return out;
    }

    /** All rows for a (did, type) ordered newest-first, capped at limit. */
    public List<EntityRow> findAllByType(String did, String entityType, int limit) {
        var out = new ArrayList<EntityRow>();
        var sql = "SELECT memory_id, entity_type, entity_role, entity_value, timestamp "
                + "FROM memory_entities "
                + "WHERE did = ? AND entity_type = ? "
                + "ORDER BY timestamp DESC LIMIT ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, entityType);
            stmt.setInt(3, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new EntityRow(
                            did,
                            rs.getString("memory_id"),
                            rs.getString("entity_type"),
                            rs.getString("entity_role"),
                            rs.getString("entity_value"),
                            rs.getLong("timestamp")));
                }
            }
        } catch (SQLException e) {
            log.debug("findAllByType query failed: {}", e.getMessage());
        }
        return out;
    }

    /** Find entities by literal value (used by graph hop-2 lookups). */
    public List<EntityRow> findByValue(String did, String entityValue, int limit) {
        var out = new ArrayList<EntityRow>();
        var sql = "SELECT memory_id, entity_type, entity_role, entity_value, timestamp "
                + "FROM memory_entities "
                + "WHERE did = ? AND entity_value = ? "
                + "ORDER BY timestamp DESC LIMIT ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, entityValue);
            stmt.setInt(3, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new EntityRow(
                            did,
                            rs.getString("memory_id"),
                            rs.getString("entity_type"),
                            rs.getString("entity_role"),
                            rs.getString("entity_value"),
                            rs.getLong("timestamp")));
                }
            }
        } catch (SQLException e) {
            log.debug("findByValue query failed: {}", e.getMessage());
        }
        return out;
    }

    /** All entity rows tied to a specific memory_id (used by multi-hop Day 4-5 hop-2 seed). */
    public List<EntityRow> findEntitiesByMemoryId(String did, String memoryId, int limit) {
        var out = new ArrayList<EntityRow>();
        var sql = "SELECT memory_id, entity_type, entity_role, entity_value, timestamp "
                + "FROM memory_entities "
                + "WHERE did = ? AND memory_id = ? "
                + "ORDER BY id ASC LIMIT ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, memoryId);
            stmt.setInt(3, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new EntityRow(
                            did,
                            rs.getString("memory_id"),
                            rs.getString("entity_type"),
                            rs.getString("entity_role"),
                            rs.getString("entity_value"),
                            rs.getLong("timestamp")));
                }
            }
        } catch (SQLException e) {
            log.debug("findEntitiesByMemoryId query failed: {}", e.getMessage());
        }
        return out;
    }

    /** Edges with subject or object matching value (used by multi-hop Day 4-5). */
    public List<EdgeRow> findEdgesTouching(String did, String value, int limit) {
        var out = new ArrayList<EdgeRow>();
        var sql = "SELECT subject, predicate, object, memory_id, confidence "
                + "FROM memory_edges "
                + "WHERE did = ? AND (subject = ? OR object = ?) "
                + "ORDER BY created_at DESC LIMIT ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, value);
            stmt.setString(3, value);
            stmt.setInt(4, Math.max(1, limit));
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new EdgeRow(
                            did,
                            rs.getString("subject"),
                            rs.getString("predicate"),
                            rs.getString("object"),
                            rs.getString("memory_id"),
                            rs.getDouble("confidence")));
                }
            }
        } catch (SQLException e) {
            log.debug("findEdgesTouching query failed: {}", e.getMessage());
        }
        return out;
    }

    /** Count entities for a DID (sanity / metrics). */
    public int countEntities(String did) {
        var sql = "SELECT COUNT(*) FROM memory_entities WHERE did = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.debug("countEntities failed: {}", e.getMessage());
        }
        return 0;
    }
}
