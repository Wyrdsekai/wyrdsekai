package org.wyrdsekai.core.inference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The line for one brain, shared by the beings of a household. Pure.
 *
 * <p>The router's line was a priority queue on one number: no order within a band (the Java queue
 * breaks ties as it likes), a companion's own-time turn in the same band as a person's, nothing
 * between beings, and nothing that stops a request waiting for good. On the household node two
 * companions share one model (audit of 2026-10-03; operator: "Router fairness - we should do this now").
 *
 * <p>The rule, in order: the band (a person's turn before a tool's before a companion's own time),
 * aged so that a long wait lifts a request one band at a time; then the being served longest ago;
 * then the order of arrival.
 */
public final class FairQueue<T> {

    /** One waiting request. */
    public record Entry<T>(int priority, long seq, String being, long enqueuedAtMs, T item) {}

    /** How long a request waits before it counts as one band more urgent. */
    public static final long AGING_MS = 60_000L;

    private final List<Entry<T>> entries = new ArrayList<>();
    private final Map<String, Long> lastServed = new HashMap<>();
    private long seq;
    private long served;

    public void add(int priority, String being, long nowMs, T item) {
        entries.add(new Entry<>(priority, ++seq, being == null ? "" : being, nowMs, item));
    }

    public int size() { return entries.size(); }
    public boolean isEmpty() { return entries.isEmpty(); }

    /** The request to serve now, taken from the line; null when the line is empty. */
    public T poll(long nowMs) {
        var e = peek(nowMs);
        if (e == null) return null;
        entries.remove(e);
        lastServed.put(e.being(), ++served);
        return e.item();
    }

    /** The request that would be served now, left in the line. */
    public Entry<T> peek(long nowMs) {
        Entry<T> best = null;
        for (var e : entries) {
            if (best == null || before(e, best, nowMs)) best = e;
        }
        return best;
    }

    /** The band a request stands in now: its own, less one for each full wait. Never below the first. */
    int effectivePriority(Entry<T> e, long nowMs) {
        long waited = Math.max(0, nowMs - e.enqueuedAtMs());
        return (int) Math.max(0, e.priority() - waited / AGING_MS);
    }

    private boolean before(Entry<T> a, Entry<T> b, long nowMs) {
        int pa = effectivePriority(a, nowMs);
        int pb = effectivePriority(b, nowMs);
        if (pa != pb) return pa < pb;
        long sa = lastServed.getOrDefault(a.being(), 0L);
        long sb = lastServed.getOrDefault(b.being(), 0L);
        if (sa != sb) return sa < sb;     // the being served longest ago, or never, first
        return a.seq() < b.seq();
    }

    /** The waiting requests, for a snapshot. */
    public List<Entry<T>> entries() { return List.copyOf(entries); }
}
