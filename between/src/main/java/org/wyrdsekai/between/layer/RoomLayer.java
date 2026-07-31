package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.common.topology.NodeAnnouncement;
import org.wyrdsekai.common.topology.NodeResources;
import org.wyrdsekai.common.topology.ReplicationTier;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomClaimMessage;
import org.wyrdsekai.common.topology.RoomOwnership;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Room topology layer for The Between.
 *
 * Manages room assignment gossip across household nodes:
 * <ul>
 *   <li>Periodic announcement of locally hosted rooms</li>
 *   <li>Inbound peer announcements → updates LocalRoomView</li>
 *   <li>Peer timeout → release rooms, claim orphans</li>
 *   <li>Room snapshot replication for shared/agent-home rooms</li>
 * </ul>
 *
 * NATS subjects:
 * <pre>
 *   between.{zone}.*.*.rooms.announcement  — periodic room list broadcast
 *   between.{zone}.*.*.rooms.claim         — room claim (conflict resolution)
 *   between.{zone}.*.*.rooms.snapshot      — room state snapshot for replication
 * </pre>
 */
public class RoomLayer extends AbstractBehavior<RoomLayer.Command> {

    private static final Logger log = LoggerFactory.getLogger(RoomLayer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    // --- Commands ---

    public sealed interface Command {}

    /** Initialize the room layer with dependencies. */
    public record Start(
        NatsBridge nats,
        String zoneId,
        String localNodeId,
        Supplier<List<RoomAssignment>> localRoomsSupplier,
        Duration announcementInterval,
        Duration snapshotInterval
    ) implements Command {
        /** Convenience constructor with default intervals. */
        public Start(NatsBridge nats, String zoneId, String localNodeId,
                     Supplier<List<RoomAssignment>> localRoomsSupplier) {
            this(nats, zoneId, localNodeId, localRoomsSupplier,
                Duration.ofSeconds(30), Duration.ofSeconds(60));
        }
    }

    /** A peer announced its room list. */
    public record AnnouncementReceived(NodeAnnouncement announcement) implements Command {}

    /** A room claim was received from a peer. */
    public record ClaimReceived(RoomClaimMessage claim) implements Command {}

    /** A peer node timed out (no heartbeat). */
    public record PeerTimedOut(String nodeId) implements Command {}

    /** A room snapshot was received from a peer. */
    public record SnapshotReceived(
        String roomId, String fromNodeId,
        byte[] snapshotData, Instant timestamp
    ) implements Command {}

    /** Periodic: announce local rooms to peers. */
    private record AnnouncementTick() implements Command {}

    /** Periodic: publish snapshots of locally hosted shared rooms. */
    private record SnapshotTick() implements Command {}

    /** Query: return the current room view. */
    public record GetView(ActorRef<LocalRoomView.Snapshot> replyTo) implements Command {}

    /** Phase 2: Notify room activity (entity entered, speech, etc.) for tier promotion. */
    public record RoomActivity(
        String roomId, RoomOwnership ownership,
        boolean hasEntities, boolean isCompanionRoom
    ) implements Command {}

    /** Phase 2: Query the current replication tier for a room. */
    public record GetTier(String roomId, ActorRef<ReplicationTier> replyTo) implements Command {}

    /** Phase 2: Query all room replication tiers. */
    public record GetAllTiers(ActorRef<Map<String, ReplicationTier>> replyTo) implements Command {}

    /** Phase 2: Periodic demotion check for idle rooms. */
    private record DemotionTick() implements Command {}

    // --- State ---

    private final TimerScheduler<Command> timers;
    private final LocalRoomView view = new LocalRoomView();
    private final RoomReplicationManager replicationManager = new RoomReplicationManager();

    private NatsBridge nats;
    private String zoneId;
    private String localNodeId;
    private Supplier<List<RoomAssignment>> localRoomsSupplier;
    private RoomEventReplicator eventReplicator;
    private boolean started = false;

    private RoomLayer(ActorContext<Command> context, TimerScheduler<Command> timers) {
        super(context);
        this.timers = timers;
    }

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers ->
                new RoomLayer(ctx, timers)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Start.class, this::onStart)
            .onMessage(AnnouncementReceived.class, this::onAnnouncementReceived)
            .onMessage(ClaimReceived.class, this::onClaimReceived)
            .onMessage(PeerTimedOut.class, this::onPeerTimedOut)
            .onMessage(SnapshotReceived.class, this::onSnapshotReceived)
            .onMessage(AnnouncementTick.class, this::onAnnouncementTick)
            .onMessage(SnapshotTick.class, this::onSnapshotTick)
            .onMessage(GetView.class, this::onGetView)
            .onMessage(RoomActivity.class, this::onRoomActivity)
            .onMessage(GetTier.class, this::onGetTier)
            .onMessage(GetAllTiers.class, this::onGetAllTiers)
            .onMessage(DemotionTick.class, this::onDemotionTick)
            .build();
    }

    // --- Handlers ---

    private Behavior<Command> onStart(Start cmd) {
        this.nats = cmd.nats();
        this.zoneId = cmd.zoneId();
        this.localNodeId = cmd.localNodeId();
        this.localRoomsSupplier = cmd.localRoomsSupplier();
        this.eventReplicator = new RoomEventReplicator(cmd.nats(), cmd.zoneId());
        this.started = true;

        // Start periodic announcement timer
        timers.startTimerWithFixedDelay(
            "room-announcement", new AnnouncementTick(), cmd.announcementInterval());

        // Start periodic snapshot timer
        timers.startTimerWithFixedDelay(
            "room-snapshot", new SnapshotTick(), cmd.snapshotInterval());

        // Start periodic demotion check (Phase 2)
        timers.startTimerWithFixedDelay(
            "room-demotion", new DemotionTick(), Duration.ofMinutes(1));

        // Perform initial announcement immediately
        announceRooms();

        log.info("RoomLayer started for node {} in zone {} — announcement={}s, snapshot={}s",
            localNodeId, zoneId,
            cmd.announcementInterval().toSeconds(), cmd.snapshotInterval().toSeconds());
        return this;
    }

    private Behavior<Command> onAnnouncementReceived(AnnouncementReceived cmd) {
        if (!started) return this;
        var announcement = cmd.announcement();

        // Ignore our own announcements
        if (localNodeId.equals(announcement.nodeId())) return this;

        view.updateFromAnnouncement(announcement);
        log.debug("RoomLayer: announcement from {} — {} rooms",
            announcement.nodeId(), announcement.rooms().size());
        return this;
    }

    private Behavior<Command> onClaimReceived(ClaimReceived cmd) {
        if (!started) return this;
        var claim = cmd.claim();

        // Ignore our own claims
        if (localNodeId.equals(claim.claimingNodeId())) return this;

        boolean accepted = view.claimRoom(claim);
        if (accepted) {
            log.info("RoomLayer: claim accepted — room {} → node {}",
                claim.roomId(), claim.claimingNodeId());
        } else {
            log.debug("RoomLayer: claim rejected — room {} by node {} (our assignment wins)",
                claim.roomId(), claim.claimingNodeId());
        }
        return this;
    }

    private Behavior<Command> onPeerTimedOut(PeerTimedOut cmd) {
        if (!started) return this;

        view.releaseRooms(cmd.nodeId());
        var orphaned = view.orphanedSharedRooms();
        log.info("RoomLayer: peer {} timed out — {} rooms orphaned",
            cmd.nodeId(), orphaned.size());

        // Claim orphaned rooms that match our policy
        for (var entry : orphaned) {
            claimRoom(entry.roomId(), entry.ownership(), Instant.EPOCH);
        }

        return this;
    }

    private Behavior<Command> onSnapshotReceived(SnapshotReceived cmd) {
        if (!started) return this;

        // Ignore our own snapshots
        if (localNodeId.equals(cmd.fromNodeId())) return this;

        view.recordSnapshot(cmd.roomId(), cmd.fromNodeId(), cmd.timestamp());
        log.debug("RoomLayer: snapshot received for room {} from {} at {}",
            cmd.roomId(), cmd.fromNodeId(), cmd.timestamp());
        return this;
    }

    private Behavior<Command> onAnnouncementTick(AnnouncementTick tick) {
        if (!started) return this;
        announceRooms();
        return this;
    }

    private Behavior<Command> onSnapshotTick(SnapshotTick tick) {
        if (!started) return this;
        // Phase 2: snapshot tick now checks tier for each room.
        // Event-sourced and write-through rooms are handled by the RoomEventReplicator
        // in real time, so this tick only covers PERIODIC and LAZY rooms.
        // Actual snapshot fetch from RoomActor requires async ask — wired by the
        // server layer that owns the ShardRegion reference.
        var tiers = replicationManager.allTiers();
        var periodicCount = tiers.values().stream()
            .filter(t -> t == ReplicationTier.PERIODIC || t == ReplicationTier.LAZY)
            .count();
        log.debug("RoomLayer: snapshot tick — {} periodic/lazy rooms tracked", periodicCount);
        return this;
    }

    private Behavior<Command> onGetView(GetView cmd) {
        cmd.replyTo().tell(view.snapshot());
        return this;
    }

    // --- Phase 2: Tier management ---

    private Behavior<Command> onRoomActivity(RoomActivity cmd) {
        if (!started) return this;

        replicationManager.recordActivity(cmd.roomId());
        var newTier = replicationManager.checkPromotion(
            cmd.roomId(), cmd.ownership(), cmd.hasEntities(), cmd.isCompanionRoom());

        if (newTier != null && eventReplicator != null) {
            eventReplicator.updateTier(cmd.roomId(), newTier);
            log.debug("RoomLayer: room {} promoted to tier {}", cmd.roomId(), newTier);
        }

        return this;
    }

    private Behavior<Command> onGetTier(GetTier cmd) {
        cmd.replyTo().tell(replicationManager.getTier(cmd.roomId()));
        return this;
    }

    private Behavior<Command> onGetAllTiers(GetAllTiers cmd) {
        cmd.replyTo().tell(replicationManager.allTiers());
        return this;
    }

    private Behavior<Command> onDemotionTick(DemotionTick tick) {
        if (!started) return this;

        // Check all tracked rooms for demotion
        for (var entry : replicationManager.allTiers().entrySet()) {
            var roomId = entry.getKey();
            var currentTier = entry.getValue();

            // Only demote event-sourced and periodic rooms
            if (currentTier == ReplicationTier.EVENT_SOURCED
                    || currentTier == ReplicationTier.PERIODIC) {
                // Re-evaluate without entities/companion (demotion assumes room emptied)
                var viewEntry = view.snapshot().rooms().get(roomId);
                var ownership = viewEntry != null ? viewEntry.ownership() : RoomOwnership.SHARED;
                var newTier = replicationManager.checkPromotion(roomId, ownership, false, false);
                if (newTier != null && eventReplicator != null) {
                    eventReplicator.updateTier(roomId, newTier);
                    log.info("RoomLayer: room {} demoted to tier {}", roomId, newTier);
                }
            }
        }

        return this;
    }

    /**
     * Get the event replicator for this layer (for wiring RoomEventListener into RoomActor).
     * Returns null if the layer has not been started yet.
     */
    public RoomEventReplicator getEventReplicator() {
        return eventReplicator;
    }

    /**
     * Get the replication manager for this layer (for testing and diagnostics).
     */
    public RoomReplicationManager getReplicationManager() {
        return replicationManager;
    }

    // --- Helpers ---

    /** Announce our locally hosted rooms to the household. */
    private void announceRooms() {
        List<RoomAssignment> localRooms;
        try {
            localRooms = localRoomsSupplier.get();
        } catch (Exception e) {
            log.debug("RoomLayer: failed to get local rooms: {}", e.getMessage());
            localRooms = List.of();
        }

        var resources = currentResources();
        var announcement = new NodeAnnouncement(
            localNodeId,
            getHostname(),
            List.of(), // owners — populated by higher layer
            localRooms,
            resources,
            Instant.now()
        );

        // Always apply to our own local view (even without NATS)
        view.updateFromAnnouncement(announcement);

        // Publish to NATS (skip if not connected — single-node mode)
        if (nats != null && nats.isConnected()) {
            try {
                var payload = MAPPER.valueToTree(announcement);
                var wrapper = MAPPER.createObjectNode();
                wrapper.put("type", "room_announcement");
                wrapper.set("announcement", payload);
                nats.broadcast("rooms", "announcement", wrapper);
            } catch (Exception e) {
                log.debug("RoomLayer: failed to publish announcement: {}", e.getMessage());
            }
        }
    }

    /** Publish a claim for a room. */
    private void claimRoom(String roomId, RoomOwnership ownership, Instant snapshotTimestamp) {
        var claim = new RoomClaimMessage(
            roomId, localNodeId, snapshotTimestamp, ownership, Instant.now());

        // Apply to our own view
        view.claimRoom(claim);

        // Publish to NATS
        if (nats != null && nats.isConnected()) {
            try {
                var payload = MAPPER.valueToTree(claim);
                var wrapper = MAPPER.createObjectNode();
                wrapper.put("type", "room_claim");
                wrapper.set("claim", payload);
                nats.broadcast("rooms", "claim", wrapper);
            } catch (Exception e) {
                log.debug("RoomLayer: failed to publish claim: {}", e.getMessage());
            }
        }

        log.info("RoomLayer: claimed room {} (ownership={})", roomId, ownership);
    }

    private NodeResources currentResources() {
        var runtime = Runtime.getRuntime();
        var ramMb = runtime.maxMemory() / (1024 * 1024);
        var loadPct = 0.0;
        try {
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            loadPct = osBean.getSystemLoadAverage();
            if (loadPct < 0) loadPct = 0;
        } catch (Exception ignored) {}

        return new NodeResources(0, ramMb, List.of(), List.of(), loadPct, 50);
    }

    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
