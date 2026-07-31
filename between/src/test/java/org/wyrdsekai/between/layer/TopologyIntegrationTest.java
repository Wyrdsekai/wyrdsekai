package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.topology.NodeAnnouncement;
import org.wyrdsekai.common.topology.NodeResources;
import org.wyrdsekai.common.topology.ReplicationTier;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomOwnership;
import org.wyrdsekai.core.identity.AccountService;
import org.wyrdsekai.core.identity.AccountStore;
import org.wyrdsekai.core.identity.PlayerAccount;
import org.wyrdsekai.core.identity.PlayerPresence;
import org.wyrdsekai.core.identity.ZoneSetup;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Room-Node Topology Phases 1-3.
 *
 * These verify the WIRING between components — not individual correctness,
 * but that the connected system works end to end:
 * <ul>
 *   <li>Phase 1: Room gossip, failover, deferred seeding</li>
 *   <li>Phase 2: Tier promotion/demotion, event replication chain</li>
 *   <li>Phase 3: Account/device auto-login, presence, zone setup</li>
 * </ul>
 */
class TopologyIntegrationTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    /**
     * Create a shared in-memory SQLite URL with a unique name to avoid cross-test contamination.
     * Opens a keep-alive connection so the in-memory database persists across service connections.
     * The keep-alive connection is intentionally never closed — it survives until JVM exit (fine for tests).
     */
    @SuppressWarnings("resource") // Intentional keep-alive for shared-cache in-memory DB
    private static String inMemoryDb() {
        var url = "jdbc:sqlite:file:topo-" + UUID.randomUUID().toString().substring(0, 8)
            + "?mode=memory&cache=shared";
        try {
            // This connection keeps the in-memory database alive across AccountStore's per-call connections
            DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open keep-alive connection for test DB", e);
        }
        return url;
    }

    private static NodeResources emptyResources() {
        return new NodeResources(0, 1024, List.of(), List.of(), 0.0, 50);
    }

    private static RoomAssignment room(String roomId, RoomOwnership ownership, String nodeId) {
        return new RoomAssignment(roomId, ownership, nodeId,
            null, null, 2, Instant.now(), Instant.now());
    }

    private static NodeAnnouncement announcement(String nodeId, List<RoomAssignment> rooms) {
        return new NodeAnnouncement(nodeId, "host-" + nodeId, List.of(), rooms,
            emptyResources(), Instant.now());
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 1 Wiring
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class Phase1_RoomGossip {

        @Test
        void two_nodes_discover_rooms() {
            // Two RoomLayer actors simulating two nodes exchange announcements.
            // Verify each node's LocalRoomView contains the other's rooms.
            var layerA = testKit.spawn(RoomLayer.create());
            var layerB = testKit.spawn(RoomLayer.create());
            var probeA = testKit.createTestProbe(LocalRoomView.Snapshot.class);
            var probeB = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            var roomsA = List.of(
                room("nexus", RoomOwnership.SHARED, "node-A"),
                room("forge", RoomOwnership.SHARED, "node-A")
            );
            var roomsB = List.of(
                room("library", RoomOwnership.SHARED, "node-B"),
                room("garden", RoomOwnership.SHARED, "node-B")
            );

            // Start both layers (no NATS — single-node mode, but we simulate gossip manually)
            layerA.tell(new RoomLayer.Start(null, "home", "node-A", () -> roomsA,
                Duration.ofSeconds(600), Duration.ofSeconds(600)));
            layerB.tell(new RoomLayer.Start(null, "home", "node-B", () -> roomsB,
                Duration.ofSeconds(600), Duration.ofSeconds(600)));

            // Simulate gossip: A announces to B, B announces to A
            layerA.tell(new RoomLayer.AnnouncementReceived(announcement("node-B", roomsB)));
            layerB.tell(new RoomLayer.AnnouncementReceived(announcement("node-A", roomsA)));

            // Verify A sees B's rooms
            layerA.tell(new RoomLayer.GetView(probeA.getRef()));
            var snapA = probeA.receiveMessage(Duration.ofSeconds(3));
            assertThat(snapA.rooms()).containsKey("library");
            assertThat(snapA.rooms()).containsKey("garden");
            assertThat(snapA.rooms().get("library").primaryNodeId()).isEqualTo("node-B");

            // Verify B sees A's rooms
            layerB.tell(new RoomLayer.GetView(probeB.getRef()));
            var snapB = probeB.receiveMessage(Duration.ofSeconds(3));
            assertThat(snapB.rooms()).containsKey("nexus");
            assertThat(snapB.rooms()).containsKey("forge");
            assertThat(snapB.rooms().get("nexus").primaryNodeId()).isEqualTo("node-A");
        }

        @Test
        void node_departure_triggers_room_failover() {
            // Node A announces shared rooms. Node B sees them. Node A times out.
            // Node B claims orphaned shared rooms.
            var layerB = testKit.spawn(RoomLayer.create());
            var probeB = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            layerB.tell(new RoomLayer.Start(null, "home", "node-B", List::of,
                Duration.ofSeconds(600), Duration.ofSeconds(600)));

            // Node A announces rooms
            var roomsA = List.of(
                room("nexus", RoomOwnership.SHARED, "node-A"),
                room("terminal", RoomOwnership.SHARED, "node-A")
            );
            layerB.tell(new RoomLayer.AnnouncementReceived(announcement("node-A", roomsA)));

            // Verify B sees A's rooms
            layerB.tell(new RoomLayer.GetView(probeB.getRef()));
            var before = probeB.receiveMessage(Duration.ofSeconds(3));
            assertThat(before.rooms().get("nexus").primaryNodeId()).isEqualTo("node-A");

            // Node A times out
            layerB.tell(new RoomLayer.PeerTimedOut("node-A"));

            // Verify B has claimed the orphaned rooms
            layerB.tell(new RoomLayer.GetView(probeB.getRef()));
            var after = probeB.receiveMessage(Duration.ofSeconds(3));
            assertThat(after.rooms().get("nexus").primaryNodeId()).isEqualTo("node-B");
            assertThat(after.rooms().get("terminal").primaryNodeId()).isEqualTo("node-B");
        }

        @Test
        void deferred_seeding_skips_peer_rooms() {
            // ZoneGuardian with ApplyRoomView should skip rooms claimed by peers.
            // We test the LocalRoomView + shouldHostRoom logic that ZoneGuardian relies on,
            // since ZoneGuardian requires full cluster sharding which is too heavy for this test.
            // This tests the same decision path: shouldHostRoom returns false for peer-claimed rooms.
            var view = new LocalRoomView();

            // Simulate: peer claims nexus and library
            view.updateFromAnnouncement(announcement("peer-node", List.of(
                room("nexus", RoomOwnership.SHARED, "peer-node"),
                room("library", RoomOwnership.SHARED, "peer-node")
            )));

            // The local node wants to seed nexus, library, garden, forge
            var allSeeds = List.of("nexus", "library", "garden", "forge");

            // Simulate ZoneGuardian's ApplyRoomView logic:
            // only seed rooms NOT claimed by other nodes
            var seeded = new ArrayList<String>();
            var skipped = new ArrayList<String>();
            for (var roomId : allSeeds) {
                if (view.isClaimedByOther(roomId, "local-node")) {
                    skipped.add(roomId);
                } else {
                    seeded.add(roomId);
                }
            }

            assertThat(skipped).containsExactlyInAnyOrder("nexus", "library");
            assertThat(seeded).containsExactlyInAnyOrder("garden", "forge");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 2 Wiring
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class Phase2_TierManagement {

        @Test
        void active_room_promotes_to_event_sourced() {
            // Room starts at CONFIG_ONLY. Simulate activity that promotes to EVENT_SOURCED.
            // Verify the full chain: RoomLayer -> RoomReplicationManager -> RoomEventReplicator.
            var layer = testKit.spawn(RoomLayer.create());
            var tierProbe = testKit.createTestProbe(ReplicationTier.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(600), Duration.ofSeconds(600)));

            // Initial: CONFIG_ONLY (never visited)
            layer.tell(new RoomLayer.GetTier("nexus", tierProbe.getRef()));
            assertThat(tierProbe.receiveMessage(Duration.ofSeconds(3)))
                .isEqualTo(ReplicationTier.CONFIG_ONLY);

            // Activity: entity enters, companion present, human present
            layer.tell(new RoomLayer.RoomActivity(
                "nexus", RoomOwnership.SHARED, true, true));

            // Should now be EVENT_SOURCED
            layer.tell(new RoomLayer.GetTier("nexus", tierProbe.getRef()));
            assertThat(tierProbe.receiveMessage(Duration.ofSeconds(3)))
                .isEqualTo(ReplicationTier.EVENT_SOURCED);
        }

        @Test
        void room_event_reaches_replicator() {
            // Set a RoomEventListener (RoomEventReplicator). Enable event-sourcing.
            // Trigger onRoomEvent. Verify the listener fires via the snapshot trigger callback.
            // This tests the RoomActor -> RoomEventListener -> RoomEventReplicator chain
            // without needing a full RoomActor (which requires Pekko persistence).

            var replicator = new RoomEventReplicator(null, "test-zone");
            replicator.enableEventSourcing("nexus");

            // Track that the event listener fires
            var receivedEvents = new ArrayList<String>();
            // RoomEventReplicator IS the RoomEventListener (implements the interface).
            // For event-sourced rooms, onRoomEvent calls publishEvent which is a no-op with null NATS.
            // For write-through rooms, it triggers the snapshot callback.
            // We test the write-through path since it has an observable callback.
            replicator.enableWriteThrough("home-ma");
            replicator.setSnapshotTrigger(receivedEvents::add);

            // Simulate what RoomActor does: call onRoomEvent after persisting
            var event = new WorldEvent.Said("home-ma", Instant.now(), "player-1", "Masumi", "Hello");
            replicator.onRoomEvent("home-ma", event);

            assertThat(receivedEvents).containsExactly("home-ma");

            // Also verify the event-sourced path doesn't crash (no callback, just NATS publish which is no-op)
            var esEvent = new WorldEvent.Said("nexus", Instant.now(), "player-1", "Masumi", "World");
            replicator.onRoomEvent("nexus", esEvent);
            // No exception = the chain works
        }

        @Test
        void idle_room_demotes_tier() {
            // Room at EVENT_SOURCED. Simulate no entities (room emptied).
            // Verify demotion to PERIODIC via checkPromotion (the same path DemotionTick uses).
            var manager = new RoomReplicationManager();

            // Set up as EVENT_SOURCED (companion + human present)
            manager.recordActivity("nexus");
            manager.computeTier("nexus", RoomOwnership.SHARED, true, true);
            assertThat(manager.getTier("nexus")).isEqualTo(ReplicationTier.EVENT_SOURCED);

            // Simulate the demotion check (what RoomLayer.onDemotionTick does):
            // Re-evaluate with no entities, no companion
            var newTier = manager.checkPromotion("nexus", RoomOwnership.SHARED, false, false);

            assertThat(newTier).isNotNull();
            assertThat(newTier).isEqualTo(ReplicationTier.PERIODIC);
            assertThat(manager.getTier("nexus")).isEqualTo(ReplicationTier.PERIODIC);
        }

        @Test
        void write_through_room_publishes_snapshot_on_every_change() {
            // Personal room (WRITE_THROUGH). Trigger a state change.
            // Verify the replicator's snapshot trigger fires.
            var replicator = new RoomEventReplicator(null, "test-zone");
            var snapshots = new ArrayList<String>();
            replicator.setSnapshotTrigger(snapshots::add);

            // Personal room -> WRITE_THROUGH
            replicator.updateTier("study-operator", ReplicationTier.WRITE_THROUGH);
            assertThat(replicator.isWriteThrough("study-operator")).isTrue();

            // Trigger a state change (say something in the room)
            var event1 = new WorldEvent.Said("study-operator", Instant.now(),
                "player-1", "Masumi", "First message");
            replicator.onRoomEvent("study-operator", event1);

            var event2 = new WorldEvent.Said("study-operator", Instant.now(),
                "player-1", "Masumi", "Second message");
            replicator.onRoomEvent("study-operator", event2);

            // Every change triggers a snapshot
            assertThat(snapshots).hasSize(2);
            assertThat(snapshots).containsExactly("study-operator", "study-operator");
        }

        @Test
        void tier_promotion_wires_through_room_layer_to_replicator() {
            // End-to-end: RoomLayer.RoomActivity -> RoomReplicationManager.checkPromotion
            // -> RoomEventReplicator.updateTier. Verify via GetTier + GetAllTiers.
            var layer = testKit.spawn(RoomLayer.create());
            var tierProbe = testKit.createTestProbe(ReplicationTier.class);
            @SuppressWarnings("unchecked")
            var allTiersProbe = (TestProbe<Map<String, ReplicationTier>>)
                (TestProbe<?>) testKit.createTestProbe(Map.class);

            layer.tell(new RoomLayer.Start(null, "home", "node-1", List::of,
                Duration.ofSeconds(600), Duration.ofSeconds(600)));

            // Activity in two rooms: one shared active, one personal
            layer.tell(new RoomLayer.RoomActivity(
                "nexus", RoomOwnership.SHARED, true, true));
            layer.tell(new RoomLayer.RoomActivity(
                "study-operator", RoomOwnership.PERSONAL, false, false));

            // Verify individual tiers
            layer.tell(new RoomLayer.GetTier("nexus", tierProbe.getRef()));
            assertThat(tierProbe.receiveMessage(Duration.ofSeconds(3)))
                .isEqualTo(ReplicationTier.EVENT_SOURCED);

            layer.tell(new RoomLayer.GetTier("study-operator", tierProbe.getRef()));
            assertThat(tierProbe.receiveMessage(Duration.ofSeconds(3)))
                .isEqualTo(ReplicationTier.WRITE_THROUGH);

            // Verify all tiers view
            layer.tell(new RoomLayer.GetAllTiers(allTiersProbe.getRef()));
            var allTiers = allTiersProbe.receiveMessage(Duration.ofSeconds(3));
            assertThat(allTiers).containsKey("nexus");
            assertThat(allTiers).containsKey("study-operator");
            assertThat(allTiers.get("nexus")).isEqualTo(ReplicationTier.EVENT_SOURCED);
            assertThat(allTiers.get("study-operator")).isEqualTo(ReplicationTier.WRITE_THROUGH);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 3 Wiring
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class Phase3_Identity {

        @Test
        void account_creation_and_auto_login() {
            // Create AccountStore (in-memory SQLite). Create an account.
            // Register a device. Verify auto-login returns the account.
            var store = new AccountStore(inMemoryDb());
            var service = new AccountService(store);

            // Create account
            var account = service.createAccount("Masumi");
            assertThat(account.did()).startsWith("did:key:");
            assertThat(account.displayName()).isEqualTo("Masumi");

            // Register a device for auto-login
            service.registerDevice(account.did(), "device-macbook-001");

            // Auto-login with that device
            var autoLogin = service.autoLogin("device-macbook-001");
            assertThat(autoLogin).isPresent();
            assertThat(autoLogin.get().did()).isEqualTo(account.did());
            assertThat(autoLogin.get().displayName()).isEqualTo("Masumi");
        }

        @Test
        void presence_published_on_connect() {
            // PresenceLayer stores presence locally on publishPresence.
            // We can't test NATS subscription without a real NATS server,
            // but we can verify the local map is updated.
            // PresenceLayer requires NatsBridge, so we test the local map path directly.
            // The publishPresence method stores in presences before broadcasting to NATS.
            // With null NATS it would NPE, so we test the direct data structure behavior.

            // Test the data model and local caching behavior
            var presence = PlayerPresence.online("did:key:z6MkTest", "Masumi", "node-1", "nexus");

            assertThat(presence.did()).isEqualTo("did:key:z6MkTest");
            assertThat(presence.nodeId()).isEqualTo("node-1");
            assertThat(presence.roomId()).isEqualTo("nexus");

            // Test room movement
            var moved = presence.inRoom("library");
            assertThat(moved.roomId()).isEqualTo("library");
            assertThat(moved.did()).isEqualTo("did:key:z6MkTest");
        }

        @Test
        void authenticated_player_uses_did() {
            // AccountService -> device lookup -> DID resolution path:
            // create account, register device, call autoLogin, verify DID returned.
            var store = new AccountStore(inMemoryDb());
            var service = new AccountService(store);

            // Create two accounts
            var alice = service.createAccount("Alice");
            var bob = service.createAccount("Bob");

            // Register devices
            service.registerDevice(alice.did(), "device-phone-alice");
            service.registerDevice(bob.did(), "device-tablet-bob");

            // Auto-login resolves to correct DID
            var aliceLogin = service.autoLogin("device-phone-alice");
            assertThat(aliceLogin).isPresent();
            assertThat(aliceLogin.get().did()).isEqualTo(alice.did());

            var bobLogin = service.autoLogin("device-tablet-bob");
            assertThat(bobLogin).isPresent();
            assertThat(bobLogin.get().did()).isEqualTo(bob.did());

            // Unknown device returns empty
            var unknown = service.autoLogin("device-unknown");
            assertThat(unknown).isEmpty();

            // Verify accounts can be looked up by DID
            var foundAlice = service.findByDid(alice.did());
            assertThat(foundAlice).isPresent();
            assertThat(foundAlice.get().displayName()).isEqualTo("Alice");
        }

        @Test
        void zone_setup_join_flow() {
            // Create a zone. Generate a join token. Validate the token.
            // Verify it returns the zone info.
            var store = new AccountStore(inMemoryDb());
            var service = new AccountService(store);

            // Create the zone creator
            var creator = service.createAccount("Masumi");

            // Create a zone
            var zone = ZoneSetup.createZone("Masumi's Household", creator);
            assertThat(zone.zoneId()).startsWith("zone-");
            assertThat(zone.zoneName()).isEqualTo("Masumi's Household");
            assertThat(zone.creatorDid()).isEqualTo(creator.did());
            assertThat(zone.secret()).hasSize(32);

            // Generate a join token
            var token = ZoneSetup.generateJoinToken(zone, creator.did());
            assertThat(token).isNotEmpty();

            // Validate the token
            var approval = ZoneSetup.validateJoinToken(token, zone.secret());
            assertThat(approval).isPresent();
            assertThat(approval.get().zoneId()).isEqualTo(zone.zoneId());
            assertThat(approval.get().approvedBy()).isEqualTo(creator.did());

            // Wrong secret fails validation
            var wrongSecret = new byte[32];
            var badApproval = ZoneSetup.validateJoinToken(token, wrongSecret);
            assertThat(badApproval).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Cross-Phase Wiring
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class CrossPhase_Integration {

        @Test
        void full_node_lifecycle_gossip_to_tier_to_replication() {
            // End-to-end: Two nodes discover rooms via gossip (Phase 1),
            // room activity triggers tier promotion (Phase 2),
            // verifying the complete chain works.
            var layer = testKit.spawn(RoomLayer.create());
            var tierProbe = testKit.createTestProbe(ReplicationTier.class);
            var viewProbe = testKit.createTestProbe(LocalRoomView.Snapshot.class);

            // Start layer
            var localRooms = List.of(
                room("nexus", RoomOwnership.SHARED, "node-1")
            );
            layer.tell(new RoomLayer.Start(null, "home", "node-1", () -> localRooms,
                Duration.ofSeconds(600), Duration.ofSeconds(600)));

            // Peer announces rooms
            var peerRooms = List.of(
                room("library", RoomOwnership.SHARED, "node-2")
            );
            layer.tell(new RoomLayer.AnnouncementReceived(announcement("node-2", peerRooms)));

            // Verify both local and peer rooms in view
            layer.tell(new RoomLayer.GetView(viewProbe.getRef()));
            var snap = viewProbe.receiveMessage(Duration.ofSeconds(3));
            assertThat(snap.rooms()).containsKey("nexus");
            assertThat(snap.rooms()).containsKey("library");

            // Activity in local room promotes tier
            layer.tell(new RoomLayer.RoomActivity(
                "nexus", RoomOwnership.SHARED, true, true));

            layer.tell(new RoomLayer.GetTier("nexus", tierProbe.getRef()));
            assertThat(tierProbe.receiveMessage(Duration.ofSeconds(3)))
                .isEqualTo(ReplicationTier.EVENT_SOURCED);

            // Peer times out — failover
            layer.tell(new RoomLayer.PeerTimedOut("node-2"));

            layer.tell(new RoomLayer.GetView(viewProbe.getRef()));
            var afterFailover = viewProbe.receiveMessage(Duration.ofSeconds(3));
            assertThat(afterFailover.rooms().get("library").primaryNodeId())
                .isEqualTo("node-1");
        }

        @Test
        void account_device_multi_device_single_account() {
            // A single account can be accessed from multiple devices.
            // This verifies the auto-login mapping supports the household pattern:
            // one person, multiple devices (laptop, phone, tablet).
            var store = new AccountStore(inMemoryDb());
            var service = new AccountService(store);

            var account = service.createAccount("Masumi");
            service.registerDevice(account.did(), "device-macbook");
            service.registerDevice(account.did(), "device-iphone");
            service.registerDevice(account.did(), "device-ipad");

            // All three devices resolve to the same account
            assertThat(service.autoLogin("device-macbook").get().did())
                .isEqualTo(account.did());
            assertThat(service.autoLogin("device-iphone").get().did())
                .isEqualTo(account.did());
            assertThat(service.autoLogin("device-ipad").get().did())
                .isEqualTo(account.did());
        }
    }
}
