package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.household.MaintenanceService;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.test.TestDb;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code world.maintenance} surfaces for the maintenance dial + key chest —
 * mirrors {@link ParentalControlPanelApiTest}: (a) safe-error provider
 * defaults, (b) {@link HomeOwnerItemProvider} wired against the real
 * service with the ACTING player's id routed as caller (steward-only
 * writes verified both ways, status readable by anyone), and (c) an
 * end-to-end GraalJS script through {@link ItemScriptExecutor}.
 */
@Tag("integration")
class MaintenancePanelApiTest {

    @TempDir
    Path tmp;

    private AuthService auth;
    private MaintenanceService maintenance;
    private BackupOrchestrator backups;
    private Path dataDir;
    private String stewardId;
    private String memberId;

    @BeforeEach
    void setUp() throws Exception {
        var jdbcUrl = TestDb.createInMemory();
        auth = new AuthService(jdbcUrl);
        // First registered user auto-becomes steward; second is a member.
        stewardId = auth.register("operator", "password123", "Operator").orElseThrow().userId();
        memberId = auth.register("kaz", "password123", "Kaz").orElseThrow().userId();

        dataDir = tmp.resolve("data");
        Files.createDirectories(dataDir);
        var worldDb = dataDir.resolve("world.db");
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + worldDb);
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)");
            stmt.execute("INSERT INTO kv(k, v) VALUES('k', 'A')");
        }
        backups = new BackupOrchestrator(dataDir.resolve("backups"));
        maintenance = new MaintenanceService(jdbcUrl, new SqlDialect.SQLite(), auth,
            backups, dataDir, worldDb, null, null, List.of());
        maintenance.initSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        maintenance.stopSchedule();
        if (executor != null) executor.close();
    }

    private HomeOwnerItemProvider providerFor(String playerId) {
        return new HomeOwnerItemProvider("zone", "zone", playerId, null, null)
            .withAuth(auth)
            .withMaintenance(maintenance);
    }

    // ─── (a) defaults are safe errors — never throw into the script ─────

    @Test
    void visitor_defaults_are_safe_errors() {
        var p = new VisitorItemProvider("zone", "zone");
        assertEquals(false, p.maintenanceStatus().get("ok"));
        assertEquals(false, p.maintenanceSetMode(true, "x").get("ok"));
        assertEquals(false, p.maintenanceBackupNow().get("ok"));
        assertEquals(false, p.maintenanceSetSchedule(6).get("ok"));
        assertEquals(false, p.maintenanceStageRestore("id").get("ok"));
        assertEquals(false, p.maintenanceClearStagedRestore().get("ok"));
        assertNotNull(p.maintenanceStatus().get("error"));
    }

    @Test
    void unwired_home_owner_provider_degrades_gracefully() {
        var p = new HomeOwnerItemProvider("zone", "zone", stewardId, null, null);
        assertEquals(false, p.maintenanceStatus().get("ok"));
        assertEquals(false, p.maintenanceSetMode(true, "x").get("ok"));
        assertEquals(false, p.maintenanceBackupNow().get("ok"));
        assertEquals(false, p.maintenanceStageRestore("id").get("ok"));
    }

    // ─── (b) wired provider — steward writes, member denials, shapes ────

    @Test
    void status_shape_is_complete_and_readable_by_member() {
        var member = providerFor(memberId);
        var s = member.maintenanceStatus();
        assertEquals(true, s.get("ok"));
        assertEquals(false, s.get("on"));
        assertEquals(0, s.get("scheduleHours"));
        assertEquals(0, s.get("snapshotCount"));
        assertNull(s.get("latestSnapshotId"));
        assertNull(s.get("staged"));
        assertTrue(s.containsKey("lastScheduledBackup"));
    }

    @Test
    void steward_flips_mode_and_status_reflects_it() {
        var steward = providerFor(stewardId);
        var res = steward.maintenanceSetMode(true, "disk swap");
        assertEquals(true, res.get("ok"));
        assertEquals(true, res.get("on"));
        assertEquals("disk swap", res.get("reason"));
        assertEquals("operator", res.get("setBy"));
        assertNotNull(res.get("since"));

        assertEquals(true, steward.maintenanceSetMode(false, null).get("ok"));
        assertEquals(false, steward.maintenanceStatus().get("on"));
    }

    @Test
    void member_writes_are_denied() {
        var member = providerFor(memberId);
        var denied = member.maintenanceSetMode(true, "nope");
        assertEquals(false, denied.get("ok"));
        assertEquals("steward only", denied.get("error"));
        assertEquals(false, maintenance.maintenanceMode().on());

        assertEquals("steward only", member.maintenanceBackupNow().get("error"));
        assertEquals("steward only", member.maintenanceSetSchedule(6).get("error"));
        assertEquals("steward only", member.maintenanceStageRestore("x").get("error"));
        assertEquals("steward only", member.maintenanceClearStagedRestore().get("error"));
    }

    @Test
    void backup_now_creates_snapshot_and_counts_in_status() {
        var steward = providerFor(stewardId);
        var res = steward.maintenanceBackupNow();
        assertEquals(true, res.get("ok"));
        assertNotNull(res.get("id"));
        assertEquals(1, backups.listSnapshots().size());
        assertEquals(1, steward.maintenanceStatus().get("snapshotCount"));
        assertEquals(res.get("id"), steward.maintenanceStatus().get("latestSnapshotId"));
    }

    @Test
    void schedule_set_and_validated() {
        var steward = providerFor(stewardId);
        var res = steward.maintenanceSetSchedule(6);
        assertEquals(true, res.get("ok"));
        assertEquals(6, res.get("scheduleHours"));
        assertEquals(6, steward.maintenanceStatus().get("scheduleHours"));

        assertEquals(false, steward.maintenanceSetSchedule(-2).get("ok"));
        assertEquals(true, steward.maintenanceSetSchedule(0).get("ok"));
        assertEquals(0, steward.maintenanceStatus().get("scheduleHours"));
    }

    @Test
    void stage_restore_round_trip_with_validation() {
        var steward = providerFor(stewardId);
        var missing = steward.maintenanceStageRestore("19700101-000000");
        assertEquals(false, missing.get("ok"));
        assertTrue(((String) missing.get("error")).startsWith("no such snapshot"));

        var snapId = (String) steward.maintenanceBackupNow().get("id");
        var staged = steward.maintenanceStageRestore(snapId);
        assertEquals(true, staged.get("ok"));
        assertEquals(snapId, staged.get("snapshotId"));

        @SuppressWarnings("unchecked")
        var stagedView = (Map<String, Object>) steward.maintenanceStatus().get("staged");
        assertNotNull(stagedView);
        assertEquals(snapId, stagedView.get("snapshotId"));
        assertEquals("operator", stagedView.get("stagedBy"));

        assertEquals(true, steward.maintenanceClearStagedRestore().get("ok"));
        assertNull(steward.maintenanceStatus().get("staged"));
        assertEquals("no restore staged",
            steward.maintenanceClearStagedRestore().get("error"));
    }

    // ─── (c) end-to-end: GraalJS script → world.maintenance ─────────────

    private ItemScriptExecutor executor;

    @Test
    void script_mode_and_status_end_to_end_with_wired_service() {
        executor = new ItemScriptExecutor();
        var res = executor.execute("maintenance_dial", """
            function invoke(p) {
              var set = world.maintenance.setMode(true, p.reason);
              if (!set.ok) return set;
              var s = world.maintenance.status();
              return {ok: true, on: s.on, reason: s.reason};
            }
            """,
            Map.of("reason", "quiet hours"),
            providerFor(stewardId));
        assertEquals(true, res.get("ok"));
        assertEquals(true, res.get("on"));
        assertEquals("quiet hours", res.get("reason"));
        assertTrue(maintenance.maintenanceMode().on());
    }

    @Test
    void script_write_denied_for_member_end_to_end() {
        executor = new ItemScriptExecutor();
        var res = executor.execute("maintenance_dial", """
            function invoke(p) {
              return world.maintenance.setMode(true, "nope");
            }
            """,
            Map.of(),
            providerFor(memberId));
        assertEquals(false, res.get("ok"));
        assertEquals("steward only", res.get("error"));
        assertEquals(false, maintenance.maintenanceMode().on());
    }
}
