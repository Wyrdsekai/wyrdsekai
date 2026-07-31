package org.wyrdsekai.core.item;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson-backed JSON utilities for
 * {@code world.json.*}. Enforces bounded depth (64) and bounded size (1MB)
 * on parse to avoid resource-exhaustion attacks from untrusted scripts.
 *
 * <p>Pure helper — no state, no actor refs. Used both by
 * {@link ItemWorldApiProviderImpl#jsonParse} and by
 * {@link ItemScheduleService} to round-trip its persisted payloads.</p>
 */
final class ItemJsonHelper {

    private static final Logger log = LoggerFactory.getLogger(ItemJsonHelper.class);

    /** Max input length (1MB) — anything larger returns an error. */
    static final int MAX_INPUT_BYTES = 1 << 20;
    /** Max nesting depth — Jackson default is 1000, we cap tighter. */
    static final int MAX_DEPTH = 64;

    private static final ObjectMapper MAPPER = newMapper();
    private static final ObjectMapper PRETTY = newMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static ObjectMapper newMapper() {
        var m = new ObjectMapper();
        m.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(MAX_DEPTH)
                .maxStringLength(1 << 22)  // 4MB string limit
                .build());
        return m;
    }

    private ItemJsonHelper() {}

    static Object parse(String text) {
        if (text == null || text.isEmpty()) return null;
        if (text.length() > MAX_INPUT_BYTES) {
            return Map.of("error", "input exceeds " + MAX_INPUT_BYTES + " bytes");
        }
        try {
            var node = MAPPER.readTree(text);
            return nodeToJava(node);
        } catch (Exception e) {
            return Map.of("error", "json.parse failed: " + e.getMessage());
        }
    }

    static String stringify(Object value) {
        return stringify(value, false);
    }

    static String stringify(Object value, boolean pretty) {
        try {
            var m = pretty ? PRETTY : MAPPER;
            return m.writeValueAsString(value);
        } catch (Exception e) {
            log.debug("json.stringify failed: {}", e.getMessage());
            return value == null ? "null" : "\"" + value + "\"";
        }
    }

    /**
     * JSONPath-style read. Supports a limited dialect:
     * <ul>
     *   <li>{@code $.foo.bar} — dotted path</li>
     *   <li>{@code $.foo[0]}  — array indexing</li>
     *   <li>{@code $}         — root</li>
     * </ul>
     * Returns null on miss.
     */
    static Object path(Object root, String jsonPath) {
        if (root == null || jsonPath == null || jsonPath.isBlank()) return null;
        var p = jsonPath.startsWith("$") ? jsonPath.substring(1) : jsonPath;
        if (p.startsWith(".")) p = p.substring(1);
        if (p.isEmpty()) return root;
        Object cur = root;
        var segments = p.split("\\.");
        for (var segRaw : segments) {
            if (cur == null) return null;
            var seg = segRaw;
            // Handle array index e.g. "items[0]"
            int br = seg.indexOf('[');
            String key = br >= 0 ? seg.substring(0, br) : seg;
            String indexPart = br >= 0 ? seg.substring(br) : "";
            if (!key.isEmpty()) {
                if (cur instanceof Map<?, ?> m) {
                    cur = m.get(key);
                } else {
                    return null;
                }
            }
            // Walk through any number of [N] suffixes.
            while (!indexPart.isEmpty() && indexPart.startsWith("[")) {
                int close = indexPart.indexOf(']');
                if (close < 0) return null;
                var idxStr = indexPart.substring(1, close);
                indexPart = indexPart.substring(close + 1);
                if (cur instanceof List<?> ll) {
                    try {
                        int idx = Integer.parseInt(idxStr);
                        if (idx < 0 || idx >= ll.size()) return null;
                        cur = ll.get(idx);
                    } catch (NumberFormatException _) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
        }
        return cur;
    }

    /**
     * Deep-merge: for each key in {@code b}, if both {@code a} and {@code b}
     * have an object value, recurse; otherwise b's value wins. Lists are
     * replaced (not concatenated), matching most "merge config" expectations.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object merge(Object a, Object b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a instanceof Map ma && b instanceof Map mb) {
            var out = new LinkedHashMap<Object, Object>(ma);
            for (var e : ((Map<Object, Object>) mb).entrySet()) {
                var k = e.getKey();
                var bv = e.getValue();
                if (out.containsKey(k)) {
                    out.put(k, merge(out.get(k), bv));
                } else {
                    out.put(k, bv);
                }
            }
            return out;
        }
        return b;
    }

    /**
     * RFC-6902 JSON Patch diff. Each entry has {@code {op, path, value}}.
     * Minimal implementation supporting {@code add}, {@code remove},
     * {@code replace}. Sufficient for config-drift surfaces.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> diff(Object a, Object b) {
        var ops = new ArrayList<Map<String, Object>>();
        diffInto(ops, "", a, b);
        return ops;
    }

    private static void diffInto(List<Map<String, Object>> ops, String basePath,
                                   Object a, Object b) {
        if (deepEquals(a, b)) return;
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            for (var entry : mb.entrySet()) {
                var k = String.valueOf(entry.getKey());
                var bv = entry.getValue();
                if (ma.containsKey(k)) {
                    @SuppressWarnings("unchecked")
                    var av = ((Map<String, Object>) ma).get(k);
                    diffInto(ops, basePath + "/" + k, av, bv);
                } else {
                    ops.add(Map.of("op", "add", "path", basePath + "/" + k, "value", bv));
                }
            }
            for (var entry : ma.entrySet()) {
                var k = String.valueOf(entry.getKey());
                if (!mb.containsKey(k)) {
                    ops.add(Map.of("op", "remove", "path", basePath + "/" + k));
                }
            }
            return;
        }
        // Leaf or list — emit a replace.
        var op = new LinkedHashMap<String, Object>();
        op.put("op", "replace");
        op.put("path", basePath.isEmpty() ? "/" : basePath);
        if (b != null) op.put("value", b);
        ops.add(op);
    }

    private static boolean deepEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    // ─── Node ↔ Java conversion ────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object nodeToJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isInt()) return n.asInt();
        if (n.isLong()) return n.asLong();
        if (n.isDouble() || n.isFloat()) return n.asDouble();
        if (n.isBigInteger() || n.isBigDecimal()) return n.asDouble();
        if (n.isTextual()) return n.asText();
        if (n.isArray()) {
            var list = new ArrayList<Object>(((ArrayNode) n).size());
            for (var item : n) list.add(nodeToJava(item));
            return list;
        }
        if (n.isObject()) {
            var map = new LinkedHashMap<String, Object>();
            Iterator<Map.Entry<String, JsonNode>> it = ((ObjectNode) n).fields();
            while (it.hasNext()) {
                var e = it.next();
                map.put(e.getKey(), nodeToJava(e.getValue()));
            }
            return map;
        }
        return n.toString();
    }
}
