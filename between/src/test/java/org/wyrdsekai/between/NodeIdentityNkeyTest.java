package org.wyrdsekai.between;

import io.nats.client.AuthHandler;
import io.nats.client.NKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * verify {@link NodeIdentity#nkeyAuthHandler()} produces
 * a working NATS auth handler whose {@code getID()} matches
 * {@link NodeIdentity#nkeyPublicKey()} and whose {@code sign(nonce)} verifies
 * against the same public key.
 *
 * <p>This is the round-trip that an actual NATS relay performs at connect time
 * against a registered pubkey — a live relay test would fail in exactly the
 * same way as a mismatch here.</p>
 */
final class NodeIdentityNkeyTest {

    @Test
    void freshly_generated_identity_round_trips_nkey_sign(@TempDir Path tmp) throws Exception {
        var identityFile = tmp.resolve("node-identity.json");
        var identity = NodeIdentity.loadOrGenerate(identityFile);

        var pubkey = identity.nkeyPublicKey();
        assertNotNull(pubkey, "NKey pubkey should be derivable");
        assertEquals(56, pubkey.length(), "NATS user NKey is 56 chars");
        assertEquals('U', pubkey.charAt(0), "User NKey starts with 'U'");

        var handler = identity.nkeyAuthHandler();
        assertEquals(pubkey, new String(handler.getID()),
            "AuthHandler.getID() must match nkeyPublicKey()");

        var nonce = "test-nonce-" + System.nanoTime();
        var sig = handler.sign(nonce.getBytes());
        assertNotNull(sig);
        assertEquals(64, sig.length, "Ed25519 signature is 64 bytes");

        // Verify: signature is valid against the registered pubkey.
        var verifierNkey = NKey.fromPublicKey(handler.getID());
        assertTrue(verifierNkey.verify(nonce.getBytes(), sig),
            "Sig produced by nkeyAuthHandler must verify against the same pubkey");
    }

    @Test
    void nkey_persists_across_load(@TempDir Path tmp) throws Exception {
        var identityFile = tmp.resolve("node-identity.json");
        var first = NodeIdentity.loadOrGenerate(identityFile);
        var firstPubkey = first.nkeyPublicKey();

        // Reload from disk — should get the same NKey, not regenerate.
        var second = NodeIdentity.loadOrGenerate(identityFile);
        assertEquals(firstPubkey, second.nkeyPublicKey(),
            "NKey must persist across load (drift recovery depends on this)");
    }

    @Test
    void getJWT_returns_null_for_nkey_only_auth(@TempDir Path tmp) throws Exception {
        var identityFile = tmp.resolve("node-identity.json");
        var identity = NodeIdentity.loadOrGenerate(identityFile);
        var handler = identity.nkeyAuthHandler();
        assertNull(handler.getJWT(),
            "NKey-only auth has no JWT scope; getJWT() must return null");
    }
}
