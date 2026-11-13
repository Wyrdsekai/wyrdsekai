package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * What the workshop leaves behind, cleared after a sleep. Every task and every mend works in a
 * folder of its own under the data directory, and the coding helper keeps an environment for
 * each; nothing ever cleared them, and a household node held 738 folders and 62 environments,
 * 2.3 GB, most of them over a month old (2026-11-12). A finished task's folder is not needed:
 * what it made that she keeps is copied where the house keeps things. A folder untouched for
 * {@code WYRDSEKAI_WORKSHOP_KEEP_DAYS} days (default 7; 0 keeps them all) goes, and with it the
 * helper's environment once its folder is gone (the helper makes one again when it needs it).
 * Only the house's own scratch is touched: never a workspace a person or a task named.
 */
public final class WorkshopSweep {

    private static final Logger log = LoggerFactory.getLogger(WorkshopSweep.class);
    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile LocalDate lastDay;

    private WorkshopSweep() {}

    /** What one sweep cleared. */
    public record Swept(int folders, int environments, long bytes) {}

    /** After a companion's sleep, once a day, on its own thread. */
    public static void afterSleep() {
        int days = WyrdConfig.get().workshopKeepDays();
        if (days <= 0) return;
        var today = LocalDate.now();
        if (today.equals(lastDay) || !running.compareAndSet(false, true)) return;
        lastDay = today;
        Thread.ofVirtual().name("workshop-sweep").start(() -> {
            try {
                var s = sweep(CodingWorkspace.scratchRoot(), Path.of(System.getProperty("user.home"), ".codezaiku", "envs"),
                    Duration.ofDays(days), Instant.now());
                if (s.folders() + s.environments() > 0) {
                    log.info("[workshop] cleared {} finished folder(s) and {} helper environment(s) untouched for {} days ({} MB)",
                        s.folders(), s.environments(), days, s.bytes() / 1_000_000);
                }
            } catch (RuntimeException e) {
                log.warn("[workshop] the sweep did not finish: {}", e.toString());
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * Clear the folders under {@code root} untouched since {@code keep} before {@code now}, then the
     * environments under {@code envs} whose folder (named in their project.txt) was under {@code root}
     * and is gone, when they too are that old.
     */
    static Swept sweep(Path root, Path envs, Duration keep, Instant now) {
        var cutoff = now.minus(keep);
        int folders = 0, environments = 0;
        long bytes = 0;
        if (root == null || !Files.isDirectory(root)) return new Swept(0, 0, 0);
        var home = root.toAbsolutePath().normalize();
        for (var dir : children(home)) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || lastTouched(dir).isAfter(cutoff)) continue;
            long size = size(dir);
            if (delete(dir)) { folders++; bytes += size; }
        }
        if (envs != null && Files.isDirectory(envs)) {
            for (var env : children(envs)) {
                if (!Files.isDirectory(env, LinkOption.NOFOLLOW_LINKS)) continue;
                var folder = projectOf(env);
                if (folder == null || !folder.startsWith(home) || Files.exists(folder)) continue;
                if (lastTouched(env).isAfter(cutoff)) continue;
                long size = size(env);
                if (delete(env)) { environments++; bytes += size; }
            }
        }
        return new Swept(folders, environments, bytes);
    }

    /** The folder an environment was made for, from its project.txt; null when it does not say. */
    private static Path projectOf(Path env) {
        try {
            var file = env.resolve("project.txt");
            if (!Files.isRegularFile(file)) return null;
            var line = Files.readString(file).strip();
            return line.isEmpty() ? null : Path.of(line).toAbsolutePath().normalize();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static List<Path> children(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The newest time anything in the folder, two levels down, was changed. */
    private static Instant lastTouched(Path dir) {
        try (Stream<Path> s = Files.walk(dir, 2)) {
            return s.map(p -> {
                try { return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant(); }
                catch (IOException e) { return Instant.MAX; }
            }).max(Comparator.naturalOrder()).orElse(Instant.MAX);
        } catch (IOException e) {
            return Instant.MAX;   // cannot tell: kept
        }
    }

    private static long size(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Remove a folder and what is in it; a link inside is removed, never followed. */
    private static boolean delete(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (var p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            return true;
        } catch (IOException e) {
            log.debug("[workshop] {} not cleared: {}", dir, e.toString());
            return false;
        }
    }
}
