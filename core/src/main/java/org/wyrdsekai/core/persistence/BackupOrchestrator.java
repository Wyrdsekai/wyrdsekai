package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backup orchestration (§64).
 * Scheduled snapshots of database + Lucene search indexes (including Study).
 * Recovery from snapshot.
 */
public class BackupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BackupOrchestrator.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    public record BackupManifest(
        String backupId,
        Path location,
        Instant timestamp,
        long sizeBytes,
        String source
    ) {}

    private final Path backupDir;
    private int maxSnapshots = 5;

    public BackupOrchestrator(Path backupDir) {
        this.backupDir = backupDir;
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            log.warn("Failed to create backup directory: {}", e.getMessage());
        }
    }

    /** Set maximum number of snapshots to retain. */
    public void setMaxSnapshots(int max) {
        this.maxSnapshots = max;
    }

    /**
     * Create a backup snapshot of a database file.
     *
     * <p>For SQLite databases (file extension {@code .db} or recognised
     * SQLite header), uses {@code VACUUM INTO} to produce an atomic,
     * consistent snapshot — captures all committed writes including those
     * still in the WAL. This is the only correct way to snapshot a live
     * SQLite database under {@code journal_mode=WAL}: a naive
     * {@code Files.copy} of {@code world.db} alone would miss the WAL
     * file and produce a stale or torn snapshot.
     *
     * <p>For non-SQLite sources (or when {@code VACUUM INTO} fails — e.g.
     * a missing JDBC driver), falls back to file-level copy with a
     * warning. Postgres production deploys should use {@code pg_dump}
     * via a separate path; this orchestrator targets the SQLite case.
     */
    public Optional<BackupManifest> snapshot(Path sourceDb) {
        try {
            var timestamp = Instant.now();
            var backupId = TIMESTAMP_FORMAT.format(timestamp);
            var fileName = sourceDb.getFileName().toString();
            var backupFile = backupDir.resolve(fileName + "." + backupId + ".bak");

            boolean used_vacuum = false;
            if (looksLikeSqlite(sourceDb)) {
                try {
                    sqliteVacuumInto(sourceDb, backupFile);
                    used_vacuum = true;
                } catch (Exception e) {
                    // Fall through to Files.copy. Don't lose the snapshot
                    // attempt entirely — degraded fidelity is better than
                    // none, and the warning surfaces the issue for ops.
                    log.warn("VACUUM INTO failed for {} ({}); falling back "
                        + "to Files.copy (WAL-unsafe)", sourceDb, e.getMessage());
                }
            }
            if (!used_vacuum) {
                Files.copy(sourceDb, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }

            var manifest = new BackupManifest(backupId, backupFile, timestamp,
                Files.size(backupFile), sourceDb.toString());

            log.info("Backup snapshot created: {} ({} bytes, mode={})",
                backupFile, manifest.sizeBytes(),
                used_vacuum ? "VACUUM INTO" : "file copy");

            // Prune old snapshots
            pruneSnapshots(fileName);

            return Optional.of(manifest);
        } catch (IOException e) {
            log.error("Backup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Quick sniff for SQLite: checks the magic header bytes
     * "SQLite format 3\0" (15 bytes + null). Avoids opening a JDBC
     * connection just to decide which backup path to take.
     */
    private static boolean looksLikeSqlite(Path db) {
        try {
            if (!Files.isRegularFile(db) || Files.size(db) < 16) return false;
            byte[] header = new byte[16];
            try (var in = Files.newInputStream(db)) {
                int n = in.read(header);
                if (n < 16) return false;
            }
            return header[0] == 'S' && header[1] == 'Q' && header[2] == 'L'
                && header[3] == 'i' && header[4] == 't' && header[5] == 'e';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Atomic SQLite snapshot via {@code VACUUM INTO 'path'}. Issues a
     * read transaction internally that captures all committed data
     * (including WAL contents) and writes a defragged copy to the
     * destination path. Safe to run against a live database under
     * concurrent writes — readers and writers proceed normally.
     */
    private static void sqliteVacuumInto(Path sourceDb, Path destFile) throws Exception {
        Files.deleteIfExists(destFile);
        var jdbcUrl = "jdbc:sqlite:" + sourceDb.toAbsolutePath();
        // SQL string literals require single-quote escaping; the destination
        // path is internal (under our backupDir) so injection isn't a worry,
        // but doubling apostrophes is the standard form.
        var escaped = destFile.toAbsolutePath().toString().replace("'", "''");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + escaped + "'");
        }
    }

    /**
     * Restore from a backup snapshot.
     * Copies the backup file to the target location.
     */
    public boolean restore(Path backupFile, Path targetDb) {
        try {
            if (!Files.exists(backupFile)) {
                log.error("Backup file not found: {}", backupFile);
                return false;
            }
            Files.copy(backupFile, targetDb, StandardCopyOption.REPLACE_EXISTING);
            log.info("Restored from backup: {} → {}", backupFile, targetDb);
            return true;
        } catch (IOException e) {
            log.error("Restore failed: {}", e.getMessage());
            return false;
        }
    }

    /** List available backup snapshots, most recent first. */
    public List<BackupManifest> listSnapshots() {
        try (var stream = Files.list(backupDir)) {
            return stream
                .filter(p -> p.toString().endsWith(".bak"))
                .map(this::toManifest)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(BackupManifest::timestamp).reversed())
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list snapshots: {}", e.getMessage());
            return List.of();
        }
    }

    /** Get the most recent snapshot. */
    public Optional<BackupManifest> latestSnapshot() {
        return listSnapshots().stream().findFirst();
    }

    /** Prune old snapshots, keeping only the most recent maxSnapshots. */
    private void pruneSnapshots(String baseFileName) {
        try (var stream = Files.list(backupDir)) {
            var snapshots = stream
                .filter(p -> p.getFileName().toString().startsWith(baseFileName + "."))
                .sorted(Comparator.comparingLong(p -> {
                    try { return Files.getLastModifiedTime((Path) p).toMillis(); }
                    catch (IOException e) { return 0L; }
                }).reversed())
                .collect(Collectors.toList());

            if (snapshots.size() > maxSnapshots) {
                for (int i = maxSnapshots; i < snapshots.size(); i++) {
                    Files.deleteIfExists(snapshots.get(i));
                    log.info("Pruned old snapshot: {}", snapshots.get(i));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to prune snapshots: {}", e.getMessage());
        }
    }

    /**
     * Create a full backup: database + Lucene search indexes.
     * The Lucene directory tree is copied to backups/search.{timestamp}/
     *
     * @param sourceDb   Path to the database file
     * @param searchDir  Path to the Lucene search directory (e.g., ~/.wyrdsekai/data/search/)
     * @return Combined manifest with both components
     */
    public Optional<BackupManifest> snapshotAll(Path sourceDb, Path searchDir) {
        return snapshotAll(sourceDb, searchDir, null, List.of());
    }

    /**
     * Three-arg snapshot: DB + search + node-identity.json.
     *
     * <p>Equivalent to {@link #snapshotAll(Path, Path, Path, List)} with
     * an empty extra-dirs list. Kept for callers (e.g. tests) that
     * predate the per-agent filesystem-state coverage.
     */
    public Optional<BackupManifest> snapshotAll(Path sourceDb, Path searchDir,
                                                   Path nodeIdentityFile) {
        return snapshotAll(sourceDb, searchDir, nodeIdentityFile, List.of());
    }

    /**
     * Four-arg snapshot: DB + search + node-identity.json + arbitrary
     * extra directories.
     *
     * <p>{@code nodeIdentityFile} is the household's irreplaceable Ed25519
     * keypair. Losing it means losing the zone's cryptographic identity:
     * federation peers reject the new identity as a different household,
     * existing soul manifests stop verifying, the household has to be
     * reissued from scratch with a new {@code did:key} and steward
     * re-bonding.
     *
     * <p>{@code extraDirs} is a list of directories whose entire tree is
     * worth preserving but whose contents live outside {@code world.db}
     * and the Lucene index. Concrete F7b coverage list:
     * <ul>
     *   <li>{@code agents/} — {@code FamilyLocker} state per agent:
     *     active forms, retired forms, named familiars, imprints, summon
     *     keys, forge cursor, deviation thresholds, personal projects.
     *     Irreplaceable: this is the agent's accumulated working state.
     *   <li>{@code classifiers/} — per-agent classifier event log
     *     ({@code <did>/events.jsonl}) + calibration. Bounded growth,
     *     but loss regresses the agent's learned heuristics.
     *   <li>{@code souls/} — legacy soul-manifest filesystem dir; still
     *     read/written for {@code souls/incoming/} seed drops and
     *     {@code souls/<entityId>.did} files.
     * </ul>
     *
     * <p>Each extra dir lands at {@code backupDir/<basename>.<backupId>/}
     * and is pruned to {@code maxSnapshots} on the same prefix. Missing
     * or non-directory entries are skipped silently — callers can pass
     * an "every dir we might care about" list without checking for
     * existence first.
     *
     * <p>{@code adapters/} (LoRA voice-alignment weights) is intentionally
     * <i>not</i> in the default extra-dirs list: it's large (~hundreds
     * of MB per adapter) and rebuildable from the corpus in
     * {@code world.db}. A separate retention policy will land in a
     * follow-up.
     */
    public Optional<BackupManifest> snapshotAll(Path sourceDb, Path searchDir,
                                                   Path nodeIdentityFile,
                                                   List<Path> extraDirs) {
        var timestamp = Instant.now();
        var backupId = TIMESTAMP_FORMAT.format(timestamp);
        long totalSize = 0;
        var sourceBuilder = new StringBuilder(sourceDb.toString());

        // 1. Database snapshot — uses VACUUM INTO when possible (WAL-safe).
        var dbResult = snapshot(sourceDb);
        if (dbResult.isPresent()) {
            totalSize += dbResult.get().sizeBytes();
        }

        // 2. Lucene search indexes.
        if (searchDir != null && Files.isDirectory(searchDir)) {
            var searchBackupDir = backupDir.resolve("search." + backupId);
            try {
                copyDirectoryRecursive(searchDir, searchBackupDir);
                long searchSize = directorySize(searchBackupDir);
                totalSize += searchSize;
                log.info("Backup: Lucene search indexes ({} bytes) → {}", searchSize, searchBackupDir);
                pruneByPrefix("search.", true);
                sourceBuilder.append(" + search");
            } catch (IOException e) {
                log.error("Backup: Lucene search snapshot failed: {}", e.getMessage());
            }
        }

        // 3. node-identity.json — the household's private key. Critical:
        //    without it, the zone's cryptographic identity is lost.
        if (nodeIdentityFile != null && Files.isRegularFile(nodeIdentityFile)) {
            var idBackupFile = backupDir.resolve(
                "node-identity." + backupId + ".bak");
            try {
                Files.copy(nodeIdentityFile, idBackupFile,
                    StandardCopyOption.REPLACE_EXISTING);
                long idSize = Files.size(idBackupFile);
                totalSize += idSize;
                log.info("Backup: node-identity.json ({} bytes) → {}",
                    idSize, idBackupFile);
                pruneByPrefix("node-identity.", false);
                sourceBuilder.append(" + node-identity");
            } catch (IOException e) {
                log.error("Backup: node-identity.json snapshot failed: {}",
                    e.getMessage());
            }
        }

        // 4. Extra dirs (agents/, classifiers/, souls/, ...).
        if (extraDirs != null) {
            for (Path src : extraDirs) {
                if (src == null || !Files.isDirectory(src)) continue;
                var basename = src.getFileName().toString();
                var dest = backupDir.resolve(basename + "." + backupId);
                try {
                    copyDirectoryRecursive(src, dest);
                    long size = directorySize(dest);
                    totalSize += size;
                    log.info("Backup: {} ({} bytes) → {}", basename, size, dest);
                    pruneByPrefix(basename + ".", true);
                    sourceBuilder.append(" + ").append(basename);
                } catch (IOException e) {
                    log.error("Backup: extra-dir {} snapshot failed: {}",
                        basename, e.getMessage());
                }
            }
        }

        if (dbResult.isPresent()) {
            return Optional.of(new BackupManifest(
                backupId, dbResult.get().location(), timestamp, totalSize,
                sourceBuilder.toString()));
        }
        return Optional.empty();
    }

    /**
     * Generic prune: keep the {@code maxSnapshots} most recent
     * direct-children of {@code backupDir} whose name starts with
     * {@code prefix}. {@code isDir} selects between directory entries
     * (search.X/, agents.X/) and regular files (node-identity.X.bak).
     */
    private void pruneByPrefix(String prefix, boolean isDir) {
        try (var stream = Files.list(backupDir)) {
            var snapshots = stream
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .filter(p -> isDir ? Files.isDirectory(p) : Files.isRegularFile(p))
                .sorted(Comparator.comparingLong((Path p) -> {
                    try { return Files.getLastModifiedTime(p).toMillis(); }
                    catch (IOException e) { return 0L; }
                }).reversed())
                .collect(Collectors.toList());
            if (snapshots.size() > maxSnapshots) {
                for (int i = maxSnapshots; i < snapshots.size(); i++) {
                    if (isDir) {
                        deleteRecursive(snapshots.get(i));
                    } else {
                        Files.deleteIfExists(snapshots.get(i));
                    }
                    log.info("Pruned old snapshot: {}", snapshots.get(i));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to prune snapshots with prefix {}: {}",
                prefix, e.getMessage());
        }
    }

    /**
     * Restore the Lucene search directory from a backup.
     *
     * @param searchBackupDir Path to the backed-up search directory (e.g., backups/search.20260326-120000/)
     * @param targetSearchDir Target search directory (e.g., ~/.wyrdsekai/data/search/)
     * @return true if restore succeeded
     */
    public boolean restoreSearch(Path searchBackupDir, Path targetSearchDir) {
        try {
            if (!Files.isDirectory(searchBackupDir)) {
                log.error("Search backup not found: {}", searchBackupDir);
                return false;
            }
            // Delete existing and copy backup
            deleteRecursive(targetSearchDir);
            copyDirectoryRecursive(searchBackupDir, targetSearchDir);
            log.info("Restored search indexes: {} → {}", searchBackupDir, targetSearchDir);
            return true;
        } catch (IOException e) {
            log.error("Search restore failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * List available search backup snapshots, most recent first.
     */
    public List<BackupManifest> listSearchSnapshots() {
        try (var stream = Files.list(backupDir)) {
            return stream
                .filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("search."))
                .map(p -> {
                    try {
                        var name = p.getFileName().toString();
                        var id = name.substring("search.".length());
                        return new BackupManifest(id, p,
                            Files.getLastModifiedTime(p).toInstant(),
                            directorySize(p), "search");
                    } catch (IOException e) { return null; }
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(BackupManifest::timestamp).reversed())
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list search snapshots: {}", e.getMessage());
            return List.of();
        }
    }

    /** Get the most recent search backup. */
    public Optional<BackupManifest> latestSearchSnapshot() {
        return listSearchSnapshots().stream().findFirst();
    }

    // --- File utilities ---

    private static void copyDirectoryRecursive(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            walk.forEach(src -> {
                var dst = target.resolve(source.relativize(src));
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); }
                catch (IOException ignored) {}
            });
        }
    }

    private static long directorySize(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                .sum();
        }
    }

    private Optional<BackupManifest> toManifest(Path path) {
        try {
            var name = path.getFileName().toString();
            var parts = name.split("\\.");
            var backupId = parts.length >= 3 ? parts[parts.length - 2] : "unknown";
            return Optional.of(new BackupManifest(
                backupId, path, Files.getLastModifiedTime(path).toInstant(),
                Files.size(path), ""));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
