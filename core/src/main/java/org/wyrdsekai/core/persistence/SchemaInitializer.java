package org.wyrdsekai.core.persistence;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Initializes the database schema for Pekko Persistence.
 * Supports both SQLite (single-node) and PostgreSQL (multi-node).
 *
 * SQLite: manual DDL with AUTOINCREMENT on INTEGER PRIMARY KEY.
 * PostgreSQL: BIGSERIAL, BYTEA, EXTRACT(EPOCH FROM NOW()).
 */
public final class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final String SQLITE_SCHEMA = "/schema/sqlite-create-schema.sql";
    private static final String POSTGRESQL_SCHEMA = "/schema/postgresql-create-schema.sql";

    private SchemaInitializer() {}

    /**
     * Initialize SQLite database (single-node mode).
     *
     * @param dbPath path to the SQLite database file (e.g., ~/.wyrdsekai/world.db)
     * @return the JDBC URL for the initialized database
     */
    public static String initialize(Path dbPath) {
        // Ensure parent directory exists
        var parent = dbPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
                log.info("Created data directory: {}", parent);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create data directory: " + parent, e);
            }
        }

        var jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            initializeSchema(conn, SQLITE_SCHEMA);
            log.info("SQLite database initialized: {}", dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database: " + dbPath, e);
        }

        return jdbcUrl;
    }

    /**
     * Initialize PostgreSQL database (multi-node/cluster mode).
     *
     * @param jdbcUrl PostgreSQL JDBC URL (e.g., jdbc:postgresql://localhost:5432/wyrdsekai)
     * @param user    database user
     * @param password database password
     * @return the JDBC URL (same as input)
     */
    public static String initializePostgres(String jdbcUrl, String user, String password) {
        try (var conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            initializeSchema(conn, POSTGRESQL_SCHEMA);
            log.info("PostgreSQL database initialized: {}", jdbcUrl);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize PostgreSQL database: " + jdbcUrl, e);
        }

        return jdbcUrl;
    }

    private static void initializeSchema(Connection conn, String schemaResource) throws SQLException {
        var sql = loadSchemaResource(schemaResource);

        // Strip comment lines, then split on semicolons
        var cleaned = sql.lines()
            .filter(line -> !line.trim().startsWith("--"))
            .reduce("", (a, b) -> a + "\n" + b);

        for (var statement : cleaned.split(";")) {
            var trimmed = statement.trim();
            if (trimmed.isEmpty()) continue;
            // Skip PRAGMA statements for PostgreSQL
            if (trimmed.startsWith("PRAGMA")) continue;
            try (var stmt = conn.createStatement()) {
                stmt.execute(trimmed);
            } catch (SQLException e) {
                // Index/column creation may fail on existing databases where
                // the referenced column doesn't exist yet (added by migration).
                // Non-fatal: migrations below will handle it.
                if (trimmed.toUpperCase().startsWith("CREATE INDEX")) {
                    log.debug("Deferred index creation (will retry after migration): {}", e.getMessage());
                } else {
                    throw e;
                }
            }
        }

        // ── Numbered, ledger-recorded migrations (pre-OSS data-durability, 2026-07-09) ──
        // Each migration is idempotent AND recorded in schema_migrations so (a) a future
        // binary knows exactly what this data dir has been through, (b) destructive
        // migrations (rename/split/backfill) can rely on ordered, run-once semantics, and
        // (c) DataVersion can detect a data dir newer than the binary (downgrade guard).
        // NEVER renumber or remove entries — append only.
        ensureMigrationLedger(conn);
        runMigration(conn, 1, "snapshot_meta_columns", () -> migrateSnapshotMeta(conn));
        runMigration(conn, 2, "user_accounts_role_userid", () -> migrateUserAccounts(conn));
        runMigration(conn, 3, "conversation_checkpoints", () -> migrateConversationCheckpoints(conn));
        runMigration(conn, 4, "inventory_script_source", () -> migrateInventoryScripts(conn));
        runMigration(conn, 5, "substrate_pressure_other_did", () -> migrateSubstratePressureOtherDid(conn));
        runMigration(conn, 6, "households_x25519", () -> migrateHouseholdsX25519(conn));
        runMigration(conn, 7, "skill_drafts_harness", () -> migrateSkillDraftsHarness(conn));
        runMigration(conn, 8, "soul_fragments_authoring_model",
            () -> migrateSoulFragmentsAuthoringModel(conn));

        // Retry deferred indexes after migrations
        for (var statement : cleaned.split(";")) {
            var trimmed = statement.trim();
            if (trimmed.isEmpty() || !trimmed.toUpperCase().startsWith("CREATE INDEX")) continue;
            try (var stmt = conn.createStatement()) {
                stmt.execute(trimmed);
            } catch (SQLException e) {
                // Already exists or still can't create — either way non-fatal
                log.debug("Index creation (retry): {}", e.getMessage());
            }
        }
    }

    /**
     * #1184 — add nullable {@code x25519_public_key} to {@code households} so a zone
     * holder can ECDH-wrap the zone master to a joining node's grant key. Existing rows have null
     * until that node next mirrors its identity (its grant keypair lazy-creates on first use).
     */
    /**
     * Highest migration id this binary knows. Written into data-version.json by
     * {@link DataVersion} so an OLDER binary opening a NEWER data dir can refuse
     * instead of silently mangling tables it doesn't understand. Append-only.
     */
    public static final int SCHEMA_VERSION = 8;

    @FunctionalInterface
    private interface Migration { void run() throws SQLException; }

    private static void ensureMigrationLedger(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                + "id INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)");
        }
    }

    /** Run a migration if its id isn't in the ledger yet, then record it. The existing
     *  migrations are individually idempotent, so re-running them on a pre-ledger data dir
     *  is safe — the ledger entry is created either way. */
    private static void runMigration(Connection conn, int id, String name, Migration m)
            throws SQLException {
        try (var check = conn.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE id = ?")) {
            check.setInt(1, id);
            try (var rs = check.executeQuery()) {
                if (rs.next()) return;   // already applied + recorded
            }
        }
        m.run();
        try (var ins = conn.prepareStatement(
                "INSERT INTO schema_migrations (id, name, applied_at) VALUES (?, ?, ?)")) {
            ins.setInt(1, id);
            ins.setString(2, name);
            ins.setString(3, Instant.now().toString());
            ins.executeUpdate();
        }
        log.info("Schema migration {} '{}' applied", id, name);
    }

    /**
     * Migration 8 — nullable {@code authoring_model} on soul_fragments: which LLM authored
     * this fragment (model-attribution for post-OSS corpus mining / regression debugging /
     * selective regeneration after model updates). Old rows stay null = "pre-attribution".
     */
    private static void migrateSoulFragmentsAuthoringModel(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE soul_fragments ADD COLUMN authoring_model TEXT");
        } catch (SQLException e) {
            if (!e.getMessage().contains("duplicate column")
                    && !e.getMessage().toLowerCase().contains("already exists")) throw e;
        }
    }

    private static void migrateHouseholdsX25519(Connection conn) throws SQLException {
        if (!hasTable(conn, "households")) return;
        if (hasColumn(conn, "households", "x25519_public_key")) return;

        log.info("Migrating households: adding x25519_public_key column");
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE households ADD COLUMN x25519_public_key BLOB");
        }
        log.info("households x25519_public_key column added");
    }

    /**
     * add nullable {@code harness_json} to {@code skill_drafts} so a
     * frozen, anchor-grounded verification harness travels WITH the skill (a Trading-Post
     * recipient re-runs it with zero model calls). Existing drafts have null = unverified.
     */
    private static void migrateSkillDraftsHarness(Connection conn) throws SQLException {
        if (!hasTable(conn, "skill_drafts")) return;
        if (hasColumn(conn, "skill_drafts", "harness_json")) return;

        log.info("Migrating skill_drafts: adding harness_json column");
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE skill_drafts ADD COLUMN harness_json TEXT");
        }
        log.info("skill_drafts harness_json column added");
    }

    /** Add script_source/script_id to inventory for scripted item transit. */
    private static void migrateInventoryScripts(Connection conn) throws SQLException {
        if (!hasTable(conn, "inventory")) return;
        if (hasColumn(conn, "inventory", "script_source")) return;

        log.info("Migrating inventory table: adding script_source/script_id columns");
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE inventory ADD COLUMN script_source TEXT");
            stmt.execute("ALTER TABLE inventory ADD COLUMN script_id TEXT");
        }
        log.info("Inventory scripted-item columns added");
    }

    /**
     * Arc 3 — add nullable {@code other_did} column to
     * {@code substrate_pressure_samples} so per-relationship partitioning is
     * available. Existing rows have null other_did → counted as bondholder
     * by default.
     */
    private static void migrateSubstratePressureOtherDid(Connection conn) throws SQLException {
        if (!hasTable(conn, "substrate_pressure_samples")) return;
        if (hasColumn(conn, "substrate_pressure_samples", "other_did")) return;

        log.info("Migrating substrate_pressure_samples: adding other_did column");
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE substrate_pressure_samples ADD COLUMN other_did TEXT");
        }
        log.info("substrate_pressure_samples other_did column added");
    }

    /** Add meta_payload/meta_ser_id/meta_ser_manifest to snapshot table if missing. */
    private static void migrateSnapshotMeta(Connection conn) throws SQLException {
        if (hasColumn(conn, "snapshot", "meta_ser_id")) return;

        log.info("Migrating snapshot table: adding meta columns");
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE snapshot ADD COLUMN meta_payload BLOB");
            stmt.execute("ALTER TABLE snapshot ADD COLUMN meta_ser_id INTEGER");
            stmt.execute("ALTER TABLE snapshot ADD COLUMN meta_ser_manifest TEXT");
        }
        log.info("Snapshot table migration complete");
    }

    /**
     * Add role column to users table and user_id column to paired_devices table if missing.
     * Also promotes the first/oldest user to steward if no steward exists.
     */
    private static void migrateUserAccounts(Connection conn) throws SQLException {
        // Add role column to users if missing
        if (!hasColumn(conn, "users", "role")) {
            log.info("Migrating users table: adding role column");
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'member'");
            }
            log.info("Users table role column added");
        }

        // Add user_id column to paired_devices if missing (table may not exist yet)
        if (hasTable(conn, "paired_devices") && !hasColumn(conn, "paired_devices", "user_id")) {
            log.info("Migrating paired_devices table: adding user_id column");
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE paired_devices ADD COLUMN user_id TEXT");
            }
            log.info("paired_devices table user_id column added");
        }

        // Promote first user to steward if no steward exists
        promoteFirstSteward(conn);
    }

    /** If users exist but none have role='steward', promote the oldest to steward. */
    private static void promoteFirstSteward(Connection conn) throws SQLException {
        // Check if any steward exists
        try (var stmt = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'steward'")) {
            var rs = stmt.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return; // steward already exists
        } catch (SQLException e) {
            // Table may not exist yet (fresh install) — that's fine
            return;
        }

        // Count total users
        int totalUsers;
        try (var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM users");
            totalUsers = rs.next() ? rs.getInt(1) : 0;
        }

        if (totalUsers == 0) return; // no users yet

        // Promote the oldest user (by created_at) to steward
        try (var stmt = conn.prepareStatement(
                "UPDATE users SET role = 'steward' WHERE id = (SELECT id FROM users ORDER BY created_at ASC LIMIT 1)")) {
            var rows = stmt.executeUpdate();
            if (rows > 0) {
                // Log which user was promoted
                try (var q = conn.prepareStatement("SELECT username FROM users WHERE role = 'steward' LIMIT 1")) {
                    var rs = q.executeQuery();
                    if (rs.next()) {
                        log.info("Promoted user '{}' to steward (migration — no steward existed)", rs.getString("username"));
                    }
                }
            }
        }
    }

    /** Create conversation_checkpoints table for crash recovery if it doesn't exist. */
    private static void migrateConversationCheckpoints(Connection conn) throws SQLException {
        if (hasTable(conn, "conversation_checkpoints")) return;

        log.info("Creating conversation_checkpoints table for crash recovery");
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS conversation_checkpoints (
                    agent_id TEXT PRIMARY KEY,
                    checkpoint_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        }
        log.info("conversation_checkpoints table created");
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private static String loadSchemaResource(String resource) {
        try (var is = SchemaInitializer.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new RuntimeException("Schema resource not found: " + resource);
            }
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read schema resource", e);
        }
    }
}
