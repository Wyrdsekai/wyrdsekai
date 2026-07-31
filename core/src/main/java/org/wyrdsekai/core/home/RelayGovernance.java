package org.wyrdsekai.core.home;

import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.RelayAdminOp;
import org.wyrdsekai.common.home.RelayAdminScope;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Relay-governance authorization predicate.
 *
 * <p>A relay is a Home-owned resource ({@code home://{owner}/relay/{relayDid}});
 * managing it, and delegating that management, are ordinary Grants on the
 * {@code relay-admin} resource ({@code home://{owner}/relay-admin/{relayDid}}).
 * There is no bespoke admin-role system — this class only <em>resolves</em>
 * existing Grants, adding the §6 scope hierarchy
 * ({@code full} ⊇ {@code moderation} ⊇ {@code invite-only}) that the generic,
 * exact-equality {@code CheckAccess} scope test cannot express on its own.</p>
 *
 * <p>This predicate is the hook the P3 signed {@code /admin/*} API calls: given
 * a caller DID (the verified signer), the relay it is acting on, and the
 * operation, it returns allow/deny. Issuance and revocation of relay-admin
 * Grants ride the ordinary grant surfaces ({@link HomeClient}, {@code world.grants},
 * {@code world.audit}); §4.5 cascade delegation is honored by
 * {@link HomeRegistryActor} at issue time, so a {@code delegate}-capability
 * holder's re-issued grants are already validated up the chain to the owner.</p>
 *
 * <p>The {@code relayDid} is the relay's stable identifier — its
 * {@code did:key:} derived from the same Ed25519 / NKey that backs the relay
 * node's registration. It is un-spoofable because
 * it is a deterministic projection of the key that authenticates.</p>
 */
public final class RelayGovernance {

    private final HomeClient homeClient;

    public RelayGovernance(HomeClient homeClient) {
        this.homeClient = homeClient;
    }

    // --- Resource URI helpers -------------------------------------------

    /** The relay resource URI: {@code home://{owner}/relay/{relayDid}}. */
    public static ResourceUri relayResource(String ownerDid, String relayDid) {
        return ResourceUri.of(ownerDid, ResourceTypeRegistry.RELAY, relayDid);
    }

    /** The relay-admin resource URI: {@code home://{owner}/relay-admin/{relayDid}}. */
    public static ResourceUri relayAdminResource(String ownerDid, String relayDid) {
        return ResourceUri.of(ownerDid, ResourceTypeRegistry.RELAY_ADMIN, relayDid);
    }

    /** Build a relay-admin grant scope payload for {@code scope} on {@code relayDid}. */
    public static Map<String, Object> scopePayload(RelayAdminScope scope, String relayDid) {
        return Map.of(
            RelayAdminScope.SCOPE_KEY, scope.wire(),
            RelayAdminScope.RELAY_KEY, relayDid);
    }

    // --- Authorization predicate (P3's hook) ----------------------------

    /**
     * Allow/deny for a relay admin operation.
     *
     * <p>Allowed iff {@code callerDid} is the relay owner ({@code ownerDid}) OR
     * holds a valid (unexpired, unrevoked) relay-admin grant covering
     * {@code relayDid} at a scope that permits {@code op}.</p>
     *
     * @param callerDid the verified signer / acting DID
     * @param ownerDid  the relay's owning Home (steward) DID
     * @param relayDid  the relay's stable DID
     * @param op        the operation being attempted
     * @return true to allow, false to deny
     */
    public boolean authorize(String callerDid, String ownerDid, String relayDid, RelayAdminOp op) {
        if (callerDid == null || ownerDid == null || relayDid == null || op == null) {
            return false;
        }
        // filing a `report` is open to any caller
        // (the signature is the whole bar relay-side); no grant required. This
        // is the zone-side parallel of the relay's _OPEN_TO_ANY_SIGNER exemption,
        // so the Warden furnishing may offer "file a report" to a caller who
        // holds no relay-admin scope. Viewing/resolving reports are NOT open.
        if (op.isOpenToAnySigner()) {
            return true;
        }
        // The owner is sovereign over their own relay resource.
        if (callerDid.equals(ownerDid)) {
            return true;
        }
        var required = op.requiredScope();
        var held = heldScope(callerDid, ownerDid, relayDid);
        return held != null && held.covers(required);
    }

    /**
     * The broadest relay-admin scope {@code callerDid} currently holds over
     * {@code relayDid} (owner's Home), or {@code null} if none. Owner-or-not is
     * NOT considered here — this is purely the granted scope. The §4.5 cascade
     * is honored implicitly: any grant present here was validated up to the
     * owner at issue time, so a transitively-delegated grant is as authoritative
     * as a direct one.
     */
    public RelayAdminScope heldScope(String callerDid, String ownerDid, String relayDid) {
        var resource = relayAdminResource(ownerDid, relayDid);
        var now = Instant.now();
        RelayAdminScope best = null;
        // A relay-admin grant may carry capability `use` (act) or `delegate`
        // (re-delegate). Both authorize acting on the relay; `delegate`
        // additionally permits re-issuance (validated by HomeRegistryActor).
        List<Grant> held = homeClient.listHeldBy(callerDid);
        for (var g : held) {
            if (!g.isActive(now)) continue;
            if (!g.resource().toString().equals(resource.toString())) continue;
            if (g.capability() != Capability.use && g.capability() != Capability.delegate) continue;
            // relay-id narrowing: if the grant pins a relay, it must match.
            var pinned = g.scope() == null ? null : g.scope().get(RelayAdminScope.RELAY_KEY);
            if (pinned != null && !pinned.equals(relayDid)) continue;
            var scopeName = g.scope() == null ? null : g.scope().get(RelayAdminScope.SCOPE_KEY);
            var scope = scopeName instanceof String s ? RelayAdminScope.parse(s) : null;
            if (scope == null) continue;
            if (best == null || scope.covers(best)) {
                best = scope;
            }
        }
        return best;
    }

    /**
     * True if {@code callerDid} may re-delegate relay admin over {@code relayDid}
     * — i.e. holds a {@code delegate}-capability relay-admin grant (or is the
     * owner). Used to gate {@code grant-admin}; actual re-issuance still goes
     * through {@link HomeClient#issue} so §4.5 subset validation applies.
     */
    public boolean canDelegate(String callerDid, String ownerDid, String relayDid) {
        if (callerDid == null || ownerDid == null || relayDid == null) return false;
        if (callerDid.equals(ownerDid)) return true;
        var resource = relayAdminResource(ownerDid, relayDid);
        var now = Instant.now();
        for (var g : homeClient.listHeldBy(callerDid)) {
            if (!g.isActive(now)) continue;
            if (g.capability() != Capability.delegate) continue;
            if (!g.resource().toString().equals(resource.toString())) continue;
            var pinned = g.scope() == null ? null : g.scope().get(RelayAdminScope.RELAY_KEY);
            if (pinned != null && !pinned.equals(relayDid)) continue;
            return true;
        }
        return false;
    }

    // --- Convenience issuance over the generic grant surface ------------

    /**
     * Issue a relay-admin grant. Pure convenience over {@link HomeClient#issue}
     * — it only assembles the §6 scope payload; the grant is validated and
     * persisted by {@link HomeRegistryActor} like every other grant (so it
     * appears in {@code world.grants}/{@code world.audit}, and §4.5 cascade
     * applies when {@code issuer} is a delegate rather than the owner).
     *
     * @param issuer     owner DID, or a delegate holding a {@code delegate} grant
     * @param subject    the delegate zone's DID
     * @param ownerDid   the relay's owning Home
     * @param relayDid   the relay's stable DID
     * @param scope      one of full | moderation | invite-only
     * @param capability {@code use} (act) or {@code delegate} (re-delegate)
     * @param expiresAt  optional expiry (null = open-ended)
     * @param reason     optional human-readable justification
     */
    public Grant grantAdmin(String issuer, String subject, String ownerDid, String relayDid,
                            RelayAdminScope scope, Capability capability,
                            Instant expiresAt, String reason) {
        var resource = relayAdminResource(ownerDid, relayDid);
        var grant = Grant.issue(issuer, subject, resource, capability,
            scopePayload(scope, relayDid), Instant.now(), expiresAt, reason);
        return homeClient.issue(grant);
    }
}
