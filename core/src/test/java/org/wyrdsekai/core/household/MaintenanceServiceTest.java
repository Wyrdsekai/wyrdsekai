package org.wyrdsekai.core.household;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaintenanceService — the substrate behind the maintenance dial and key
 * chest: steward-gated maintenance mode with persisted state, real
 * backup-now snapshots through {@link BackupOrchestrator}, persisted
 * backup schedule, and staged restore (marker file only — the live db is
 * never touched until boot applies it).
 */
@Tag("integration")
class MaintenanceServiceTest {

    @TempDir
    Path tmp;

    private String jdbcUrl;
    private AuthService auth;
    private MaintenanceService service;
    private BackupOrchestrator backups;
    private Path dataDir;
    private Path worldDb;
    private String stewardId;
    private String memberId;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = TestDb.createInMemory();
        auth = new AuthService(jdbcUrl);
        // First registered user auto-becomes steward; second is a member.
        stewardId = auth.register("operator", "password123", "Operator").orElseThrow().userId();
        memberId = auth.register("kaz", "password123", "Kaz").orElseThrow().userId();

        dataDir = tmp.resolve("data");
        Files.createDirectories(dataDir);
        worldDb = dataDir.resolve("world.db");
        createSqliteDb(worldDb, "A");
        backups = new BackupOrchestrator(dataDir.resolve("backups"));

        service = newService();
    }

    @AfterEach
    void tearDown() {
        service.stopSchedule();
        MaintenanceService.resetForTests();
    }

    private MaintenanceService newService() {
        var svc = new MaintenanceService(jdbcUrl, new SqlDialect.SQLite(), auth,
            backups, dataDir, worldDb, null, null, List.of());
        svc.initSchema();
        return svc;
    }

    /** Real SQLite file with one row so snapshots have committed content. */
    static void createSqliteDb(Path db, String value) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)");
            stmt.execute("INSERT INTO kv(k, v) VALUES('k', '" + value + "') "
                + "ON CONFLICT(k) DO UPDATE SET v = excluded.v");
        }
    }

    // ─── Maintenance mode: steward gate + persistence ────────────────────

    @Test
    void mode_defaults_off_and_is_steward_gated() {
        assertThat(service.maintenanceMode().on()).isFalse();
        assertThat(service.allowsLogin(memberId)).isTrue();

        assertThat(service.setMaintenanceMode(memberId, true, "nope")).isFalse();
        assertThat(service.setMaintenanceMode(null, true, "nope")).isFalse();
        assertThat(service.maintenanceMode().on()).isFalse();

        assertThat(service.setMaintenanceMode(stewardId, true, "disk swap")).isTrue();
        var mode = service.maintenanceMode();
        assertThat(mode.on()).isTrue();
        assertThat(mode.reason()).isEqualTo("disk swap");
        assertThat(mode.setBy()).isEqualTo(stewardId);
        assertThat(mode.since()).isNotNull();
    }

    @Test
    void mode_persists_across_service_instances() {
        service.setMaintenanceMode(stewardId, true, "disk swap");

        var reloaded = newService();
        assertThat(reloaded.maintenanceMode().on()).isTrue();
        assertThat(reloaded.maintenanceMode().reason()).isEqualTo("disk swap");

        reloaded.setMaintenanceMode(stewardId, false, null);
        assertThat(newService().maintenanceMode().on()).isFalse();
    }

    @Test
    void allows_login_blocks_non_stewards_while_on() {
        service.setMaintenanceMode(stewardId, true, "rewiring");
        assertThat(service.allowsLogin(stewardId)).isTrue();
        assertThat(service.allowsLogin(memberId)).isFalse();
        assertThat(service.allowsLogin(null)).isFalse();
        assertThat(service.allowsLogin("anon-12345678")).isFalse();
        assertThat(service.refusalLine()).contains("rewiring");

        service.setMaintenanceMode(stewardId, false, null);
        assertThat(service.allowsLogin(memberId)).isTrue();
    }

    // ─── backupNow: steward gate + a real snapshot on disk ───────────────

    @Test
    void backup_now_is_steward_gated_and_creates_real_snapshot() {
        var denied = service.backupNow(memberId);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("steward only");
        assertThat(backups.listSnapshots()).isEmpty();

        var res = service.backupNow(stewardId);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("id")).isNotNull();
        assertThat((Long) res.get("sizeBytes")).isGreaterThan(0L);

        var snaps = backups.listSnapshots();
        assertThat(snaps).hasSize(1);
        assertThat(snaps.get(0).backupId()).isEqualTo(res.get("id"));
        assertThat(snaps.get(0).location()).exists();
    }

    // ─── Schedule: steward gate + persistence + tick ─────────────────────

    @Test
    void schedule_is_steward_gated_validated_and_persisted() {
        assertThat(service.setBackupSchedule(memberId, 6)).isFalse();
        assertThat(service.setBackupSchedule(stewardId, -1)).isFalse();
        assertThat(service.backupScheduleHours()).isZero();

        assertThat(service.setBackupSchedule(stewardId, 6)).isTrue();
        assertThat(service.backupScheduleHours()).isEqualTo(6);
        assertThat(newService().backupScheduleHours()).isEqualTo(6);

        assertThat(service.setBackupSchedule(stewardId, 0)).isTrue();
        assertThat(newService().backupScheduleHours()).isZero();
    }

    @Test
    void scheduled_tick_snapshots_and_records_last_run() {
        assertThat(service.lastScheduledBackup()).isNull();
        service.scheduledBackupTick();
        assertThat(backups.listSnapshots()).hasSize(1);
        assertThat(service.lastScheduledBackup()).isNotNull();
        // status() surfaces the same facts in one read.
        var status = service.status();
        assertThat(status.snapshotCount()).isEqualTo(1);
        assertThat(status.latestSnapshotId()).isNotNull();
    }

    @Test
    void a_restart_counts_the_schedule_from_the_newest_backup_not_from_the_start() {
        // No backup on disk: soon after this start, not a whole interval later.
        assertThat(service.firstScheduledRunIn(24, Instant.now())).isEqualTo(Duration.ofMinutes(10));
        service.scheduledBackupTick();
        // A restart is a new service over the same state table and backups directory.
        var restarted = newService();
        assertThat(restarted.firstScheduledRunIn(24, Instant.now()))
            .isGreaterThan(Duration.ofHours(23)).isLessThanOrEqualTo(Duration.ofHours(24));
        // A day and a half on with no backup in between: due, so soon.
        assertThat(restarted.firstScheduledRunIn(24, Instant.now().plus(Duration.ofHours(36))))
            .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void backup_now_without_a_search_index_still_takes_the_trail() throws Exception {
        // runBackup fell back to world.db alone when the node had no search directory.
        var trail = dataDir.resolve("agent-activity.jsonl");
        Files.writeString(trail, "{\"type\":\"speak\"}\n");
        var svc = new MaintenanceService(jdbcUrl, new SqlDialect.SQLite(), auth,
            backups, dataDir, worldDb, dataDir.resolve("no-search-here"), null, List.of(trail));
        svc.initSchema();

        var res = svc.backupNow(stewardId);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat((String) res.get("source")).contains("agent-activity.jsonl");
        try (var s = Files.list(dataDir.resolve("backups"))) {
            var copy = s.filter(p -> p.getFileName().toString().startsWith("agent-activity.jsonl."))
                .findFirst().orElseThrow();
            assertThat(copy.resolve("agent-activity.jsonl")).exists();
        }
    }

    // ─── Staged restore: validation + marker lifecycle ───────────────────

    @Test
    void a_restore_by_id_stages_the_database_not_the_node_identity_taken_with_it() throws Exception {
        // A full pass writes world.db.<id>.bak and node-identity.<id>.bak under one id; the newer of
        // the two used to be staged as world.db, and the node could not open its record.
        var identity = dataDir.resolve("node-identity.json");
        Files.writeString(identity, "{\"did\":\"did:test:node\"}");
        var svc = new MaintenanceService(jdbcUrl, new SqlDialect.SQLite(), auth,
            backups, dataDir, worldDb, null, identity, List.of());
        svc.initSchema();
        var res = svc.backupNow(stewardId);
        assertThat(res.get("ok")).isEqualTo(true);
        var id = backups.snapshotsOf(worldDb).get(0).backupId();
        try (var s = Files.list(dataDir.resolve("backups"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList())
                .anyMatch(n -> n.startsWith("node-identity.") && n.contains(id));
        }
        var staged = svc.stageRestore(stewardId, id);
        assertThat(staged.get("ok")).isEqualTo(true);
        assertThat((String) staged.get("backupFile")).contains(worldDb.getFileName().toString() + ".")
            .doesNotContain("node-identity");
    }

    @Test
    void the_chest_lists_what_a_restore_can_stage_and_a_brainstem_copy_can_be_staged() throws Exception {
        var identity = dataDir.resolve("node-identity.json");
        Files.writeString(identity, "{\"did\":\"did:test:node\"}");
        var svc = new MaintenanceService(jdbcUrl, new SqlDialect.SQLite(), auth,
            backups, dataDir, worldDb, null, identity, List.of());
        svc.initSchema();
        svc.backupNow(stewardId);
        assertThat(svc.listSnapshots()).as("the node identity is not a database snapshot")
            .noneMatch(m -> m.location().getFileName().toString().startsWith("node-identity."));
        var brainstemCopy = dataDir.resolve("backups").resolve("brainstem.world.db.20990101-000000.bak");
        Files.copy(worldDb, brainstemCopy);
        var staged = svc.stageRestore(stewardId, "20990101-000000");
        assertThat(staged.get("ok")).isEqualTo(true);
        assertThat((String) staged.get("backupFile")).contains("brainstem.world.db.20990101-000000.bak");
    }

    @Test
    void a_brainstem_copy_restores_with_the_rows_its_wal_held() throws Exception {
        // Without sqlite3 (and always on Windows) the brainstem copies world.db and world.db-wal
        // as a pair. The newest committed rows are only in the WAL; restoring the file alone
        // went back to the last checkpoint and reported success.
        var copy = dataDir.resolve("backups").resolve("brainstem.world.db.20990101-000000.bak");
        var copyWal = copy.resolveSibling(copy.getFileName() + "-wal");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + worldDb);
             var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA wal_autocheckpoint=0");
            stmt.execute("UPDATE kv SET v = 'W' WHERE k = 'k'");
            stmt.execute("CREATE TABLE trail(n INTEGER)");
            for (int i = 0; i < 50; i++) {
                stmt.execute("INSERT INTO trail(n) VALUES(" + i + ")");
            }
            // Copied while the connection is still open, as from a hung server: nothing checkpointed.
            Files.copy(worldDb, copy);
            Files.copy(worldDb.resolveSibling("world.db-wal"), copyWal);
        }
        var alone = tmp.resolve("alone.db");
        Files.copy(copy, alone);
        assertThat(scalar(alone, "SELECT v FROM kv WHERE k = 'k'"))
            .as("the copied file alone is from before the WAL's rows").isEqualTo("A");
        createSqliteDb(worldDb, "B");

        var staged = service.stageRestore(stewardId, "20990101-000000");
        assertThat(staged.get("ok")).isEqualTo(true);
        MaintenanceService.applyStagedRestoreIfAny(dataDir, worldDb);

        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FAILED_FILE)).doesNotExist();
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE)).doesNotExist();
        assertThat(dataDir.resolve("world.db-wal")).doesNotExist();
        assertThat(dataDir.resolve("world.db.restore-tmp")).doesNotExist();
        assertThat(dataDir.resolve("world.db.restore-tmp-wal")).doesNotExist();
        assertThat(dataDir.resolve("world.db.restore-tmp-shm")).doesNotExist();
        assertThat(copyWal).as("the brainstem's pair is left as it was").exists();
        assertThat(scalar(worldDb, "SELECT v FROM kv WHERE k = 'k'")).isEqualTo("W");
        assertThat(scalar(worldDb, "SELECT count(*) FROM trail")).isEqualTo("50");
        assertThat(scalar(worldDb, "PRAGMA integrity_check")).isEqualTo("ok");
    }

    private static String scalar(Path db, String sql) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void the_schedule_counts_from_a_full_backup_not_from_the_brainstems_copy() throws Exception {
        service.scheduledBackupTick();
        // The brainstem copies world.db before each hang restart, into the same directory.
        var brainstemCopy = dataDir.resolve("backups").resolve("brainstem.world.db.20990101-000000.bak");
        Files.writeString(brainstemCopy, "copy");
        // ...taken 29 h after the full backup, one hour before this restart.
        Files.setLastModifiedTime(brainstemCopy,
            FileTime.from(Instant.now().plus(Duration.ofHours(29))));
        var restarted = newService();
        var due = restarted.firstScheduledRunIn(24, Instant.now().plus(Duration.ofHours(30)));
        assertThat(due).as("the full backup is 30 h old; the brainstem copy does not reset the clock")
            .isEqualTo(Duration.ofMinutes(10));
    }


    @Test
    void stage_restore_validates_steward_and_snapshot_id() {
        service.backupNow(stewardId);
        var snapId = backups.listSnapshots().get(0).backupId();

        var denied = service.stageRestore(memberId, snapId);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("error")).isEqualTo("steward only");

        var missing = service.stageRestore(stewardId, "19700101-000000");
        assertThat(missing.get("ok")).isEqualTo(false);
        assertThat((String) missing.get("error")).startsWith("no such snapshot");
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE)).doesNotExist();

        var staged = service.stageRestore(stewardId, snapId);
        assertThat(staged.get("ok")).isEqualTo(true);
        assertThat(staged.get("snapshotId")).isEqualTo(snapId);
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE)).exists();

        var read = service.stagedRestore().orElseThrow();
        assertThat(read.snapshotId()).isEqualTo(snapId);
        assertThat(read.backupFile()).isNotBlank();
        assertThat(read.stagedBy()).isEqualTo(stewardId);
        assertThat(read.stagedAt()).isNotNull();
    }

    @Test
    void clear_staged_restore_is_steward_gated_and_removes_marker() {
        service.backupNow(stewardId);
        var snapId = backups.listSnapshots().get(0).backupId();
        service.stageRestore(stewardId, snapId);

        var denied = service.clearStagedRestore(memberId);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(service.stagedRestore()).isPresent();

        assertThat(service.clearStagedRestore(stewardId).get("ok")).isEqualTo(true);
        assertThat(service.stagedRestore()).isEmpty();
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE)).doesNotExist();

        var again = service.clearStagedRestore(stewardId);
        assertThat(again.get("ok")).isEqualTo(false);
        assertThat(again.get("error")).isEqualTo("no restore staged");
    }

    @Test
    void status_reads_everything_in_one_pass() {
        service.setMaintenanceMode(stewardId, true, "quiet hours");
        service.setBackupSchedule(stewardId, 12);
        service.backupNow(stewardId);
        var snapId = backups.listSnapshots().get(0).backupId();
        service.stageRestore(stewardId, snapId);

        var s = service.status();
        assertThat(s.mode().on()).isTrue();
        assertThat(s.mode().reason()).isEqualTo("quiet hours");
        assertThat(s.backupScheduleHours()).isEqualTo(12);
        assertThat(s.snapshotCount()).isEqualTo(1);
        assertThat(s.latestSnapshotId()).isEqualTo(snapId);
        assertThat(s.stagedRestore()).isPresent();
        assertThat(s.stagedRestore().get().snapshotId()).isEqualTo(snapId);
    }
}
