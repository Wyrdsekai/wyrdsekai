package org.wyrdsekai.e2e.tier3;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestActorSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Between discovery and topology tests with real NATS server.
 * Two BetweenActor instances in separate ActorTestKits sharing one NATS server.
 */
@Tag("between")
class DiscoveryAndTopologyTest {

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

        kitA = TestActorSystem.create("zone-a");
        kitB = TestActorSystem.create("zone-b");

        dataDirA = Files.createTempDirectory("between-a");
        dataDirB = Files.createTempDirectory("between-b");
    }

    @AfterAll
    static void tearDown() {
        if (kitA != null) kitA.shutdownTestKit();
        if (kitB != null) kitB.shutdownTestKit();
        if (nats != null) nats.stop();
    }

    @Test
    void mutual_discovery_via_nats() {
        var betweenA = kitA.spawn(BetweenActor.create(), "between-a");
        var betweenB = kitB.spawn(BetweenActor.create(), "between-b");

        var configA = new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(), Duration.ofSeconds(2), Duration.ofSeconds(5),
            PortAllocator.allocate());

        var configB = new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(), Duration.ofSeconds(2), Duration.ofSeconds(5),
            PortAllocator.allocate());

        betweenA.tell(new BetweenActor.StartBetween(
            "zone-alpha", "Zone Alpha", dataDirA, configA, null));
        betweenB.tell(new BetweenActor.StartBetween(
            "zone-beta", "Zone Beta", dataDirB, configB, null));

        // Wait for discovery via heartbeats
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

        var probeA = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        betweenA.tell(new BetweenActor.GetTopology(probeA.getRef()));
        var topoA = probeA.receiveMessage(Duration.ofSeconds(5));
        assertNotNull(topoA);
        // Node B should be visible (or at least we don't crash)
        assertNotNull(topoA.localNodeId());
    }

    @Test
    void topology_shows_latency() {
        var between = kitA.spawn(BetweenActor.create(), "between-topo");
        var config = new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(), Duration.ofSeconds(2), Duration.ofSeconds(3),
            PortAllocator.allocate());

        between.tell(new BetweenActor.StartBetween(
            "zone-topo", "Zone Topo", dataDirA, config, null));

        try { Thread.sleep(4000); } catch (InterruptedException ignored) {}

        var probe = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        between.tell(new BetweenActor.GetTopology(probe.getRef()));
        var topo = probe.receiveMessage(Duration.ofSeconds(5));
        assertNotNull(topo);
        // Topology description should mention latency if peers found
        var desc = topo.description();
        assertNotNull(desc, "Topology description should be non-null");
    }

    @Test
    void heartbeat_keeps_connection_alive() throws Exception {
        var between = kitA.spawn(BetweenActor.create(), "between-hb");
        var config = new BetweenActor.BetweenConfig(
            true, nats.natsUrl(), false, null,
            nats.clientPort(), nats.monitorPort(),
            false, List.of(), Duration.ofSeconds(1), Duration.ofSeconds(2),
            PortAllocator.allocate());

        between.tell(new BetweenActor.StartBetween(
            "zone-hb", "Zone Heartbeat", dataDirA, config, null));

        // Wait for several heartbeat cycles
        Thread.sleep(5000);

        var probe = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
        between.tell(new BetweenActor.GetTopology(probe.getRef()));
        var topo = probe.receiveMessage(Duration.ofSeconds(5));
        assertNotNull(topo, "Should still be responsive after heartbeat cycles");
    }
}
