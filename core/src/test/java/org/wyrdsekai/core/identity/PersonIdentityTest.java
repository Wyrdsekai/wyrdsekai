package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.security.Signature;
import java.time.Instant;
import java.util.List;

/**
 * A person must be able to SIGN. That is the whole point of this class, and it
 * is exactly what {@link PlayerAccount#create} could not do — it generated a
 * keypair and threw the private half away, leaving a DID nobody could ever
 * prove ownership of.
 */
class PersonIdentityTest {

    private static byte[] householdSecret() {
        var s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    /** The defect this class exists to fix: a person can sign, and it verifies. */
    @Test
    void person_can_sign_and_the_signature_verifies() throws Exception {
        var secret = householdSecret();
        var me = PersonIdentity.generate(secret);

        var msg = "rebind: did:key:zOld is now did:key:zNew".getBytes(StandardCharsets.UTF_8);
        var sig = me.sign(msg, secret);

        assertTrue(me.verify(msg, sig), "a person must be able to sign as themselves");
    }

    /** Tampered payloads must not verify — this is what makes an attestation worth anything. */
    @Test
    void tampered_payload_does_not_verify() throws Exception {
        var secret = householdSecret();
        var me = PersonIdentity.generate(secret);

        var sig = me.sign("original".getBytes(StandardCharsets.UTF_8), secret);
        assertFalse(me.verify("tampered".getBytes(StandardCharsets.UTF_8), sig));
    }

    /** Another person's signature must not verify against this identity. */
    @Test
    void another_persons_signature_does_not_verify() throws Exception {
        var secret = householdSecret();
        var me = PersonIdentity.generate(secret);
        var someoneElse = PersonIdentity.generate(secret);

        var msg = "household transfer".getBytes(StandardCharsets.UTF_8);
        var theirSig = someoneElse.sign(msg, secret);

        assertFalse(me.verify(msg, theirSig));
        assertNotEquals(me.did(), someoneElse.did(), "each mint must be a distinct person");
    }

    /**
     * The private key is only recoverable with the household secret. This is the
     * key-custody property the whole migration plan leans on.
     */
    @Test
    void private_key_is_not_recoverable_with_the_wrong_secret() throws Exception {
        var secret = householdSecret();
        var me = PersonIdentity.generate(secret);

        assertThrows(Exception.class, () -> me.signingKey(householdSecret()),
            "a different household secret must not unwrap the private key");
    }

    /** The stored key material must actually be encrypted, not merely relocated. */
    @Test
    void private_key_is_encrypted_at_rest() throws Exception {
        var secret = householdSecret();
        var me = PersonIdentity.generate(secret);

        var raw = AgentIdentity.decryptPrivateKey(me.encryptedPrivateKey(), secret);
        assertEquals(32, raw.length, "Ed25519 raw private key is 32 bytes");
        // The ciphertext must not contain the plaintext key.
        var haystack = me.encryptedPrivateKey();
        assertFalse(indexOf(haystack, raw) >= 0, "raw private key must not appear in stored bytes");
    }

    /** Round-trip: the recovered signing key is the one that made the DID. */
    @Test
    void recovered_key_matches_the_identity() throws Exception {
        var secret = householdSecret();
        var me = PersonIdentity.generate(secret);

        var recovered = me.signingKey(secret);
        var sig = Signature.getInstance("Ed25519");
        sig.initSign(recovered);
        var msg = "round trip".getBytes(StandardCharsets.UTF_8);
        sig.update(msg);

        assertTrue(me.verify(msg, sig.sign()));
    }

    /** Inception event with a pre-rotation commitment — rotation must stay reachable. */
    @Test
    void inception_event_carries_a_pre_rotation_commitment() throws Exception {
        var me = PersonIdentity.generate(householdSecret());

        assertEquals(1, me.keyLog().size(), "a fresh identity has exactly the inception event");
        var icp = me.keyLog().get(0);
        assertEquals("icp", icp.path("t").asText(), "first event must be inception");
        assertTrue(icp.has("n") && !icp.path("n").isEmpty(),
            "inception must commit to the next key (pre-rotation), else rotation is unreachable");
    }

    /** A person DID must be a did:key, not a UUID or a username — the original bug. */
    @Test
    void rejects_non_did_identifiers() {
        var pub = new byte[32];
        var enc = new byte[64];
        assertThrows(IllegalArgumentException.class,
            () -> new PersonIdentity("1d8d87ce-b7c0-46f4-b827-5fbaf797dbb3", pub, enc,
                List.of(), Instant.now()),
            "a UUID must not be accepted as a person identity");
        assertThrows(IllegalArgumentException.class,
            () -> new PersonIdentity("operator", pub, enc,
                List.of(), Instant.now()),
            "a username must not be accepted as a person identity");
    }

    /** Key material must never reach a log line. */
    @Test
    void toString_does_not_leak_key_material() throws Exception {
        var me = PersonIdentity.generate(householdSecret());
        var s = me.toString();
        assertTrue(s.contains(me.did()));
        assertFalse(s.contains("encryptedPrivateKey"));
        assertFalse(s.contains(Arrays.toString(me.encryptedPrivateKey())));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
