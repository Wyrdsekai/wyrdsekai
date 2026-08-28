package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An attestation is what lets identity change WITHOUT rewriting signed artifacts
 * or falsifying audit history — the two things a field-update cannot touch.
 */
class RebindAttestationTest {

    private static byte[] secret() {
        var s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    /** Signed by the OLD identity — that is what makes the claim mean anything. */
    @Test
    void attestation_is_signed_by_the_identity_being_left_behind() throws Exception {
        var hs = secret();
        var oldMe = PersonIdentity.generate(hs);
        var newMe = PersonIdentity.generate(hs);

        var att = RebindAttestation.issue(oldMe, newMe, hs);

        assertTrue(att.verify(oldMe), "must verify against the old identity's key");
        assertFalse(att.verify(newMe), "must not verify against the new identity");
        assertEquals(oldMe.did(), att.fromDid());
        assertEquals(newMe.did(), att.toDid());
    }

    /** A third party must not be able to forge someone else's rebind. */
    @Test
    void cannot_be_forged_by_an_unrelated_identity() throws Exception {
        var hs = secret();
        var oldMe = PersonIdentity.generate(hs);
        var newMe = PersonIdentity.generate(hs);
        var attacker = PersonIdentity.generate(hs);

        var forged = RebindAttestation.issue(attacker, newMe, hs);
        assertFalse(forged.verify(oldMe),
            "an attestation signed by someone else must not speak for this person");
    }

    /** Tampering with the target identity must invalidate the signature. */
    @Test
    void tampered_target_fails_verification() throws Exception {
        var hs = secret();
        var oldMe = PersonIdentity.generate(hs);
        var newMe = PersonIdentity.generate(hs);
        var attackerTarget = PersonIdentity.generate(hs);

        var att = RebindAttestation.issue(oldMe, newMe, hs);
        var tampered = new RebindAttestation(
            att.fromDid(), attackerTarget.did(), att.issuedAt(), att.signature());

        assertFalse(tampered.verify(oldMe), "redirecting a rebind must break the signature");
    }

    /** A rebind must actually change identity. */
    @Test
    void refuses_a_rebind_to_the_same_identity() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);
        assertThrows(IllegalArgumentException.class, () -> RebindAttestation.issue(me, me, hs));
    }

    /**
     * The point of the chain: an audit row written under an identity from two
     * rebinds ago still resolves to the person, without the row being edited.
     */
    @Test
    void old_identity_resolves_forward_through_the_chain() throws Exception {
        var hs = secret();
        var a = PersonIdentity.generate(hs);
        var b = PersonIdentity.generate(hs);
        var c = PersonIdentity.generate(hs);

        var atts = List.of(
            RebindAttestation.issue(a, b, hs),
            RebindAttestation.issue(b, c, hs));

        assertEquals(c.did(), RebindAttestation.resolveCurrent(a.did(), atts),
            "a historical identity must resolve to the current person");
        assertEquals(c.did(), RebindAttestation.resolveCurrent(b.did(), atts));
        assertEquals(c.did(), RebindAttestation.resolveCurrent(c.did(), atts),
            "the current identity resolves to itself");
    }

    /** An identity never rebound resolves to itself. */
    @Test
    void unrebound_identity_resolves_to_itself() throws Exception {
        var hs = secret();
        var me = PersonIdentity.generate(hs);
        assertEquals(me.did(), RebindAttestation.resolveCurrent(me.did(), List.of()));
    }

    /** A corrupt cycle must not hang the resolver. */
    @Test
    void a_cyclic_chain_terminates() throws Exception {
        var hs = secret();
        var a = PersonIdentity.generate(hs);
        var b = PersonIdentity.generate(hs);

        var atts = List.of(
            RebindAttestation.issue(a, b, hs),
            RebindAttestation.issue(b, a, hs));

        var resolved = RebindAttestation.resolveCurrent(a.did(), atts);
        assertTrue(resolved.equals(a.did()) || resolved.equals(b.did()),
            "a cycle must terminate rather than loop forever");
    }

    /** The chain is inspectable — a person or a companion can be shown what happened. */
    @Test
    void chain_reports_each_step_in_order() throws Exception {
        var hs = secret();
        var a = PersonIdentity.generate(hs);
        var b = PersonIdentity.generate(hs);
        var c = PersonIdentity.generate(hs);

        var atts = List.of(
            RebindAttestation.issue(a, b, hs),
            RebindAttestation.issue(b, c, hs));

        var chain = RebindAttestation.chain(a.did(), atts);
        assertEquals(2, chain.size());
        assertEquals(a.did(), chain.get(0).fromDid());
        assertEquals(b.did(), chain.get(0).toDid());
        assertEquals(c.did(), chain.get(1).toDid());
    }

    /** You can ask how a given identity was arrived at. */
    @Test
    void can_find_the_attestation_that_produced_an_identity() throws Exception {
        var hs = secret();
        var a = PersonIdentity.generate(hs);
        var b = PersonIdentity.generate(hs);
        var atts = List.of(RebindAttestation.issue(a, b, hs));

        assertEquals(a.did(),
            RebindAttestation.attestationTo(b.did(), atts).orElseThrow().fromDid());
        assertTrue(RebindAttestation.attestationTo(a.did(), atts).isEmpty());
    }

    /**
     * An attestation issued while the old key existed still verifies later — which
     * is what makes it usable after the old key is lost or compromised.
     */
    @Test
    void remains_verifiable_from_the_public_half_alone() throws Exception {
        var hs = secret();
        var oldMe = PersonIdentity.generate(hs);
        var newMe = PersonIdentity.generate(hs);
        var att = RebindAttestation.issue(oldMe, newMe, hs);

        // Reconstruct the old identity WITHOUT any usable private key material.
        var publicOnly = new PersonIdentity(
            oldMe.did(), oldMe.publicKey(), new byte[64], List.of(), oldMe.createdAt());

        assertTrue(att.verify(publicOnly),
            "verification must need only the public key — the old private key may be gone");
    }
}
