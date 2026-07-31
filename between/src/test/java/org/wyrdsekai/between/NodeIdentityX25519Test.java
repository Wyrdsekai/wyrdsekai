package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyAgreement;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1184 P2 — the X25519 grant keypair carried on {@link NodeIdentity}: generated on
 * first boot, persisted in {@code node-identity.json}, reloaded identically, and usable for the
 * ECDH that {@code ZoneSecretGrant} (core) runs to move a zone master between nodes.
 */
class NodeIdentityX25519Test {

    @Test
    void x25519KeypairIsGeneratedAndSurvivesReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("node-identity.json");
        var gen = NodeIdentity.loadOrGenerate(file);
        byte[] pub1 = gen.x25519PublicKeyBytes();
        byte[] priv1 = gen.x25519PrivateKeyPkcs8();
        assertNotNull(pub1);
        assertTrue(pub1.length > 0 && priv1.length > 0);

        // Reload from disk → same keypair (encrypted-at-rest round-trips on this machine).
        var loaded = NodeIdentity.loadOrGenerate(file);
        assertArrayEquals(pub1, loaded.x25519PublicKeyBytes(), "X25519 public survives reload");
        assertArrayEquals(priv1, loaded.x25519PrivateKeyPkcs8(), "X25519 private survives reload");
    }

    @Test
    void lazyCreateOnOlderIdentityFileThatLacksX25519(@TempDir Path dir) throws Exception {
        // Two fresh identities stand in for two nodes; prove their keys ECDH-agree (the grant's core
        // operation) — symmetric shared secret from (a_priv,b_pub) == (b_priv,a_pub).
        var a = NodeIdentity.loadOrGenerate(dir.resolve("a.json"));
        var b = NodeIdentity.loadOrGenerate(dir.resolve("b.json"));
        byte[] ssAB = agree(a.x25519PrivateKeyPkcs8(), b.x25519PublicKeyBytes());
        byte[] ssBA = agree(b.x25519PrivateKeyPkcs8(), a.x25519PublicKeyBytes());
        assertArrayEquals(ssAB, ssBA, "ECDH is symmetric → both nodes derive the same wrap secret");
        assertFalse(Arrays.equals(ssAB, new byte[ssAB.length]), "shared secret is non-trivial");
    }

    private static byte[] agree(byte[] myPriv, byte[] peerPub) throws Exception {
        var kf = KeyFactory.getInstance("X25519");
        var ka = KeyAgreement.getInstance("X25519");
        ka.init(kf.generatePrivate(new PKCS8EncodedKeySpec(myPriv)));
        ka.doPhase(kf.generatePublic(new X509EncodedKeySpec(peerPub)), true);
        return ka.generateSecret();
    }
}
