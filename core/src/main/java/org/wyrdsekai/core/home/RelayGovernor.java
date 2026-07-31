package org.wyrdsekai.core.home;

import org.wyrdsekai.common.home.RelayAdminOp;
import org.wyrdsekai.common.home.RelayAdminScope;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * (P4) — the in-world governance binding.
 *
 * <p>Bundles the four things the governance furnishing needs to manage a relay
 * from inside the world:
 * <ol>
 *   <li>the relay's {@code owner_did} and stable {@code relayDid} (which relay,
 *       and who roots its grant chain);</li>
 *   <li>the {@link RelayGovernance} predicate (P2) for <em>per-action,
 *       zone-side</em> authorization — so the furnishing only offers/permits ops
 *       the caller is entitled to before any network call;</li>
 *   <li>a {@link RelayAdminGateway} (server-implemented, signed) that performs
 *       the actual {@code /admin} call (P3), which re-authorizes relay-side;</li>
 *   <li>the acting caller's DID (the signer / zone).</li>
 * </ol>
 *
 * <p>This object is owner-and-grant aware: {@link #scopeOf(String)} reports the
 * caller's effective scope ({@code "owner"} | {@code full} | {@code moderation}
 * | {@code invite-only} | {@code null}) so the furnishing can show/hide actions.
 * Per-action gating is enforced in {@link #authorizeAndCall} — an op the caller
 * lacks scope for is refused before the gateway is touched.</p>
 *
 * <p>P4 covers a single relay (the one this zone configured + owns-or-was-granted).
 * Multi-relay is a list of these, one per administered relay.</p>
 */
public final class RelayGovernor {

    private final RelayGovernance governance;
    private final RelayAdminGateway gateway;
    private final String ownerDid;
    private final String relayDid;
    private final String relayLabel;
    // #13 (2026-07-19 OSS hardening) — recognises the zone steward as owner for
    // zone-side authorization. Nullable (no steward-awareness). See isOwner.
    private final Predicate<String> stewardCheck;

    public RelayGovernor(RelayGovernance governance, RelayAdminGateway gateway,
                         String ownerDid, String relayDid, String relayLabel) {
        this(governance, gateway, ownerDid, relayDid, relayLabel, null);
    }

    public RelayGovernor(RelayGovernance governance, RelayAdminGateway gateway,
                         String ownerDid, String relayDid, String relayLabel,
                         Predicate<String> stewardCheck) {
        this.governance = governance;
        this.gateway = gateway;
        this.ownerDid = ownerDid;
        this.relayDid = relayDid;
        this.relayLabel = relayLabel;
        this.stewardCheck = stewardCheck;
    }

    public String ownerDid() { return ownerDid; }
    public String relayDid() { return relayDid; }
    public String relayLabel() { return relayLabel; }

    /** The acting/signing DID (the zone). */
    public String actingDid() { return gateway == null ? null : gateway.actingDid(); }

    /** True if {@code callerDid} is the relay owner. */
    public boolean isOwner(String callerDid) {
        if (callerDid == null) return false;
        if (callerDid.equals(ownerDid)) return true;
        // #13 (2026-07-19 OSS hardening) — on a home / co-located relay the
        // ownerDid defaults to the node's NKey identity, but the human who owns
        // the zone administers the relay via their ACCOUNT DID (the Warden
        // furnishing passes playerId as callerDid). Without this the steward who
        // deployed and owns the relay was denied all relay admin on a fresh home
        // relay (scopeOf → null). Treat the zone steward as owner for zone-side
        // authorization; the relay-side re-auth at the gateway (signed by the
        // node NKey) is unaffected and still applies.
        return stewardCheck != null && stewardCheck.test(callerDid);
    }

    /**
     * The caller's effective scope label for the furnishing:
     * {@code "owner"} | {@code "full"} | {@code "moderation"} |
     * {@code "invite-only"} | {@code null} (no authority).
     */
    public String scopeOf(String callerDid) {
        if (isOwner(callerDid)) return "owner";
        var held = governance.heldScope(callerDid, ownerDid, relayDid);
        return held == null ? null : held.wire();
    }

    /** Zone-side per-action authorization (P2). */
    public boolean canDo(String callerDid, RelayAdminOp op) {
        // #13 — the zone steward is owner-equivalent for zone-side authz;
        // RelayGovernance.authorize only knows the NKey ownerDid, so short-circuit.
        if (isOwner(callerDid)) return true;
        return governance.authorize(callerDid, ownerDid, relayDid, op);
    }

    /** True if the caller may issue/re-delegate relay-admin grants. */
    public boolean canDelegate(String callerDid) {
        // #13 — the zone steward owns the relay and may delegate grants.
        if (isOwner(callerDid)) return true;
        return governance.canDelegate(callerDid, ownerDid, relayDid);
    }

    /**
     * Authorize {@code op} for {@code callerDid} zone-side, then (if allowed)
     * dispatch the signed call via the gateway. Returns a flat result map.
     * On a zone-side denial it returns {@code {ok:false, status:403,
     * error:"..."}} WITHOUT touching the network — the furnishing should not
     * even surface ops the caller can't do, but this is the defense in depth.
     */
    public Map<String, Object> authorizeAndCall(String callerDid, RelayAdminOp op,
                                                 Map<String, Object> args) {
        if (gateway == null) {
            return Map.of("ok", false, "status", 0,
                "error", "no relay admin gateway configured for this zone");
        }
        if (op == null) {
            return Map.of("ok", false, "status", 400, "error", "unknown op");
        }
        if (!canDo(callerDid, op)) {
            return Map.of("ok", false, "status", 403,
                "error", "you lack relay-admin scope for '" + op.wire()
                    + "' on this relay (needs " + op.requiredScope().wire() + "+)");
        }
        return gateway.call(op.wire(), args);
    }

    /** The relay's registrations (already authorized via {@link #authorizeAndCall}). */
    public List<Map<String, Object>> listRegistrations(String callerDid) {
        var res = authorizeAndCall(callerDid, RelayAdminOp.LIST, null);
        var v = res.get("registrations");
        if (v instanceof List<?> l) {
            @SuppressWarnings("unchecked")
            var typed = (List<Map<String, Object>>) l;
            return typed;
        }
        return List.of();
    }

    /** Parse a scope wire-name to the enum, tolerating null. */
    public static RelayAdminScope parseScope(String scope) {
        return RelayAdminScope.parse(scope);
    }
}
