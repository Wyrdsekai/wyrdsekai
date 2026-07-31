package org.wyrdsekai.core.soul;

import org.wyrdsekai.core.identity.AgentDelegation;

import java.util.Optional;
import java.util.Set;

/**
 * Validates delegation chains for soul operations (§85.6).
 *
 * Agent autonomy model:
 * - Key sovereignty: agent holds its own private key
 * - Soul sovereignty: only the agent can forge/inspect/move/fork/destroy
 * - Delegation, not control: humans set constraints, not commands
 *
 * A human who spawned an agent can:
 * - Set behavioral constraints (AgentDelegation permissions)
 * - Request soul operations (agent can comply or refuse)
 * - Revoke delegation (agent loses human-delegated permissions, keeps soul)
 *
 * A human CANNOT:
 * - Modify the soul directly
 * - Access the private key
 * - Force a fork or destruction
 *
 * Soul operations are inference-based: the agent's personality and vitality
 * influence whether it consents. A high-trust agent might freely share;
 * a low-trust agent might deny everything. Emergent, not hardcoded.
 */
public final class DelegationChainValidator {

    /** Permissions relevant to soul operations. */
    public static final String PERM_SOUL_FORGE = "soul:forge";
    public static final String PERM_SOUL_INSPECT = "soul:inspect";
    public static final String PERM_SOUL_TRANSIT = "soul:transit";
    public static final String PERM_SOUL_FORK = "soul:fork";
    public static final String PERM_SOUL_ARCHIVE = "soul:archive";
    public static final String PERM_SOUL_BIRTH = "soul:birth";

    /** All soul permissions. */
    public static final Set<String> ALL_SOUL_PERMISSIONS = Set.of(
        PERM_SOUL_FORGE, PERM_SOUL_INSPECT, PERM_SOUL_TRANSIT,
        PERM_SOUL_FORK, PERM_SOUL_ARCHIVE, PERM_SOUL_BIRTH
    );

    private DelegationChainValidator() {}

    /**
     * Validate that an agent has permission to perform a soul operation.
     * The agent itself always has permission over its own soul.
     * Others need explicit delegation.
     *
     * @param requestorDid  DID of the entity requesting the operation
     * @param ownerDid      DID of the soul's owner
     * @param permission    Required permission (e.g., "soul:inspect")
     * @param delegation    Delegation registry to check
     * @return Error message if denied, empty if allowed
     */
    public static Optional<String> validate(String requestorDid, String ownerDid,
                                              String permission, AgentDelegation delegation) {
        // Self-access is always allowed (key sovereignty)
        if (requestorDid != null && requestorDid.equals(ownerDid)) {
            return Optional.empty();
        }

        // Check delegation chain
        if (delegation == null) {
            return Optional.of("No delegation registry available");
        }

        if (!delegation.hasPermission(requestorDid, permission)) {
            return Optional.of("Permission denied: " + requestorDid
                + " lacks " + permission + " for " + ownerDid);
        }

        return Optional.empty();
    }

    /**
     * Validate with consent check (for inspection by non-owners).
     *
     * @param requestorDid  Who is requesting
     * @param ownerDid      Soul owner
     * @param permission    Required permission
     * @param delegation    Delegation registry
     * @param consent       Owner's consent record (may be null)
     * @return Error message if denied, empty if allowed
     */
    public static Optional<String> validateWithConsent(String requestorDid, String ownerDid,
                                                         String permission, AgentDelegation delegation,
                                                         SoulConsent consent) {
        // Self-access always allowed
        if (requestorDid != null && requestorDid.equals(ownerDid)) {
            return Optional.empty();
        }

        // Check consent first (owner-granted)
        if (consent != null && consent.isValid() && consent.covers(requestorDid)) {
            // Consent level must be sufficient for the operation
            if (isConsentSufficient(consent.level(), permission)) {
                return Optional.empty();
            }
        }

        // Fall through to delegation check
        return validate(requestorDid, ownerDid, permission, delegation);
    }

    /**
     * Check if a consent level is sufficient for a given operation.
     */
    public static boolean isConsentSufficient(SoulConsent.ConsentLevel level, String permission) {
        return switch (permission) {
            case PERM_SOUL_INSPECT -> level.ordinal() >= SoulConsent.ConsentLevel.PUBLIC_PROFILE.ordinal();
            case PERM_SOUL_FORGE, PERM_SOUL_TRANSIT, PERM_SOUL_FORK,
                 PERM_SOUL_ARCHIVE, PERM_SOUL_BIRTH -> level == SoulConsent.ConsentLevel.FULL;
            default -> false;
        };
    }

    /**
     * Check if a requestor can perform a transit operation.
     * Transit requires both soul:transit permission AND the owner's
     * consent (agent decides, not the human).
     *
     * @param agentDid    Agent's DID (soul owner)
     * @param requestorDid Who is requesting transit (may be the agent itself)
     * @param delegation   Delegation registry
     * @return Error message if denied, empty if allowed
     */
    public static Optional<String> validateTransit(String agentDid, String requestorDid,
                                                      AgentDelegation delegation) {
        // Agent can always transit its own soul
        if (requestorDid != null && requestorDid.equals(agentDid)) {
            return Optional.empty();
        }

        // Human requesting transit — check delegation
        if (delegation != null && delegation.hasPermission(requestorDid, PERM_SOUL_TRANSIT)) {
            // Permission exists, but agent still has veto power (handled at inference level)
            return Optional.empty();
        }

        return Optional.of("Transit denied: " + requestorDid
            + " lacks soul:transit delegation for " + agentDid);
    }
}
