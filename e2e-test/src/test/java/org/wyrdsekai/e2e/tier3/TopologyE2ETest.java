package org.wyrdsekai.e2e.tier3;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.between.layer.LocalRoomView;
import org.wyrdsekai.between.layer.PresenceLayer;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomOwnership;
import org.wyrdsekai.core.identity.PlayerPresence;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestActorSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 3+ Topology E2E tests.
 *
 * <p>Verifies multi-node room topology via the Between layer with real NATS:
 * gossip convergence, deferred seeding, snapshot replication, player presence
 * across nodes, and graceful shutdown / room release.
 *
 * <p>Uses the lighter approach: two BetweenActor instances in separate
 * ActorTestKits sharing one NATS server. No full Javalin servers needed.
 *
 * <p>Each test uses a unique zone ID to isolate NATS subject namespaces.
 * Each pair of nodes gets fresh temp directories for NodeIdentity files.
 *
 * <p>Skips gracefully when NATS is unavailable (neither Docker nor local binary).
 *
 * <p>Timing notes: The RoomLayer announces rooms immediately on start, then every
 * 30 seconds. To avoid missing the initial announcement, the receiving node's
 * RoomLayer is started before the announcing node's. Convergence timeout is
 * set to 45 seconds as a safety margin.
 */
@Tag("topology")
class TopologyE2ETest {

    private static final Duration HEARTBEAT = Duration.ofSeconds(2);
    private static final Duration PROBE = Duration.ofSeconds(5);
    /**
     * Max time to wait for gossip convergence. Must exceed the RoomLayer
     * announcement interval (30s default) to handle the race between
     * subscription setup and initial announcement.
     */
    private static final Duration CONVERGENCE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final AtomicInteger testCounter = new AtomicInteger(0);

    private static NatsServerFixture nats;
    private static ActorTestKit kitA;
    private static ActorTestKit kitB;

    @BeforeAll
    static void setUp() throws Exception {
        NatsServerFixture.assumeAvailable();

        nats = new NatsServerFixture();
        nats.start();

        kitA = TestActorSystem.create("topo-node-a");
        kitB = TestActorSystem.create("topo-node-b");
    }

    @AfterAll
    static void tearDown() {
        if (kitA != null) kitA.shutdownTestKit();
        if (kitB != null) kitB.shutdownTestKit();
        if (nats != null) nats.stop();
    }

    // ─── Test 1: Two nodes discover each other via heartbeat ────────────────

    @Test
    void two_nodes_discover_each_other() throws Exception {
        var ctx = newTestContext("disc");

        ctx.startBoth();

        // Wait for mutual discovery via hello/hello_ack exchange
        await().atMost(Duration.ofSeconds(15)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var probeA = kitA.<BetweenActor.TopologySnapshot>createTestProbe();
            ctx.betweenA.tell(new BetweenActor.GetTopology(probeA.getRef()));
            var topoA = probeA.receiveMessage(Duration.ofSeconds(3));

            var probeB = kitB.<BetweenActor.TopologySnapshot>createTestProbe();
            ctx.betweenB.tell(new BetweenActor.GetTopology(probeB.getRef()));
            var topoB = probeB.receiveMessage(Duration.ofSeconds(3));

            assertNotNull(topoA.localNodeId(), "Node A should have a local node ID");
            assertNotNull(topoB.localNodeId(), "Node B should have a local node ID");
            assertNotEquals(topoA.localNodeId(), topoB.localNodeId(),
                "Node A and B should have different IDs");
            assertTrue(topoA.connectedNodes() >= 1,
                "Node A should see at least 1 peer, got: " + topoA.connectedNodes());
            assertTrue(topoB.connectedNodes() >= 1,
                "Node B should see at least 1 peer, got: " + topoB.connectedNodes());
        });
    }

    // ─── Test 2: Foundation rooms not duplicated (deferred seeding) ─────────

    @Test
    void foundation_rooms_not_duplicated() throws Exception {
        var ctx = newTestContext("dup");

        // Start BOTH nodes — ensures NATS subscriptions are live
        ctx.startBoth();

        // Query real nodeIds
        var nodeIdA = queryNodeId(kitA, ctx.betweenA);
        var nodeIdB = queryNodeId(kitB, ctx.betweenB);

        // Start B's RoomLayer FIRST (empty rooms) — subscribes B to room NATS subjects
        ctx.betweenB.tell(new BetweenActor.StartRoomLayer(
            () -> List.of(personalRoom("workshop-bob", "did:bob"))));

        // Brief pause to ensure B's NATS room subscription is established
        sleep(1000);

        // Now start A's RoomLayer — its initial announcement will be caught by B
        ctx.betweenA.tell(new BetweenActor.StartRoomLayer(
            () -> foundationRooms(nodeIdA)));

        // Wait for B's RoomLayer to converge with A's announcements
        await().atMost(CONVERGENCE_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var probe = kitB.<LocalRoomView.Snapshot>createTestProbe();
            ctx.betweenB.tell(new BetweenActor.GetRoomView(probe.getRef()));
            var viewB = probe.receiveMessage(Duration.ofSeconds(3));

            // B should see the Nexus room (from A's announcement)
            var nexusEntry = viewB.rooms().get("nexus");
            assertNotNull(nexusEntry, "Node B should see Nexus in its room view");
            // Nexus should be owned by node A
            assertEquals(nodeIdA, nexusEntry.primaryNodeId(),
                "Nexus should show node A as primary in B's view");
        });

        // Node B's own view should include its personal room
        var probe = kitB.<LocalRoomView.Snapshot>createTestProbe();
        ctx.betweenB.tell(new BetweenActor.GetRoomView(probe.getRef()));
        var finalView = probe.receiveMessage(Duration.ofSeconds(3));

        assertTrue(finalView.rooms().containsKey("workshop-bob"),
            "Node B should still have its personal workshop-bob room");

        // Verify the deferred-seeding contract: B's LocalRoomView shows A as
        // primary for all foundation rooms, so isClaimedByOther() returns true
        for (var roomId : List.of("nexus", "terminal", "vault", "docks", "bridge")) {
            var entry = finalView.rooms().get(roomId);
            assertNotNull(entry, "Node B should see " + roomId + " from A's gossip");
            assertEquals(nodeIdA, entry.primaryNodeId(),
                roomId + " should be claimed by node A");
            assertNotEquals(nodeIdB, entry.primaryNodeId(),
                roomId + " should NOT be claimed by node B");
        }
    }

    // ─── Test 3: Room snapshot replication ──────────────────────────────────

    @Test
    void room_snapshot_replication() throws Exception {
        var ctx = newTestContext("snap");

        ctx.startBoth();

        var nodeIdA = queryNodeId(kitA, ctx.betweenA);

        // Start B's RoomLayer first to ensure its subscription is live
        ctx.betweenB.tell(new BetweenActor.StartRoomLayer(() -> List.of()));
        sleep(1000);

        // Node A hosts Nexus with a recent snapshot timestamp
        var snapshotTime = Instant.now();
        ctx.betweenA.tell(new BetweenActor.StartRoomLayer(() -> List.of(
            new RoomAssignment("nexus", RoomOwnership.SHARED, nodeIdA, null, null,
                1, Instant.now(), snapshotTime)
        )));

        // Wait for B to see the Nexus room with its snapshot timestamp
        await().atMost(CONVERGENCE_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var probe = kitB.<LocalRoomView.Snapshot>createTestProbe();
            ctx.betweenB.tell(new BetweenActor.GetRoomView(probe.getRef()));
            var viewB = probe.receiveMessage(Duration.ofSeconds(3));

            var nexus = viewB.rooms().get("nexus");
            assertNotNull(nexus, "Node B should see Nexus via room announcement");
            assertNotNull(nexus.lastSnapshotAt(),
                "Node B should see Nexus snapshot timestamp from A's announcement");
            assertEquals(nodeIdA, nexus.primaryNodeId(),
                "Nexus primary should be node A");
        });
    }

    // ─── Test 4: Player presence visible across nodes ───────────────────────

    @Test
    void player_presence_visible_across_nodes() throws Exception {
        var ctx = newTestContext("pres");

        ctx.startBoth();

        // Wait for both nodes to be up and PresenceLayer started
        await().atMost(Duration.ofSeconds(5)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var probeA = kitA.<PresenceLayer>createTestProbe();
            ctx.betweenA.tell(new BetweenActor.GetPresenceLayer(probeA.getRef()));
            var layerA = probeA.receiveMessage(Duration.ofSeconds(3));
            assertNotNull(layerA, "Node A PresenceLayer should be initialized");

            var probeB = kitB.<PresenceLayer>createTestProbe();
            ctx.betweenB.tell(new BetweenActor.GetPresenceLayer(probeB.getRef()));
            var layerB = probeB.receiveMessage(Duration.ofSeconds(3));
            assertNotNull(layerB, "Node B PresenceLayer should be initialized");
        });

        // Player connects on node A — publish presence
        var nodeIdA = queryNodeId(kitA, ctx.betweenA);
        var playerPresence = new PlayerPresence(
            "did:player:alice", "Alice", nodeIdA, "nexus", Instant.now());
        ctx.betweenA.tell(new BetweenActor.PublishPresence(playerPresence));

        // Node B should see Alice via the PresenceLayer subscription
        await().atMost(Duration.ofSeconds(15)).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var probeB = kitB.<PresenceLayer>createTestProbe();
            ctx.betweenB.tell(new BetweenActor.GetPresenceLayer(probeB.getRef()));
            var layerB = probeB.receiveMessage(Duration.ofSeconds(3));
            assertNotNull(layerB, "Node B PresenceLayer should exist");

            var alice = layerB.getPresence("did:player:alice");
            assertTrue(alice.isPresent(),
                "Node B should see Alice's presence via NATS gossip");
            assertEquals("nexus", alice.get().roomId(),
                "Alice should be in the Nexus room");
            assertEquals("Alice", alice.get().displayName());
        });
    }

    // ─── Test 5: Node shutdown releases rooms ───────────────────────────────

    @Test
    void node_shutdown_releases_rooms() throws Exception {
        var ctx = newTestContext("shut");

        ctx.startBoth();

        // Get A's real nodeId
        var nodeIdA = queryNodeId(kitA, ctx.betweenA);

        // Start B's RoomLayer first — ensure subscription is live
        ctx.betweenB.tell(new BetweenActor.StartRoomLayer(() -> List.of()));
        sleep(1000);

        // Start A's RoomLayer with foundation rooms
        ctx.betweenA.tell(new BetweenActor.StartRoomLayer(
            () -> foundationRooms(nodeIdA)));

        // Wait for B to see A's rooms
        await().atMost(CONVERGENCE_TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
            var probe = kitB.<LocalRoomView.Snapshot>createTestProbe();
            ctx.betweenB.tell(new BetweenActor.GetRoomView(probe.getRef()));
            var viewB = probe.receiveMessage(Duration.ofSeconds(3));

            var nexus = viewB.rooms().get("nexus");
            assertNotNull(nexus,
                "Node B should see Nexus from A before shutdown");
            assertEquals(nodeIdA, nexus.primaryNodeId(),
                "Nexus should be owned by A before shutdown");
        });

        // Stop node A — triggers PostStop which broadcasts "leaving" message.
        // B's BetweenActor receives it, calls handleLeaving, which sends
        // PeerTimedOut(nodeIdA) to B's RoomLayer, which calls releaseRooms(nodeIdA).
        // Then onPeerTimedOut calls orphanedSharedRooms() and claims them for B.
        var nodeIdB = queryNodeId(kitB, ctx.betweenB);
        kitA.stop(ctx.betweenA);

        // Node B should detect A's departure and release A's rooms.
        // B will then re-claim orphaned shared rooms for itself.
        // The end state: rooms are either orphaned (null) or claimed by B — never by A.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            var probe = kitB.<LocalRoomView.Snapshot>createTestProbe();
            ctx.betweenB.tell(new BetweenActor.GetRoomView(probe.getRef()));
            var viewB = probe.receiveMessage(Duration.ofSeconds(3));

            var nexus = viewB.rooms().get("nexus");
            assertNotNull(nexus, "Nexus should still be in B's view after A shutdown");
            // After releaseRooms, A is no longer primary.
            // B claims orphaned shared rooms via onPeerTimedOut, so Nexus may
            // already be re-claimed by B or still orphaned (null).
            assertNotEquals(nodeIdA, nexus.primaryNodeId(),
                "Nexus should no longer be owned by A after shutdown");
            // Verify B claimed it (expected behavior for shared rooms)
            assertTrue(nexus.primaryNodeId() == null || nexus.primaryNodeId().equals(nodeIdB),
                "Nexus should be orphaned or claimed by B, was: " + nexus.primaryNodeId());
        });
    }

    // ─── Test infrastructure ────────────────────────────────────────────────

    /**
     * Per-test context with isolated zone ID and data directories.
     * Each test pair of BetweenActors uses a unique zone to prevent
     * NATS subject cross-talk between tests running in the same JVM.
     */
    private record TestContext(
        String zoneId,
        Path dataDirA,
        Path dataDirB,
        ActorRef<BetweenActor.Command> betweenA,
        ActorRef<BetweenActor.Command> betweenB
    ) {
        void startBoth() {
            betweenA.tell(new BetweenActor.StartBetween(
                zoneId, "Node Alpha", dataDirA, betweenConfig(zoneId), null));
            betweenB.tell(new BetweenActor.StartBetween(
                zoneId, "Node Beta", dataDirB, betweenConfig(zoneId), null));
        }
    }

    /** Create an isolated test context with unique zone ID and data dirs. */
    private static TestContext newTestContext(String label) throws Exception {
        var seq = testCounter.incrementAndGet();
        var zoneId = "topo-" + label + "-" + seq;
        var dirA = Files.createTempDirectory("topo-" + label + "-a-");
        var dirB = Files.createTempDirectory("topo-" + label + "-b-");

        var betweenA = kitA.spawn(BetweenActor.create(), "between-" + label + "-a-" + seq);
        var betweenB = kitB.spawn(BetweenActor.create(), "between-" + label + "-b-" + seq);

        return new TestContext(zoneId, dirA, dirB, betweenA, betweenB);
    }

    /** Create a BetweenConfig pointing at the shared NATS fixture. */
    private static BetweenActor.BetweenConfig betweenConfig(String zoneId) {
        return new BetweenActor.BetweenConfig(
            true,                    // enabled
            nats.natsUrl(),          // natsUrl
            false,                   // natsAutoStart (we manage NATS externally)
            null,                    // natsExecutable
            nats.clientPort(),       // natsClientPort
            nats.monitorPort(),      // natsMonitorPort
            false,                   // mdnsEnabled (not needed in test)
            List.of(),               // seedNodes
            HEARTBEAT,               // heartbeatInterval
            PROBE,                   // probeInterval
            PortAllocator.allocate() // arteryPort
        );
    }

    /**
     * Query a BetweenActor for its local nodeId (generated from NodeIdentity).
     * Blocks until the nodeId is available, up to 5 seconds.
     */
    private static String queryNodeId(ActorTestKit kit,
                                       ActorRef<BetweenActor.Command> between) {
        var probe = kit.<BetweenActor.TopologySnapshot>createTestProbe();
        between.tell(new BetweenActor.GetTopology(probe.getRef()));
        var snapshot = probe.receiveMessage(Duration.ofSeconds(5));
        assertNotNull(snapshot.localNodeId(), "BetweenActor should have a nodeId after start");
        assertNotEquals("unknown", snapshot.localNodeId(),
            "BetweenActor nodeId should not be 'unknown'");
        return snapshot.localNodeId();
    }

    /** Build foundation room assignments stamped with the given nodeId. */
    private static List<RoomAssignment> foundationRooms(String nodeId) {
        var now = Instant.now();
        return List.of(
            new RoomAssignment("nexus", RoomOwnership.SHARED, nodeId, null, null, 1, now, now),
            new RoomAssignment("terminal", RoomOwnership.SHARED, nodeId, null, null, 1, now, now),
            new RoomAssignment("vault", RoomOwnership.SHARED, nodeId, null, null, 1, now, now),
            new RoomAssignment("docks", RoomOwnership.SHARED, nodeId, null, null, 1, now, now),
            new RoomAssignment("bridge", RoomOwnership.SHARED, nodeId, null, null, 1, now, now)
        );
    }

    /** Build a single personal room assignment (not replicated). */
    private static RoomAssignment personalRoom(String roomId, String ownerDid) {
        var now = Instant.now();
        return new RoomAssignment(roomId, RoomOwnership.PERSONAL, null, ownerDid, null, 0, now, now);
    }

    /** Sleep without checked exception (for brief pauses between setup steps). */
    @SuppressWarnings("SameParameterValue")
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
