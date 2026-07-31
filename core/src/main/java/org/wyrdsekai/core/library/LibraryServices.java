package org.wyrdsekai.core.library;

import java.nio.file.Path;

/**
 * Process-scoped singletons for storage —
 * {@link ArrivalTable} (pending pack proposals) and {@link ReadingLog}
 * (gap-detection substrate). Initialized at zone bootstrap, queried by
 * agents and scripted items.
 *
 * <p>Both are zone-wide (one per running JVM, not per-companion), parked
 * under the zone's library data dir. {@code init()} is idempotent and
 * should be called once during {@code CoreServices} startup.</p>
 */
public final class LibraryServices {

    private static volatile ArrivalTable arrivalTable;
    private static volatile ReadingLog readingLog;

    private LibraryServices() {}

    /** Initialize both stores under the supplied root. Idempotent. */
    public static synchronized void init(Path root) {
        if (root == null) return;
        if (arrivalTable == null) arrivalTable = new ArrivalTable(root);
        if (readingLog == null) readingLog = new ReadingLog(root);
    }

    /** Returns the arrival table, or {@code null} if {@link #init(Path)} hasn't run. */
    public static ArrivalTable arrivalTable() { return arrivalTable; }

    /** Returns the reading log, or {@code null} if {@link #init(Path)} hasn't run. */
    public static ReadingLog readingLog() { return readingLog; }

    /** Test-only: clear singletons so tests can re-init. */
    public static synchronized void reset() {
        arrivalTable = null;
        readingLog = null;
    }
}
