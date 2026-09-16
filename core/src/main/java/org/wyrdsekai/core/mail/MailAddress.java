package org.wyrdsekai.core.mail;

import java.util.Locale;

/**
 * A mail address, and the one rule that says where a message is going.
 *
 * <ul>
 *   <li>no {@code @} — someone in this household: {@code mia}, {@code kaz}, {@code "ada lovelace"}</li>
 *   <li>{@code @} and this node's own zone — the same thing, written out: {@code mia@neo}</li>
 *   <li>{@code @} and another zone id — a federated household: {@code mia@alpha}</li>
 *   <li>{@code @} and a dotted host — the actual internet: {@code bob@example.org}</li>
 * </ul>
 *
 * <p>Naming your own zone is local delivery, not a federation hop — the same short-circuit
 * {@code CrossZoneTellService} already makes for {@code tell}. It matters for replies: mail
 * that arrived from {@code kaz@neo} has to be replyable at that literal address without a
 * round trip through the federation subjects.</p>
 *
 * <p>The {@code zone.name} form {@code tell} accepts ({@code alpha.mia}) is taken too, so
 * nobody has to learn a second grammar; {@link #display()} always renders the canonical
 * {@code name@zone}.</p>
 *
 * <p>Addresses are display text. Delivery resolves to an identity and stores that, so a zone
 * the steward later renames does not strand old mail (see {@link MailDirectory}).</p>
 */
public record MailAddress(String name, String zone, Scope scope, String display) {

    public enum Scope {
        /** Someone in this household. */
        LOCAL,
        /** Someone in a federated household. */
        ZONE,
        /** An address out on the internet. */
        EXTERNAL
    }

    /**
     * Parse an address as typed. Returns {@code null} when there is nothing addressable —
     * an empty string, a bare {@code @host}, or a name with no host after the {@code @}.
     *
     * @param localZone this node's zone id; an address naming it is {@link Scope#LOCAL}
     */
    public static MailAddress parse(String raw, String localZone) {
        if (raw == null) return null;
        var s = raw.strip();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.isEmpty()) return null;
        var local = localZone == null || localZone.isBlank() ? "home" : localZone.strip();

        int at = s.lastIndexOf('@');
        if (at < 0) {
            // tell's own form: "alpha.mia" — one dot, both halves non-empty, no spaces.
            int dot = s.indexOf('.');
            if (dot > 0 && dot == s.lastIndexOf('.') && dot < s.length() - 1
                    && s.indexOf(' ') < 0) {
                var zone = s.substring(0, dot);
                var name = s.substring(dot + 1);
                return of(name, zone, local);
            }
            return new MailAddress(s, local, Scope.LOCAL, s + "@" + local);
        }
        var name = s.substring(0, at).strip();
        var host = s.substring(at + 1).strip();
        if (name.isEmpty() || host.isEmpty()) return null;
        return of(name, host, local);
    }

    private static MailAddress of(String name, String host, String localZone) {
        var h = host.toLowerCase(Locale.ROOT);
        if (h.equalsIgnoreCase(localZone)) {
            return new MailAddress(name, localZone, Scope.LOCAL, name + "@" + localZone);
        }
        if (h.indexOf('.') >= 0) {
            return new MailAddress(name, h, Scope.EXTERNAL, name + "@" + h);
        }
        return new MailAddress(name, h, Scope.ZONE, name + "@" + h);
    }

    /** True when this address is delivered inside this household. */
    public boolean isLocal() {
        return scope == Scope.LOCAL;
    }

    /** The name as a lookup key: case- and spacing-insensitive. */
    public String key() {
        return name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
