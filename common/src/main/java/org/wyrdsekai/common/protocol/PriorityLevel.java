package org.wyrdsekai.common.protocol;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Event priority levels for the wire protocol (§66.3).
 * <p>
 * Controls how clients render and announce events, especially for
 * screen readers and non-visual interfaces.
 * <ul>
 *   <li>CRITICAL — announce immediately, interrupt current output</li>
 *   <li>NORMAL — queue, announce at natural pauses</li>
 *   <li>AMBIENT — available on request, never auto-announced</li>
 * </ul>
 */
public enum PriorityLevel {
    CRITICAL("critical"),
    NORMAL("normal"),
    AMBIENT("ambient");

    private final String wire;

    PriorityLevel(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** Parse from wire value, defaulting to NORMAL for unrecognized values. */
    public static PriorityLevel fromWire(String value) {
        if (value == null) return NORMAL;
        return switch (value.toLowerCase()) {
            case "critical", "important" -> CRITICAL;
            case "ambient", "whisper", "background" -> AMBIENT;
            default -> NORMAL;
        };
    }
}
