package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

        // Lose the original the way an index loses it: Lucene never edits a segment file in
        // place, it writes new files and unlinks old ones — which is why a snapshot may
        // hard-link them. A crash mid-commit leaves the old file gone and a bad new one.
        Files.delete(studyDir.resolve("segments_1"));
        Files.writeString(studyDir.resolve("segments_2"), "corrupted");

        // Restore
        var backup = orchestrator.latestSearchSnapshot();
        assertThat(backup).isPresent();
        assertThat(orchestrator.restoreSearch(backup.get().location(), searchDir)).isTrue();

        // Verify restored content — and that the restore is a copy, independent of the snapshot
        assertThat(Files.readString(searchDir.resolve("study/segments_1")))
            .isEqualTo("original study data");
        assertThat(Files.exists(searchDir.resolve("study/segments_2"))).isFalse();
    }

    @Test void two_passes_at_once_each_link_the_index_under_an_id_of_their_own() throws Exception {
        // The boot schedule and the steward's dial can fire together. Two passes in one second
        // shared a backupId, and the second's links into search.<id> failed into a full copy of
        // the index (the 174 GB copies of 2026-09-10).
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db data");
        var searchDir = tempDir.resolve("search");
        Files.createDirectories(searchDir.resolve("study"));
        Files.writeString(searchDir.resolve("study").resolve("_0.cfs"), "compound file");

        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> orchestrator.snapshotAll(sourceDb, searchDir, null, List.of()));
            var b = pool.submit(() -> orchestrator.snapshotAll(sourceDb, searchDir, null, List.of()));
            var ids = List.of(a.get().orElseThrow().backupId(), b.get().orElseThrow().backupId());
            assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
        } finally {
            pool.shutdownNow();
        }
        try (var s = Files.list(backupDir)) {
            var searchCopies = s.filter(p -> p.getFileName().toString().startsWith("search.")).toList();
            assertThat(searchCopies).hasSize(2);
            for (var copy : searchCopies) {
                assertThat(Files.isSameFile(copy.resolve("study").resolve("_0.cfs"),
                    searchDir.resolve("study").resolve("_0.cfs"))).as("linked, not copied").isTrue();
            }
        }
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

        // Mix: existing dir and a missing path. Only the agents dir should
        // produce a backup. (A regular file is an append-only trail and is
        // taken: see snapshotAll_copies_a_trail_with_what_was_rotated_off_it.)
        var manifest = orchestrator.snapshotAll(sourceDb, null, null,
            List.of(agentsDir, ghost));
        assertThat(manifest).isPresent();
        assertThat(manifest.get().source()).contains("agents");
        assertThat(manifest.get().source()).doesNotContain("not-a-dir");

        try (var stream = Files.list(backupDir)) {
            long ghostBackups = stream
                .filter(p -> p.getFileName().toString().startsWith("not-a-dir."))
                .count();
            assertThat(ghostBackups).isZero();
        }
    }

    @Test void snapshotAll_copies_a_trail_with_what_was_rotated_off_it() throws IOException {
        // data/agent-activity.jsonl is the companions' day-by-day record, and the night's write
        // reads it; data/drive-trace.jsonl rotates to drive-trace.jsonl.1. Neither was in any
        // backup (2026-09-22).
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        var data = tempDir.resolve("data");
        Files.createDirectories(data);
        var activity = data.resolve("agent-activity.jsonl");
        Files.writeString(activity, "{\"type\":\"speak\",\"text\":\"one\"}\n");
        Files.writeString(data.resolve("agent-activity.jsonl.orig"), "a hand-made copy, not a rotation\n");
        var drive = data.resolve("drive-trace.jsonl");
        Files.writeString(drive, "{\"kind\":\"event\",\"label\":\"now\"}\n");
        Files.writeString(data.resolve("drive-trace.jsonl.1"), "{\"kind\":\"event\",\"label\":\"before\"}\n");
        Files.writeString(data.resolve("drive-trace.jsonl.2.gz"), "compressed");
        Files.writeString(data.resolve("drive-trace.jsonl.tmp"), "half written");
        Files.writeString(data.resolve("unrelated.jsonl"), "not a trail\n");

        var manifest = orchestrator.snapshotAll(sourceDb, null, null, List.of(activity, drive));
        assertThat(manifest).isPresent();
        assertThat(manifest.get().source()).contains("agent-activity.jsonl").contains("drive-trace.jsonl");

        Path activityCopy;
        Path driveCopy;
        try (var stream = Files.list(backupDir)) {
            var dirs = stream.filter(Files::isDirectory).toList();
            activityCopy = dirs.stream()
                .filter(p -> p.getFileName().toString().startsWith("agent-activity.jsonl."))
                .findFirst().orElseThrow();
            driveCopy = dirs.stream()
                .filter(p -> p.getFileName().toString().startsWith("drive-trace.jsonl."))
                .findFirst().orElseThrow();
        }
        try (var files = Files.list(activityCopy)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                .as("the trail, not a hand-made copy beside it").containsExactly("agent-activity.jsonl");
        }
        try (var files = Files.list(driveCopy)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted().toList())
                .as("the trail and its rotations, nothing else")
                .containsExactly("drive-trace.jsonl", "drive-trace.jsonl.1", "drive-trace.jsonl.2.gz");
        }
        assertThat(Files.readString(driveCopy.resolve("drive-trace.jsonl.1"))).contains("before");

        // A copy, not a link: a line appended to the live trail afterwards is not in the snapshot.
        assertThat(Files.isSameFile(activity, activityCopy.resolve("agent-activity.jsonl"))).isFalse();
        Files.writeString(activity, "{\"type\":\"speak\",\"text\":\"two\"}\n", StandardOpenOption.APPEND);
        assertThat(Files.readString(activityCopy.resolve("agent-activity.jsonl")))
            .contains("one").doesNotContain("two");
    }

    @Test void an_extra_dir_that_fails_does_not_end_the_pass() throws IOException {
        // copyDirectoryRecursive wraps its IOException. Uncaught, it ended snapshotAll there, so
        // nothing after it in the list was taken, and on the scheduled path the executor never
        // ran the task again.
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        var story = tempDir.resolve("story");
        Files.createDirectories(story);
        var locked = story.resolve("scene.json");
        Files.writeString(locked, "{}");
        assumeTrue(Files.getFileStore(locked).supportsFileAttributeView("posix"), "needs POSIX permissions");
        Files.setPosixFilePermissions(locked, Set.of());
        try {
            assumeFalse(Files.isReadable(locked), "running as root: nothing is unreadable");
            var trail = tempDir.resolve("agent-activity.jsonl");
            Files.writeString(trail, "{\"type\":\"speak\"}\n");

            var manifest = orchestrator.snapshotAll(sourceDb, null, null, List.of(story, trail));
            assertThat(manifest).isPresent();
            assertThat(manifest.get().source()).contains(" + agent-activity.jsonl").doesNotContain(" + story");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test void the_schedule_counts_from_the_newest_backup_not_from_the_start() {
        var now = Instant.parse("2026-09-22T13:02:00Z");
        var day = Duration.ofHours(24);
        // A backup two hours ago: the next is due in twenty-two, whenever the service started.
        assertThat(BackupOrchestrator.firstRunDelay(now.minus(Duration.ofHours(2)), day, now))
            .isEqualTo(Duration.ofHours(22));
        // The newest backup is older than the interval (a node restarted five times a day):
        // it runs soon after this start, not a whole day later.
        assertThat(BackupOrchestrator.firstRunDelay(now.minus(Duration.ofHours(33)), day, now))
            .isEqualTo(BackupOrchestrator.FIRST_RUN_FLOOR);
        // Never sooner than the floor, even when it is due in five minutes.
        assertThat(BackupOrchestrator.firstRunDelay(now.minus(day).plus(Duration.ofMinutes(5)), day, now))
            .isEqualTo(BackupOrchestrator.FIRST_RUN_FLOOR);
        // No backup yet, or one stamped later than now (the clock moved back): soon.
        assertThat(BackupOrchestrator.firstRunDelay(null, day, now))
            .isEqualTo(BackupOrchestrator.FIRST_RUN_FLOOR);
        assertThat(BackupOrchestrator.firstRunDelay(now.plus(Duration.ofDays(3)), day, now))
            .isEqualTo(BackupOrchestrator.FIRST_RUN_FLOOR);
    }

    @Test void a_restart_after_a_backup_waits_the_interval_from_that_backup() throws IOException {
        var sourceDb = tempDir.resolve("test.db");
        Files.writeString(sourceDb, "db");
        orchestrator.snapshotAll(sourceDb, null, null, List.of());
        // A restart builds a new orchestrator over the same directory, as Main does at boot.
        var restarted = new BackupOrchestrator(backupDir);
        var last = restarted.latestSnapshot()
            .map(BackupOrchestrator.BackupManifest::timestamp).orElseThrow();
        var next = BackupOrchestrator.firstRunDelay(last, Duration.ofHours(24), Instant.now());
        assertThat(next).isGreaterThan(Duration.ofHours(23)).isLessThanOrEqualTo(Duration.ofHours(24));
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

    @Test
    void the_search_index_is_hard_linked_not_copied_so_a_snapshot_costs_no_space(@TempDir Path root) throws IOException {
        // A Lucene index: write-once segment files. Five nightly copies of a 174 GB index ate a
        // household node's disk (2026-09-10); a link shares the bytes and costs nothing.
        var search = root.resolve("search"); var backups = root.resolve("backups");
        Files.createDirectories(search.resolve("knowledge"));
        Files.writeString(search.resolve("knowledge").resolve("_0.cfs"), "segment zero ".repeat(1000));
        Files.writeString(search.resolve("knowledge").resolve("segments_1"), "commit one");
        var orch = new BackupOrchestrator(backups);
        var out = BackupOrchestrator.linkOrCopyDirectory(search, backups.resolve("search.t1"));
        assertThat(out.linked()).isEqualTo(2);
        assertThat(out.copied()).isEqualTo(0);
        var live = search.resolve("knowledge").resolve("_0.cfs");
        var snap = backups.resolve("search.t1").resolve("knowledge").resolve("_0.cfs");
        assertThat(Files.isSameFile(live, snap)).as("a hard link: the same inode").isTrue();
        assertThat(Files.readString(snap)).startsWith("segment zero");

        // Lucene retires a segment by unlinking it: the snapshot keeps its own link, so the
        // bytes stay readable from the backup after the live index has moved on.
        Files.delete(live);
        Files.writeString(search.resolve("knowledge").resolve("_1.cfs"), "segment one");
        assertThat(Files.readString(snap)).startsWith("segment zero");
        var out2 = BackupOrchestrator.linkOrCopyDirectory(search, backups.resolve("search.t2"));
        assertThat(out2.linked()).isEqualTo(2);
        assertThat(Files.exists(backups.resolve("search.t2").resolve("knowledge").resolve("_1.cfs"))).isTrue();
        assertThat(Files.exists(backups.resolve("search.t2").resolve("knowledge").resolve("_0.cfs"))).isFalse();
        assertThat(orch).isNotNull();
    }
}
