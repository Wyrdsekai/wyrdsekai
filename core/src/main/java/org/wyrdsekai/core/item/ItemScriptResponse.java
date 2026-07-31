package org.wyrdsekai.core.item;

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

    private ItemScriptResponse() {}

    /**
     * Returns the text the user should see for a scripted-item invocation.
     *
     * <p>Priority — first non-null wins:</p>
     * <ol>
     *   <li>{@code result["response"]} — the canonical script-author field</li>
     *   <li>{@code result["text"]} — older field, still honored</li>
     *   <li>{@code result["error"]} — script signaled trouble; surface it</li>
     *   <li>Fallback: {@code "You use the <itemName>."} — only fires when
     *       the script ran cleanly but emitted no message, which means
     *       either (a) author forgot to set one, or (b) the script is a
     *       passive observer that shouldn't have been invoked. Either way
     *       the user gets a non-empty acknowledgment.</li>
     * </ol>
     */
    public static String extractText(Map<String, Object> result, String itemName) {
        var name = itemName == null ? "item" : itemName;
        var defaultMsg = "You use the " + name + ".";
        return String.valueOf(result.getOrDefault("response",
            result.getOrDefault("text",
                result.getOrDefault("error", defaultMsg))));
    }
}
