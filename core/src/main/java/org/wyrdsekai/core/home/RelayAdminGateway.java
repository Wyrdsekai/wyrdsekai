package org.wyrdsekai.core.home;

import java.util.List;
import java.util.Map;

/**
 * (P4) — the core-side seam to the signed relay
 * admin API. The actual signed-POST caller ({@code RelayAdminClient}) lives in
 * the {@code server} module because it needs the node's private signing key
 * ({@code between.NodeIdentity}), which {@code core} deliberately does not
 * depend on (it has only public-key views). This interface lets the in-world
 * governance furnishing — wired in {@code core} via {@link RelayGovernor} —
 * dispatch admin ops without reaching into {@code between}.
 *
 * <p>Authorization is layered: the caller is gated zone-side by
 * {@link RelayGovernance#authorize} (so the furnishing only offers/permits ops
 * the caller is entitled to) AND again relay-side by the signed endpoint (owner
 * or local grant). This gateway performs only the signed network call; it does
 * not itself re-check the Grant scope.</p>
 */
public interface RelayAdminGateway {

    /**
     * The acting DID this gateway signs as (the zone's {@code did:key:}). The
     * relay authorizes against this; it is also the {@code callerDid} the
     * zone-side {@link RelayGovernance} predicate is checked with.
     */
    String actingDid();

    /** The relay's stable {@code did:key:} this gateway administers. */
    String relayDid();

    /** A human-facing hint for which relay this is (host/url), for the furnishing. */
    String relayLabel();

    /**
     * Dispatch a signed admin op. {@code op} is the wire name
     * (e.g. {@code "list"}, {@code "grant-admin"}); {@code args} may be null.
     * Returns a flat result map carrying at least {@code status} (HTTP-equiv int),
     * {@code ok} (boolean), and the relay's parsed response fields (e.g.
     * {@code registrations}, {@code invite_url}, {@code error}).
     */
    Map<String, Object> call(String op, Map<String, Object> args);

    /** Convenience for the read path: the relay's registrations, or empty. */
    @SuppressWarnings("unchecked")
    default List<Map<String, Object>> listRegistrations() {
        var res = call("list", null);
        var v = res == null ? null : res.get("registrations");
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
