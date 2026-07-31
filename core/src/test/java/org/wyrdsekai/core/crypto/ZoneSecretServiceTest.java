package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * foundation — the per-zone shared secret + envelope encryption every zone-shared
 * secret derives from. Asserts: purpose-key derivation is deterministic + separated, the at-rest
 * per-node wrap round-trips (and fails under the wrong KEK), the cross-node X25519 ECIES grant
 * round-trips (and fails for the wrong key), and — the property that makes argot work — two nodes
 * holding the same master derive the IDENTICAL purpose key (so same-zone agents compute the same
 * codebook) while an outsider derives nothing.
 */
class ZoneSecretServiceTest {

    @Test
    void deriveIsDeterministicAndPurposeSeparated() {
        var s = new ZoneSecretService();
        s.generate("zone-a");
        var argot1 = s.derive("zone-a", "argot-v1");
        var argot2 = s.derive("zone-a", "argot-v1");
        var token = s.derive("zone-a", "join-token-v1");
        assertArrayEquals(argot1, argot2, "same zone+purpose → same key");
        assertFalse(Arrays.equals(argot1, token), "different purposes → different keys");
        assertEquals(32, argot1.length);
    }

    @Test
    void differentZonesDeriveDifferentKeys() {
        var s = new ZoneSecretService();
        s.generate("zone-a");
        s.generate("zone-b");
        assertFalse(Arrays.equals(s.derive("zone-a", "argot-v1"), s.derive("zone-b", "argot-v1")));
    }

    @Test
    void atRestWrapRoundTripsAndFailsUnderWrongKek() {
        var node = new ZoneSecretService();
        var master = node.generate("zone-a");
        var kek = ZoneSecretService.nodeKek(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        var wrapped = node.wrapForNode("zone-a", kek);

        // A fresh process unwraps with the same KEK and recovers the identical master + keys.
        var reboot = new ZoneSecretService();
        reboot.installFromWrapped("zone-a", wrapped, kek);
        assertTrue(reboot.has("zone-a"));
        assertArrayEquals(node.derive("zone-a", "argot-v1"), reboot.derive("zone-a", "argot-v1"));

        var wrongKek = ZoneSecretService.nodeKek(new byte[]{9, 9, 9, 9});
        var other = new ZoneSecretService();
        assertThrows(RuntimeException.class, () -> other.installFromWrapped("zone-a", wrapped, wrongKek));
    }

    @Test
    void crossNodeX25519GrantRoundTripsEndToEnd() {
        // Node A holds the zone; Node B is joining and has its own X25519 keypair.
        var nodeA = new ZoneSecretService();
        nodeA.generate("zone-a");
        var nodeBKeys = ZoneSecretService.generateNodeEcdhKeyPair();

        // A grants to B's public key (this envelope travels the signed federation channel).
        var grant = nodeA.grantTo("zone-a", nodeBKeys.getPublic().getEncoded());

        // B accepts with its private key and now derives the SAME argot key as A (comprehension).
        var nodeB = new ZoneSecretService();
        nodeB.acceptGrant("zone-a", grant, nodeBKeys.getPrivate());
        assertTrue(nodeB.has("zone-a"));
        assertArrayEquals(nodeA.derive("zone-a", "argot-v1"), nodeB.derive("zone-a", "argot-v1"),
            "both nodes derive the identical zone argot key → same codebook");
    }

    @Test
    void grantCannotBeOpenedByTheWrongNode() {
        var nodeA = new ZoneSecretService();
        nodeA.generate("zone-a");
        var intended = ZoneSecretService.generateNodeEcdhKeyPair();
        var grant = nodeA.grantTo("zone-a", intended.getPublic().getEncoded());

        // An eavesdropping node with a DIFFERENT keypair cannot unwrap the grant.
        var attackerKeys = ZoneSecretService.generateNodeEcdhKeyPair();
        var attacker = new ZoneSecretService();
        assertThrows(RuntimeException.class,
            () -> attacker.acceptGrant("zone-a", grant, attackerKeys.getPrivate()));
        assertFalse(attacker.has("zone-a"));
    }

    @Test
    void grantForOneZoneCannotBeReplayedForAnother() {
        // The HKDF context binds the wrap key to the zone id, so a blob minted for zone-a fails to
        // unwrap if the grantee tries to install it under zone-b (GCM auth failure) — prevents a
        // captured grant from being smuggled into the wrong zone's master slot.
        var nodeA = new ZoneSecretService();
        nodeA.generate("zone-a");
        var nodeBKeys = ZoneSecretService.generateNodeEcdhKeyPair();
        var grant = nodeA.grantTo("zone-a", nodeBKeys.getPublic().getEncoded());

        var nodeB = new ZoneSecretService();
        assertThrows(RuntimeException.class,
            () -> nodeB.acceptGrant("zone-b", grant, nodeBKeys.getPrivate()));
        assertFalse(nodeB.has("zone-b"));
        // Same blob, correct zone → succeeds (proves the failure was the zone binding, not the blob).
        nodeB.acceptGrant("zone-a", grant, nodeBKeys.getPrivate());
        assertArrayEquals(nodeA.derive("zone-a", "argot-v1"), nodeB.derive("zone-a", "argot-v1"));
    }

    @Test
    void outsiderWithoutMasterCannotDerive() {
        var s = new ZoneSecretService();
        assertThrows(IllegalStateException.class, () -> s.derive("zone-unknown", "argot-v1"));
        assertFalse(s.has("zone-unknown"));
    }

    @Test
    void hkdfIsStableAcrossInstances() {
        // The HKDF is the deterministic core that lets independent nodes agree — pin it.
        var okm = ZoneSecretService.hkdf(new byte[32], "salt".getBytes(), "info".getBytes(), 32);
        var okm2 = ZoneSecretService.hkdf(new byte[32], "salt".getBytes(), "info".getBytes(), 32);
        assertEquals(HexFormat.of().formatHex(okm), HexFormat.of().formatHex(okm2));
        assertEquals(32, okm.length);
    }
}
