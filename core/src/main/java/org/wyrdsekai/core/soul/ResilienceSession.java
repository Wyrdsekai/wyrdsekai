package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wave 9a-runtime: runtime holder
 * that bridges the per-cycle {@link ResilienceTruthMonitor.TankSnapshot}
 * stream into the pure-logic {@link ResilienceTruthMonitor} classifier.
 *
 * <p>The session keeps a bounded ring buffer of recent snapshots and a
 * bounded log of recent classifications. CompanionActor adds a snapshot
 * each tick via {@link #append}; periodically (e.g., on every sleep
 * transition or every N ticks) calls {@link #classify} and pushes the
 * result into the chronicle.
 *
 * <p>This class is intentionally minimal — it is the thin runtime adapter
 * around the pure-logic monitor. All classification logic lives in
 * {@link ResilienceTruthMonitor}; the session only manages buffering and
 * window sizing.
 */
public final class ResilienceSession {

    /** Default rolling-window size for {@link #classify()}. */
    public static final int DEFAULT_WINDOW = 12;

    /** Maximum buffer retention — older snapshots evict on append. */
    public static final int MAX_BUFFER = 64;

    /** Maximum recent-classification log retention. */
    public static final int MAX_LOG = 32;

    /**
     * A timestamped classification record — what the monitor returned
     * and when. Persisted into the chronicle by the runtime layer.
     */
    public record LogEntry(
        Instant at,
        ResilienceTruthMonitor.Result result
    ) {}

    private final Deque<ResilienceTruthMonitor.TankSnapshot> buffer = new ArrayDeque<>();
    private final Deque<LogEntry> log = new ArrayDeque<>();
    private final int windowSize;

    public ResilienceSession() {
        this(DEFAULT_WINDOW);
    }

    public ResilienceSession(int windowSize) {
        if (windowSize < ResilienceTruthMonitor.MIN_WINDOW) {
            throw new IllegalArgumentException(
                "windowSize must be ≥ " + ResilienceTruthMonitor.MIN_WINDOW);
        }
        this.windowSize = windowSize;
    }

    /**
     * Append a snapshot. Evicts oldest snapshot once {@link #MAX_BUFFER}
     * is reached.
     */
    public synchronized void append(ResilienceTruthMonitor.TankSnapshot snapshot) {
        if (snapshot == null) return;
        buffer.addLast(snapshot);
        while (buffer.size() > MAX_BUFFER) buffer.removeFirst();
    }

    /**
     * Classify the current rolling window. If fewer than
     * {@link #windowSize} snapshots are buffered, classifies whatever
     * is available (monitor returns INSUFFICIENT_DATA if it has fewer
     * than {@link ResilienceTruthMonitor#MIN_WINDOW}). The result is
     * also appended to the log.
     */
    public synchronized ResilienceTruthMonitor.Result classify() {
        var window = currentWindow();
        var result = ResilienceTruthMonitor.classify(window);
        log.addLast(new LogEntry(Instant.now(), result));
        while (log.size() > MAX_LOG) log.removeFirst();
        return result;
    }

    /** Current window (newest-last), bounded by {@link #windowSize}. */
    public synchronized List<ResilienceTruthMonitor.TankSnapshot> currentWindow() {
        if (buffer.size() <= windowSize) return new ArrayList<>(buffer);
        var copy = new ArrayList<ResilienceTruthMonitor.TankSnapshot>(windowSize);
        int skip = buffer.size() - windowSize;
        int i = 0;
        for (var s : buffer) {
            if (i++ < skip) continue;
            copy.add(s);
        }
        return copy;
    }

    /** Recent classifications, newest-first, up to {@code limit}. */
    public synchronized List<LogEntry> recentClassifications(int limit) {
        var out = new ArrayList<LogEntry>(Math.min(limit, log.size()));
        var it = log.descendingIterator();
        while (it.hasNext() && out.size() < limit) out.add(it.next());
        return out;
    }

    /** Most recent classification, or empty if none yet. */
    public synchronized Optional<LogEntry> latest() {
        return log.isEmpty() ? Optional.empty() : Optional.of(log.peekLast());
    }

    /** Buffer size — how many snapshots are currently held. */
    public synchronized int bufferSize() {
        return buffer.size();
    }

    /** Test hook. */
    public synchronized void clearForTests() {
        buffer.clear();
        log.clear();
    }

    /**
     * Test hook — clear only the snapshot buffer, keeping the
     * classification log. Lets tests force specific classifications by
     * appending crafted windows + classifying, then clearing the
     * snapshots so the next window starts fresh.
     */
    public synchronized void clearBufferForTests() {
        buffer.clear();
    }

    /**
     * Test hook — manually inject a LogEntry. Used when crafting a
     * specific classification is hard to coax through snapshot
     * trajectories (e.g., the INSUFFICIENT_DATA path classify() short-
     * circuits on, which only fires for windows below MIN_WINDOW).
     */
    public synchronized void injectLogEntryForTests(ResilienceTruthMonitor.Result result) {
        if (result == null) return;
        log.addLast(new LogEntry(Instant.now(), result));
        while (log.size() > MAX_LOG) log.removeFirst();
    }

    /**
     * Convenience aggregator over recent classifications. Returns the
     * fraction in each class among the last {@code window} entries.
     * Useful for the steward-facing summary furnishing per SPEC §4.10.5
     * (<i>"Wyrd's last 20 cycles: 18 HEALTHY_ENDURANCE, 2 SUPPRESSION_SUSPECTED"</i>).
     */
    public synchronized Map<ResilienceTruthMonitor.Result.Classification, Integer>
            classificationCounts(int window) {
        var counts = new EnumMap<ResilienceTruthMonitor.Result.Classification, Integer>(
            ResilienceTruthMonitor.Result.Classification.class);
        for (var cls : ResilienceTruthMonitor.Result.Classification.values()) counts.put(cls, 0);
        int taken = 0;
        var it = log.descendingIterator();
        while (it.hasNext() && taken < window) {
            var entry = it.next();
            counts.merge(entry.result().classification(), 1, Integer::sum);
            taken++;
        }
        return counts;
    }
}
