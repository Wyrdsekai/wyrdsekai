package org.wyrdsekai.common.home;

import java.util.Locale;

/**
 * Scope vocabulary for a {@code relay-admin} Grant.
 *
 * <p>A relay-admin Grant carries one of these as its {@code scope} payload under
 * the key {@link #SCOPE_KEY}. The three scopes form a containment hierarchy:
 * {@code full} ⊇ {@code moderation} ⊇ {@code invite-only}. The
 * {@code RelayGovernance} authorization predicate resolves operations to a
 * required scope and allows a caller iff a held grant's scope covers it.</p>
 *
 * <ul>
 *   <li>{@link #INVITE_ONLY} — mint invites, nothing else.</li>
 *   <li>{@link #MODERATION} — list/remove/report-queue/promote/demote (NOT
 *       mode or policy change).</li>
 *   <li>{@link #FULL} — everything, including set-mode/set-policy/grant-admin.</li>
 * </ul>
 */
public enum RelayAdminScope {

    /** Mint invites only. */
    INVITE_ONLY("invite-only", 1),
    /** Moderation: list/remove/report-queue/promote/demote. */
    MODERATION("moderation", 2),
    /** Full management, including mode/policy changes and admin delegation. */
    FULL("full", 3);

    /** Scope-Map key under which a relay-admin grant stores its scope name. */
    public static final String SCOPE_KEY = "relay-scope";

    /** Scope-Map key (optional) narrowing a grant to a specific relay DID. */
    public static final String RELAY_KEY = "relay";

    private final String wire;
    private final int rank;

    RelayAdminScope(String wire, int rank) {
        this.wire = wire;
        this.rank = rank;
    }

    /** The on-the-wire / scope-Map name (e.g. {@code "moderation"}). */
    public String wire() { return wire; }

    /**
     * True if this scope covers (is at least as broad as) {@code required}.
     * {@code full} covers everything; {@code invite-only} covers only itself.
     */
    public boolean covers(RelayAdminScope required) {
        return this.rank >= required.rank;
    }

    /** Parse a scope name; case-insensitive. Returns {@code null} on miss/null. */
    public static RelayAdminScope parse(String name) {
        if (name == null) return null;
        var n = name.trim().toLowerCase(Locale.ROOT);
        for (var s : values()) {
            if (s.wire.equals(n)) return s;
        }
        // tolerate enum-name form too (e.g. "INVITE_ONLY")
        for (var s : values()) {
            if (s.name().equalsIgnoreCase(name.trim())) return s;
        }
        return null;
    }
}
