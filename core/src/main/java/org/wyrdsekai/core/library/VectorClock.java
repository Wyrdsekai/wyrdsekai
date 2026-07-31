package org.wyrdsekai.core.library;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Vector-clock comparison for Study item sync — the server-side twin of the
 * clients' {@code VectorClock.ts} / {@code VectorClock.kt}. The phone and the
 * home-zone server are co-authoritative CRDT peers
 * so the merge rules here MUST match the clients byte-for-byte or concurrent
 * edits resolve differently on each side and the two Studies diverge.
 *
 * A clock is a {@code {deviceId → version}} map; each device increments its own
 * slot on write. {@code a} dominates {@code b} when every slot in {@code a} is
 * {@code >=} the matching slot in {@code b} and at least one is strictly {@code >}.
 */
public final class VectorClock {

    private VectorClock() {}

    /** Ordering of two clocks — mirrors the clients' {@code Relation} union. */
    public enum Relation { DOMINATES, DOMINATED, CONCURRENT, EQUAL }

    /**
     * Compare two clocks. Missing slots read as 0 (a device that never wrote).
     * Port of {@code VectorClock.ts compare()}.
     */
    public static Relation compare(Map<String, Integer> a, Map<String, Integer> b) {
        var keys = new HashSet<String>();
        if (a != null) keys.addAll(a.keySet());
        if (b != null) keys.addAll(b.keySet());
        boolean aGreater = false;
        boolean bGreater = false;
        for (var key : keys) {
            int va = slot(a, key);
            int vb = slot(b, key);
            if (va > vb) aGreater = true;
            if (vb > va) bGreater = true;
        }
        if (aGreater && !bGreater) return Relation.DOMINATES;
        if (bGreater && !aGreater) return Relation.DOMINATED;
        if (!aGreater && !bGreater) return Relation.EQUAL;
        return Relation.CONCURRENT;
    }

    /** Merge two clocks, taking the max of each slot. Port of {@code merge()}. */
    public static Map<String, Integer> merge(Map<String, Integer> a, Map<String, Integer> b) {
        var result = new HashMap<String, Integer>();
        if (a != null) result.putAll(a);
        if (b != null) {
            for (var e : b.entrySet()) {
                result.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        return result;
    }

    /** Increment {@code deviceId}'s slot. Port of {@code tick()}. */
    public static Map<String, Integer> tick(Map<String, Integer> clock, String deviceId) {
        var result = new HashMap<String, Integer>();
        if (clock != null) result.putAll(clock);
        result.merge(deviceId, 1, Integer::sum);
        return result;
    }

    private static int slot(Map<String, Integer> m, String key) {
        if (m == null) return 0;
        var v = m.get(key);
        return v == null ? 0 : v;
    }
}
