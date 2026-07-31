package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Resume ledger for bulk document ingest. One append-only file per
 * (user, collection) under {@code <dataDir>/ingest/}; each line is
 * {@code <mtimeMillis>\t<absolute path>}. A file counts as done when its
 * recorded mtime matches the file on disk — touch/replace a book and the
 * next run re-indexes just that file.
 *
 * <p>Built for the 75k-ebook case: a crashed or interrupted
 * {@code wyrd library ingest} re-run skips everything already indexed
 * instead of re-extracting for hours. Combined with the deterministic
 * chunk ids in {@link StudyService}, re-runs are fully idempotent.</p>
 */
public final class IngestLedger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IngestLedger.class);

    private final Path file;
    private final Map<String, Long> done = new HashMap<>();
    private BufferedWriter writer;

    private IngestLedger(Path file) {
        this.file = file;
    }

    /** Open (creating if absent) the ledger for a user+collection ingest. */
    public static IngestLedger open(String userDid, String collection) throws IOException {
        var ledger = new IngestLedger(defaultDir()
            .resolve(slug(userDid) + "-" + slug(collection) + ".ledger"));
        ledger.load();
        return ledger;
    }

    /** Test seam: open a ledger at an explicit path. */
    static IngestLedger openAt(Path file) throws IOException {
        var ledger = new IngestLedger(file);
        ledger.load();
        return ledger;
    }

    private void load() throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    done.put(line.substring(tab + 1), Long.parseLong(line.substring(0, tab)));
                } catch (NumberFormatException e) {
                    log.debug("ingest ledger: skipping malformed line in {}", file);
                }
            }
        }
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** True when this file was already indexed with the same mtime. */
    public boolean isDone(Path path) {
        var recorded = done.get(path.toAbsolutePath().toString());
        if (recorded == null) return false;
        try {
            return Files.getLastModifiedTime(path).toMillis() == recorded;
        } catch (IOException e) {
            return false;
        }
    }

    /** Record a file as fully indexed. */
    public void markDone(Path path) {
        try {
            var mtime = Files.getLastModifiedTime(path).toMillis();
            var key = path.toAbsolutePath().toString();
            done.put(key, mtime);
            writer.write(mtime + "\t" + key);
            writer.newLine();
        } catch (IOException e) {
            log.warn("ingest ledger: failed to record {}: {}", path, e.getMessage());
        }
    }

    /** Flush pending entries to disk (call alongside index commits). */
    public void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            log.warn("ingest ledger: flush failed: {}", e.getMessage());
        }
    }

    public int doneCount() { return done.size(); }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("ingest ledger: close failed: {}", e.getMessage());
        }
    }

    private static Path defaultDir() {
        var base = WyrdConfig.get().dataDir();
        var home = base != null && !base.isBlank()
            ? Path.of(base)
            : Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai");
        return home.resolve("ingest");
    }

    private static String slug(String s) {
        return s == null ? "_" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
