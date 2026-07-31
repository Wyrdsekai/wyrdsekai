package org.wyrdsekai.core.agent;

import java.util.Map;

/**
 * Typed envelope for scripted-tool results crossing the script→actor boundary.
 *
 * <p>The raw result of an item script is whatever its {@code invoke()} returned —
 * an author-defined object with no schema. Before this envelope existed the actor
 * passed that raw {@code Map} around and every consumer key-sniffed it, each one
 * written against whichever producer its author was testing at the time. That
 * boundary produced a steady stream of incidents (skill-cost learning silently
 * blind to scripted-item outcomes, "[Tool failed] the tool" with no usage
 * contract, contradictory completed+error trigger text — second-node 2026-07-08..10).
 *
 * <p>Normalization happens ONCE, at the dispatch site that authoritatively knows
 * the tool identity. Consumers read typed fields. The raw map survives as
 * {@link #payload()} for the shape-specific renderers (findings / content /
 * sent / summary…), which are legitimately duck-typed.
 */
public record ToolResultEnvelope(
    String toolId,
    boolean ok,
    String error,
    Map<String, Object> payload) {

    /**
     * Build an envelope from a script's raw return map.
     *
     * @param toolId the authoritative tool/item id from the DISPATCH site — never
     *               trust the script to self-identify; falls back to a {@code tool}
     *               key in the map only when the caller has nothing better.
     * @param raw    the script's return object (may be null on executor failure).
     */
    public static ToolResultEnvelope normalize(String toolId, Map<String, Object> raw) {
        Map<String, Object> payload = raw != null ? raw : Map.of();
        String id = toolId;
        if (id == null || id.isBlank()) {
            var t = payload.get("tool");
            id = t != null ? String.valueOf(t) : null;
        }
        // Failure = explicit error key OR the items-API convention {ok:false}.
        // (Scripts that return {ok:false} without an error message previously
        // slipped through every consumer as a "success".)
        boolean failed = payload.containsKey("error")
            || Boolean.FALSE.equals(payload.get("ok"));
        String error = null;
        if (failed) {
            var e = payload.get("error");
            error = e != null && !String.valueOf(e).isBlank()
                ? String.valueOf(e) : "tool reported failure without a message";
            // Scripts often put the human explanation in a separate `message` key
            // (second-node 2026-07-10: trip_planner's "origin, destination, and date are
            // required" never reached the model — error carried only "missing_args").
            var m = payload.get("message");
            if (m != null && !String.valueOf(m).isBlank()
                    && !error.contains(String.valueOf(m))) {
                error = error + " — " + m;
            }
        }
        return new ToolResultEnvelope(id, !failed, error, payload);
    }

    public boolean has(String key) {
        return payload.containsKey(key);
    }

    public Object get(String key) {
        return payload.get(key);
    }
}
