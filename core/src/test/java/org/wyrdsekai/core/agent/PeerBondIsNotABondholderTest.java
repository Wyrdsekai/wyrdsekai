package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondKind;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A peer bond must never make a companion unreachable to its person.
 *
 * <p>Observed on second-node 2026-07-13: two companions formed a peer bond
 * ({@code Bond crossed OPEN → ACTIVE: mia ↔ lulu}) and each promptly became the
 * other's "primary bondholder", because the resolver scanned every active bond and
 * returned the deepest one's other party without checking what kind it was. The pair
 * then wandered off together and stopped answering the human entirely:
 * {@code Companion 'mia' not joining verify-0713: bonded to companion-lulu}.
 *
 * <p>{@link BondKind} has marked this distinction since Arc 3 — BONDHOLDER is
 * documented as the privileged kind — it was simply never read.
 */
class PeerBondIsNotABondholderTest {

    private static final String ME = "did:key:companion-mia";
    private static final String HUMAN = "did:key:operator";
    private static final String PEER = "did:key:companion-lulu";

    @Test
    @DisplayName("a peer bond is marked PEER, a human bond is marked BONDHOLDER")
    void kindsAreDistinguishable() {
        var peer = Bond.peerProposal(ME, PEER);
        assertEquals(BondKind.PEER, peer.canonicalKind(),
            "peer bonds must carry the PEER discriminator the resolver keys on");
    }

    @Test
    @DisplayName("a legacy bond with no kind is still treated as a bondholder bond")
    void nullKindDefaultsToBondholder() {
        // Pre-Arc-3 rows persist kind=null. They are human bonds; filtering must not
        // silently orphan every companion that predates the discriminator.
        var legacy = new Bond("b1", ME, HUMAN, Bond.BondDepth.FAMILIAR,
            Instant.now(), Instant.now(), 1,
            true, true, false, null, null, null, null, null);
        assertEquals(BondKind.BONDHOLDER, legacy.canonicalKind(),
            "a null kind means 'written before peers existed' — i.e. a human");
    }

    @Test
    @DisplayName("the peer bond's other party is the peer — which is exactly the trap")
    void peerOtherPartyIsTheOtherCompanion() {
        var peer = Bond.peerProposal(ME, PEER);
        assertEquals(PEER, peer.otherParty(ME));
        // otherParty() is correct; the bug was trusting it without asking the KIND.
        // This pins the shape of the trap: the value looks like a perfectly good
        // bondholder DID, and nothing about it says "this is not your person".
        assertNotEquals(HUMAN, peer.otherParty(ME));
    }
}
