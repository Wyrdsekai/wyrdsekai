package org.wyrdsekai.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * @param origin who told the fact and whether privately ({@link MemoryOrigin}); rows read
     *               back are filtered by the person a turn answers ({@link MemoryReader})
     */
    public record EntityRow(
            String did,
            String memoryId,
            String entityType,
            String entityRole,
            String entityValue,
            long timestamp,
            MemoryOrigin origin) {

        public EntityRow {
            if (origin == null) origin = MemoryOrigin.UNKNOWN;
        }

        /** A row whose teller is not known: read only in turns with her bondholder. */
        public EntityRow(String did, String memoryId, String entityType, String entityRole,
                         String entityValue, long timestamp) {
            this(did, memoryId, entityType, entityRole, entityValue, timestamp, MemoryOrigin.UNKNOWN);
        }
    }

    public record EdgeRow(
            String did,
            String subject,
            String predicate,
            String object,
            String memoryId,
            double confidence,
            MemoryOrigin origin) {

        public EdgeRow {
            if (origin == null) origin = MemoryOrigin.UNKNOWN;
        }

        /** An edge whose teller is not known: read only in turns with her bondholder. */
        public EdgeRow(String did, String subject, String predicate, String object,
                       String memoryId, double confidence) {
            this(did, subject, predicate, object, memoryId, confidence, MemoryOrigin.UNKNOWN);
        }
    }

    private static final String ENTITY_COLUMNS =
            "memory_id, entity_type, entity_role, entity_value, timestamp, teller_did, visibility";

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
                + "(did, memory_id, entity_type, entity_role, entity_value, timestamp, "
                + "teller_did, visibility) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, row.did());
            stmt.setString(2, row.memoryId());
            stmt.setString(3, row.entityType());
            stmt.setString(4, row.entityRole());
            stmt.setString(5, row.entityValue());
            stmt.setLong(6, row.timestamp());
            stmt.setString(7, row.origin().tellerDid());
            stmt.setString(8, row.origin().visibility().column());
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
                + "(did, memory_id, entity_type, entity_role, entity_value, timestamp, "
                + "teller_did, visibility) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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
                    stmt.setString(7, r.origin().tellerDid());
                    stmt.setString(8, r.origin().visibility().column());
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
                + "(did, subject, predicate, object, memory_id, confidence, teller_did, visibility) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, row.did());
            stmt.setString(2, row.subject());
            stmt.setString(3, row.predicate());
            stmt.setString(4, row.object());
            stmt.setString(5, row.memoryId());
            stmt.setDouble(6, row.confidence());
            stmt.setString(7, row.origin().tellerDid());
            stmt.setString(8, row.origin().visibility().column());
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
                + "(did, subject, predicate, object, memory_id, confidence, teller_did, visibility) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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
                    stmt.setString(7, r.origin().tellerDid());
                    stmt.setString(8, r.origin().visibility().column());
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
    //  Query — every read is for one reader (the person the turn answers)
    // -----------------------------------------------------------------

    /**
     * Find the latest entity for (did, type[, role]) among the facts this person told her
     * themselves (recall: "what am I allergic to?" is answered from the asker's own words,
     * never another person's). Role may be null — then any role matches.
     */
    public Optional<EntityRow> findLatest(String did, String entityType, String entityRole,
                                          MemoryReader reader) {
        var params = new ArrayList<Object>();
        params.add(did);
        params.add(entityType);
        var sql = new StringBuilder("SELECT " + ENTITY_COLUMNS + " FROM memory_entities "
                + "WHERE did = ? AND entity_type = ?");
        if (entityRole != null) {
            sql.append(" AND entity_role = ?");
            params.add(entityRole);
        }
        sql.append(" AND ").append(toldBy("memory_entities", reader, params));
        sql.append(" ORDER BY timestamp DESC LIMIT 1");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql.toString())) {
            bind(stmt, params);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(entityFrom(did, rs));
            }
        } catch (SQLException e) {
            log.debug("findLatest query failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Latest entity per (entity_type, entity_role) among the facts this person told her,
     * ordered newest-first, capped at {@code limit}. Used to render the "What you know about
     * the user" block. Empty for a reader who is no one. The append-only schema means we pick
     * the most recent timestamp per key — supersession resolves naturally, within what the
     * person told.
     *
     * <p>The SQLite/PostgreSQL query uses a correlated subquery rather than
     * window functions for portability across both dialects we support.</p>
     */
    public List<EntityRow> findAllForDid(String did, int limit, MemoryReader reader) {
        var out = new ArrayList<EntityRow>();
        var params = new ArrayList<Object>();
        params.add(did);
        var outer = toldBy("e1", reader, params);
        var inner = toldBy("e2", reader, params);
        params.add(Math.max(1, limit));
        var sql = "SELECT " + ENTITY_COLUMNS + " "
                + "FROM memory_entities e1 "
                + "WHERE did = ? AND " + outer + " AND timestamp = ("
                + "  SELECT MAX(timestamp) FROM memory_entities e2 "
                + "  WHERE e2.did = e1.did AND e2.entity_type = e1.entity_type "
                + "    AND ((e2.entity_role IS NULL AND e1.entity_role IS NULL) "
                + "         OR e2.entity_role = e1.entity_role)"
                + "    AND " + inner
                + ") "
                + "ORDER BY timestamp DESC LIMIT ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            bind(stmt, params);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) out.add(entityFrom(did, rs));
            }
        } catch (SQLException e) {
            log.debug("findAllForDid query failed: {}", e.getMessage());
        }
        return out;
    }

    /** All rows for a (did, type) the reader may read, ordered newest-first, capped at limit. */
    public List<EntityRow> findAllByType(String did, String entityType, int limit, MemoryReader reader) {
        var params = new ArrayList<Object>();
        params.add(did);
        params.add(entityType);
        var visible = visibleTo("memory_entities", reader, params);
        params.add(Math.max(1, limit));
        return queryEntities(did, "SELECT " + ENTITY_COLUMNS + " FROM memory_entities "
                + "WHERE did = ? AND entity_type = ? AND " + visible
                + " ORDER BY timestamp DESC LIMIT ?", params, "findAllByType");
    }

    /** Entities by literal value the reader may read (used by graph hop-2 lookups). */
    public List<EntityRow> findByValue(String did, String entityValue, int limit, MemoryReader reader) {
        var params = new ArrayList<Object>();
        params.add(did);
        params.add(entityValue);
        var visible = visibleTo("memory_entities", reader, params);
        params.add(Math.max(1, limit));
        return queryEntities(did, "SELECT " + ENTITY_COLUMNS + " FROM memory_entities "
                + "WHERE did = ? AND entity_value = ? AND " + visible
                + " ORDER BY timestamp DESC LIMIT ?", params, "findByValue");
    }

    /** Entity rows tied to a memory_id that the reader may read (multi-hop hop-2 seed). */
    public List<EntityRow> findEntitiesByMemoryId(String did, String memoryId, int limit,
                                                  MemoryReader reader) {
        var params = new ArrayList<Object>();
        params.add(did);
        params.add(memoryId);
        var visible = visibleTo("memory_entities", reader, params);
        params.add(Math.max(1, limit));
        return queryEntities(did, "SELECT " + ENTITY_COLUMNS + " FROM memory_entities "
                + "WHERE did = ? AND memory_id = ? AND " + visible
                + " ORDER BY id ASC LIMIT ?", params, "findEntitiesByMemoryId");
    }

    /** Edges with subject or object matching value, that the reader may read (multi-hop). */
    public List<EdgeRow> findEdgesTouching(String did, String value, int limit, MemoryReader reader) {
        var out = new ArrayList<EdgeRow>();
        var params = new ArrayList<Object>();
        params.add(did);
        params.add(value);
        params.add(value);
        var visible = visibleTo("memory_edges", reader, params);
        params.add(Math.max(1, limit));
        var sql = "SELECT subject, predicate, object, memory_id, confidence, teller_did, visibility "
                + "FROM memory_edges "
                + "WHERE did = ? AND (subject = ? OR object = ?) AND " + visible
                + " ORDER BY created_at DESC LIMIT ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            bind(stmt, params);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new EdgeRow(
                            did,
                            rs.getString("subject"),
                            rs.getString("predicate"),
                            rs.getString("object"),
                            rs.getString("memory_id"),
                            rs.getDouble("confidence"),
                            MemoryOrigin.of(rs.getString("teller_did"), rs.getString("visibility"))));
                }
            }
        } catch (SQLException e) {
            log.debug("findEdgesTouching query failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Record the origin of rows written before origins were recorded, once it is known from
     * the source memory's text. Only rows still marked {@code unknown} are touched.
     *
     * @return rows updated across both tables
     */
    public int labelUnknown(String did, String memoryId, MemoryOrigin origin) {
        if (did == null || memoryId == null || origin == null
                || origin.visibility() == MemoryOrigin.Visibility.UNKNOWN) return 0;
        int n = 0;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            for (var table : new String[] {"memory_entities", "memory_edges"}) {
                try (var stmt = conn.prepareStatement("UPDATE " + table
                        + " SET teller_did = ?, visibility = ? "
                        + "WHERE did = ? AND memory_id = ? AND visibility = 'unknown'")) {
                    stmt.setString(1, origin.tellerDid());
                    stmt.setString(2, origin.visibility().column());
                    stmt.setString(3, did);
                    stmt.setString(4, memoryId);
                    n += stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.debug("labelUnknown failed for {}: {}", memoryId, e.getMessage());
        }
        return n;
    }

    /** Rows of this companion still of unknown origin (read only with her bondholder). */
    public int countUnknown(String did) {
        var sql = "SELECT COUNT(*) FROM memory_entities WHERE did = ? AND visibility = 'unknown'";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.debug("countUnknown failed: {}", e.getMessage());
        }
        return 0;
    }

    // -----------------------------------------------------------------
    //  Who may read what
    // -----------------------------------------------------------------

    /** Rows the reader may read: open, their own private ones, unknown ones for her bondholder. */
    private static String visibleTo(String alias, MemoryReader reader, List<Object> params) {
        var r = reader == null ? MemoryReader.NO_ONE : reader;
        var sb = new StringBuilder("(").append(alias).append(".visibility = 'open'");
        if (r.isSomeone()) {
            sb.append(" OR (").append(alias).append(".visibility = 'private' AND ")
              .append(alias).append(".teller_did IN (").append(placeholders(r.personIds().size()))
              .append("))");
            params.addAll(r.personIds());
        }
        if (r.bondholder()) sb.append(" OR ").append(alias).append(".visibility = 'unknown'");
        return sb.append(")").toString();
    }

    /** Rows the reader told her themselves (plus unknown ones for her bondholder). */
    private static String toldBy(String alias, MemoryReader reader, List<Object> params) {
        var r = reader == null ? MemoryReader.NO_ONE : reader;
        if (!r.isSomeone()) return "(1 = 0)";
        var sb = new StringBuilder("((").append(alias).append(".visibility <> 'unknown' AND ")
            .append(alias).append(".teller_did IN (").append(placeholders(r.personIds().size()))
            .append("))");
        params.addAll(r.personIds());
        if (r.bondholder()) sb.append(" OR ").append(alias).append(".visibility = 'unknown'");
        return sb.append(")").toString();
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(Math.max(1, n), "?"));
    }

    private static void bind(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            var p = params.get(i);
            if (p instanceof Integer n) stmt.setInt(i + 1, n);
            else stmt.setString(i + 1, p == null ? null : p.toString());
        }
    }

    private List<EntityRow> queryEntities(String did, String sql, List<Object> params, String what) {
        var out = new ArrayList<EntityRow>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            bind(stmt, params);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) out.add(entityFrom(did, rs));
            }
        } catch (SQLException e) {
            log.debug("{} query failed: {}", what, e.getMessage());
        }
        return out;
    }

    private static EntityRow entityFrom(String did, ResultSet rs) throws SQLException {
        return new EntityRow(
                did,
                rs.getString("memory_id"),
                rs.getString("entity_type"),
                rs.getString("entity_role"),
                rs.getString("entity_value"),
                rs.getLong("timestamp"),
                MemoryOrigin.of(rs.getString("teller_did"), rs.getString("visibility")));
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
