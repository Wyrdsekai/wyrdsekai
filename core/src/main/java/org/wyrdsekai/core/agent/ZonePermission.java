package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single zone permission entry: allow or deny a namespace.action pair.
 * Extends the Ward system (room-level) with service-level granularity.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code ("codeplane", "status", ALLOW)} — can check CodePlane status</li>
 *   <li>{@code ("codeplane", "*", DENY)} — denied all CodePlane actions</li>
 *   <li>{@code ("*", "*", ALLOW)} — allowed everything (steward/admin)</li>
 * </ul>
 *
 * @param namespace Namespace to match ("codeplane", "iot", "*" for all)
 * @param action    Action to match ("create", "approve", "*" for all)
 * @param level     ALLOW or DENY
 */
public record ZonePermission(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("action") String action,
    @JsonProperty("level") PermissionLevel level
) {
    @JsonCreator
    public ZonePermission {}

    public enum PermissionLevel { ALLOW, DENY }

    /**
     * Check if this permission entry matches the given namespace and action.
     * Wildcards ("*") match anything.
     */
    public boolean matches(String ns, String act) {
        return ("*".equals(namespace) || namespace.equals(ns))
            && ("*".equals(action) || action.equals(act));
    }
}
