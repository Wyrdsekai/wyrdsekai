package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.identity.DidKey;

import java.security.KeyPairGenerator;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

class HouseholdIdentityTest {

    private static PublicKey newEd25519PublicKey() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        return kpg.generateKeyPair().getPublic();
    }

    @Test void did_hasWyrdScheme() throws Exception {
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        assertTrue(identity.did().startsWith("did:wyrd:"),
            "expected did:wyrd prefix, got: " + identity.did());
    }

    @Test void did_usesMultibaseZ6MkEncoding() throws Exception {
        // Ed25519 keys encoded as multicodec + base58btc always start with
        // z6Mk (first byte of multicodec 0xed + first byte of base58-of-rest
        // decodes to Mk prefix). This is a contract with did:key consumers.
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        var fp = identity.fingerprint();
        assertTrue(fp.startsWith("z6Mk"),
            "expected z6Mk prefix on fingerprint, got: " + fp);
    }

    @Test void fingerprint_isScheme_prefix_stripped() throws Exception {
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        assertEquals("did:wyrd:" + identity.fingerprint(), identity.did());
    }

    @Test void sameKey_producesSameFingerprint() throws Exception {
        var pk = newEd25519PublicKey();
        var a = HouseholdIdentity.fromPublicKey(pk);
        var b = HouseholdIdentity.fromPublicKey(pk);
        assertEquals(a.fingerprint(), b.fingerprint());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test void differentKeys_produceDifferentFingerprints() throws Exception {
        var a = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        var b = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test void fromSpkiBytes_matchesFromPublicKey() throws Exception {
        // Round-trip through SPKI bytes (NodeIdentity.publicKeyBytes() path).
        var pk = newEd25519PublicKey();
        var fromKey = HouseholdIdentity.fromPublicKey(pk);
        var fromBytes = HouseholdIdentity.fromSpkiBytes(pk.getEncoded());
        assertEquals(fromKey.fingerprint(), fromBytes.fingerprint());
    }

    @Test void fromSpkiBytes_rejectsGarbage() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> HouseholdIdentity.fromSpkiBytes(new byte[]{1, 2, 3, 4}));
        assertTrue(ex.getMessage().contains("Ed25519") || ex.getMessage().contains("SPKI"));
    }

    @Test void rawPublicKey_isDefensivelyCopied() throws Exception {
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        var a = identity.rawPublicKey();
        a[0] = (byte) 0xff;
        var b = identity.rawPublicKey();
        assertNotEquals(a[0], b[0],
            "rawPublicKey() must defensively copy — caller mutation must not affect internal state");
    }

    @Test void rawPublicKey_is32Bytes() throws Exception {
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        assertEquals(32, identity.rawPublicKey().length);
    }

    @Test void zone_validatesLabel() throws Exception {
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        assertDoesNotThrow(() -> identity.zone("kitchen"));
        assertThrows(IllegalArgumentException.class, () -> identity.zone("home"));
        assertThrows(IllegalArgumentException.class, () -> identity.zone("Kitchen"));
    }

    @Test void zone_returnsAddressWithMyFingerprint() throws Exception {
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        var addr = identity.zone("kitchen");
        assertEquals(identity.fingerprint(), addr.fingerprint());
        assertEquals("kitchen", addr.label());
    }

    @Test void toString_returnsDid() throws Exception {
        var identity = HouseholdIdentity.fromPublicKey(newEd25519PublicKey());
        assertEquals(identity.did(), identity.toString());
    }

    @Test void didKey_compatibility_swapScheme() throws Exception {
        // Contract check: our did:wyrd fingerprint IS the same multibase as
        // did:key would produce — a consumer who replaces our scheme with
        // did:key and feeds the result to DidKey.rawPublicKeyFromMultibase
        // should get the same 32 bytes we hold.
        var pk = newEd25519PublicKey();
        var identity = HouseholdIdentity.fromPublicKey(pk);
        var didKeyEquivalent = "did:key:" + identity.fingerprint();
        var multibase = didKeyEquivalent.substring("did:key:".length());
        var raw = DidKey.rawPublicKeyFromMultibase(multibase);
        assertArrayEquals(identity.rawPublicKey(), raw);
    }
}
