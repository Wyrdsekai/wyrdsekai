package org.wyrdsekai.core.household;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.test.TestDb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-time application of a staged restore
 * ({@link MaintenanceService#applyStagedRestoreIfAny}): the marker written
 * by {@code stageRestore} swaps the snapshot in BEFORE the world db opens,
 * keeps the displaced db as {@code world.db.pre-restore-<ts>}, and on ANY
 * failure leaves the current db byte-for-byte in place, parking the marker
 * as {@code restore-staged.failed.json}.
 */
@Tag("integration")
class StagedRestoreBootTest {

    @TempDir
    Path tmp;

    private Path dataDir;
    private Path worldDb;
    private BackupOrchestrator backups;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tmp.resolve("data");
        Files.createDirectories(dataDir);
        worldDb = dataDir.resolve("world.db");
        backups = new BackupOrchestrator(dataDir.resolve("backups"));
    }

    private static String readValue(Path db) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT v FROM kv WHERE k = 'k'");
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private List<Path> preRestoreFiles() throws IOException {
        try (Stream<Path> files = Files.list(dataDir)) {
            return files
                .filter(p -> p.getFileName().toString().startsWith("world.db.pre-restore-"))
                .toList();
        }
    }

    @Test
    void staged_restore_swaps_snapshot_in_and_keeps_pre_restore_copy() throws Exception {
        // db-A, snapshot it, mutate to db-B.
        MaintenanceServiceTest.createSqliteDb(worldDb, "A");
        var snapshot = backups.snapshot(worldDb).orElseThrow();
        MaintenanceServiceTest.createSqliteDb(worldDb, "B");
        assertThat(readValue(worldDb)).isEqualTo("B");

        // Stage through the REAL service path (steward-gated marker write).
        var jdbcUrl = TestDb.createInMemory();
        var auth = new AuthService(jdbcUrl);
        var stewardId = auth.register("operator", "password123", "Masumi")
            .orElseThrow().userId();
        var service = new MaintenanceService(jdbcUrl, new SqlDialect.SQLite(), auth,
            backups, dataDir, worldDb, null, null, List.of());
        service.initSchema();
        var staged = service.stageRestore(stewardId, snapshot.backupId());
        assertThat(staged.get("ok")).isEqualTo(true);

        MaintenanceService.applyStagedRestoreIfAny(dataDir, worldDb);

        // The world is db-A again; the marker is consumed; db-B survives
        // as the pre-restore copy.
        assertThat(readValue(worldDb)).isEqualTo("A");
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE)).doesNotExist();
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FAILED_FILE)).doesNotExist();
        var preRestore = preRestoreFiles();
        assertThat(preRestore).hasSize(1);
        assertThat(readValue(preRestore.get(0))).isEqualTo("B");
    }

    @Test
    void failure_leaves_current_db_intact_and_parks_failed_marker() throws Exception {
        MaintenanceServiceTest.createSqliteDb(worldDb, "B");
        var before = Files.readAllBytes(worldDb);

        // Marker pointing at a snapshot file that no longer exists.
        var marker = new LinkedHashMap<String, Object>();
        marker.put("snapshotId", "20260101-000000");
        marker.put("backupFile", dataDir.resolve("backups")
            .resolve("world.db.20260101-000000.bak").toString());
        marker.put("stagedBy", "someone");
        marker.put("stagedAt", Instant.now().toString());
        Files.writeString(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE),
            Json.mapper().writeValueAsString(marker));

        MaintenanceService.applyStagedRestoreIfAny(dataDir, worldDb);

        // db-B untouched, marker consumed, failed marker carries the error.
        assertThat(Files.readAllBytes(worldDb)).isEqualTo(before);
        assertThat(readValue(worldDb)).isEqualTo("B");
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE)).doesNotExist();
        var failed = dataDir.resolve(MaintenanceService.STAGED_RESTORE_FAILED_FILE);
        assertThat(failed).exists();
        assertThat(Files.readString(failed)).contains("backup file missing");
        assertThat(preRestoreFiles()).isEmpty();
        // No temp leftovers either.
        assertThat(dataDir.resolve("world.db.restore-tmp")).doesNotExist();
    }

    @Test
    void no_marker_is_a_noop() throws Exception {
        MaintenanceServiceTest.createSqliteDb(worldDb, "B");
        var before = Files.readAllBytes(worldDb);
        MaintenanceService.applyStagedRestoreIfAny(dataDir, worldDb);
        assertThat(Files.readAllBytes(worldDb)).isEqualTo(before);
        assertThat(preRestoreFiles()).isEmpty();
    }

    @Test
    void unparseable_marker_fails_safe() throws Exception {
        MaintenanceServiceTest.createSqliteDb(worldDb, "B");
        Files.writeString(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE),
            "not json at all {");

        MaintenanceService.applyStagedRestoreIfAny(dataDir, worldDb);

        assertThat(readValue(worldDb)).isEqualTo("B");
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FILE)).doesNotExist();
        assertThat(dataDir.resolve(MaintenanceService.STAGED_RESTORE_FAILED_FILE)).exists();
    }
}
