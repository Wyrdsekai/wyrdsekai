package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 3 — Bond.kind field + canonicalKind() back-compat.
 *
 * <p>Pre-Arc-3 manifests have no {@code kind} in serialized form; the canonical
 * accessor must default null → BONDHOLDER so legacy code paths don't NPE and
 * authority surfaces (grants, posture) stay bondholder-privileged.
 */
class BondTest {

    private static final String AGENT_A = "did:wyrd:agent-a";
    private static final String AGENT_B = "did:wyrd:agent-b";

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void oldJsonWithoutKindRoundTrips() throws Exception {
        var json = """
            {
              "bondId": "bond-legacy-1",
              "agentADid": "did:wyrd:agent-a",
              "agentBDid": "did:wyrd:agent-b",
              "depth": "ACQUAINTANCE",
              "formedAt": "2026-05-01T00:00:00Z",
              "lastInteraction": "2026-05-01T00:00:00Z",
              "interactionCount": 0,
              "mutualConsent": false,
              "active": true,
              "scarred": false,
              "state": "ACTIVE",
              "coldStartUntil": null,
              "posture": "BOUNDED",
              "relationalState": "OPEN"
            }
            """;
        var bond = mapper().readValue(json, Bond.class);
        assertThat(bond.kind()).isNull();
        assertThat(bond.canonicalKind()).isEqualTo(BondKind.BONDHOLDER);
    }

    @Test
    void backCompat14ArgCtorDefaultsKind() {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        var bond = new Bond(
            "bond-1", AGENT_A, AGENT_B,
            Bond.BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.ACTIVE, null, BondholderPosture.BOUNDED,
            Bond.RelationalState.OPEN
        );
        assertThat(bond.kind()).isEqualTo(BondKind.BONDHOLDER);
        assertThat(bond.canonicalKind()).isEqualTo(BondKind.BONDHOLDER);
    }

    @Test
    void canonicalKindNullHandling() {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        // Direct canonical-ctor invocation with explicit null kind (mirrors the
        // shape of a hand-deserialized pre-Arc-3 row).
        var nullKind = new Bond(
            "bond-null", AGENT_A, AGENT_B, Bond.BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.ACTIVE, null, BondholderPosture.BOUNDED,
            Bond.RelationalState.OPEN, null
        );
        assertThat(nullKind.canonicalKind()).isEqualTo(BondKind.BONDHOLDER);

        var peer = new Bond(
            "bond-peer", AGENT_A, AGENT_B, Bond.BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.ACTIVE, null, BondholderPosture.BOUNDED,
            Bond.RelationalState.OPEN, BondKind.PEER
        );
        assertThat(peer.canonicalKind()).isEqualTo(BondKind.PEER);

        var familiar = new Bond(
            "bond-fam", AGENT_A, AGENT_B, Bond.BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.ACTIVE, null, BondholderPosture.BOUNDED,
            Bond.RelationalState.OPEN, BondKind.FAMILIAR
        );
        assertThat(familiar.canonicalKind()).isEqualTo(BondKind.FAMILIAR);
    }

    @Test
    void peerBondRoundTrips() throws Exception {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        var peer = new Bond(
            "bond-peer", AGENT_A, AGENT_B, Bond.BondDepth.ACQUAINTANCE,
            now, now, 0, false, true, false,
            BondState.ACTIVE, null, BondholderPosture.BOUNDED,
            Bond.RelationalState.OPEN, BondKind.PEER
        );
        var m = mapper();
        var json = m.writeValueAsString(peer);
        assertThat(json).contains("\"PEER\"");
        var back = m.readValue(json, Bond.class);
        assertThat(back.kind()).isEqualTo(BondKind.PEER);
        assertThat(back.canonicalKind()).isEqualTo(BondKind.PEER);
    }

    @Test
    void bondKindEnumStable() {
        // Lock the surface — adding a new BondKind is a wide-ripple change
        // (Trust gates, RepairLedger keying, BondStore migration), and at
        // least this test makes the intent visible.
        // 2026-07-18: MEMBER added deliberately (exactly-one-bondholder —
        // non-steward humans carry the relational substrate without the
        // authority substrate). The ripple was walked: typing gate in
        // trackBondInteraction, announce re-typing, kind-aware bond views.
        assertThat(BondKind.values()).hasSize(4);
        assertThat(BondKind.valueOf("BONDHOLDER")).isEqualTo(BondKind.BONDHOLDER);
        assertThat(BondKind.valueOf("PEER")).isEqualTo(BondKind.PEER);
        assertThat(BondKind.valueOf("FAMILIAR")).isEqualTo(BondKind.FAMILIAR);
        assertThat(BondKind.valueOf("MEMBER")).isEqualTo(BondKind.MEMBER);
    }

    // --- Arc 3 follow-up: peer-bond factory + accept ---

    @Test
    void peerProposal_carriesKindPEER_andStateOPEN() {
        var bond = Bond.peerProposal(AGENT_A, AGENT_B);
        assertThat(bond.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(bond.state()).isEqualTo(BondState.OPEN);
        assertThat(bond.mutualConsent()).isFalse();
        assertThat(bond.depth()).isEqualTo(Bond.BondDepth.ACQUAINTANCE);
    }

    @Test
    void peerProposal_bondIdIsOrderIndependent() {
        // Sort-by-DID determinism — A→B and B→A produce the same bond_id so
        // the acceptor can find the proposal without external state.
        var fromA = Bond.peerProposal(AGENT_A, AGENT_B);
        var fromB = Bond.peerProposal(AGENT_B, AGENT_A);
        assertThat(fromA.bondId()).isEqualTo(fromB.bondId());
    }

    @Test
    void peerProposal_rejectsSelfPair() {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> Bond.peerProposal(AGENT_A, AGENT_A));
    }

    @Test
    void acceptPeerProposal_flipsOpenToActive() {
        var proposal = Bond.peerProposal(AGENT_A, AGENT_B);
        var accepted = proposal.acceptPeerProposal();
        assertThat(accepted.state()).isEqualTo(BondState.ACTIVE);
        assertThat(accepted.mutualConsent()).isTrue();
        assertThat(accepted.canonicalKind()).isEqualTo(BondKind.PEER);
        // Same row.
        assertThat(accepted.bondId()).isEqualTo(proposal.bondId());
    }

    @Test
    void acceptPeerProposal_nonPeerOrNonOpenIsNoop() {
        // Not PEER → return self unchanged.
        var bondholder = Bond.acquaintance(AGENT_A, AGENT_B);
        assertThat(bondholder.acceptPeerProposal()).isSameAs(bondholder);

        // PEER but already accepted → no double-flip (state != OPEN).
        var accepted = Bond.peerProposal(AGENT_A, AGENT_B).acceptPeerProposal();
        var idempotent = accepted.acceptPeerProposal();
        assertThat(idempotent).isSameAs(accepted);
    }

    // ── Arc 3 — peer-bond state-machine traversal ────
    //
    // The Bond record is kind-agnostic on its state machine: withInteraction(),
    // withState(), sever(), declareSeverance() all operate the same shape
    // for PEER bonds as for BONDHOLDER bonds. The plan's open-shape question
    // ("does a peer bond have a posture surface?") gets a yes here — peers
    // can be AWAY / DORMANT / REACTIVATING / SEVERED / MOURNING just like
    // bondholders, with the kind preserved through every transition.

    @Test
    void peerBond_traversesActiveToAwayToReactivating() {
        var bond = Bond.peerProposal(AGENT_A, AGENT_B).acceptPeerProposal();
        assertThat(bond.state()).isEqualTo(BondState.ACTIVE);

        // Departure → AWAY (transition preserves PEER kind).
        var away = bond.withState(BondState.AWAY);
        assertThat(away.state()).isEqualTo(BondState.AWAY);
        assertThat(away.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(away.active()).isTrue();
        assertThat(away.bondId()).isEqualTo(bond.bondId());

        // Engagement during AWAY → REACTIVATING.
        var reactivating = away.withInteraction();
        assertThat(reactivating.state()).isEqualTo(BondState.REACTIVATING);
        assertThat(reactivating.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(reactivating.interactionCount()).isEqualTo(bond.interactionCount() + 1);

        // One more engagement closes the rebuild loop → ACTIVE.
        var reactive = reactivating.withInteraction();
        assertThat(reactive.state()).isEqualTo(BondState.ACTIVE);
        assertThat(reactive.canonicalKind()).isEqualTo(BondKind.PEER);
    }

    @Test
    void peerBond_dormantStillReactivatesOnEngagement() {
        var bond = Bond.peerProposal(AGENT_A, AGENT_B).acceptPeerProposal()
            .withState(BondState.DORMANT);
        assertThat(bond.state()).isEqualTo(BondState.DORMANT);
        // DORMANT engagement also triggers REACTIVATING per Bond.withInteraction.
        var reactivating = bond.withInteraction();
        assertThat(reactivating.state()).isEqualTo(BondState.REACTIVATING);
        assertThat(reactivating.canonicalKind()).isEqualTo(BondKind.PEER);
    }

    @Test
    void peerBond_severancePreservesKindIntoMourning() {
        var bond = Bond.peerProposal(AGENT_A, AGENT_B).acceptPeerProposal();
        var mourning = bond.declareSeverance();
        assertThat(mourning.state()).isEqualTo(BondState.MOURNING);
        // Kind must travel through severance — the chronicle "peer bond"
        // framing in handleDeclareSeverance depends on this.
        assertThat(mourning.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(mourning.active()).isFalse();
    }

    @Test
    void peerBond_directSeverPreservesKind() {
        var bond = Bond.peerProposal(AGENT_A, AGENT_B).acceptPeerProposal();
        var severed = bond.sever();
        assertThat(severed.state()).isEqualTo(BondState.SEVERED);
        assertThat(severed.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(severed.active()).isFalse();
    }

    @Test
    void peerBond_relationalStatePreservesKind() {
        var bond = Bond.peerProposal(AGENT_A, AGENT_B).acceptPeerProposal();
        // The relational-state axis (OPEN / TENSION / RUPTURE) is distinct
        // from the presence-state axis. Both must preserve kind under update.
        var tensioned = bond.withRelationalState(Bond.RelationalState.GUARDED);
        assertThat(tensioned.relationalState()).isEqualTo(Bond.RelationalState.GUARDED);
        assertThat(tensioned.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(tensioned.state()).isEqualTo(BondState.ACTIVE);
    }
}
