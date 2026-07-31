package org.wyrdsekai.core.config;

import java.util.Locale;
import java.util.Objects;

/**
 * One relay "leg" a zone is homed on.
 *
 * <p>A multi-homed zone holds an ordered list of these — like a laptop on
 * wifi + ethernet. Each leg is an independent connection to one relay; legs
 * never forward traffic between each other (that is what keeps a private
 * relay's bus from welding onto a public one). The zone identity (NKey) is the
 * same across every leg; each relay registers it independently.</p>
 *
 * <p>{@code token}/{@code user} are local secrets and are NEVER advertised to
 * federation peers — peers only learn {@code url}, {@code caFingerprint}, and
 * {@code visibility} (see {@code RelayAdvert}, P3).</p>
 */
public record RelayLegConfig(
    String url,            // nats:// or wss:// dial address (required)
    String user,           // account/NKey user (may be null in NKey mode)
    String token,          // bootstrap token (null in NKey mode)
    String caFingerprint,  // pinned household-CA fingerprint (may be null for legacy)
    Visibility visibility  // PRIVATE (hidden/LAN/invite-gated) | PUBLIC (commons)
) {
    public RelayLegConfig {
        Objects.requireNonNull(url, "relay leg url");
        Objects.requireNonNull(visibility, "relay leg visibility");
    }

    /** Relay visibility class. The zone's privacy floor is the MIN over its legs. */
    public enum Visibility {
        /** Hidden / LAN / invite-gated. Safe for a private zone's federation traffic. */
        PRIVATE,
        /** Public commons — strangers share the bus. A private zone must not egress here. */
        PUBLIC;

        /** Parse a config string; unknown/blank → the supplied default (never throws). */
        public static Visibility parse(String s, Visibility dflt) {
            if (s == null || s.isBlank()) return dflt;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "public", "commons" -> PUBLIC;
                case "private", "hidden", "lan" -> PRIVATE;
                default -> dflt;
            };
        }
    }

    public boolean isPublic()  { return visibility == Visibility.PUBLIC; }
    public boolean isPrivate() { return visibility == Visibility.PRIVATE; }
}
