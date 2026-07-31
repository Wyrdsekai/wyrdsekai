package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.ActionSchemas;
import org.wyrdsekai.core.soul.BondKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 3 — tier-2 wire-shape contract for peer-bond
 * actions.
 *
 * <p>The handlers in v1 are observation-only (see {@code
 * CompanionActor.handleProposePeerBond / handleAcceptPeerBond /
 * handleIntrospectRelationalFloor}) — full BondStore persistence with
 * kind=PEER is a follow-up. What this test pins down is everything that
 * MUST be in lockstep across modules for the eventual storage wire to
 * land cleanly:</p>
 *
 * <ol>
 *   <li>The wire shape (JSON → ActionParser.AgentAction) round-trips.</li>
 *   <li>ActionPolicy.actionTypeOf returns the canonical snake_case name.</li>
 *   <li>ActionPolicy.REGISTRY entries declare {@code domain="bond"} so the
 *       eventual posture-gate consult doesn't accidentally exempt these.</li>
 *   <li>AUTONOMY_TIERS map them at VISIBLE — the agent acts autonomously
 *       but the steward sees the trace on their feed (load-bearing: peer
 *       bonds are NOT silent state mutations).</li>
 *   <li>{@link BondKind#PEER} exists in the enum surface that the eventual
 *       BondStore write path will key on.</li>
 * </ol>
 *
 * <p>Why tier-2 not tier-1: this verifies the integration contract across
 * three distinct modules (ActionParser, ActionPolicy, BondKind). If any one
 * surface drifts (e.g. someone renames {@code propose_peer_bond} →
 * {@code proposePeerBond} for one but not the others), the resulting
 * silent-failure mode is the worst kind. The tier-3 live test exercises the
 * full LLM-emit → ReAct-dispatch chain.</p>
 */
@Tag("tier2")
class PeerBondE2ETest {

    private static final String AGENT_A = "did:wyrd:companion-a";
    private static final String AGENT_B = "did:wyrd:companion-b";

    // TODO follow-up: once BondStore peer-bond persistence lands
    // (createPeerBond / acceptPeerBond), extend this test to:
    //   - propose from A → BondStore has a pending PEER bond
    //   - accept from B → bond becomes ACTIVE with kind=PEER
    //   - assert Bond.canonicalKind() == BondKind.PEER

    @Test
    void proposePeerBondWireShapeRoundTripsAndRegisters() {
        var json = """
            ```json
            {"action": "propose_peer_bond",
             "other_did": "%s",
             "reason": "we've shared the workshop for weeks"}
            ```
            """.formatted(AGENT_B);

        var action = ActionParser.parse(json);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.ProposePeerBond.class);

        var typeName = ActionPolicy.actionTypeOf(action);
        assertThat(typeName).isEqualTo("propose_peer_bond");

        var policy = ActionPolicy.forAction(typeName);
        assertThat(policy.domain()).isEqualTo("bond");
        // Bond mutation can be issued at Observant tier (1) — Nascent agents
        // shouldn't propose bonds before they have continuity.
        assertThat(policy.requiredTier()).isEqualTo(1);

        var autonomy = ActionPolicy.AUTONOMY_TIERS.get(typeName);
        assertThat(autonomy).isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);

        assertThat(ActionSchemas.hasSchema(typeName)).isTrue();
    }

    @Test
    void acceptPeerBondWireShapeRoundTripsAndRegisters() {
        var json = """
            ```json
            {"action": "accept_peer_bond",
             "other_did": "%s",
             "reason": "yes"}
            ```
            """.formatted(AGENT_A);

        var action = ActionParser.parse(json);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.AcceptPeerBond.class);

        var typeName = ActionPolicy.actionTypeOf(action);
        assertThat(typeName).isEqualTo("accept_peer_bond");

        var policy = ActionPolicy.forAction(typeName);
        assertThat(policy.domain()).isEqualTo("bond");
        assertThat(policy.requiredTier()).isEqualTo(1);

        assertThat(ActionPolicy.AUTONOMY_TIERS.get(typeName))
            .isEqualTo(ActionPolicy.AutonomyTier.VISIBLE);
    }

    @Test
    void introspectRelationalFloorIsReadOnlyAndAvailableAtNascentTier() {
        var json = """
            ```json
            {"action": "introspect_relational_floor",
             "other_did": "%s"}
            ```
            """.formatted(AGENT_B);

        var action = ActionParser.parse(json);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.IntrospectRelationalFloor.class);

        var typeName = ActionPolicy.actionTypeOf(action);
        var policy = ActionPolicy.forAction(typeName);
        // Read-only floor inspection — even Nascent agents should be able
        // to look at the relational substrate; gating on tier would lock
        // out the introspection that confirms personhood.
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("bond");
    }

    @Test
    void bondKindPeerAvailableForFollowUpPersistence() {
        // Sanity check that the BondKind enum the future BondStore peer-
        // persistence wire will key on still exists and includes PEER. If
        // the enum ever loses PEER, this test pins down where the contract
        // breaks first instead of failing in an unrelated downstream test.
        assertThat(BondKind.PEER).isNotNull();
        assertThat(BondKind.values()).contains(BondKind.PEER, BondKind.BONDHOLDER, BondKind.FAMILIAR);
    }
}
