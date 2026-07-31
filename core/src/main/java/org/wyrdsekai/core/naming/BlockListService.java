package org.wyrdsekai.core.naming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton holder for the household's {@link BlockList}.
 *
 * <p>Loaded once at bootstrap via {@link org.wyrdsekai.core.bootstrap.CoreServices#initNaming}.
 * Consulted at envelope intake — every inbound federation message goes
 * through {@link #isBlocked(String)} before any handler dispatches. Blocked
 * DIDs are silent-dropped per spec §6.5 ("silent drop — no feedback to
 * sender, avoids giving harassers calibration on effectiveness").</p>
 *
 * <p>Same null-get WARN pattern as {@code CrossZoneTellService} /
 * {@code ZoneAddressResolverService}: callers that hit {@link #get()}
 * before init see a rate-limited warning so bootstrap bugs surface in logs
 * instead of presenting as silent federation failures downstream.</p>
 */
public final class BlockListService {

    private static final Logger log = LoggerFactory.getLogger(BlockListService.class);
    private static final AtomicReference<BlockListService> INSTANCE = new AtomicReference<>();
    private static volatile Instant lastNullWarn = Instant.MIN;
    private static final Duration NULL_WARN_WINDOW = Duration.ofMinutes(1);

    // Hot-reloaded: `wyrd block`/`wyrd unblock` run as a SEPARATE process that
    // rewrites the file on disk (2026-07-18). Boot-once hydration meant the live
    // server kept accepting a freshly-blocked DID's federation traffic until a
    // full restart — a safety control that silently no-op'd. We re-stat the file
    // on each check and reload when its mtime moves.
    private volatile BlockList blocks;
    private final Path file;
    private volatile long loadedMtime;

    private BlockListService(BlockList blocks, Path file) {
        this.blocks = blocks;
        this.file = file;
        this.loadedMtime = currentMtime(file);
    }

    private static long currentMtime(Path file) {
        try {
            return Files.exists(file)
                ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Reload from disk if the file changed since we last read it. Cheap stat on
     *  the hot path; a real reload only on an actual block/unblock. */
    private void reloadIfChanged() {
        long m = currentMtime(file);
        if (m == loadedMtime) return;
        synchronized (this) {
            if (m == loadedMtime) return;   // double-check under lock
            try {
                blocks = BlockList.load(file);
                loadedMtime = m;
                log.info("BlockListService: reloaded {} — {} entries (out-of-band change)",
                    file, blocks.size());
            } catch (IOException e) {
                log.warn("BlockListService: reload of {} failed, keeping current list: {}",
                    file, e.getMessage());
                loadedMtime = m;   // don't spin on a broken file every call
            }
        }
    }

    /**
     * Initialise the singleton. Loads {@code dataDir/blocks} (empty if
     * missing). Idempotent — subsequent calls are no-ops until
     * {@link #resetForTests()}.
     */
    public static synchronized void init(Path dataDir) {
        if (INSTANCE.get() != null) return;
        var blocksFile = dataDir.resolve("blocks");
        try {
            var blocks = BlockList.load(blocksFile);
            INSTANCE.set(new BlockListService(blocks, blocksFile));
            log.info("BlockListService initialised: {} entries loaded from {}",
                blocks.size(), blocksFile);
        } catch (IOException e) {
            // Don't fail bootstrap on a malformed blocks file — better to boot
            // with an empty list than refuse to start. Log LOUDLY so the
            // operator fixes it. Federation stays open to everyone in the
            // interim, which may be unsafe but is visibly so.
            log.error("BlockListService: failed to load {}: {}. Starting with EMPTY blocklist. "
                + "Review/fix the file and restart to re-enable enforcement.",
                blocksFile, e.getMessage());
            INSTANCE.set(new BlockListService(BlockList.empty(blocksFile), blocksFile));
        }
    }

    /**
     * @return the singleton, or {@code null} if {@link #init} hasn't run.
     *     Rate-limited WARN on first null access per window.
     */
    public static BlockListService get() {
        var inst = INSTANCE.get();
        if (inst == null) warnUninitialised();
        return inst;
    }

    private static synchronized void warnUninitialised() {
        var now = Instant.now();
        if (now.isAfter(lastNullWarn.plus(NULL_WARN_WINDOW))) {
            log.warn("BlockListService.get() called before init — blocklist enforcement is OFF. "
                + "Ensure CoreServices.initNaming() runs at startup.");
            lastNullWarn = now;
        }
    }

    public static synchronized void resetForTests() {
        INSTANCE.set(null);
        lastNullWarn = Instant.MIN;
    }

    /**
     * Fast check at envelope intake. {@code src} is the DID from the
     * envelope; if blocked, caller MUST silent-drop without dispatching.
     * Handles null/empty gracefully (unblocked). Short-circuits to false
     * if service is in a bad state.
     */
    public boolean isBlocked(String src) {
        if (src == null || src.isBlank()) return false;
        reloadIfChanged();
        return blocks.contains(src);
    }

    /** Direct access to the underlying {@link BlockList} for admin ops (CLI). */
    public BlockList blockList() {
        reloadIfChanged();
        return blocks;
    }

    /** Persist any in-memory changes to disk atomically. */
    public void save() throws IOException {
        blocks.save();
    }
}
