package org.wyrdsekai.core.item;

import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies a value that crossed out of a script context into plain Java — maps, lists,
 * strings, numbers, booleans — so nothing downstream still points at the context.
 *
 * <p>A JS object handed to a host method arrives as a {@code Map} that is a live view of
 * the object inside the polyglot context. It works for as long as the context is open.
 * The room event replicator serialized one after the item's invocation had finished and
 * the context was closed: {@code Failed to publish event … The Context is already closed
 * (through reference chain: WorldEvent$ScriptTriggered["context"])} (household node,
 * 2026-09-12 14:43). Anything that leaves the bridge must be copied here, while the
 * context is still open.</p>
 */
public final class PlainValues {

    private PlainValues() {}

    /** Guard against a self-referencing object; deeper than this is not data. */
    private static final int MAX_DEPTH = 32;

    /** A plain-Java deep copy of {@code v}; unknown host objects become their string form. */
    public static Object deepCopy(Object v) {
        return copy(v, 0);
    }

    /** {@link #deepCopy} for a map; never null. */
    public static Map<String, Object> deepCopy(Map<?, ?> m) {
        if (m == null) return Map.of();
        var out = copy(m, 0);
        if (out instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) mm;
            return typed;
        }
        return Map.of();
    }

    private static Object copy(Object v, int depth) {
        if (v == null) return null;
        if (depth > MAX_DEPTH) return String.valueOf(v);
        if (v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Number n) return normalizeNumber(n);
        if (v instanceof Value pv) return copyValue(pv, depth);
        if (isExecutable(v)) return null;                       // a function is not data
        if (v instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : m.entrySet()) {
                if (isExecutable(e.getValue())) continue;
                out.put(String.valueOf(e.getKey()), copy(e.getValue(), depth + 1));
            }
            return out;
        }
        if (v instanceof Iterable<?> it) {
            var out = new ArrayList<Object>();
            for (var x : it) out.add(copy(x, depth + 1));
            return out;
        }
        if (v instanceof Object[] arr) {
            var out = new ArrayList<Object>(arr.length);
            for (var x : arr) out.add(copy(x, depth + 1));
            return out;
        }
        if (v instanceof Enum<?> e) return e.name();
        return String.valueOf(v);
    }

    /** A JS function reaches the host as a proxy that can be executed; it carries nothing to keep. */
    private static boolean isExecutable(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) return false;
        try {
            var pv = v instanceof Value x ? x : Value.asValue(v);
            return pv.canExecute() && !pv.hasArrayElements();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Object copyValue(Value pv, int depth) {
        if (pv.isNull()) return null;
        if (pv.isString()) return pv.asString();
        if (pv.isBoolean()) return pv.asBoolean();
        if (pv.isNumber()) {
            if (pv.fitsInLong()) return pv.asLong();
            return pv.asDouble();
        }
        if (pv.isHostObject()) return copy(pv.asHostObject(), depth + 1);
        if (pv.hasArrayElements()) {
            var out = new ArrayList<Object>();
            long n = pv.getArraySize();
            for (long i = 0; i < n; i++) out.add(copy(pv.getArrayElement(i), depth + 1));
            return out;
        }
        if (pv.hasMembers()) {
            var out = new LinkedHashMap<String, Object>();
            for (var key : pv.getMemberKeys()) {
                var member = pv.getMember(key);
                if (member != null && member.canExecute()) continue;   // functions are not data
                out.put(key, copy(member, depth + 1));
            }
            return out;
        }
        return pv.toString();
    }

    /** JS numbers arrive as Double even when integral; keep whole numbers whole. */
    private static Object normalizeNumber(Number n) {
        if (n instanceof Double d && !d.isNaN() && !d.isInfinite() && d == Math.rint(d)
                && Math.abs(d) < 9.007199254740992E15) {
            return d.longValue();
        }
        if (n instanceof Float f && !f.isNaN() && !f.isInfinite() && f == Math.rint(f)) {
            return f.longValue();
        }
        return n;
    }

    /** Convenience for callers that hold a list. */
    public static List<Object> deepCopy(List<?> l) {
        var out = copy(l, 0);
        if (out instanceof List<?> ll) {
            @SuppressWarnings("unchecked")
            var typed = (List<Object>) ll;
            return typed;
        }
        return List.of();
    }
}
