package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.recipe.CommandRunner;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.RecipeManifest;
import org.wyrdsekai.core.recipe.RecipeParser;
import org.wyrdsekai.core.recipe.RecipeRunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * tier-3 live verify for {@code consolidate-memory-graph}
 * (#1026). Real SQLite, real Python execution of the
 * {@code scripts/memory/consolidate_graph.py} script, real
 * {@link RecipeRunner} driving the bundled recipe YAML end-to-end.
 *
 * <p>Three scenarios validate that the welfare gates aren't decorative:</p>
 * <ol>
 *   <li><b>Happy path</b>: seed 10 entities with 3 duplicate (type, value)
 *       tuples that should collapse. Recipe runs → SUCCESS. DB ends at
 *       7 entities; the lost rows are the older-timestamp duplicates.</li>
 *   <li><b>Delta gate STOP</b>: seed 10 entities where 9 share one
 *       (type, value) → 90% delta would be applied. Override
 *       {@code max_entity_delta_pct} to 50 → gate-delta fires → recipe
 *       returns GATE_FAILED. The DB MUST be unchanged. This is the
 *       load-bearing guarantee of the plan-and-commit split.</li>
 *   <li><b>No-op</b>: seed 5 entities with no duplicates. Recipe runs to
 *       SUCCESS with deduped_count=0. DB unchanged.</li>
 * </ol>
 *
 * <p>Gated on {@code WYRDSEKAI_LIVE_RECIPE_E2E=1} + python3 reachable.
 * Schema (memory_entities, memory_edges) is created inline using the
 * same DDL shape as {@code core/src/main/resources/schema/sqlite-create-schema.sql}.</p>
 *
 * <p>Run from repo root:</p>
 * <pre>
 *   WYRDSEKAI_LIVE_RECIPE_E2E=1 ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.ConsolidateMemoryGraphLiveE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_RECIPE_E2E", matches = "1|true")
class ConsolidateMemoryGraphLiveE2ETest {

    private static final String AGENT_DID = "did:wyrd:e2e-memgraph-companion";

    private Path repoRoot;

    @BeforeEach
    void setUp() {
        repoRoot = findRepoRoot();
        assumeTrue(repoRoot != null,
            "repo root with scripts/memory/consolidate_graph.py not found");
        assumeThat(canRunPython3())
            .as("python3 must be reachable").isTrue();
    }

    @Test
    void happy_path_dedups_three_duplicates_and_keeps_latest(@TempDir Path tmp) throws Exception {
        Path dbPath = tmp.resolve("memgraph.db");
        clearLeftoverPlanFiles();
        try (Connection conn = openDb(dbPath)) {
            createSchema(conn);
            // 10 rows; (Person, alice) appears 3x (kept: ts=2000); (Project, wyrd) 2x;
            // 5 distinct other rows. Net: 3 duplicates → 7 surviving.
            insertEntity(conn, AGENT_DID, "Person", "alice", 1000);
            insertEntity(conn, AGENT_DID, "Person", "alice", 1500);
            insertEntity(conn, AGENT_DID, "Person", "alice", 2000);   // canonical
            insertEntity(conn, AGENT_DID, "Project", "wyrd", 800);
            insertEntity(conn, AGENT_DID, "Project", "wyrd", 1800);   // canonical
            insertEntity(conn, AGENT_DID, "Person", "bob",   900);
            insertEntity(conn, AGENT_DID, "Person", "carol", 950);
            insertEntity(conn, AGENT_DID, "Place",  "study", 1100);
            insertEntity(conn, AGENT_DID, "Topic",  "music", 1200);
            insertEntity(conn, AGENT_DID, "Tool",   "lantern", 1300);
            insertEdge(conn, AGENT_DID, "alice", "knows", "bob",
                    System.currentTimeMillis(), 0.9);
        }

        var run = runRecipe(dbPath, Map.of("agent_did", AGENT_DID));
        printRun(run);

        assertThat(run.status())
            .as("happy path must reach SUCCESS — msg=%s", run.message())
            .isEqualTo(RecipeRunner.Status.SUCCESS);

        try (Connection conn = openDb(dbPath)) {
            int post = entityCount(conn, AGENT_DID);
            assertThat(post)
                .as("3 duplicates should collapse: 10 → 7")
                .isEqualTo(7);
            // Latest-timestamp row for (Person, alice) survives.
            int aliceLatestTs = scalarInt(conn,
                "SELECT MAX(timestamp) FROM memory_entities "
                    + "WHERE did = ? AND entity_type = ? AND entity_value = ?",
                AGENT_DID, "Person", "alice");
            assertThat(aliceLatestTs)
                .as("dedup must keep the latest-timestamp row for each (type,value)")
                .isEqualTo(2000);
            int aliceRows = scalarInt(conn,
                "SELECT COUNT(*) FROM memory_entities "
                    + "WHERE did = ? AND entity_type = ? AND entity_value = ?",
                AGENT_DID, "Person", "alice");
            assertThat(aliceRows)
                .as("only the canonical row should remain for (Person, alice)")
                .isEqualTo(1);
        }
    }

    @Test
    void delta_gate_blocks_destructive_run_db_unchanged(@TempDir Path tmp) throws Exception {
        Path dbPath = tmp.resolve("memgraph.db");
        clearLeftoverPlanFiles();
        try (Connection conn = openDb(dbPath)) {
            createSchema(conn);
            // 10 entities; 9 share (Person, alice). Dedup plan = 8 deletes →
            // entity_delta_pct = 80%. With max_entity_delta_pct=50 gate STOPs.
            for (int i = 0; i < 9; i++) {
                insertEntity(conn, AGENT_DID, "Person", "alice", 100L + i * 100);
            }
            insertEntity(conn, AGENT_DID, "Place", "hearth", 9999);
        }
        int preCount;
        try (Connection conn = openDb(dbPath)) {
            preCount = entityCount(conn, AGENT_DID);
        }
        assertThat(preCount).isEqualTo(10);

        var run = runRecipe(dbPath, Map.of(
            "agent_did", AGENT_DID,
            "max_entity_delta_pct", 50));
        printRun(run);

        assertThat(run.status())
            .as("welfare gate-delta must STOP a runaway-dedup run")
            .isEqualTo(RecipeRunner.Status.GATE_FAILED);
        assertThat(run.message()).contains("gate-delta");

        try (Connection conn = openDb(dbPath)) {
            int post = entityCount(conn, AGENT_DID);
            assertThat(post)
                .as("DB MUST be unchanged when gate-delta STOPs — "
                    + "this is the load-bearing guarantee of plan-and-commit")
                .isEqualTo(10);
        }
    }

    @Test
    void no_op_run_on_clean_graph_succeeds_unchanged(@TempDir Path tmp) throws Exception {
        Path dbPath = tmp.resolve("memgraph.db");
        clearLeftoverPlanFiles();
        try (Connection conn = openDb(dbPath)) {
            createSchema(conn);
            insertEntity(conn, AGENT_DID, "Person", "alice", 1000);
            insertEntity(conn, AGENT_DID, "Person", "bob",   1100);
            insertEntity(conn, AGENT_DID, "Place",  "study", 1200);
            insertEntity(conn, AGENT_DID, "Topic",  "music", 1300);
            insertEntity(conn, AGENT_DID, "Tool",   "lantern", 1400);
        }

        var run = runRecipe(dbPath, Map.of("agent_did", AGENT_DID));
        printRun(run);

        assertThat(run.status()).isEqualTo(RecipeRunner.Status.SUCCESS);
        try (Connection conn = openDb(dbPath)) {
            assertThat(entityCount(conn, AGENT_DID))
                .as("no duplicates → entity count must stay at 5")
                .isEqualTo(5);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private RecipeRunner.RecipeRun runRecipe(Path dbPath, Map<String, Object> params)
            throws Exception {
        var manifest = loadBundledRecipe();
        // Recipe references {{jdbc_url}} from params + a ${WYRDSEKAI_JDBC_URL:-...}
        // shell expansion. Setting the JDBC param drives the SQLite path.
        var fullParams = new HashMap<>(params);
        fullParams.put("jdbc_url", "jdbc:sqlite:" + dbPath.toAbsolutePath());

        CommandRunner cmd = new ProcessCommandRunner(repoRoot.toFile(),
                Duration.ofMinutes(2));
        var runner = new RecipeRunner(cmd, null);
        return runner.run(manifest, fullParams);
    }

    private static RecipeManifest loadBundledRecipe() {
        String resource = "recipes/consolidate-memory-graph.recipe.yaml";
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing bundled recipe: " + resource);
            }
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return RecipeParser.parseManifest(yaml);
        } catch (IOException e) {
            throw new AssertionError("failed to load " + resource, e);
        }
    }

    private static Connection openDb(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS memory_entities("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, did TEXT NOT NULL,"
                + "memory_id TEXT NOT NULL, entity_type TEXT NOT NULL,"
                + "entity_role TEXT, entity_value TEXT NOT NULL,"
                + "timestamp INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000))");
            st.execute("CREATE TABLE IF NOT EXISTS memory_edges("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, did TEXT NOT NULL,"
                + "subject TEXT NOT NULL, predicate TEXT NOT NULL,"
                + "object TEXT NOT NULL, memory_id TEXT NOT NULL,"
                + "confidence REAL NOT NULL DEFAULT 1.0,"
                + "created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000))");
        }
    }

    private static void insertEntity(Connection conn, String did, String etype,
            String evalue, long ts) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO memory_entities(did, memory_id, entity_type, "
                    + "entity_value, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, did);
            ps.setString(2, "mem-" + etype + "-" + evalue + "-" + ts);
            ps.setString(3, etype);
            ps.setString(4, evalue);
            ps.setLong(5, ts);
            ps.executeUpdate();
        }
    }

    private static void insertEdge(Connection conn, String did, String subject,
            String predicate, String object, long createdAtMs, double confidence)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO memory_edges(did, subject, predicate, object, "
                    + "memory_id, confidence, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, did);
            ps.setString(2, subject);
            ps.setString(3, predicate);
            ps.setString(4, object);
            ps.setString(5, "edge-" + subject + "-" + predicate + "-" + object);
            ps.setDouble(6, confidence);
            ps.setLong(7, createdAtMs);
            ps.executeUpdate();
        }
    }

    private static int entityCount(Connection conn, String did) throws SQLException {
        return scalarInt(conn,
            "SELECT COUNT(*) FROM memory_entities WHERE did = ?", did);
    }

    private static int scalarInt(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                return 0;
            }
        }
    }

    /** Plan files live at /tmp/<safe-did>-memgraph-*.json. Wipe before each
     *  test so a previous run's plan doesn't bleed into this one. */
    private void clearLeftoverPlanFiles() {
        String safe = AGENT_DID.replace(":", "_").replace("/", "_");
        for (String suffix : new String[] {"snapshot", "dedup-plan", "prune-plan"}) {
            Path p = Path.of("/tmp", safe + "-memgraph-" + suffix + ".json");
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }

    private static void printRun(RecipeRunner.RecipeRun run) {
        System.out.println("=== Recipe outcome: " + run.status() + " — " + run.message());
        for (var o : run.outcomes()) {
            System.out.println("    " + o.id() + " [" + o.kind() + "] "
                + (o.ok() ? "OK" : "FAIL") + " :: " + o.detail());
        }
    }

    private static Path findRepoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            if (new File(dir, "scripts/memory/consolidate_graph.py").isFile()) {
                return dir.toPath();
            }
        }
        return null;
    }

    private static boolean canRunPython3() {
        try {
            var p = new ProcessBuilder("python3", "--version")
                .redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
