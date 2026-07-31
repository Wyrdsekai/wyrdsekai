package org.wyrdsekai.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.agent.research.ZoneArgotService;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * live verification of the zone-secret BOOT WIRE end to end, exercising the exact path
 * Main runs: the real {@link SchemaInitializer} (which must apply the {@code zone_wrapped_secrets}
 * migration), a real {@link NodeIdentity} (Ed25519 seed → node KEK), and
 * {@link ZoneSecrets#bootstrapLocalZone}. Asserts: the table migrates, a wrapped row is persisted,
 * the secret argot provider installs (tokens differ from the public seed), and a SECOND boot loads
 * the same master and derives the IDENTICAL argot tokens (persistence survives restart).
 */
class ZoneSecretBootstrapLiveTest {

    @AfterEach
    void clearProvider() {
        ZoneArgotService.setArgotKeyProvider(null);   // don't leak static state across tests
    }

    @Test
    void bootWireOriginatesPersistsAndInstallsSecretProvider(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));

        // The migration must have created the table the bootstrap writes to.
        try (var c = DriverManager.getConnection(jdbc);
             var rs = c.getMetaData().getTables(null, null, "zone_wrapped_secrets", null)) {
            assertTrue(rs.next(), "zone_wrapped_secrets table must exist after SchemaInitializer");
        }

        var identity = NodeIdentity.loadOrGenerate(dir.resolve("node-identity.json"));
        var nodeId = identity.nodeId();
        var zone = "zone-livetest";

        // What public-seed argot looks like (provider absent).
        var publicWire = new ZoneArgotService().encodeForPeer(zone, "help here now");

        // FIRST boot — originate + persist + install the secret provider (sole node → enabled).
        ZoneSecrets.bootstrapLocalZone(jdbc, zone, nodeId, identity.privateKeySeedBytes(), true);

        var secretWire1 = new ZoneArgotService().encodeForPeer(zone, "help here now");
        assertNotEquals(publicWire, secretWire1, "secret seed installed → tokens differ from public seed");

        // A wrapped row was persisted (never plaintext).
        try (var c = DriverManager.getConnection(jdbc);
             var st = c.prepareStatement(
                 "SELECT wrapped_secret FROM zone_wrapped_secrets WHERE zone_id=? AND node_id=?")) {
            st.setString(1, zone);
            st.setString(2, nodeId);
            try (var rs = st.executeQuery()) {
                assertTrue(rs.next(), "a wrapped zone secret row must be persisted");
                assertTrue(rs.getBytes(1).length >= 32, "wrapped blob present");
            }
        }

        // SECOND boot (simulated restart) — must LOAD the same master and derive identical tokens.
        ZoneArgotService.setArgotKeyProvider(null);
        ZoneSecrets.bootstrapLocalZone(jdbc, zone, nodeId, identity.privateKeySeedBytes(), true);
        var secretWire2 = new ZoneArgotService().encodeForPeer(zone, "help here now");
        assertEquals(secretWire1, secretWire2, "restart loads the same secret → identical argot (durable)");
    }

    @Test
    void multiNodeGateKeepsPublicSeed(@TempDir Path dir) throws Exception {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var identity = NodeIdentity.loadOrGenerate(dir.resolve("node-identity.json"));
        var zone = "zone-multinode";
        var publicWire = new ZoneArgotService().encodeForPeer(zone, "help here now");

        // installSecretProvider=false (multi-node gate) → master persisted but argot stays public.
        ZoneSecrets.bootstrapLocalZone(jdbc, zone, identity.nodeId(),
            identity.privateKeySeedBytes(), false);

        assertEquals(publicWire, new ZoneArgotService().encodeForPeer(zone, "help here now"),
            "multi-node gate → argot stays on the public seed (no cross-node divergence)");
    }
}
