package org.wyrdsekai.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.agent.research.ZoneArgotService;
import org.wyrdsekai.core.crypto.ZoneSecretService;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1184 — the multi-node grant seam end to end, across the module boundary it crosses in
 * production: a holder's {@link ZoneSecretService#grantTo} (core) wraps the zone master to a JOINING
 * node's X25519 public key carried on its {@link NodeIdentity} (between), and
 * {@link ZoneSecrets#installGrantedMaster} (core) accepts it with that node's X25519 private key,
 * persists it, and promotes the joiner to the secret codebook. Proves the two same-zone nodes then
 * encode argot IDENTICALLY — the whole point of the grant (cross-node argot decodes).
 *
 * <p>This is the load-bearing interop: {@code NodeIdentity} generates its keypair via the
 * {@code "X25519"} JCA name while {@code ZoneSecretService} agrees via {@code "XDH"}; they share the
 * same encoded form, so the grant round-trips. This test pins that.
 */
class ZoneSecretGrantMultiNodeLiveTest {

    private static final String ZONE = "zone-grant-mn";

    @AfterEach
    void cleanup() {
        ZoneArgotService.setArgotKeyProvider(null);
        ZoneSecrets.service().forget(ZONE);
    }

    @Test
    void holderGrantsZoneMasterToJoinerWhichThenSpeaksTheSameSecretArgot(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));

        // The JOINING node (mac-node stand-in): a real NodeIdentity carrying the X25519 grant key.
        var joiner = NodeIdentity.loadOrGenerate(dir.resolve("node-identity.json"));

        // The HOLDER (home-server stand-in) holds the zone master and what its secret argot looks like.
        var holder = new ZoneSecretService();
        holder.generate(ZONE);
        var holderKey = holder.derive(ZONE, "argot-v1");

        // Before any grant, this process (the joiner) has no master → public seed only.
        ZoneSecrets.service().forget(ZONE);
        var publicWire = new ZoneArgotService().encodeForPeer(ZONE, "help here now");

        // Holder wraps the master to the joiner's X25519 PUBLIC key (only the blob crosses the wire).
        byte[] grant = holder.grantTo(ZONE, joiner.x25519PublicKeyBytes());

        // Joiner accepts with its X25519 PRIVATE key → installs + persists + secret provider on.
        boolean ok = ZoneSecrets.installGrantedMaster(
            jdbc, ZONE, joiner.nodeId(), joiner.privateKeySeedBytes(),
            grant, joiner.x25519PrivateKeyPkcs8());
        assertTrue(ok, "the joiner accepts the grant");

        // The keys match → the codebooks match → argot encoded on either node decodes on the other.
        assertArrayEquals(holderKey, ZoneSecrets.service().derive(ZONE, "argot-v1"),
            "joiner derives the IDENTICAL argot key as the holder");
        var secretWire = new ZoneArgotService().encodeForPeer(ZONE, "help here now");
        assertNotEquals(publicWire, secretWire, "joiner now speaks the secret codebook, not the public seed");

        // Persistence: a fresh boot as a NON-sole node loads the granted master (no re-originate).
        ZoneSecrets.service().forget(ZONE);
        ZoneSecrets.bootstrapLocalZone(jdbc, ZONE, joiner.nodeId(),
            joiner.privateKeySeedBytes(), /*soleNode=*/false);
        assertTrue(ZoneSecrets.service().has(ZONE), "reboot loads the granted master from disk");
        assertArrayEquals(holderKey, ZoneSecrets.service().derive(ZONE, "argot-v1"),
            "after reboot the joiner still shares the holder's argot key");
    }
}
