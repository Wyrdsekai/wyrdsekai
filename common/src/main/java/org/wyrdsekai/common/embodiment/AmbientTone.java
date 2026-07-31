package org.wyrdsekai.common.embodiment;

import java.util.Locale;
/**
 * Layer 5 — the base sensory character of a room before
 * a {@link AmbientPhase} is applied.
 *
 * <p>Each foundation room declares one tone; dynamic-provisioner rooms
 * inherit a tone from their kind (Study=SOFT, Workshop=WARM, etc.). A
 * room's rendered ambient line is a function of {@code tone × phase}.
 *
 * <ul>
 *   <li>{@code WARM} — hearth, parlor, workshop — fire-light or oil-lamp tone.</li>
 *   <li>{@code BRIGHT} — nexus, atrium, bridge, terminal — open active spaces.</li>
 *   <li>{@code SOFT} — library, study, lexicon, sanctuary, chapel — quieted thinking.</li>
 *   <li>{@code DIM} — vault, safe, oracle, the-loom, boiler-room, gpu-chamber — low light, machine-hum.</li>
 * </ul>
 */
public enum AmbientTone {
    WARM,
    BRIGHT,
    SOFT,
    DIM;

    /** Lowercase key, for i18n / config files. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Reverse lookup, returning a default of {@link #SOFT} for null/unknown input. */
    public static AmbientTone ofKeyOrDefault(String key) {
        if (key == null) return SOFT;
        try {
            return AmbientTone.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SOFT;
        }
    }
}
