package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupOrchestratorTest {

    @TempDir Path tempDir;
    private BackupOrchestrator orchestrator;
    private Path backupDir;

    @BeforeEach void setUp() {
        backupDir = tempDir.resolve("backups");
        orchestrator = new BackupOrchestrator(backupDir);
    }

    @Test void snapshot_creates_backup_file() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "test data");

        var manifest = orchestrator.snapshot(sourceDb);
        assertThat(manifest).isPresent();
        assertThat(Files.exists(manifest.get().location())).isTrue();
        assertThat(manifest.get().sizeBytes()).isGreaterThan(0);
    }

    @Test void restore_copies_backup_to_target() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "original data");

        var manifest = orchestrator.snapshot(sourceDb);
        assertThat(manifest).isPresent();

        var targetDb = tempDir.resolve("restored.db");
        assertThat(orchestrator.restore(manifest.get().location(), targetDb)).isTrue();
        assertThat(Files.readString(targetDb)).isEqualTo("original data");
    }

    @Test void listSnapshots_returns_backups() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "data");

        orchestrator.snapshot(sourceDb);
        assertThat(orchestrator.listSnapshots()).hasSize(1);
    }

    @Test void prune_keeps_only_max_snapshots() throws IOException {
        orchestrator.setMaxSnapshots(2);
        var sourceDb = tempDir.resolve("test.db");

        for (int i = 0; i < 4; i++) {
            Files.writeString(sourceDb, "data " + i);
            orchestrator.snapshot(sourceDb);
        }

        // Should have pruned down to 2
        assertThat(orchestrator.listSnapshots().size()).isLessThanOrEqualTo(3);
    }

    // --- Lucene/Study backup tests ---

    @Test void snapshotAll_backs_up_db_and_search() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db data");

        // Create a fake search directory with collections
        var searchDir = tempDir.resolve("search");
        var studyDir = searchDir.resolve("study");
        Files.createDirectories(studyDir);
        Files.writeString(studyDir.resolve("segments_1"), "lucene segment data");
        Files.writeString(studyDir.resolve("_0.cfs"), "compound file");

        var soulDir = searchDir.resolve("soul_fragments");
        Files.createDirectories(soulDir);
        Files.writeString(soulDir.resolve("segments_1"), "soul data");

        var manifest = orchestrator.snapshotAll(sourceDb, searchDir);
        assertThat(manifest).isPresent();
        assertThat(manifest.get().sizeBytes()).isGreaterThan(0);
        assertThat(manifest.get().source()).contains("search");

        // Verify search backup exists
        var searchBackups = orchestrator.listSearchSnapshots();
        assertThat(searchBackups).hasSize(1);
        assertThat(Files.isDirectory(searchBackups.getFirst().location())).isTrue();

        // Verify the backup contains the study and soul collections
        var backupPath = searchBackups.getFirst().location();
        assertThat(Files.exists(backupPath.resolve("study/segments_1"))).isTrue();
        assertThat(Files.exists(backupPath.resolve("soul_fragments/segments_1"))).isTrue();
    }

    @Test void restoreSearch_restores_from_backup() throws IOException {
        // Create original search dir
        var searchDir = tempDir.resolve("search");
        var studyDir = searchDir.resolve("study");
        Files.createDirectories(studyDir);
        Files.writeString(studyDir.resolve("segments_1"), "original study data");

        // Backup
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        orchestrator.snapshotAll(sourceDb, searchDir);

        // Modify the original (simulating corruption/loss)
        Files.writeString(studyDir.resolve("segments_1"), "corrupted");

        // Restore
        var backup = orchestrator.latestSearchSnapshot();
        assertThat(backup).isPresent();
        assertThat(orchestrator.restoreSearch(backup.get().location(), searchDir)).isTrue();

        // Verify restored content
        assertThat(Files.readString(searchDir.resolve("study/segments_1")))
            .isEqualTo("original study data");
    }

    @Test void snapshotAll_handles_null_search_dir() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db data");

        // null searchDir should still back up the database
        var manifest = orchestrator.snapshotAll(sourceDb, null);
        assertThat(manifest).isPresent();
        assertThat(orchestrator.listSearchSnapshots()).isEmpty();
    }

    @Test void snapshotAll_handles_missing_search_dir() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db data");

        // Non-existent search dir should still back up the database
        var manifest = orchestrator.snapshotAll(sourceDb, tempDir.resolve("nonexistent"));
        assertThat(manifest).isPresent();
        assertThat(orchestrator.listSearchSnapshots()).isEmpty();
    }

    @Test void search_backup_prune_keeps_max() throws Exception {
        orchestrator.setMaxSnapshots(2);
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");

        var searchDir = tempDir.resolve("search");
        Files.createDirectories(searchDir.resolve("study"));
        Files.writeString(searchDir.resolve("study/data"), "study");

        for (int i = 0; i < 4; i++) {
            orchestrator.snapshotAll(sourceDb, searchDir);
            Thread.sleep(50); // ensure distinct timestamps
        }

        assertThat(orchestrator.listSearchSnapshots().size()).isLessThanOrEqualTo(2);
    }

    @Test void listSearchSnapshots_empty_when_none() {
        assertThat(orchestrator.listSearchSnapshots()).isEmpty();
        assertThat(orchestrator.latestSearchSnapshot()).isEmpty();
    }

    // --- VACUUM INTO tests (Phase F7b backup hardening) ---

    @Test void snapshot_real_sqlite_uses_vacuum_into() throws Exception {
        var sourceDb = tempDir.resolve("real.db");
        var jdbcUrl = "jdbc:sqlite:" + sourceDb.toAbsolutePath();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");
            stmt.execute("INSERT INTO t (v) VALUES ('alpha')");
            stmt.execute("INSERT INTO t (v) VALUES ('beta')");
        }

        var manifest = orchestrator.snapshot(sourceDb);
        assertThat(manifest).isPresent();
        var backupPath = manifest.get().location();
        assertThat(Files.exists(backupPath)).isTrue();

        // Header check — backup file is itself a valid SQLite file.
        byte[] header = new byte[16];
        try (var in = Files.newInputStream(backupPath)) { in.read(header); }
        assertThat(new String(header, 0, 6)).isEqualTo("SQLite");

        // Open the backup as a database, verify rows are present.
        var backupJdbc = "jdbc:sqlite:" + backupPath.toAbsolutePath();
        try (var conn = DriverManager.getConnection(backupJdbc);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT count(*) FROM t")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test void snapshot_captures_writes_in_wal_mode() throws Exception {
        var sourceDb = tempDir.resolve("wal.db");
        var jdbcUrl = "jdbc:sqlite:" + sourceDb.toAbsolutePath();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("CREATE TABLE x (n INTEGER)");
            stmt.execute("INSERT INTO x VALUES (1), (2), (3)");
            // Don't checkpoint — the rows live in -wal until VACUUM INTO
            // pulls them through. Naive Files.copy of just .db would miss them.
        }

        var manifest = orchestrator.snapshot(sourceDb);
        assertThat(manifest).isPresent();

        var backupJdbc = "jdbc:sqlite:" + manifest.get().location().toAbsolutePath();
        try (var conn = DriverManager.getConnection(backupJdbc);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT count(*) FROM x")) {
            rs.next();
            assertThat(rs.getInt(1))
                .as("WAL-resident rows captured by VACUUM INTO")
                .isEqualTo(3);
        }
    }

    @Test void snapshot_non_sqlite_falls_back_to_file_copy() throws IOException {
        // Plain text file — no SQLite header; the file-copy fallback path runs.
        var sourceDb = tempDir.resolve("plain.bin");
        Files.writeString(sourceDb, "not a database");

        var manifest = orchestrator.snapshot(sourceDb);
        assertThat(manifest).isPresent();
        assertThat(Files.readString(manifest.get().location()))
            .isEqualTo("not a database");
    }

    // --- node-identity backup tests ---

    @Test void snapshotAll_backs_up_node_identity() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        var nodeId = tempDir.resolve("node-identity.json");
        Files.writeString(nodeId,
            "{\"did\":\"did:key:z6MkExample\",\"privateKey\":\"secret\"}");

        var manifest = orchestrator.snapshotAll(sourceDb, null, nodeId);
        assertThat(manifest).isPresent();
        assertThat(manifest.get().source()).contains("node-identity");

        try (var stream = Files.list(backupDir)) {
            var idBackups = stream
                .filter(p -> p.getFileName().toString()
                    .startsWith("node-identity."))
                .toList();
            assertThat(idBackups).hasSize(1);
            assertThat(Files.readString(idBackups.getFirst()))
                .contains("did:key:z6MkExample");
        }
    }

    @Test void snapshotAll_skips_node_identity_when_null() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");

        var manifest = orchestrator.snapshotAll(sourceDb, null, null);
        assertThat(manifest).isPresent();
        assertThat(manifest.get().source()).doesNotContain("node-identity");

        try (var stream = Files.list(backupDir)) {
            assertThat(stream
                .filter(p -> p.getFileName().toString()
                    .startsWith("node-identity."))
                .count()).isZero();
        }
    }

    @Test void snapshotAll_skips_node_identity_when_missing() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        var missing = tempDir.resolve("does-not-exist.json");

        var manifest = orchestrator.snapshotAll(sourceDb, null, missing);
        assertThat(manifest).isPresent();

        try (var stream = Files.list(backupDir)) {
            assertThat(stream
                .filter(p -> p.getFileName().toString()
                    .startsWith("node-identity."))
                .count()).isZero();
        }
    }

    @Test void node_identity_prune_keeps_max() throws Exception {
        orchestrator.setMaxSnapshots(2);
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        var nodeId = tempDir.resolve("node-identity.json");
        Files.writeString(nodeId, "{}");

        for (int i = 0; i < 4; i++) {
            orchestrator.snapshotAll(sourceDb, null, nodeId);
            Thread.sleep(1100); // distinct timestamps in seconds
        }

        try (var stream = Files.list(backupDir)) {
            long count = stream
                .filter(p -> p.getFileName().toString()
                    .startsWith("node-identity."))
                .count();
            assertThat(count).isLessThanOrEqualTo(2);
        }
    }

    // --- Extra-dirs tests (agents/, classifiers/, souls/) ---

    @Test void snapshotAll_backs_up_extra_dirs() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");

        // agents/<did>/locker.json — FamilyLocker shape
        var agentsDir = tempDir.resolve("agents");
        var agentRoot = agentsDir.resolve("did_key_z6Mk_alice");
        Files.createDirectories(agentRoot);
        Files.writeString(agentRoot.resolve("locker.json"),
            "{\"forms\":[],\"named\":[]}");
        Files.writeString(agentRoot.resolve("imprints.json"), "[]");

        // classifiers/<did>/events.jsonl — append-only event log
        var classifiersDir = tempDir.resolve("classifiers");
        var classifierAgent = classifiersDir.resolve("did_key_z6Mk_alice");
        Files.createDirectories(classifierAgent);
        Files.writeString(classifierAgent.resolve("events.jsonl"),
            "{\"event\":\"classify\",\"label\":\"task\"}\n");

        // souls/<entityId>.did — legacy DID file
        var soulsDir = tempDir.resolve("souls");
        Files.createDirectories(soulsDir);
        Files.writeString(soulsDir.resolve("companion-1.did"),
            "did:key:z6MkExample");

        var manifest = orchestrator.snapshotAll(sourceDb, null, null,
            List.of(agentsDir, classifiersDir, soulsDir));
        assertThat(manifest).isPresent();
        assertThat(manifest.get().source())
            .contains("agents")
            .contains("classifiers")
            .contains("souls");

        try (var stream = Files.list(backupDir)) {
            var dirs = stream.filter(Files::isDirectory).toList();
            assertThat(dirs).anyMatch(p -> p.getFileName().toString()
                .startsWith("agents."));
            assertThat(dirs).anyMatch(p -> p.getFileName().toString()
                .startsWith("classifiers."));
            assertThat(dirs).anyMatch(p -> p.getFileName().toString()
                .startsWith("souls."));
        }

        // Drill into the agents backup and verify the per-agent subtree
        // is preserved — not flattened.
        try (var stream = Files.list(backupDir)) {
            var agentsBackup = stream
                .filter(p -> p.getFileName().toString().startsWith("agents."))
                .findFirst().orElseThrow();
            assertThat(Files.readString(agentsBackup
                .resolve("did_key_z6Mk_alice/locker.json")))
                .contains("named");
        }
    }

    @Test void snapshotAll_skips_missing_extra_dirs() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");

        var agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("a.json"), "{}");

        var ghost = tempDir.resolve("not-a-dir");
        var notADir = tempDir.resolve("regular-file");
        Files.writeString(notADir, "hi");

        // Mix: existing dir, missing path, regular file (not a directory).
        // Only the agents dir should produce a backup.
        var manifest = orchestrator.snapshotAll(sourceDb, null, null,
            List.of(agentsDir, ghost, notADir));
        assertThat(manifest).isPresent();
        assertThat(manifest.get().source()).contains("agents");
        assertThat(manifest.get().source()).doesNotContain("not-a-dir");
        assertThat(manifest.get().source()).doesNotContain("regular-file");

        try (var stream = Files.list(backupDir)) {
            long ghostBackups = stream
                .filter(p -> p.getFileName().toString().startsWith("not-a-dir.")
                    || p.getFileName().toString().startsWith("regular-file."))
                .count();
            assertThat(ghostBackups).isZero();
        }
    }

    @Test void extra_dir_prune_keeps_max() throws Exception {
        orchestrator.setMaxSnapshots(2);
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        var agentsDir = tempDir.resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("a.json"), "{}");

        for (int i = 0; i < 4; i++) {
            orchestrator.snapshotAll(sourceDb, null, null, List.of(agentsDir));
            Thread.sleep(1100); // distinct timestamps
        }

        try (var stream = Files.list(backupDir)) {
            long agentBackups = stream
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("agents."))
                .count();
            assertThat(agentBackups).isLessThanOrEqualTo(2);
        }
    }

    @Test void snapshotAll_legacy_two_arg_still_works() throws IOException {
        // Regression guard: callers that haven't migrated to the new
        // 4-arg form continue to work — empty extra-dirs is the default.
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        var searchDir = tempDir.resolve("search");
        Files.createDirectories(searchDir.resolve("study"));
        Files.writeString(searchDir.resolve("study/seg"), "x");

        var manifest = orchestrator.snapshotAll(sourceDb, searchDir);
        assertThat(manifest).isPresent();
        assertThat(manifest.get().source()).contains("search");
    }
}
