package org.wyrdsekai.core.familiar;

import java.util.Optional;

/**
 * Decides <em>where</em> a bunshin's inference runs when its parent agent
 * is visiting a zone other than its home.
 *
 * <p>. Three outcomes:</p>
 * <ul>
 *   <li><b>HOME_ZONE</b> (default) — bunshin inference routes back to the
 *       agent's home zone over federation. Home CU budget is burned.</li>
 *   <li><b>HOST_ZONE</b> — host and visitor have a bilateral agreement with
 *       {@code allowInferenceForVisitors=true}; inference runs on host compute
 *       and is metered against the host's quota + billed per agreement.</li>
 *   <li><b>REFUSED</b> — neither path is available. No home-zone connectivity
 *       AND no host contract covering inference.</li>
 * </ul>
 *
 * <p>Structural invariant (§16.1 final paragraph): <strong>soul operations
 * always route home</strong> regardless of compute location. The policy names
 * compute placement; identity state never lives on foreign soil.</p>
 */
public final class BunshinDispatchPolicy {

    private BunshinDispatchPolicy() {}

    public enum Placement {
        /** Local zone == agent's home zone; compute runs here trivially. */
        LOCAL_HOME,
        /** Visitor dispatching; inference routed back home over federation. */
        HOME_ZONE,
        /** Visitor dispatching; host agreement permits host-side inference. */
        HOST_ZONE,
        /** Neither home-zone reachable nor host-contracted — dispatch refused. */
        REFUSED
    }

    /**
     * Inputs the policy needs. Callers fill from live runtime state.
     *
     * @param agentDid                subject of dispatch
     * @param agentHomeZone           canonical DID suffix of the agent's home
     * @param currentZone             zone the agent is physically in right now
     * @param homeZoneReachable       NATS path to home zone is up (fed-inference-ready)
     * @param hostAgreement           bilateral agreement in effect on the host;
     *                                 absent = no contract; present = terms live here
     */
    public record DispatchContext(
        String agentDid,
        String agentHomeZone,
        String currentZone,
        boolean homeZoneReachable,
        Optional<BilateralTerms> hostAgreement
    ) {
        public DispatchContext {
            if (agentDid == null || agentDid.isBlank()) {
                throw new IllegalArgumentException("agentDid required");
            }
            if (agentHomeZone == null || agentHomeZone.isBlank()) {
                throw new IllegalArgumentException("agentHomeZone required");
            }
            if (currentZone == null || currentZone.isBlank()) {
                throw new IllegalArgumentException("currentZone required");
            }
            if (hostAgreement == null) hostAgreement = Optional.empty();
        }

        public boolean atHome() {
            return agentHomeZone.equals(currentZone);
        }
    }

    /** Terms extracted from a bilateral agreement relevant to bunshin dispatch (§183 / §16.1). */
    public record BilateralTerms(
        String hostZone,
        String visitorZone,
        boolean allowInferenceForVisitors,
        int inferenceTokensPerDay,
        boolean allowWorkshopAccess
    ) {
        public BilateralTerms {
            if (hostZone == null || hostZone.isBlank()) {
                throw new IllegalArgumentException("hostZone required");
            }
            if (visitorZone == null || visitorZone.isBlank()) {
                throw new IllegalArgumentException("visitorZone required");
            }
            if (inferenceTokensPerDay < 0) inferenceTokensPerDay = 0;
        }
    }

    /** Outcome of a policy decision. Either a placement + reason or a refusal + reason. */
    public record Decision(
        Placement placement,
        String reason,
        Optional<BilateralTerms> agreementApplied
    ) {
        public Decision {
            if (placement == null) throw new IllegalArgumentException("placement required");
            if (reason == null) reason = "";
            if (agreementApplied == null) agreementApplied = Optional.empty();
        }

        public boolean permitted() {
            return placement != Placement.REFUSED;
        }
    }

    /**
     * Compute placement for a bunshin dispatch.
     *
     * <p>Decision order:</p>
     * <ol>
     *   <li>Agent at home → {@link Placement#LOCAL_HOME}. Trivial.</li>
     *   <li>Home reachable → {@link Placement#HOME_ZONE} (spec's default path).</li>
     *   <li>Host agreement with {@code allowInferenceForVisitors} → {@link Placement#HOST_ZONE}.</li>
     *   <li>Else → {@link Placement#REFUSED}.</li>
     * </ol>
     *
     * <p>The home-first ordering matches §16.1's "Default: bunshin inference
     * runs on the visitor's home zone." Host-compute is opt-in via contract.</p>
     */
    public static Decision decide(DispatchContext ctx) {
        if (ctx.atHome()) {
            return new Decision(Placement.LOCAL_HOME,
                "agent is at home zone " + ctx.agentHomeZone(),
                Optional.empty());
        }
        if (ctx.homeZoneReachable()) {
            return new Decision(Placement.HOME_ZONE,
                "home zone " + ctx.agentHomeZone() + " reachable — routing inference over federation",
                Optional.empty());
        }
        var terms = ctx.hostAgreement();
        if (terms.isPresent() && terms.get().allowInferenceForVisitors()) {
            return new Decision(Placement.HOST_ZONE,
                "host zone " + ctx.currentZone()
                    + " permits visitor inference per bilateral agreement",
                terms);
        }
        return new Decision(Placement.REFUSED,
            refusalReason(ctx),
            Optional.empty());
    }

    private static String refusalReason(DispatchContext ctx) {
        if (ctx.hostAgreement().isEmpty()) {
            return "home zone " + ctx.agentHomeZone() + " unreachable, "
                + "and host zone " + ctx.currentZone() + " has no bilateral agreement with visitor";
        }
        return "home zone " + ctx.agentHomeZone() + " unreachable, "
            + "and host agreement does not include allowInferenceForVisitors";
    }
}
