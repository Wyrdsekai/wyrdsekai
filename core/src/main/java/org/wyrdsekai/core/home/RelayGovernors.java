package org.wyrdsekai.core.home;

import java.util.function.Function;

/**
 * (P4) — process-wide holder for the relay-governance
 * binding factory.
 *
 * <p>The signed relay caller ({@code RelayAdminClient}) lives in the
 * {@code server} module (it needs the node's private signing key, which
 * {@code core} does not depend on). {@code Main} resolves the configured relay
 * + its {@code owner_did} at boot and installs a factory here:
 * {@code (callerDid) -> RelayGovernor}. Core's {@code CompanionActor} then wires
 * each furnishing provider with {@link #forAgent(String)} so the Warden
 * furnishing can govern without {@code core} reaching into {@code between}.</p>
 *
 * <p>Returns {@code null} when no relay is administered (the furnishing degrades
 * to "no relay configured"). Install once from {@code Main}.</p>
 */
public final class RelayGovernors {

    private static volatile Function<String, RelayGovernor> factory;

    private RelayGovernors() {}

    /** Install the factory (called once from Main when a relay is administered). */
    public static void setFactory(Function<String, RelayGovernor> f) {
        factory = f;
    }

    /**
     * The relay-governance binding for {@code callerDid} (the acting agent /
     * zone), or {@code null} if no relay is administered. The returned governor
     * still gates each action by the caller's grant scope, so it is safe to
     * hand the same governor to any caller; the {@code callerDid} is supplied
     * per-action.
     */
    public static RelayGovernor forAgent(String callerDid) {
        var f = factory;
        return f == null ? null : f.apply(callerDid);
    }
}
