package org.wyrdsekai.hermod;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pins grant crypto: mint/verify roundtrip, tamper fails, wrong key fails, no-key denies. */
class OnlyTheHouseholdsHandOpensTheDomainTest {

    @Test
    void grantCryptoEndToEndAtTheDoor() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var authority = kpg.generateKeyPair();
        var stranger = kpg.generateKeyPair();
        var now = Instant.now();

        var grant = GrantAuthority.mint("g1", "hh1", "photos", "node",
            now, now.plusSeconds(300), "v1", authority.getPrivate());

        var verify = GrantAuthority.verifier(authority.getPublic().getEncoded());
        assertTrue(verify.test(grant), "authority-minted grant verifies");

        // tamper: same signature, re-scoped domain
        var rescoped = new SignedGrant(grant.grantId(), grant.householdId(), "journals",
            grant.grantedToDeviceClass(), grant.issuedAt(), grant.expiresAt(),
            grant.policyVersion(), grant.authoritySignature());
        assertFalse(verify.test(rescoped), "a grant cannot be re-scoped after minting");

        // wrong authority
        var wrongVerify = GrantAuthority.verifier(stranger.getPublic().getEncoded());
        assertFalse(wrongVerify.test(grant), "a stranger's key opens nothing");

        // no authority = deny by default
        assertFalse(GrantAuthority.verifier(null).test(grant));

        // and through the actual door:
        var gate = new LocalAdmissionGate(Clock.systemUTC(), 1000, verify, e -> false);
        var envelope = new TaskEnvelope("e1", "hh1", "phone", "index.local", "photos",
            "llm.a1b", Map.of(), 100, now, now.plusSeconds(60),
            Optional.of(grant), new byte[]{1});
        assertEquals(AdmissionGate.Verdict.ADMIT, gate.consider(envelope).verdict());

        var badGate = new LocalAdmissionGate(Clock.systemUTC(), 1000, wrongVerify, e -> false);
        var d = badGate.consider(envelope);
        assertEquals(AdmissionGate.Verdict.REFUSE, d.verdict());
        assertTrue(d.reason().contains("signature"), d.reason());
    }
}
