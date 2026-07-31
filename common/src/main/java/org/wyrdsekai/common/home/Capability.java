package org.wyrdsekai.common.home;

/**
 * The five capabilities a {@link Grant} can carry.
 *
 * <p>Kept small on purpose: expressivity without sprawl. {@code execute} is
 * subsumed by {@code use} (consuming compute) and {@code write} (mutating
 * state); {@code own} is deliberately absent because ownership is not
 * transferable — it's the outside of the grant system.</p>
 */
public enum Capability {
    /** Observe, query, fetch. */
    read,
    /** Create, modify, delete within scope. */
    write,
    /** Invoke, consume, actuate. */
    use,
    /** Co-sign, witness, vouch. */
    attest,
    /** Re-grant this capability to others (subject to subset constraints — §4.5). */
    delegate;

    /** Parse a capability name case-insensitively; returns {@code null} on miss. */
    public static Capability parse(String name) {
        if (name == null) return null;
        try {
            return Capability.valueOf(name.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
