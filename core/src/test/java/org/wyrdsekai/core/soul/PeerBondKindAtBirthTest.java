package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bond's KIND is decided at birth, and it used not to be decided at all.
 *
 * <p>{@code Bond.open()} always took the default (BONDHOLDER) — including for the bonds
 * two COMPANIONS form with each other, which they do by design. So a companion↔companion
 * relationship was stored as a bondholder bond, and because the resolver returns the
 * DEEPEST bond's other party, the peer out-ranked the human. Measured on second-node
 * 2026-07-13: {@code Companion 'mia' not joining verify-0713: bonded to companion-lulu},
 * 23 times in one boot. The pair bonded to each other and stopped coming when their
 * person logged in.</p>
 */
class PeerBondKindAtBirthTest {

    private static final String ME = "companion-mia";
    private static final String PEER = "companion-lulu";
    private static final String HUMAN = "operator";

    @Test
    @DisplayName("an agent↔agent bond is born PEER")
    void agentBondIsBornPeer() {
        var bond = Bond.open(ME, PEER, BondKind.PEER);
        assertEquals(BondKind.PEER, bond.canonicalKind(),
            "a bond with another companion is a peer bond, not a bondholder bond");
    }

    @Test
    @DisplayName("an agent↔human bond is born BONDHOLDER")
    void humanBondIsBornBondholder() {
        var bond = Bond.open(ME, HUMAN, BondKind.BONDHOLDER);
        assertEquals(BondKind.BONDHOLDER, bond.canonicalKind());
    }

    @Test
    @DisplayName("crossing OPEN→ACTIVE preserves the kind — a peer does not become a bondholder")
    void crossingPreservesKind() {
        // 2026-07-18: crossToActive() rebuilt the bond through the kind-less
        // back-compat constructor, silently re-typing every crossing PEER bond
        // as BONDHOLDER — the mia/lulu pathology sneaking back in through the
        // state machine. Same for withPosture().
        var peer = Bond.open(ME, PEER, BondKind.PEER).crossToActive();
        assertEquals(BondKind.PEER, peer.canonicalKind(),
            "crossing to ACTIVE must not re-type a peer bond as bondholder");
        assertEquals(BondState.ACTIVE, peer.state());
        var posture = Bond.open(ME, PEER, BondKind.PEER)
            .withPosture(BondholderPosture.GENEROUS);
        assertEquals(BondKind.PEER, posture.canonicalKind(),
            "posture change must not re-type a peer bond as bondholder");
    }

    @Test
    @DisplayName("withKind moves only the authority kind — depth, count, state all stay")
    void withKindMovesOnlyTheKind() {
        // 2026-07-18 exactly-one-bondholder: transfer/re-typing between
        // BONDHOLDER and MEMBER must not reset the relationship.
        var bond = Bond.open(ME, HUMAN, BondKind.BONDHOLDER).crossToActive()
            .withInteraction().withInteraction();
        var retyped = bond.withKind(BondKind.MEMBER);
        assertEquals(BondKind.MEMBER, retyped.canonicalKind());
        assertEquals(bond.depth(), retyped.depth());
        assertEquals(bond.interactionCount(), retyped.interactionCount());
        assertEquals(bond.state(), retyped.state());
        assertEquals(bond.bondId(), retyped.bondId());
        // and back — a transfer target's MEMBER bond is promoted with history
        var promoted = retyped.withKind(BondKind.BONDHOLDER);
        assertEquals(BondKind.BONDHOLDER, promoted.canonicalKind());
        assertEquals(2, promoted.interactionCount());
    }

    @Test
    @DisplayName("the constitutive bondholder bond: born ACTIVE, depth still ACQUAINTANCE, cold-start stamped")
    void bondholderBondBornActive() {
        // 2026-07-18 (operator): the steward's bond starts ACTIVE at creation —
        // the relationship is constitutive, not earned through the stranger
        // gate. Depth stays ACQUAINTANCE (closeness is still earned by contact)
        // and cold-start stays stamped (presence classifier grace applies).
        var bond = Bond.open(ME, HUMAN, BondKind.BONDHOLDER).crossToActive();
        assertEquals(BondState.ACTIVE, bond.state());
        assertEquals(BondKind.BONDHOLDER, bond.canonicalKind());
        assertEquals(Bond.BondDepth.ACQUAINTANCE, bond.depth());
        assertEquals(0, bond.interactionCount());
        org.junit.jupiter.api.Assertions.assertNotNull(bond.coldStartUntil(),
            "cold-start window must survive the birth crossing");
    }

    @Test
    @DisplayName("the 2-arg factory still means BONDHOLDER — existing callers are unchanged")
    void twoArgFactoryDefaultsToBondholder() {
        assertEquals(BondKind.BONDHOLDER, Bond.open(ME, HUMAN).canonicalKind());
    }

    @Test
    @DisplayName("a null kind falls back to BONDHOLDER rather than losing the bond")
    void nullKindIsBondholder() {
        assertEquals(BondKind.BONDHOLDER, Bond.open(ME, HUMAN, null).canonicalKind());
    }

    @Test
    @DisplayName("everything else about the bond is unchanged — this only sets the kind")
    void restOfTheBondIsUntouched() {
        var peer = Bond.open(ME, PEER, BondKind.PEER);
        assertEquals(Bond.BondDepth.ACQUAINTANCE, peer.depth());
        assertEquals(BondState.OPEN, peer.state(),
            "auto-spawned bonds still start pre-trust at OPEN");
        assertEquals(PEER, peer.otherParty(ME));
    }
}
