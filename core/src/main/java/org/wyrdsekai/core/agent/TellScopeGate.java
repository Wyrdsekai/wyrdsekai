package org.wyrdsekai.core.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * Delivery-scope gate for {@code tell} and {@code whisper}
 *
 * <p>A tell/whisper is allowed when <b>either</b>:</p>
 * <ul>
 *   <li>The target is in the same room as the sender, <b>or</b></li>
 *   <li>The sender's zone holds a bilateral contract with the target's
 *       zone that carries tell-scope to that entity.</li>
 * </ul>
 *
 * <p>Without this rule, a stranger in a public Parlor could
 * {@code tell alice "spam"} and inbox-bomb any resident even if Alice
 * isn't in the room — tunnelling past the Parlor's contract gate into the
 * private interior. The two-check rule keeps the mental model simple:
 * either you share physical presence OR you share a contract.</p>
 *
 * <h2>Contract lookup is injected</h2>
 *
 * <p>{@link ContractLookup} is a functional interface the caller supplies.
 * Core doesn't depend on the {@code between} module where
 * {@code FederationService} lives, so the <i>shape</i> of the query
 * (given sender and target zones, is there an active contract with
 * tell-scope to this target?) lives here; the <i>answer</i> is produced
 * by server wiring. This inverts the dependency and keeps the gate
 * testable without spinning up the federation stack.</p>
 *
 * <h2>Phase 1 semantics</h2>
 *
 * <p>For the first rollout we treat <i>any active bilateral agreement</i>
 * as carrying implicit tell-scope to every entity in the remote zone. A
 * later wave (tracked in the spec as fine-grained §6.10 rate-limit tiers)
 * can narrow this to per-entity or per-tier scopes.</p>
 */
public final class TellScopeGate {

    /** Decision types — sealed so callers can exhaustively switch. */
    public sealed interface Decision
        permits Decision.AllowSameRoom, Decision.AllowContract, Decision.Deny {

        /** Target shares a room with the sender — free to talk. */
        record AllowSameRoom() implements Decision {}

        /**
         * Sender's zone has a contract with the target's zone that carries
         * tell-scope. Includes the target zone for logging.
         */
        record AllowContract(String targetZoneId) implements Decision {}

        /**
         * Neither predicate held. {@code reason} is safe to surface to the
         * sender ("no route to Alice (requires same-room or contract)").
         */
        record Deny(String reason) implements Decision {}
    }

    /**
     * Injected query for "does the sender's zone hold a bilateral contract
     * with the target's zone, and does that contract include tell-scope
     * to the target entity?"
     *
     * <p>Server wiring implements this with {@code FederationService} plus
     * whatever per-target scope policy applies. A deny-by-default
     * implementation is provided via {@link #DENY_ALL} for contexts where
     * no contract lookup is available (single-node deployments,
     * unit tests for same-room paths).</p>
     */
    @FunctionalInterface
    public interface ContractLookup {
        boolean hasTellScope(String senderZoneId, String targetZoneId, String targetEntityId);
    }

    /**
     * Trivial implementation for environments without federation wiring.
     * Every tell becomes same-room-only under this lookup — sufficient for
     * single-node tests and pre-federation deployments.
     */
    public static final ContractLookup DENY_ALL = (s, t, te) -> false;

    private TellScopeGate() {}

    /**
     * Decide whether {@code sender} may tell/whisper {@code target}.
     *
     * @param senderEntityId   sender's entity id (non-null)
     * @param senderZoneId     sender's home zone id
     * @param targetEntityId   target's entity id (non-null)
     * @param targetZoneId     target's home zone id (may be {@code null} if
     *                         we don't yet know — treated as same-zone
     *                         as sender for lookup purposes)
     * @param registry         EntityRegistry for room lookup
     * @param contracts        contract lookup (use {@link #DENY_ALL} if none)
     */
    public static Decision check(String senderEntityId, String senderZoneId,
                                  String targetEntityId, String targetZoneId,
                                  EntityRegistry registry, ContractLookup contracts) {
        Objects.requireNonNull(senderEntityId, "senderEntityId");
        Objects.requireNonNull(targetEntityId, "targetEntityId");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(contracts, "contracts");

        // Self-tell is always allowed — echo to yourself is harmless and a
        // null-target blocker would bite legitimate "reminder to self" flows.
        if (senderEntityId.equals(targetEntityId)) {
            return new Decision.AllowSameRoom();
        }

        // 1. Same-room check. If either party isn't online / tracked,
        //    same-room can't be true and we fall through to contract.
        Optional<String> senderRoom = registry.roomOf(senderEntityId);
        Optional<String> targetRoom = registry.roomOf(targetEntityId);
        if (senderRoom.isPresent() && targetRoom.isPresent()
                && senderRoom.get().equals(targetRoom.get())) {
            return new Decision.AllowSameRoom();
        }

        // 2. Contract check. A tell within the same zone with no contract
        //    (e.g. local agent-to-agent where both are tracked in
        //    EntityRegistry but not in the same room) falls through here —
        //    in Phase-1 we permit intra-zone tells unconditionally, since
        //    the household is a single trust boundary. That's the "no
        //    targetZoneId means same-zone-as-sender" branch.
        if (targetZoneId == null || targetZoneId.isBlank()
                || targetZoneId.equals(senderZoneId)) {
            // Intra-zone tell — always allowed.
            return new Decision.AllowContract(senderZoneId);
        }
        if (contracts.hasTellScope(senderZoneId, targetZoneId, targetEntityId)) {
            return new Decision.AllowContract(targetZoneId);
        }

        return new Decision.Deny(
            "no route to " + targetEntityId
                + " (requires same-room or contract with " + targetZoneId + ")");
    }

    /**
     * Convenience predicate for callers that don't need the reason string.
     * Equivalent to {@code check(...) instanceof Decision.Allow*}.
     */
    public static boolean allows(String senderEntityId, String senderZoneId,
                                  String targetEntityId, String targetZoneId,
                                  EntityRegistry registry, ContractLookup contracts) {
        return !(check(senderEntityId, senderZoneId, targetEntityId, targetZoneId,
            registry, contracts) instanceof Decision.Deny);
    }
}
