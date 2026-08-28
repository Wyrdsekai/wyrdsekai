package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Extracts a user-facing prose response from an item-script execution
 * result. Centralizes the priority chain
 * {@code response > text > error > fallback} that was previously inlined in
 * three transports (SSH, WS, virtual-session).
 *
 * <p>The shared helper exists so a single edit to fallback semantics (e.g.
 * the future Examine refactor adding a distinct "you examine the X" path)
 * touches one place, not three. Identical behavior across transports is
 * the actual cross-transport invariance §10 promises.</p>
 */
public final class ItemScriptResponse {

    private static final Logger log = LoggerFactory.getLogger(ItemScriptResponse.class);

    private ItemScriptResponse() {}

    /**
     * Returns the text the user should see for a scripted-item invocation.
     *
     * <p>Priority — first non-blank wins:</p>
     * <ol>
     *   <li>{@code response} — the canonical script-author field</li>
     *   <li>{@code text} — older field, still honored</li>
     *   <li>{@code summary} — <b>what the items-as-tools preamble actually teaches</b></li>
     *   <li>{@code message} — the remaining common spelling</li>
     *   <li>{@code error} — script signaled trouble; surface it</li>
     *   <li>Fallback: {@code "You use the <itemName>."}</li>
     * </ol>
     *
     * <h2>Why {@code summary} is on this list</h2>
     * It was not, and the preamble every coding backend is handed ends its FILE SHAPE
     * block with exactly this:
     *
     * <pre>
     *   function invoke(params) {
     *     return &#123; ok: true, summary: "..." &#125;;
     *   }
     * </pre>
     *
     * <p>So the contract told the author to put the answer in {@code summary}, and the
     * only code that turns a result into words never looked there. Live 2026-08-21: the
     * steward asked for a tool that queries the library and tells a story about what it
     * finds. She routed it to the workshop, goose wrote a clean {@code library_keeper},
     * the bridge registered and kept it, she handed it over — and
     * {@code use library_keeper details} answered <b>"You use the library_keeper."</b>
     * The item had done its work and returned a proper answer; we threw it away and
     * printed a stock acknowledgment. Every surface, because they all share this method.
     *
     * <p>The fallback is a last resort for a script that genuinely said nothing. When a
     * script returns a field we don't read, that is our bug, not the author's — so a
     * result that is non-empty but yields no text is logged rather than silently
     * flattened.
     */
    public static String extractText(Map<String, Object> result, String itemName) {
        var name = itemName == null ? "item" : itemName;
        if (result != null) {
            for (var key : TEXT_FIELDS) {
                var v = unwrapEnvelope(result.get(key));
                if (v == null) continue;
                var text = String.valueOf(v);
                if (!text.isBlank()) {
                    var extra = detailText(result, text);
                    return extra == null ? text : text + "\n" + extra;
                }
            }
            if (!result.isEmpty()) {
                log.info("Item '{}' returned {} but none of {} carried text — showing the"
                    + " stock acknowledgment. If the script meant to say something, the"
                    + " field it used is not one this reads.",
                    name, result.keySet(), TEXT_FIELDS);
            }
        }
        return "You use the " + name + ".";
    }

    /**
     * Every field an item script may put its answer in, in the order we prefer them.
     *
     * <p>Keep this in step with the FILE SHAPE block of
     * {@code OpenHandsBackend.ITEMS_AS_TOOLS_PREAMBLE} — that block is the contract the
     * authors are held to, and this list is the only thing that honours it. If they ever
     * disagree again, the item works and the person sees nothing.
     */
    public static final List<String> TEXT_FIELDS =
        List.of("response", "text", "summary", "narrative", "message", "error");

    /**
     * The first of {@link #TEXT_FIELDS} this result carries, or null.
     *
     * <p>Exists so {@code RoomActor} can share the list rather than keep its own. It had
     * one — {@code summary, narrative, response, text, error} — which is how
     * {@code narrative} came to be readable when an item was placed in the room and not
     * when the same item was picked up. Two readers of one contract is the defect this
     * whole day was made of; the room needs its own FALLBACK (it pretty-prints the raw
     * result rather than saying "you use the X"), not its own field list.
     */
    public static String firstTextField(Map<String, Object> result) {
        if (result == null) return null;
        for (var key : TEXT_FIELDS) {
            var v = unwrapEnvelope(result.get(key));
            // String.valueOf, not instanceof String — detailText's own javadoc
            // records why: a polyglot value that isn't java.lang.String would be
            // silently dropped by a type check.
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    /**
     * A map-valued text field is an ENVELOPE the author forgot to open. Live
     * 2026-08-24: an item did {@code summary: world.llm.complete(...)} and the
     * person read {@code {tokensOut=189, tokensIn=83, text=El bosque…,
     * latencyMs=2023}} — the whole wire envelope, braces and all, where the
     * story should have been. The preamble documents the {@code {text, …}}
     * shape; small models will keep forgetting {@code .text}. One level of
     * unwrap: when a text field holds a map, take THAT map's first text field.
     * A nested map with no text field falls through unchanged, so genuinely
     * structured results still render as before.
     */
    private static Object unwrapEnvelope(Object v) {
        if (v instanceof Map<?, ?> nested) {
            for (var key : TEXT_FIELDS) {
                var inner = nested.get(key);
                if (inner != null && !(inner instanceof Map)
                        && !String.valueOf(inner).isBlank()) {
                    return inner;
                }
            }
        }
        return v;
    }

    /**
     * The long half of an answer, when the item wrote one.
     *
     * <h2>Why this is here and not in the room</h2>
     * An item that returns BOTH a summary and details meant the person to have both. Live
     * 2026-08-22: {@code venture_scout} put "generated three radical business ideas with
     * TAM estimates" in {@code summary} and the three ideas themselves in {@code details},
     * and the steward heard the description instead of the work.
     *
     * <p>It lives HERE because the field list already does: this class exists because the
     * room kept its own copy of "which fields carry text", so {@code narrative} rendered
     * for an item on the floor and vanished for the same item in someone's hands. Adding
     * a second reader of the response contract in {@code RoomActor} would rebuild exactly
     * that bug. Every surface asks this one method.
     *
     * <p>{@code String.valueOf}, never {@code instanceof String}: a JS string does not
     * reliably cross the polyglot boundary as {@code java.lang.String}, and a type check
     * on a script's value silently drops it — the same shape as the {@code List<String>}
     * parameters that JS arrays could not bind to.
     */
    public static String detailText(Map<String, Object> result, String alreadySaid) {
        if (result == null) return null;
        // details is an envelope-leak path too: second-node 2026-08-24 evening, dev10 —
        // the item put its story in details as the raw llm.complete map, summary
        // rendered clean and the braces followed it. Same one-level unwrap as
        // the text fields; the first fix covered those and missed this one.
        var raw = unwrapEnvelope(result.get("details"));
        if (raw == null) return null;
        var extra = String.valueOf(raw).trim();
        if (extra.isBlank() || "null".equals(extra) || "undefined".equals(extra)) return null;
        if (alreadySaid != null && alreadySaid.contains(extra)) return null;
        return extra;
    }
}
