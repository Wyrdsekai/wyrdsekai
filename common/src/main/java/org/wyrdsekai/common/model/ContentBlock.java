package org.wyrdsekai.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * A typed content block for zone-type-specific UX (§83.5).
 * Clients render blocks they understand; fall back to prose for unknown types.
 *
 * <p>Format names are namespaced to prevent collisions:
 * <ul>
 *   <li>{@code wyrdsekai.room} — room state (built into all clients)</li>
 *   <li>{@code codezaiku.diff} — side-by-side diff viewer</li>
 *   <li>{@code codezaiku.cost} — cost panel</li>
 *   <li>{@code homekit.device} — device toggle/slider (future)</li>
 * </ul>
 *
 * @param format   Namespaced type identifier (e.g. "codezaiku.diff", "wyrdsekai.room")
 * @param data     Structured payload — arbitrary JSON specific to the format
 * @param fallback Prose text for clients that don't understand this format. Always present.
 */
public record ContentBlock(
    String format,
    JsonNode data,
    String fallback
) {
    public ContentBlock {
        if (format == null || format.isBlank()) throw new IllegalArgumentException("format required");
        if (fallback == null) fallback = "";
        if (data == null) data = NullNode.getInstance();
    }
}
