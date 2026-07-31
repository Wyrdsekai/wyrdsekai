package org.wyrdsekai.core.codeplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.coding.BuildArtifact;
import org.wyrdsekai.core.coding.CodePlaneBackend;
import org.wyrdsekai.core.coding.SourceArtifact;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for coding artifacts ({@link SourceArtifact} /
 * {@link BuildArtifact}).
 *
 * <p>SQLite (household default) with the same DriverManager pattern as other
 * stores. The on-disk table names are kept as {@code codex_items} /
 * {@code artifact_items} for backwards-compatibility with households who
 * upgraded across the Phase-1a → Phase-2 cleanup boundary; the in-memory
 * shape is now the backend-agnostic record family from
 * </p>
 *
 * <p>Disk-compat strategy (per the Phase 2 cleanup brief): table names and
 * legacy CodePlane-specific columns are preserved as-is. Two new columns —
 * {@code backend} and {@code metadata_json} — are added by an idempotent
 * {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} on first connect. Legacy
 * rows therefore round-trip cleanly (the {@code backend} defaults to
 * {@code "codeplane"} and CodePlane-specific fields are folded into
 * {@code backendMetadata} on read).</p>
 */
public class CodeItemStore {

    private static final Logger log = LoggerFactory.getLogger(CodeItemStore.class);

    private final String jdbcUrl;

    public CodeItemStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        initSchema();
    }

    // --- SourceArtifact ---

    /**
     * Save a {@link SourceArtifact}. Idempotent (insert-or-replace on the
     * legacy CodePlane {@code codexId} key, or on the artifact UUID for
     * non-CodePlane backends).
     */
    public void saveSource(SourceArtifact src) {
        var sql = "INSERT OR REPLACE INTO codex_items "
            + "(codex_id, workspace_path, host_node, board_id, language, files_json, git_ref, "
            + " created_at, created_by, backend, metadata_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            var meta = src.backendMetadata() != null ? src.backendMetadata() : Map.<String, Object>of();
            stmt.setString(1, codexIdFor(src));
            stmt.setString(2, src.workspacePath());
            stmt.setString(3, stringOrEmpty(meta.get("hostNode")));
            stmt.setString(4, stringOrEmpty(meta.getOrDefault("boardId", src.taskId())));
            stmt.setString(5, stringOrEmpty(meta.get("language")));
            stmt.setString(6, Json.mapper().writeValueAsString(
                src.files() != null ? src.files() : List.of()));
            stmt.setString(7, src.gitRef());
            stmt.setString(8, src.createdAt() != null ? src.createdAt().toString() : null);
            stmt.setString(9, stringOrEmpty(meta.get("createdBy")));
            stmt.setString(10, src.backend() != null ? src.backend() : CodePlaneBackend.NAME);
            stmt.setString(11, Json.mapper().writeValueAsString(meta));
            stmt.executeUpdate();
            log.debug("SourceArtifact saved: backend={} task={} codexId={}",
                src.backend(), src.taskId(), codexIdFor(src));
        } catch (Exception e) {
            log.error("Failed to save SourceArtifact {}: {}", src.artifactId(), e.getMessage());
            throw new RuntimeException("SourceArtifact save failed", e);
        }
    }

    /**
     * Find a {@link SourceArtifact} by its legacy CodePlane {@code codexId}
     * (kept for backwards-compat with households who reference codex IDs
     * directly from saved soul-state).
     */
    public Optional<SourceArtifact> findSource(String codexId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT * FROM codex_items WHERE codex_id = ?")) {
            stmt.setString(1, codexId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(readSource(rs));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to find SourceArtifact {}: {}", codexId, e.getMessage());
            return Optional.empty();
        }
    }

    /** List all source artifacts, newest first. */
    public List<SourceArtifact> listSources() {
        var result = new ArrayList<SourceArtifact>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT * FROM codex_items ORDER BY created_at DESC")) {
            while (rs.next()) {
                result.add(readSource(rs));
            }
        } catch (Exception e) {
            log.error("Failed to list SourceArtifacts: {}", e.getMessage());
        }
        return result;
    }

    /** Delete a {@link SourceArtifact} by its legacy {@code codexId}. */
    public boolean deleteSource(String codexId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("DELETE FROM codex_items WHERE codex_id = ?")) {
            stmt.setString(1, codexId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete SourceArtifact {}: {}", codexId, e.getMessage());
            throw new RuntimeException("SourceArtifact delete failed", e);
        }
    }

    // --- BuildArtifact ---

    /** Save a {@link BuildArtifact}. */
    public void saveBuild(BuildArtifact build) {
        var sql = "INSERT OR REPLACE INTO artifact_items "
            + "(artifact_id, artifact_path, host_node, codex_id, board_id, "
            + "artifact_type, tests_passed, tests_failed, build_status, created_at, "
            + "backend, metadata_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            var meta = build.backendMetadata() != null ? build.backendMetadata() : Map.<String, Object>of();
            stmt.setString(1, artifactIdFor(build));
            stmt.setString(2, stringOrEmpty(meta.get("artifactPath")));
            stmt.setString(3, stringOrEmpty(meta.get("hostNode")));
            stmt.setString(4, build.sourceArtifactId());
            stmt.setString(5, stringOrEmpty(meta.getOrDefault("boardId", build.taskId())));
            stmt.setString(6, stringOrEmpty(meta.get("artifactType")));
            stmt.setInt(7, build.testsPassed());
            stmt.setInt(8, build.testsFailed());
            stmt.setString(9, build.status());
            stmt.setString(10, build.createdAt() != null ? build.createdAt().toString() : null);
            stmt.setString(11, build.backend() != null ? build.backend() : CodePlaneBackend.NAME);
            stmt.setString(12, Json.mapper().writeValueAsString(meta));
            stmt.executeUpdate();
            log.debug("BuildArtifact saved: backend={} task={} artifactId={}",
                build.backend(), build.taskId(), artifactIdFor(build));
        } catch (SQLException | JsonProcessingException e) {
            log.error("Failed to save BuildArtifact {}: {}", build.artifactId(), e.getMessage());
            throw new RuntimeException("BuildArtifact save failed", e);
        }
    }

    /** Find a {@link BuildArtifact} by its legacy {@code artifactId}. */
    public Optional<BuildArtifact> findBuild(String artifactId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT * FROM artifact_items WHERE artifact_id = ?")) {
            stmt.setString(1, artifactId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(readBuild(rs));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to find BuildArtifact {}: {}", artifactId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Find all builds derived from a given source-artifact / codex.
     * The lookup key is the legacy {@code codexId} for CodePlane backends and
     * {@link BuildArtifact#sourceArtifactId()} for newer backends — they map
     * to the same {@code codex_id} column.
     */
    public List<BuildArtifact> findBuildsBySource(String sourceId) {
        var result = new ArrayList<BuildArtifact>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT * FROM artifact_items WHERE codex_id = ? ORDER BY created_at DESC")) {
            stmt.setString(1, sourceId);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(readBuild(rs));
            }
        } catch (Exception e) {
            log.error("Failed to find builds for source {}: {}", sourceId, e.getMessage());
        }
        return result;
    }

    /** Delete a {@link BuildArtifact} by its legacy {@code artifactId}. */
    public boolean deleteBuild(String artifactId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("DELETE FROM artifact_items WHERE artifact_id = ?")) {
            stmt.setString(1, artifactId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete BuildArtifact {}: {}", artifactId, e.getMessage());
            throw new RuntimeException("BuildArtifact delete failed", e);
        }
    }

    // --- Schema ---

    private void initSchema() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS codex_items (
                    codex_id TEXT PRIMARY KEY,
                    workspace_path TEXT NOT NULL,
                    host_node TEXT NOT NULL,
                    board_id TEXT NOT NULL,
                    language TEXT,
                    files_json TEXT,
                    git_ref TEXT,
                    created_at TEXT,
                    created_by TEXT,
                    backend TEXT,
                    metadata_json TEXT
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS artifact_items (
                    artifact_id TEXT PRIMARY KEY,
                    artifact_path TEXT NOT NULL,
                    host_node TEXT NOT NULL,
                    codex_id TEXT NOT NULL,
                    board_id TEXT NOT NULL,
                    artifact_type TEXT,
                    tests_passed INTEGER DEFAULT 0,
                    tests_failed INTEGER DEFAULT 0,
                    build_status TEXT,
                    created_at TEXT,
                    backend TEXT,
                    metadata_json TEXT
                )""");

            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_artifact_codex ON artifact_items(codex_id)");

            // Idempotent migration for households upgrading from the
            // pre-Phase-2 schema (legacy `codex_items` / `artifact_items`
            // tables without the `backend` / `metadata_json` columns). The
            // CREATE TABLE above is a no-op when the table already exists,
            // so we add the new columns on a separate code path.
            addColumnIfMissing(conn, "codex_items", "backend", "TEXT");
            addColumnIfMissing(conn, "codex_items", "metadata_json", "TEXT");
            addColumnIfMissing(conn, "artifact_items", "backend", "TEXT");
            addColumnIfMissing(conn, "artifact_items", "metadata_json", "TEXT");

            log.debug("Coding-artifact schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize coding-artifact schema", e);
        }
    }

    private static void addColumnIfMissing(Connection conn, String table,
                                           String column, String type)
            throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (var rs = meta.getColumns(null, null, table, column)) {
            if (rs.next()) return; // already present
        }
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("Migrated {}: added column {} {}", table, column, type);
        }
    }

    // --- Row mappers ---

    @SuppressWarnings("unchecked")
    private static SourceArtifact readSource(ResultSet rs) throws Exception {
        var filesJson = rs.getString("files_json");
        List<String> files = filesJson != null
            ? Json.mapper().readValue(filesJson, List.class)
            : List.of();
        var createdAtStr = rs.getString("created_at");
        var createdAt = createdAtStr != null ? Instant.parse(createdAtStr) : null;

        var codexId = rs.getString("codex_id");
        var boardId = rs.getString("board_id");
        var hostNode = rs.getString("host_node");
        var language = rs.getString("language");
        var createdBy = rs.getString("created_by");
        var backend = rs.getString("backend");
        if (backend == null || backend.isBlank()) backend = CodePlaneBackend.NAME;

        Map<String, Object> metadata = readMetadata(rs);
        // Always re-derive legacy fields into the metadata map so callers
        // that still read `codexId`/`boardId`/`hostNode`/`language`/
        // `createdBy` straight off the metadata don't see drift between the
        // explicit columns and the persisted JSON blob (the JSON could be
        // stale on legacy rows that pre-date the `metadata_json` column).
        var merged = new LinkedHashMap<String, Object>(metadata);
        if (codexId != null) merged.put("codexId", codexId);
        if (boardId != null) merged.put("boardId", boardId);
        if (hostNode != null) merged.put("hostNode", hostNode);
        if (language != null) merged.put("language", language);
        if (createdBy != null) merged.put("createdBy", createdBy);

        return new SourceArtifact(
            UUID.nameUUIDFromBytes((backend + "-codex-" + codexId).getBytes()),
            backend,
            boardId != null ? boardId : codexId,
            rs.getString("workspace_path"),
            files,
            rs.getString("git_ref"),
            createdAt,
            Map.copyOf(merged)
        );
    }

    private static BuildArtifact readBuild(ResultSet rs) throws Exception {
        var createdAtStr = rs.getString("created_at");
        var createdAt = createdAtStr != null ? Instant.parse(createdAtStr) : null;

        var artifactId = rs.getString("artifact_id");
        var codexId = rs.getString("codex_id");
        var boardId = rs.getString("board_id");
        var hostNode = rs.getString("host_node");
        var artifactPath = rs.getString("artifact_path");
        var artifactType = rs.getString("artifact_type");
        var backend = rs.getString("backend");
        if (backend == null || backend.isBlank()) backend = CodePlaneBackend.NAME;

        Map<String, Object> metadata = readMetadata(rs);
        var merged = new LinkedHashMap<String, Object>(metadata);
        if (artifactId != null) merged.put("artifactId", artifactId);
        if (boardId != null) merged.put("boardId", boardId);
        if (hostNode != null) merged.put("hostNode", hostNode);
        if (artifactPath != null) merged.put("artifactPath", artifactPath);
        if (artifactType != null) merged.put("artifactType", artifactType);

        return new BuildArtifact(
            UUID.nameUUIDFromBytes((backend + "-artifact-" + artifactId).getBytes()),
            backend,
            boardId != null ? boardId : codexId,
            codexId,
            rs.getString("build_status"),
            rs.getInt("tests_passed"),
            rs.getInt("tests_failed"),
            createdAt,
            Map.copyOf(merged)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMetadata(ResultSet rs) throws Exception {
        // metadata_json column may be absent on legacy rows (pre-Phase-2
        // schema). Defensive: catch the missing-column case and fall back
        // to an empty map.
        String json;
        try {
            json = rs.getString("metadata_json");
        } catch (SQLException e) {
            return new HashMap<>();
        }
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return Json.mapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse metadata_json: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    // --- Helpers ---

    /**
     * Best-effort legacy {@code codexId} for a {@link SourceArtifact}.
     * Honours an existing {@code codexId} in {@code backendMetadata}; falls
     * back to the first 8 hex chars of the artifact UUID.
     */
    private static String codexIdFor(SourceArtifact src) {
        if (src.backendMetadata() != null) {
            var explicit = src.backendMetadata().get("codexId");
            if (explicit instanceof String s && !s.isBlank()) return s;
        }
        return src.artifactId().toString().substring(0, 8);
    }

    /**
     * Best-effort legacy {@code artifactId} for a {@link BuildArtifact}.
     * Honours an existing {@code artifactId} in {@code backendMetadata};
     * falls back to the first 8 hex chars of the artifact UUID.
     */
    private static String artifactIdFor(BuildArtifact build) {
        if (build.backendMetadata() != null) {
            var explicit = build.backendMetadata().get("artifactId");
            if (explicit instanceof String s && !s.isBlank()) return s;
        }
        return build.artifactId().toString().substring(0, 8);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : o.toString();
    }
}
