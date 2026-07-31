package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.home.ActionGrantCheck;
import org.wyrdsekai.core.security.Denial;
import org.wyrdsekai.core.security.DenialCatalog;

import java.util.Set;

/**
 * / ACTION — the autonomy-consent axis
 * of action enforcement, evaluated AFTER the maturity tier gate and ONLY for
 * autonomous (non-human-directed) actions.
 *
 * <p>{@link ActionPolicy#autonomyTierFor(String)} classifies every verb:
 * <ul>
 *   <li>{@code AMBIENT}/{@code VISIBLE} — allowed autonomously.</li>
 *   <li>{@code CONSENT} — allowed by default (a household that wants opt-in
 *       consent sets {@code WYRDSEKAI_ACTION_STRICT_GRANTS=true}, after which
 *       the verb needs an owner-issued
 *       {@code home://{owner}/action/{verb}} use-grant).</li>
 *   <li>{@code FORBIDDEN} — never autonomous UNLESS the owner has explicitly
 *       granted the verb (the grant IS the owner promoting it up the ladder,
 * per the upgrade rule). Human-directed
 *       flows are unaffected — this gate never sees them.</li>
 * </ul>
 *
 * <p>{@code emergency_call} is safety-floored: never consent-blocked, mirroring
 * the {@code herald.call.emergency} skill-permission floor. An agent that
 * detects a crisis on its own time (fall detection, unresponsive bondholder)
 * must not find the phone consent-locked.
 *
 * <p>Denials carry the {@code request_access} template for
 * {@code home://{owner}/action/{verb}} — the existing GrantRequest pipeline
 * (Board approve) mints the grant, so the agent can ask and the steward can
 * say yes without any new admin surface.
 */
public final class AutonomyGate {

    /** Verbs never consent-blocked, whatever the mode (welfare/safety floor). */
    static final Set<String> SAFETY_FLOOR = Set.of("emergency_call");

    private AutonomyGate() {}

    /** Outcome of the consent-axis check. */
    public sealed interface Decision {
        record Allow() implements Decision {}
        record Deny(Denial denial) implements Decision {}
    }

    private static final Decision.Allow ALLOW = new Decision.Allow();

    /**
     * Evaluate the consent axis for an AUTONOMOUS action. Callers must have
     * already applied the human-directed bypass and the maturity tier gate.
     *
     * @param actionType        canonical verb ({@link ActionPolicy#actionTypeOf})
     * @param actionDescription human phrase ({@link ActionPolicy#describeAction})
     * @param grantCheck        owner-grant lookup (strict-constructed; null =
     *                          no grant infrastructure in this process)
     * @param consentStrict     household opt-in: CONSENT verbs need a grant
     * @param companionDid      grant subject (world entity id, matching the
     *                          request_access pipeline)
     * @param ownerDid          grant owner (bondholder, or steward fallback);
     *                          null when neither exists
     */
    public static Decision evaluate(String actionType, String actionDescription,
            ActionGrantCheck grantCheck, boolean consentStrict,
            String companionDid, String ownerDid) {
        if (actionType == null) return ALLOW;
        if (SAFETY_FLOOR.contains(actionType)) return ALLOW;

        var tier = ActionPolicy.autonomyTierFor(actionType);
        switch (tier) {
            case AMBIENT, VISIBLE -> {
                return ALLOW;
            }
            case CONSENT -> {
                if (!consentStrict) return ALLOW;
                if (hasGrant(grantCheck, companionDid, ownerDid, actionType)) return ALLOW;
                return new Decision.Deny(DenialCatalog.autonomyGated(
                    actionType, actionDescription, tier, ownerDid));
            }
            case FORBIDDEN -> {
                if (hasGrant(grantCheck, companionDid, ownerDid, actionType)) return ALLOW;
                return new Decision.Deny(DenialCatalog.autonomyGated(
                    actionType, actionDescription, tier, ownerDid));
            }
        }
        return ALLOW;
    }

    private static boolean hasGrant(ActionGrantCheck grantCheck,
            String companionDid, String ownerDid, String actionType) {
        if (grantCheck == null || companionDid == null || ownerDid == null) return false;
        return grantCheck.canPerform(companionDid, ownerDid, actionType);
    }
}
