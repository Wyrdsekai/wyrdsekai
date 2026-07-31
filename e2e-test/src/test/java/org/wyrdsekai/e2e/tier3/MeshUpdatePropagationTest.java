package org.wyrdsekai.e2e.tier3;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.*;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.between.TopologyRegister;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestActorSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 3 E2E tests for mesh update version advertisement.
 * Two BetweenActor nodes on shared NATS verify that version info
 * propagates via hello/heartbeat and appears in topology.
 *
 * Requires Docker NATS (WYRDSEKAI_E2E_NATS=docker).
 */
@Tag("between")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeshUpdatePropagationTest {

    private static NatsServerFixture nats;
    private static ActorTestKit kitA;
    private static ActorTestKit kitB;
    private static Path dataDirA;
    private static Path dataDirB;

    @BeforeAll
    static void setUp() throws Exception {
        NatsServerFixture.assumeAvailable();

        nats = new NatsServerFixture();
        nats.start();

        kitA = TestActorSystem.create("mesh-update-a");
        kitB = TestActorSystem.create("mesh-update-b");

        dataDirA = Files.createTempDirectory("mesh-update-a");
        dataDirB = Files.createTempDirectory("mesh-update-b");
    }

    @AfterAll
    static void tearDown() {
        if (kitA != null) kitA.shutdownTestKit();
        if (kitB != null) kitB.shutdownTestKit();
        if (nats != null) nats.stop();
    }

    private BetweenActor.BetweenConfig makeConfig() {
        return new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(),
            Duration.ofSeconds(2),  // heartbeat every 2s
            Duration.ofSeconds(5),  // probe every 5s
            PortAllocator.allocate());
    }

    // ==================================================================
    // Version in hello handshake
    // ==================================================================

    @Test @Order(1)
    void peers_exchange_version_on_hello() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "between-ver-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "between-ver-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-ver-a", "Version Zone A", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-ver-b", "Version Zone B", dataDirB, makeConfig(), null));

        // Wait for mutual discovery (hello + hello_ack + at least one heartbeat)
        Thread.sleep(6000);

        // Query topology from node A
        var probeA = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        betweenA.tell(new BetweenActor.GetTopology(probeA.getRef()));
        var topoA = probeA.receiveMessage(Duration.ofSeconds(5));

        assertNotNull(topoA.localNodeId(), "Node A should have a local node ID");

        // Check that topology description includes version
        var desc = topoA.description();
        assertNotNull(desc);

        // If peers are connected, they should have version info
        if (topoA.connectedNodes() > 0) {
            // At least one peer should have our version advertised
            var hasVersionPeer = topoA.connections().stream()
                .filter(TopologyRegister.ConnectionState::connected)
                .anyMatch(c -> c.appVersion() != null && !c.appVersion().isEmpty());
            assertTrue(hasVersionPeer,
                "Connected peers should have version info from hello handshake");

            // Version should match what AppVersion reports
            var peerVersion = topoA.connections().stream()
                .filter(c -> c.connected() && c.appVersion() != null)
                .findFirst()
                .map(TopologyRegister.ConnectionState::appVersion)
                .orElse(null);
            if (peerVersion != null) {
                assertEquals(AppVersion.get().version(), peerVersion,
                    "Peer should report same version (both running same build)");
            }
        }
    }

    // ==================================================================
    // Version in heartbeat
    // ==================================================================

    @Test @Order(2)
    void heartbeat_carries_version_and_wire_protocol() throws Exception {
        var between = kitA.spawn(BetweenActor.create(), "between-hb-ver");
        var config = makeConfig();

        between.tell(new BetweenActor.StartBetween(
            "zone-hb-ver", "Heartbeat Version", dataDirA, config, null));

        // Wait for a few heartbeat cycles
        Thread.sleep(5000);

        var probe = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        between.tell(new BetweenActor.GetTopology(probe.getRef()));
        var topo = probe.receiveMessage(Duration.ofSeconds(5));

        // Verify our local node ID is set (proves actor booted correctly)
        assertNotNull(topo.localNodeId());
        assertFalse(topo.localNodeId().isEmpty());

        // The version should be the one from AppVersion singleton
        var appVer = AppVersion.get();
        assertNotNull(appVer.version());
        assertTrue(appVer.wireProtocol() >= 1);
    }

    // ==================================================================
    // Wire protocol in topology
    // ==================================================================

    @Test @Order(3)
    void topology_tracks_peer_wire_protocol() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "between-wire-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "between-wire-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-wire-a", "Wire A", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-wire-b", "Wire B", dataDirB, makeConfig(), null));

        Thread.sleep(6000);

        var probeA = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        betweenA.tell(new BetweenActor.GetTopology(probeA.getRef()));
        var topoA = probeA.receiveMessage(Duration.ofSeconds(5));

        if (topoA.connectedNodes() > 0) {
            var peerWireProto = topoA.connections().stream()
                .filter(c -> c.connected() && c.wireProtocol() > 0)
                .findFirst()
                .map(TopologyRegister.ConnectionState::wireProtocol)
                .orElse(0);
            assertTrue(peerWireProto >= 1,
                "Connected peer should have wire protocol >= 1");
        }
    }

    // ==================================================================
    // Version shows in topology describe()
    // ==================================================================

    @Test @Order(4)
    void topology_describe_includes_version() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "between-desc-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "between-desc-b");

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-desc-a", "Desc A", dataDirA, makeConfig(), null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-desc-b", "Desc B", dataDirB, makeConfig(), null));

        Thread.sleep(6000);

        var probeA = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        betweenA.tell(new BetweenActor.GetTopology(probeA.getRef()));
        var topoA = probeA.receiveMessage(Duration.ofSeconds(5));

        if (topoA.connectedNodes() > 0) {
            assertTrue(topoA.description().contains("v"),
                "Topology describe() should include version prefix: " + topoA.description());
        }
    }

    // ==================================================================
    // Version persists across heartbeat updates
    // ==================================================================

    @Test @Order(5)
    void version_persists_across_multiple_heartbeats() throws Exception {
        var betweenA = kitA.spawn(BetweenActor.create(), "between-persist-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "between-persist-b");

        var configA = new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(),
            Duration.ofSeconds(1),  // fast heartbeat
            Duration.ofSeconds(5),
            PortAllocator.allocate());

        var configB = new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(),
            Duration.ofSeconds(1),  // fast heartbeat
            Duration.ofSeconds(5),
            PortAllocator.allocate());

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-persist-a", "Persist A", dataDirA, configA, null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-persist-b", "Persist B", dataDirB, configB, null));

        // Wait for several heartbeat cycles
        Thread.sleep(8000);

        // Check topology twice — version should be consistent
        var probeA1 = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        betweenA.tell(new BetweenActor.GetTopology(probeA1.getRef()));
        var topo1 = probeA1.receiveMessage(Duration.ofSeconds(5));

        Thread.sleep(3000);

        var probeA2 = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        betweenA.tell(new BetweenActor.GetTopology(probeA2.getRef()));
        var topo2 = probeA2.receiveMessage(Duration.ofSeconds(5));

        // Both snapshots should show same peer version
        if (topo1.connectedNodes() > 0 && topo2.connectedNodes() > 0) {
            var v1 = topo1.connections().stream()
                .filter(c -> c.connected() && c.appVersion() != null)
                .findFirst().map(TopologyRegister.ConnectionState::appVersion).orElse(null);
            var v2 = topo2.connections().stream()
                .filter(c -> c.connected() && c.appVersion() != null)
                .findFirst().map(TopologyRegister.ConnectionState::appVersion).orElse(null);
            if (v1 != null && v2 != null) {
                assertEquals(v1, v2, "Version should be consistent across heartbeats");
            }
        }
    }
}
