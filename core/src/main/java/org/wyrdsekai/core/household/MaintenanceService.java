package org.wyrdsekai.core.household;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.ConfigApplyCoordinator;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Household maintenance — maintenance mode, backup now / backup schedule,
 * and staged restore (the promises of the Study's maintenance dial and
 * key chest, now enforced by the substrate).
 * <p>
 * Same shape as {@link ParentalControlService}: a JDBC-backed state table
 * ({@code maintenance_state}), writes steward-gated at the service (the
 * caller's role verified via the injected {@link AuthService}), and a
 * {@link #init}/{@link #get()}/{@link #resetForTests()} singleton that
 * enforcement points MUST treat as ALLOW when {@code null} — tests and
 * bare boots that never call {@code init} see no behavior change.
 * <p>
 * Backups delegate to the boot {@link BackupOrchestrator}; a restore is
 * never applied to the live database. Instead {@link #stageRestore} drops
 * a {@code restore-staged.json} marker in the data dir and the NEXT boot
 * applies it via {@link #applyStagedRestoreIfAny} before the world db is
 * opened — restore-to-temp first, atomic swap only on success, the
 * displaced db kept as {@code world.db.pre-restore-<ts>}.
 */
public final class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    /** Marker file staged by {@link #stageRestore}, consumed at next boot. */
    public static final String STAGED_RESTORE_FILE = "restore-staged.json";
    /** Where a failed boot-time restore parks the marker (with the error). */
    public static final String STAGED_RESTORE_FAILED_FILE = "restore-staged.failed.json";

    private static final DateTimeFormatter TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    // maintenance_state keys
    private static final String K_MODE = "maintenance_mode";
    private static final String K_REASON = "maintenance_reason";
    private static final String K_SET_BY = "maintenance_set_by";
    private static final String K_SINCE = "maintenance_since";
    private static final String K_SCHEDULE_HOURS = "backup_schedule_hours";
    private static final String K_LAST_SCHEDULED = "last_scheduled_backup";

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private final AuthService authService;
    private final BackupOrchestrator backups;
    private final Path dataDir;
    private final Path worldDb;
    private final Path searchDir;          // nullable — falls back to plain snapshot
    private final Path nodeIdentityFile;   // nullable
    private final List<Path> extraBackupDirs;

    private volatile ScheduledExecutorService backupTicker;

    /** Current maintenance mode. {@code since} is when it was last flipped on. */
    public record Mode(boolean on, String reason, String setBy, Instant since) {}

    /** A restore staged for the next boot (contents of the marker file). */
    public record StagedRestore(String snapshotId, String backupFile,
                                String stagedBy, Instant stagedAt) {}

    /** One status() read — everything the maintenance dial shows. */
    public record Status(Mode mode, int backupScheduleHours, Instant lastScheduledBackup,
                         int snapshotCount, String latestSnapshotId,
                         Instant latestSnapshotAt, Optional<StagedRestore> stagedRestore) {}

    public MaintenanceService(String jdbcUrl, SqlDialect dialect, AuthService authService,
                              BackupOrchestrator backups, Path dataDir, Path worldDb,
                              Path searchDir, Path nodeIdentityFile,
                              List<Path> extraBackupDirs) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
        this.authService = authService;
        this.backups = backups;
        this.dataDir = dataDir;
        this.worldDb = worldDb;
        this.searchDir = searchDir;
        this.nodeIdentityFile = nodeIdentityFile;
        this.extraBackupDirs = extraBackupDirs == null ? List.of() : List.copyOf(extraBackupDirs);
    }

    // ─── Singleton accessor (same pattern as ParentalControlService) ─────

    private static volatile MaintenanceService INSTANCE;

    /**
     * Build, initialize schema, re-arm the persisted backup schedule, and
     * register the singleton. Called once at boot from Main. Enforcement
     * points that see {@code get() == null} treat every check as ALLOW.
     */
    public static MaintenanceService init(String jdbcUrl, SqlDialect dialect,
                                          AuthService authService, BackupOrchestrator backups,
                                          Path dataDir, Path worldDb, Path searchDir,
                                          Path nodeIdentityFile, List<Path> extraBackupDirs) {
        var svc = new MaintenanceService(jdbcUrl, dialect, authService, backups,
            dataDir, worldDb, searchDir, nodeIdentityFile, extraBackupDirs);
        svc.initSchema();
        svc.armSchedule(svc.backupScheduleHours());
        INSTANCE = svc;
        return svc;
    }

    /** Returns the registered instance, or {@code null} when maintenance isn't wired. */
    public static MaintenanceService get() {
        return INSTANCE;
    }

    /** Test hook — drop the singleton (and its scheduler) so other tests see a bare world. */
    public static void resetForTests() {
        var svc = INSTANCE;
        INSTANCE = null;
        if (svc != null) svc.stopSchedule();
    }

    /** Initialize the state table. Idempotent (CREATE TABLE IF NOT EXISTS). */
    public void initSchema() {
        try (var conn = getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS maintenance_state("
                + "key TEXT PRIMARY KEY, "
                + "value TEXT)");
            log.debug("Maintenance schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize maintenance schema", e);
        }
    }

    // ─── Maintenance mode ─────────────────────────────────────────────────

    /** The current maintenance mode (off with empty fields when never set). */
    public Mode maintenanceMode() {
        var on = "on".equals(getState(K_MODE));
        var reason = getState(K_REASON);
        var setBy = getState(K_SET_BY);
        var since = parseInstant(getState(K_SINCE));
        return new Mode(on, reason == null ? "" : reason, setBy, since);
    }

    /**
     * Flip maintenance mode. Steward-only: returns false when
     * {@code callerUserId} does not hold the steward role.
     */
    public boolean setMaintenanceMode(String callerUserId, boolean on, String reason) {
        if (!isSteward(callerUserId)) {
            log.warn("maintenance setMode denied — caller {} is not steward", callerUserId);
            return false;
        }
        setState(K_MODE, on ? "on" : "off");
        setState(K_REASON, on && reason != null ? reason.trim() : "");
        setState(K_SET_BY, callerUserId);
        setState(K_SINCE, Long.toString(Instant.now().getEpochSecond()));
        log.info("Maintenance mode {} by {}{}", on ? "ON" : "OFF", callerUserId,
            on && reason != null && !reason.isBlank() ? " — " + reason : "");
        return true;
    }

    /**
     * Login enforcement: true when the world is open to this user. Mode off
     * → everyone; mode on → steward only. Surfaces call this ADJACENT to the
     * parental time-limit gate and must ALLOW when {@link #get()} is null.
     */
    public boolean allowsLogin(String userId) {
        if (!maintenanceMode().on()) return true;
        return isSteward(userId);
    }

    /** The kind refusal line the login gates show a non-steward during maintenance. */
    public String refusalLine() {
        var reason = maintenanceMode().reason();
        if (reason == null || reason.isBlank()) {
            return "The household is under maintenance. The steward may still enter.";
        }
        return "The household is under maintenance — " + reason
            + ". The steward may still enter.";
    }

    // ─── Backups ──────────────────────────────────────────────────────────

    /**
     * Run a backup right now. Steward-only. Returns the manifest map
     * ({@code ok, id, location, timestamp, sizeBytes, source}) or
     * {@code ok:false} with an {@code error}.
     */
    public Map<String, Object> backupNow(String callerUserId) {
        if (!isSteward(callerUserId)) {
            log.warn("maintenance backupNow denied — caller {} is not steward", callerUserId);
            return Map.of("ok", false, "error", "steward only");
        }
        var manifest = runBackup();
        if (manifest.isEmpty()) {
            return Map.of("ok", false, "error", "backup failed — see server log");
        }
        return manifestMap(manifest.get());
    }

    /**
     * Set the scheduled-backup cadence in hours ({@code 0} = off).
     * Steward-only. Persisted, and re-armed from the persisted value on
     * {@link #init}. Returns false on denial or a negative value.
     */
    public boolean setBackupSchedule(String callerUserId, int hours) {
        if (!isSteward(callerUserId)) {
            log.warn("maintenance setBackupSchedule denied — caller {} is not steward",
                callerUserId);
            return false;
        }
        if (hours < 0) return false;
        setState(K_SCHEDULE_HOURS, Integer.toString(hours));
        armSchedule(hours);
        log.info("Backup schedule set to {} by {}",
            hours == 0 ? "off" : "every " + hours + "h", callerUserId);
        return true;
    }

    /** The persisted schedule cadence in hours ({@code 0} = off). */
    public int backupScheduleHours() {
        var v = getState(K_SCHEDULE_HOURS);
        if (v == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** When the scheduler last fired a snapshot, or null when it never has. */
    public Instant lastScheduledBackup() {
        return parseInstant(getState(K_LAST_SCHEDULED));
    }

    /** Everything the maintenance dial shows in one read. */
    public Status status() {
        var latest = backups.latestSnapshot();
        return new Status(
            maintenanceMode(),
            backupScheduleHours(),
            lastScheduledBackup(),
            backups.listSnapshots().size(),
            latest.map(BackupOrchestrator.BackupManifest::backupId).orElse(null),
            latest.map(BackupOrchestrator.BackupManifest::timestamp).orElse(null),
            stagedRestore());
    }

    /** Snapshot listing pass-through (key chest). */
    public List<BackupOrchestrator.BackupManifest> listSnapshots() {
        return backups.listSnapshots();
    }

    private Optional<BackupOrchestrator.BackupManifest> runBackup() {
        try {
            if (searchDir != null && Files.isDirectory(searchDir)) {
                return backups.snapshotAll(worldDb, searchDir, nodeIdentityFile,
                    extraBackupDirs);
            }
            return backups.snapshot(worldDb);
        } catch (RuntimeException e) {
            log.error("maintenance backup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> manifestMap(BackupOrchestrator.BackupManifest m) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("id", m.backupId());
        out.put("location", m.location() != null ? m.location().toString() : null);
        out.put("timestamp", m.timestamp() != null ? m.timestamp().toString() : null);
        out.put("sizeBytes", m.sizeBytes());
        out.put("source", m.source());
        return out;
    }

    // ─── Scheduled backups (daemon, EmbeddingService recycle shape) ───────

    /** (Re)arm the schedule: cancel any running ticker, start a new one when hours > 0. */
    private synchronized void armSchedule(int hours) {
        stopSchedule();
        if (hours <= 0) return;
        var exec = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "maintenance-backup-schedule");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(this::scheduledBackupTick, hours, hours, TimeUnit.HOURS);
        backupTicker = exec;
        log.info("Maintenance backup schedule armed (every {}h)", hours);
    }

    /** Stop the schedule ticker (shutdown / tests / schedule=off). */
    public synchronized void stopSchedule() {
        if (backupTicker != null) {
            backupTicker.shutdownNow();
            backupTicker = null;
        }
    }

    /** One scheduler pass — package-visible so tests can drive it without the clock. */
    void scheduledBackupTick() {
        try {
            var manifest = runBackup();
            setState(K_LAST_SCHEDULED, Long.toString(Instant.now().getEpochSecond()));
            manifest.ifPresentOrElse(
                m -> log.info("Scheduled backup complete: {} ({} bytes)",
                    m.backupId(), m.sizeBytes()),
                () -> log.warn("Scheduled backup produced no snapshot"));
        } catch (RuntimeException e) {
            log.warn("Scheduled backup tick failed: {}", e.getMessage());
        }
    }

    // ─── Staged restore ───────────────────────────────────────────────────

    /**
     * Stage a snapshot restore for the NEXT boot. Steward-only. Validates
     * the id against {@link BackupOrchestrator#listSnapshots()} and writes
     * the {@code restore-staged.json} marker into the data dir. Nothing
     * touches the live database until {@link #applyStagedRestoreIfAny}
     * runs at boot; the caller narrates the restart step (Scroll of
     * Settings apply / {@link #requestRestart()}).
     */
    public Map<String, Object> stageRestore(String callerUserId, String snapshotId) {
        if (!isSteward(callerUserId)) {
            log.warn("maintenance stageRestore denied — caller {} is not steward", callerUserId);
            return Map.of("ok", false, "error", "steward only");
        }
        if (snapshotId == null || snapshotId.isBlank()) {
            return Map.of("ok", false, "error", "snapshot id required");
        }
        var match = backups.listSnapshots().stream()
            .filter(s -> snapshotId.equals(s.backupId()))
            .findFirst();
        if (match.isEmpty()) {
            return Map.of("ok", false, "error", "no such snapshot: " + snapshotId);
        }
        var staged = new StagedRestore(snapshotId,
            match.get().location().toAbsolutePath().toString(),
            callerUserId, Instant.now());
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(STAGED_RESTORE_FILE),
                Json.mapper().writeValueAsString(markerMap(staged)));
        } catch (Exception e) {
            log.error("stageRestore({}) marker write failed: {}", snapshotId, e.getMessage());
            return Map.of("ok", false, "error", "could not stage restore: " + e.getMessage());
        }
        log.warn("RESTORE STAGED: snapshot {} ({}) by {} — applies at next restart",
            snapshotId, staged.backupFile(), callerUserId);
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("snapshotId", staged.snapshotId());
        out.put("backupFile", staged.backupFile());
        out.put("stagedBy", staged.stagedBy());
        out.put("stagedAt", staged.stagedAt().toString());
        return out;
    }

    /** Remove a staged restore before it applies. Steward-only. */
    public Map<String, Object> clearStagedRestore(String callerUserId) {
        if (!isSteward(callerUserId)) {
            log.warn("maintenance clearStagedRestore denied — caller {} is not steward",
                callerUserId);
            return Map.of("ok", false, "error", "steward only");
        }
        var marker = dataDir.resolve(STAGED_RESTORE_FILE);
        try {
            if (!Files.deleteIfExists(marker)) {
                return Map.of("ok", false, "error", "no restore staged");
            }
        } catch (IOException e) {
            return Map.of("ok", false, "error", "could not clear staged restore: "
                + e.getMessage());
        }
        log.info("Staged restore cleared by {}", callerUserId);
        return Map.of("ok", true);
    }

    /** The currently staged restore, if any (open read — it shows in status). */
    public Optional<StagedRestore> stagedRestore() {
        return readMarker(dataDir.resolve(STAGED_RESTORE_FILE));
    }

    /**
     * Request the same restart-to-apply mechanism the Scroll of Settings
     * uses ({@link ConfigApplyCoordinator}). Honors the
     * {@code wyrdsekai.configApply.disableExit} system property, so tests
     * never die.
     */
    public void requestRestart() {
        ConfigApplyCoordinator.requestRestart("maintenance: restart requested "
            + "(apply staged restore / maintenance dial)");
    }

    /**
     * Boot-time application of a staged restore. Called from Main BEFORE
     * the world db is opened / SchemaInitializer runs; the absence of the
     * marker costs one file-stat.
     * <p>
     * Semantics: restore the snapshot to a TEMP file first; only when that
     * succeeds is the live db renamed to {@code <db>.pre-restore-<ts>} and
     * the temp file atomically moved into place (stale {@code -wal}/
     * {@code -shm} siblings are removed so the pre-restore WAL can't
     * corrupt the fresh copy). On ANY failure the original db is left in
     * place (moved back if the swap half-completed) and the marker is
     * renamed to {@code restore-staged.failed.json} with the error, so a
     * broken staging can never boot-loop the household.
     */
    public static void applyStagedRestoreIfAny(Path dataDir, Path worldDb) {
        var marker = dataDir.resolve(STAGED_RESTORE_FILE);
        if (!Files.exists(marker)) return;

        var staged = readMarker(marker);
        if (staged.isEmpty()) {
            failStagedRestore(dataDir, marker, "unparseable marker file");
            return;
        }
        var backupFile = Path.of(staged.get().backupFile());
        var tmp = worldDb.resolveSibling(worldDb.getFileName() + ".restore-tmp");
        log.warn("STAGED RESTORE FOUND: applying snapshot {} ({}) staged by {} at {}",
            staged.get().snapshotId(), backupFile,
            staged.get().stagedBy(), staged.get().stagedAt());
        try {
            Files.deleteIfExists(tmp);
            if (!Files.isRegularFile(backupFile)) {
                failStagedRestore(dataDir, marker, "backup file missing: " + backupFile);
                return;
            }
            // Restore into the temp file first — the live db stays untouched
            // until the copy has fully succeeded.
            var orchestrator = new BackupOrchestrator(dataDir.resolve("backups"));
            if (!orchestrator.restore(backupFile, tmp)) {
                Files.deleteIfExists(tmp);
                failStagedRestore(dataDir, marker, "restore copy failed: " + backupFile);
                return;
            }
            Path preRestore = null;
            if (Files.exists(worldDb)) {
                preRestore = worldDb.resolveSibling(
                    worldDb.getFileName() + ".pre-restore-" + TS_FORMAT.format(Instant.now()));
                Files.move(worldDb, preRestore);
            }
            try {
                try {
                    Files.move(tmp, worldDb, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, worldDb);
                }
            } catch (IOException swapFailed) {
                // Put the original back — the household must boot on its old db.
                if (preRestore != null && !Files.exists(worldDb)) {
                    try {
                        Files.move(preRestore, worldDb);
                    } catch (IOException e2) {
                        log.error("CRITICAL: could not move {} back to {} after failed "
                            + "restore swap: {}", preRestore, worldDb, e2.getMessage());
                    }
                }
                Files.deleteIfExists(tmp);
                failStagedRestore(dataDir, marker, "swap failed: " + swapFailed.getMessage());
                return;
            }
            // Stale WAL/SHM from the displaced db would corrupt the restored
            // copy on first open — the snapshot (VACUUM INTO) is self-contained.
            Files.deleteIfExists(worldDb.resolveSibling(worldDb.getFileName() + "-wal"));
            Files.deleteIfExists(worldDb.resolveSibling(worldDb.getFileName() + "-shm"));
            Files.deleteIfExists(marker);
            log.warn("STAGED RESTORE APPLIED: {} → {} (displaced db kept at {})",
                backupFile, worldDb, preRestore != null ? preRestore : "<none — fresh db>");
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort tmp cleanup; the failed marker below still lands
            }
            failStagedRestore(dataDir, marker, e.toString());
        }
    }

    /** Failure path: park the marker as restore-staged.failed.json with the error. */
    private static void failStagedRestore(Path dataDir, Path marker, String error) {
        log.error("STAGED RESTORE FAILED — original db left in place: {}", error);
        try {
            Map<String, Object> body;
            try {
                body = Json.mapper().readValue(Files.readString(marker),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception unparseable) {
                body = new LinkedHashMap<>();
            }
            body.put("error", error);
            body.put("failedAt", Instant.now().toString());
            Files.writeString(dataDir.resolve(STAGED_RESTORE_FAILED_FILE),
                Json.mapper().writeValueAsString(body));
            Files.deleteIfExists(marker);
        } catch (Exception e) {
            log.error("Could not park failed restore marker: {}", e.getMessage());
        }
    }

    private static Map<String, Object> markerMap(StagedRestore s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("snapshotId", s.snapshotId());
        m.put("backupFile", s.backupFile());
        m.put("stagedBy", s.stagedBy());
        m.put("stagedAt", s.stagedAt().toString());
        return m;
    }

    private static Optional<StagedRestore> readMarker(Path marker) {
        if (!Files.exists(marker)) return Optional.empty();
        try {
            var m = Json.mapper().readValue(Files.readString(marker),
                new TypeReference<Map<String, Object>>() {});
            return Optional.of(new StagedRestore(
                str(m.get("snapshotId")), str(m.get("backupFile")),
                str(m.get("stagedBy")), parseInstantIso(str(m.get("stagedAt")))));
        } catch (Exception e) {
            log.warn("Unreadable staged-restore marker {}: {}", marker, e.getMessage());
            return Optional.empty();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Instant parseInstantIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── internals ────────────────────────────────────────────────────────

    private boolean isSteward(String callerUserId) {
        if (callerUserId == null || authService == null) return false;
        try {
            return authService.findUser(callerUserId)
                .map(u -> "steward".equals(u.role()))
                .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static Instant parseInstant(String epochSeconds) {
        if (epochSeconds == null || epochSeconds.isBlank()) return null;
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getState(String key) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT value FROM maintenance_state WHERE key = ?")) {
            stmt.setString(1, key);
            var rs = stmt.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (SQLException e) {
            log.warn("maintenance getState({}) failed: {}", key, e.getMessage());
        }
        return null;
    }

    private void setState(String key, String value) {
        try (var conn = getConnection()) {
            var sql = dialect.upsert("maintenance_state",
                "key, value", "?,?", "key", "value = EXCLUDED.value");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                stmt.setString(2, value);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to persist maintenance state " + key, e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
