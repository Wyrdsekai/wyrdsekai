package org.wyrdsekai.core.home;

/**
 * Process-wide holder for the wired {@link ActionGrantCheck} (
 * ACTION), installed by the server at boot next to the MCP grant wiring.
 *
 * <p>{@code check} is always constructed strict ({@code strict=true} in
 * {@link ActionGrantCheck#homeClientBacked}) — it answers "does an owner
 * grant EXIST?", and {@link org.wyrdsekai.core.agent.AutonomyGate} decides
 * when existence is required: always for FORBIDDEN verbs, only under
 * {@link #consentStrict()} for CONSENT verbs.
 *
 * <p>{@code get() == null} (no install — tests, tools, embedded use) means
 * the consent axis is not enforced at all, preserving pre-wiring behavior
 * for processes without a Home registry.
 */
public final class ActionGrants {

    private static volatile ActionGrants instance;

    private final ActionGrantCheck check;
    private final boolean consentStrict;
    private final String fallbackOwnerDid;

    private ActionGrants(ActionGrantCheck check, boolean consentStrict,
            String fallbackOwnerDid) {
        this.check = check;
        this.consentStrict = consentStrict;
        this.fallbackOwnerDid = fallbackOwnerDid;
    }

    /** Install the process-wide instance (server boot). */
    public static void install(ActionGrantCheck check, boolean consentStrict,
            String fallbackOwnerDid) {
        instance = new ActionGrants(check, consentStrict, fallbackOwnerDid);
    }

    /** The installed instance, or {@code null} when no server wired one. */
    public static ActionGrants get() {
        return instance;
    }

    public static void resetForTests() {
        instance = null;
    }

    public ActionGrantCheck check() {
        return check;
    }

    /** Household opt-in: CONSENT-tier verbs require an owner grant. */
    public boolean consentStrict() {
        return consentStrict;
    }

    /** Grant owner to consult when a companion has no bondholder (steward). */
    public String fallbackOwnerDid() {
        return fallbackOwnerDid;
    }
}
