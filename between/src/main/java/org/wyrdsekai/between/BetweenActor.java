package org.wyrdsekai.between;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.wyrdsekai.between.federation.FederationActor;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.layer.BetweenLockerBridge;
import org.wyrdsekai.between.layer.CourierFileLayer;
import org.wyrdsekai.between.layer.CrdtLayer;
import org.wyrdsekai.between.layer.InferenceGossip;
import org.wyrdsekai.between.layer.InferenceLayer;
import org.wyrdsekai.between.layer.LocalRoomView;
import org.wyrdsekai.between.layer.IdentityReplicator;
import org.wyrdsekai.between.layer.CompanionTransitProtocol;
import org.wyrdsekai.between.layer.HouseholdObservability;
import org.wyrdsekai.between.layer.HouseholdScheduler;
import org.wyrdsekai.between.layer.MemoryLayer;
import org.wyrdsekai.between.layer.NodeCapabilities;
import org.wyrdsekai.between.layer.PeerCapabilityGossip;
import org.wyrdsekai.between.layer.PlacementEngine;
import org.wyrdsekai.between.layer.PresenceLayer;
import org.wyrdsekai.between.layer.ResourceHeartbeat;
import org.wyrdsekai.between.layer.RoomCommandBridge;
import org.wyrdsekai.between.layer.ResourceRegistry;
import org.wyrdsekai.between.layer.RoomEventReplicator;
import org.wyrdsekai.between.layer.RoomLayer;
import org.wyrdsekai.between.layer.MutationRouter;
import org.wyrdsekai.between.layer.RoomMutationExecutor;
import org.wyrdsekai.between.layer.RoomPrimaryProtocol;
import org.wyrdsekai.between.layer.ServiceCheckpointer;
import org.wyrdsekai.between.layer.SoulLayer;
import org.wyrdsekai.between.layer.StigmergicTrace;
import org.wyrdsekai.between.layer.UnifiedSessionService;
import org.wyrdsekai.between.skill.BetweenSkillBridge;
import org.wyrdsekai.core.config.MdnsDiscovery;
import org.wyrdsekai.core.config.RelayLegConfig;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomEventListener;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.skill.SkillBootstrap;
import org.wyrdsekai.core.soul.LockerSyncHub;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.common.topology.NodeAnnouncement;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomClaimMessage;
import org.wyrdsekai.core.identity.PlayerPresence;
import org.wyrdsekai.core.soul.SoulStore;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Top-level actor for The Between — manages NATS connection, node discovery,
 * handshake protocol, heartbeat, and topology register.
 *
 * Lifecycle: StartBetween → discover peers → handshake → heartbeat loop
 */
public class BetweenActor extends AbstractBehavior<BetweenActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(BetweenActor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    // --- Protocol ---

    public sealed interface Command {}

    /** Initialize The Between. */
    public record StartBetween(
        String zoneId,
        String zoneName,
        Path dataDir,
        BetweenConfig config,
        String jdbcUrl,
        SoulStore soulStore,   // nullable — soul replication disabled if null
        NatsBridge preConnectedBridge  // nullable — if set, use this instead of connecting
    ) implements Command {
        /** Backward-compatible constructor without SoulStore or pre-connected bridge. */
        public StartBetween(String zoneId, String zoneName, Path dataDir,
                            BetweenConfig config, String jdbcUrl) {
            this(zoneId, zoneName, dataDir, config, jdbcUrl, null, null);
        }
        /** Backward-compatible constructor without pre-connected bridge. */
        public StartBetween(String zoneId, String zoneName, Path dataDir,
                            BetweenConfig config, String jdbcUrl, SoulStore soulStore) {
            this(zoneId, zoneName, dataDir, config, jdbcUrl, soulStore, null);
        }
    }

    /** A peer node was discovered via mDNS or configured seeds. */
    private record PeerDiscovered(
        String nodeId,
        String natsUrl,
        String host,
        int arteryPort
    ) implements Command {}

    /** A Between envelope was received from NATS. */
    private record EnvelopeReceived(BetweenEnvelope envelope) implements Command {}

    /** Periodic heartbeat tick. */
    private record HeartbeatTick() implements Command {}

    /** Periodic probe tick (latency measurement). */
    private record ProbeTick() implements Command {}

    /** Query topology state. */
    public record GetTopology(ActorRef<TopologySnapshot> replyTo) implements Command {}

    /** Topology snapshot response. */
    public record TopologySnapshot(
        String localNodeId,
        int connectedNodes,
        Collection<TopologyRegister.ConnectionState> connections,
        String description
    ) {}

    // --- Federation proxy commands (forwarded to child FederationActor) ---

    /** Get federation status. */
    public record GetFederationStatus(
        ActorRef<FederationActor.StatusResult> replyTo) implements Command {}

    /** F12: get mesh-state matrix (fan-out probe of every partner). */
    public record GetFederationMeshStatus(
        ActorRef<FederationActor.MeshStatusResult> replyTo) implements Command {}

    /** Propose federation to a remote zone. */
    public record ProposeFederation(
        String targetZoneId, ActorRef<String> replyTo) implements Command {}

    /** Accept a pending federation proposal. */
    public record AcceptFederation(
        String remoteZoneId, ActorRef<String> replyTo) implements Command {}

    /** Notify that a federation with a remote zone is now active.
     *  Triggers relay bridge subscription to the remote zone's namespace. */
    public record FederationActivated(String remoteZoneId) implements Command {}

    /** Revoke a federation agreement. */
    public record RevokeFederation(
        String remoteZoneId, ActorRef<String> replyTo) implements Command {}

    /** Request transit to a remote zone. */
    public record RequestTransit(
        String targetZoneId, String agentId, String agentName,
        ActorRef<FederationActor.TransitResult> replyTo) implements Command {}

    /** List visiting agents. */
    public record ListVisitors(ActorRef<String> replyTo) implements Command {}

    /** Trigger soul replication to peer nodes after forge (proxy to SoulLayer). */
    public record ReplicateSoul(String agentDid) implements Command {}

    /** Start the room topology layer with a supplier for local room assignments. */
    public record StartRoomLayer(
        Supplier<List<RoomAssignment>> localRoomsSupplier
    ) implements Command {}

    /** Query the current room topology view (proxy to RoomLayer). */
    public record GetRoomView(
        ActorRef<LocalRoomView.Snapshot> replyTo
    ) implements Command {}

    /** Publish player presence update via Between. */
    public record PublishPresence(PlayerPresence presence) implements Command {}

    /** Publish player offline notice via Between. */
    public record PublishOffline(String did) implements Command {}

    /** Query the current PresenceLayer (returns the layer reference for direct use). */
    public record GetPresenceLayer(ActorRef<PresenceLayer> replyTo) implements Command {}

    /** Broadcast updated relay configuration to all peers via Between headlines. */
    public record BroadcastRelayConfig(String relayUrl, String relayToken) implements Command {}

    /** Query the broadcast-all RoomEventListener for wiring into RoomActor.
     *  Returns null if Between has not been started. */
    public record GetRoomEventReplicator(ActorRef<RoomEventListener> replyTo) implements Command {}

    /**
     * expose the FederationActor ref to Main.java so
     * it can register the inbound CompanionRelocate sink + publish via the
     * outbound PublishCompanionRelocate command. Returns {@code null} if
     * Between hasn't started or federation isn't enabled.
     */
    public record GetFederationActor(
        ActorRef<ActorRef<FederationActor.Command>> replyTo
    ) implements Command {}

    /** Get the RoomCommandBridge for cross-node room command proxying. */
    public record GetRoomCommandBridge(ActorRef<RoomCommandBridge> replyTo) implements Command {}

    /** Get the PlacementEngine for companion/room placement decisions (Wave 2). */
    public record GetPlacementEngine(ActorRef<PlacementEngine> replyTo) implements Command {}

    /** Get the ResourceHeartbeat for companion heartbeat management (Wave 2). */
    public record GetResourceHeartbeat(ActorRef<ResourceHeartbeat> replyTo) implements Command {}

    /** Get the NodeCapabilities for this node (Wave 2). */
    public record GetNodeCapabilities(ActorRef<NodeCapabilities> replyTo) implements Command {}

    /** Get the CompanionTransitProtocol (Wave 3). */
    public record GetTransitProtocol(ActorRef<CompanionTransitProtocol> replyTo) implements Command {}

    /** Get the RoomPrimaryProtocol (Wave 4). */
    public record GetRoomPrimaryProtocol(ActorRef<RoomPrimaryProtocol> replyTo) implements Command {}

    /** Get the MutationRouter (Phase 4: primary-routed mutations). */
    public record GetMutationRouter(ActorRef<MutationRouter> replyTo) implements Command {}

    /** Wire account replication into the Between mesh (Wave 1+2). */
    public record StartAccountReplication(
        AuthService authService, InviteService inviteService
    ) implements Command {}

    /** Get the IdentityReplicator (for wiring into AuthRoutes). */
    public record GetIdentityReplicator(ActorRef<IdentityReplicator> replyTo) implements Command {}

    /** Get the UnifiedSessionService (Wave 5). */
    public record GetSessionService(ActorRef<UnifiedSessionService> replyTo) implements Command {}

    /** Get the HouseholdObservability (Wave 8). */
    public record GetObservability(ActorRef<HouseholdObservability> replyTo) implements Command {}

    /** Get the HouseholdScheduler (Wave 7). */
    public record GetScheduler(ActorRef<HouseholdScheduler> replyTo) implements Command {}

    // --- W5 distributed layers (spawned alongside Room/Soul layers) ---

    /** Propagate a CRDT state delta: applied locally AND broadcast to peers. */
    public record PropagateCrdtState(String roomId, String stateType,
                                     Map<String, String> delta) implements Command {}

    /** Store a shared-memory entry: applied locally AND broadcast to peers. */
    public record ShareMemory(String namespace, String key, String value,
                              int ttlSeconds) implements Command {}

    /** Get the CrdtLayer ref (nullable if Between not started). */
    public record GetCrdtLayer(
        ActorRef<ActorRef<CrdtLayer.Command>> replyTo) implements Command {}

    /** Get the MemoryLayer ref (nullable if Between not started). */
    public record GetMemoryLayer(
        ActorRef<ActorRef<MemoryLayer.Command>> replyTo) implements Command {}

    /** Get the InferenceLayer ref (nullable if Between not started). */
    public record GetInferenceLayer(
        ActorRef<ActorRef<InferenceLayer.Command>> replyTo) implements Command {}

    /** Get the BetweenSkillBridge (nullable if Between not started). */
    public record GetSkillBridge(ActorRef<BetweenSkillBridge> replyTo) implements Command {}

    /**
     * W5: install the MCP service-id supplier so this node's
     * {@code wyrd.discovery.capabilities} announcements carry the real
     * gateway-registered service set (sent from Main after the MCP registry
     * is loaded).
     */
    public record SetMcpServiceIds(Supplier<Set<String>> serviceIds) implements Command {}

    // --- Configuration ---

    public record BetweenConfig(
        boolean enabled,
        String natsUrl,
        boolean natsAutoStart,
        String natsExecutable,
        int natsClientPort,
        int natsMonitorPort,
        boolean mdnsEnabled,
        List<String> seedNodes,
        Duration heartbeatInterval,
        Duration probeInterval,
        int arteryPort,
        String relayUrl,
        String relayToken,
        // the zone's full relay leg set. Leg 0
        // mirrors relayUrl/relayToken (the primary); additional legs add
        // federation reach. Empty for a single-relay (or no-relay) zone.
        List<RelayLegConfig> relayLegs
    ) {
        /** Backward-compatible constructor without relay fields. */
        public BetweenConfig(boolean enabled, String natsUrl, boolean natsAutoStart,
                             String natsExecutable, int natsClientPort, int natsMonitorPort,
                             boolean mdnsEnabled, List<String> seedNodes,
                             Duration heartbeatInterval, Duration probeInterval, int arteryPort) {
            this(enabled, natsUrl, natsAutoStart, natsExecutable, natsClientPort, natsMonitorPort,
                 mdnsEnabled, seedNodes, heartbeatInterval, probeInterval, arteryPort,
                 null, null, List.of());
        }

        /** Backward-compatible constructor with single relay (pre-multihoming). */
        public BetweenConfig(boolean enabled, String natsUrl, boolean natsAutoStart,
                             String natsExecutable, int natsClientPort, int natsMonitorPort,
                             boolean mdnsEnabled, List<String> seedNodes,
                             Duration heartbeatInterval, Duration probeInterval, int arteryPort,
                             String relayUrl, String relayToken) {
            this(enabled, natsUrl, natsAutoStart, natsExecutable, natsClientPort, natsMonitorPort,
                 mdnsEnabled, seedNodes, heartbeatInterval, probeInterval, arteryPort,
                 relayUrl, relayToken, List.of());
        }

        public BetweenConfig {
            relayLegs = relayLegs == null ? List.of() : List.copyOf(relayLegs);
        }
    }

    // --- State ---

    private final TimerScheduler<Command> timers;
    private NodeIdentity identity;
    private NatsServerManager natsServer;
    private NatsBridge natsBridge;
    private RelayBridge relayBridge;
    /**
     * relay legs beyond the primary ({@link #relayBridge}).
     * Each is an independent bridge to a different relay; together they let one
     * zone be reachable on several relays at once. Empty for a single-homed zone.
     */
    private final List<RelayBridge> additionalBridges = new ArrayList<>();
    private MdnsDiscovery mdns;
    private final TopologyRegister topology = new TopologyRegister();
    private final ConcurrentHashMap<String, byte[]> peerPublicKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> probeTimestamps = new ConcurrentHashMap<>();
    private final Set<String> knownCrossZoneNodes = ConcurrentHashMap.newKeySet();
    private ActorRef<FederationActor.Command> federationActor;
    private ActorRef<SoulLayer.Command> soulLayerActor;
    private ActorRef<RoomLayer.Command> roomLayerActor;
    private PresenceLayer presenceLayer;
    private CourierFileLayer courierLayer;
    private RoomEventReplicator roomEventReplicator; // broadcast-all: publishes every room event to NATS
    private NodeCapabilities nodeCapabilities;       // Wave 2: local node capability detection
    private PlacementEngine placementEngine;         // Wave 2: companion/room placement scoring
    private ResourceHeartbeat resourceHeartbeat;     // Wave 2: heartbeat protocol for companions/rooms
    private CompanionTransitProtocol transitProtocol; // Wave 3: companion migration protocol
    private RoomPrimaryProtocol roomPrimaryProtocol;  // Wave 4: room primary ownership protocol
    private MutationRouter mutationRouter;            // Phase 4: primary-routed mutations
    private RoomCommandBridge roomCommandBridge;      // Cross-node room command proxying
    private UnifiedSessionService sessionService;     // Wave 5: unified member sessions
    private ServiceCheckpointer checkpointer;         // Wave 6: state continuity
    private HouseholdScheduler householdScheduler;    // Wave 7: household scheduler
    private HouseholdObservability observability;      // Wave 8: observability
    private IdentityReplicator accountReplicator;      // Wave 1+2: account data replication
    // W5: distributed layers + bridges (audit 2026-07-11 — never spawned before)
    private ActorRef<CrdtLayer.Command> crdtLayerActor;
    private ActorRef<InferenceLayer.Command> inferenceLayerActor;
    private ActorRef<MemoryLayer.Command> memoryLayerActor;
    private BetweenSkillBridge skillBridge;
    private BetweenLockerBridge lockerBridge;
    private InferenceGossip inferenceGossip;
    private PeerCapabilityGossip capabilityGossip;
    private Supplier<Set<String>> mcpServiceIdsSupplier;
    /** Composite listener: replicator broadcast-all + primary-side room.event/state
     *  bridge broadcasts (W5 item 6 — replicas already subscribe via ZoneGuardian). */
    private RoomEventListener primaryRoomEventListener;
    private final ConcurrentHashMap<String, Long> lastStateBroadcastMs = new ConcurrentHashMap<>();
    private String zoneId;
    private BetweenConfig config;
    private int probeSeq = 0;

    private BetweenActor(ActorContext<Command> context, TimerScheduler<Command> timers) {
        super(context);
        this.timers = timers;
    }

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers ->
                new BetweenActor(ctx, timers)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(StartBetween.class, this::onStartBetween)
            .onMessage(PeerDiscovered.class, this::onPeerDiscovered)
            .onMessage(EnvelopeReceived.class, this::onEnvelopeReceived)
            .onMessage(HeartbeatTick.class, this::onHeartbeatTick)
            .onMessage(ProbeTick.class, this::onProbeTick)
            .onMessage(GetTopology.class, this::onGetTopology)
            .onMessage(GetFederationStatus.class, this::onGetFederationStatus)
            .onMessage(GetFederationMeshStatus.class, this::onGetFederationMeshStatus)
            .onMessage(ProposeFederation.class, this::onProposeFederation)
            .onMessage(AcceptFederation.class, this::onAcceptFederation)
            .onMessage(FederationActivated.class, this::onFederationActivated)
            .onMessage(RevokeFederation.class, this::onRevokeFederation)
            .onMessage(RequestTransit.class, this::onRequestTransit)
            .onMessage(ListVisitors.class, this::onListVisitors)
            .onMessage(ReplicateSoul.class, this::onReplicateSoul)
            .onMessage(StartRoomLayer.class, this::onStartRoomLayer)
            .onMessage(GetRoomView.class, this::onGetRoomView)
            .onMessage(PublishPresence.class, this::onPublishPresence)
            .onMessage(PublishOffline.class, this::onPublishOffline)
            .onMessage(GetPresenceLayer.class, this::onGetPresenceLayer)
            .onMessage(GetRoomEventReplicator.class, this::onGetRoomEventReplicator)
            .onMessage(GetFederationActor.class, msg -> {
                msg.replyTo().tell(federationActor);
                return this;
            })
            .onMessage(GetPlacementEngine.class, this::onGetPlacementEngine)
            .onMessage(GetResourceHeartbeat.class, this::onGetResourceHeartbeat)
            .onMessage(GetNodeCapabilities.class, this::onGetNodeCapabilities)
            .onMessage(GetTransitProtocol.class, this::onGetTransitProtocol)
            .onMessage(GetRoomPrimaryProtocol.class, this::onGetRoomPrimaryProtocol)
            .onMessage(GetMutationRouter.class, this::onGetMutationRouter)
            .onMessage(GetRoomCommandBridge.class, msg -> {
                msg.replyTo().tell(roomCommandBridge);
                return this;
            })
            .onMessage(StartAccountReplication.class, this::onStartAccountReplication)
            .onMessage(GetIdentityReplicator.class, this::onGetIdentityReplicator)
            .onMessage(GetSessionService.class, this::onGetSessionService)
            .onMessage(GetObservability.class, this::onGetObservability)
            .onMessage(GetScheduler.class, this::onGetScheduler)
            .onMessage(BroadcastRelayConfig.class, this::onBroadcastRelayConfig)
            .onMessage(PropagateCrdtState.class, this::onPropagateCrdtState)
            .onMessage(ShareMemory.class, this::onShareMemory)
            .onMessage(GetCrdtLayer.class, msg -> {
                msg.replyTo().tell(crdtLayerActor);
                return this;
            })
            .onMessage(GetMemoryLayer.class, msg -> {
                msg.replyTo().tell(memoryLayerActor);
                return this;
            })
            .onMessage(GetInferenceLayer.class, msg -> {
                msg.replyTo().tell(inferenceLayerActor);
                return this;
            })
            .onMessage(GetSkillBridge.class, msg -> {
                msg.replyTo().tell(skillBridge);
                return this;
            })
            .onMessage(SetMcpServiceIds.class, msg -> {
                this.mcpServiceIdsSupplier = msg.serviceIds();
                log.info("Between: MCP service-id supplier installed — discovery gossip "
                    + "will announce the gateway's real service set");
                return this;
            })
            .onSignal(PostStop.class, this::onPostStop)
            .build();
    }

    // --- Handlers ---

    private Behavior<Command> onStartBetween(StartBetween msg) {
        this.zoneId = msg.zoneId();
        this.config = msg.config();

        try {
            // 1. Load or generate node identity
            var identityFile = msg.dataDir().resolve("node-identity.json");
            identity = NodeIdentity.loadOrGenerate(identityFile);
            log.info("Between: node identity {} in zone {}", identity.nodeId(), msg.zoneId());

            // 2. Use pre-connected NATS bridge or create a new one
            String natsUrl = msg.config().natsUrl(); // default from config
            if (msg.preConnectedBridge() != null) {
                // Pre-connected bridge from main() — avoids macOS dual-homed routing issues
                // where Pekko ClusterSharding startup corrupts the JVM's outbound socket routing.
                natsBridge = msg.preConnectedBridge();
                natsUrl = natsBridge.natsUrl();
                log.info("Between: using pre-connected NATS bridge ({})", natsUrl);
            } else {
                // Start or connect to NATS
                if (msg.config().natsAutoStart()) {
                    if (!NatsServerManager.isAvailable(msg.config().natsExecutable())) {
                        log.warn("nats-server not found on PATH — Between disabled (single-node mode)");
                        return this;
                    }
                    natsServer = new NatsServerManager(
                        msg.config().natsExecutable(),
                        msg.config().natsClientPort(),
                        msg.config().natsMonitorPort(),
                        msg.dataDir(),
                        true  // bind all interfaces for cluster mode
                    );
                    natsUrl = natsServer.start();
                } else {
                    natsUrl = msg.config().natsUrl();
                }

                // Connect with resilient retry (handles transient network issues)
                natsBridge = new NatsBridge(natsUrl, identity.nodeId(), msg.zoneId(), identity);
                int maxRetries = 5;
                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try {
                        natsBridge.connect();
                        break;
                    } catch (IOException e) {
                        if (attempt == maxRetries) throw e;
                        var delay = Math.min(attempt * 2, 10);
                        log.warn("NATS connection attempt {}/{} failed ({}), retrying in {}s...",
                            attempt, maxRetries, e.getMessage(), delay);
                        Thread.sleep(delay * 1000L);
                    }
                }
            }

            // 3b. Create broadcast-all RoomEventReplicator so room events
            //     from RoomActor flow to NATS for external subscribers
            //     (e.g. Claude room-resident bridge).
            roomEventReplicator = new RoomEventReplicator(natsBridge, msg.zoneId(), true);

            // 4. Subscribe to cluster messages
            subscribeToClusterMessages();


            // 4b. Start relay bridge if configured
            var relayUrl = msg.config().relayUrl();
            var relayToken = msg.config().relayToken();
            if (relayUrl != null && !relayUrl.isEmpty()) {
                try {
                    // dual-mode: opt-in to NKey auth via
                    // WYRDSEKAI_RELAY_USE_NKEY=true. When enabled, RelayBridge uses
                    // NodeIdentity's NKey AuthHandler; otherwise falls back to
                    // user/password (legacy). After Phase 4 retires password mode,
                    // this flag's default flips to true and the env-var goes away.
                    boolean useNkey = "true".equalsIgnoreCase(
                        System.getenv().getOrDefault("WYRDSEKAI_RELAY_USE_NKEY", "false"));
                    var authUser = System.getenv().getOrDefault("WYRDSEKAI_RELAY_USER",
                        "hh-" + msg.zoneId());
                    // dual-set diagnostic: when both NKey
                    // is enabled AND a password token is present (common during
                    // mid-migration when operator forgot to clean up the env),
                    // log explicitly that NKey takes precedence. Helps debug the
                    // "I set USE_NKEY=true but it's still using password" support
                    // ticket — which is actually impossible because we always
                    // prefer NKey when wired, but operators don't know that.
                    if (useNkey && relayToken != null && !relayToken.isEmpty()) {
                        log.info("Between: NKey + password both configured — NKey takes "
                            + "precedence. Remove WYRDSEKAI_RELAY_TOKEN once NKey verified.");
                    }
                    // multi-leg zones share one
                    // inbound-dedup set so a peer reachable over >1 shared relay
                    // is not delivered twice. Null for a single-leg zone.
                    var legs = msg.config().relayLegs();
                    var dedup = legs.size() > 1 ? new RelaySeenSet(8192) : null;

                    // Primary leg's visibility decides whether it may carry
                    // federation egress (privacy rail R1). Default PRIVATE when
                    // the leg isn't in the configured set (legacy single-relay).
                    boolean primaryPublic = legs.stream()
                        .filter(l -> l.url().equals(relayUrl))
                        .findFirst().map(RelayLegConfig::isPublic).orElse(false);

                    relayBridge = new RelayBridge(
                        relayUrl, natsUrl, msg.zoneId(),
                        identity.nodeId(), authUser, relayToken,
                        useNkey ? identity : null, dedup, !primaryPublic);
                    relayBridge.start();
                    log.info("Between: relay bridge active — remote access via {} (auth={})",
                        relayUrl, useNkey ? "nkey" : "password");

                    // bring up the additional legs
                    // (every configured leg whose URL is not the primary). Each
                    // is independent; a failure on one doesn't block the others.
                    for (var leg : legs) {
                        if (leg.url().equals(relayUrl)) continue; // already the primary
                        try {
                            var legUser = leg.user() != null ? leg.user() : authUser;
                            var legBridge = new RelayBridge(
                                leg.url(), natsUrl, msg.zoneId(),
                                identity.nodeId(), legUser, leg.token(),
                                useNkey ? identity : null, dedup, !leg.isPublic());
                            legBridge.start();
                            additionalBridges.add(legBridge);
                            log.info("Between: additional relay leg active — {} (visibility={})",
                                leg.url(), leg.visibility());
                        } catch (Exception le) {
                            log.warn("Between: additional relay leg {} failed to start — "
                                + "continuing with other legs", leg.url(), le);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Between: relay bridge failed to start — remote access unavailable", e);
                    relayBridge = null;
                }
            }

            // 5. Start mDNS discovery — only on the NATS host node.
            // Nodes connecting to an external NATS don't need mDNS (they already know the NATS URL).
            // JmDNS multicast can disrupt TCP connections on macOS dual-homed machines.
            if (msg.config().mdnsEnabled() && msg.config().natsAutoStart()) {
                startMdnsDiscovery(natsUrl);
            } else if (msg.config().mdnsEnabled()) {
                log.info("Between: mDNS skipped — connecting to external NATS (discovery not needed)");
            }

            // 6. Connect to configured seed nodes
            for (var seed : msg.config().seedNodes()) {
                log.info("Between: configured seed node: {}", seed);
                // Seed nodes are NATS URLs — we're already connected if same server
            }

            // 7. Broadcast hello

            broadcastHello();

            // 8. Start heartbeat timer
            timers.startTimerWithFixedDelay("heartbeat",
                new HeartbeatTick(), msg.config().heartbeatInterval());

            // 9. Start probe timer
            timers.startTimerWithFixedDelay("probe",
                new ProbeTick(), msg.config().probeInterval());

            // 10. Spawn FederationActor as child
            var fedService = new FederationService(msg.jdbcUrl());
            federationActor = getContext().spawn(
                FederationActor.create(), "federation");
            federationActor.tell(new FederationActor.Initialize(
                natsBridge, identity, msg.zoneId(), msg.zoneName(), fedService));
            log.info("Between: FederationActor spawned");

            // 11. Spawn SoulLayer as child (with SoulStore if available)
            soulLayerActor = getContext().spawn(
                SoulLayer.create(identity.nodeId(), natsBridge, msg.soulStore()),
                "soul-layer");
            subscribeToSoulMessages();
            log.info("Between: SoulLayer spawned{}",
                msg.soulStore() != null ? " (with SoulStore)" : " (no SoulStore)");

            // 12. Start PresenceLayer for player presence gossip
            presenceLayer = new PresenceLayer(natsBridge, identity.nodeId());
            presenceLayer.start();
            log.info("Between: PresenceLayer started");

            // 12b. courier satchel household
            //      transport. Wires NetworkWiring's transport seam (which was
            //      the plan-of-record's "household transport is null" gap), so
            //      world.net.household_copy reaches enrolled peers over the
            //      roster-verified bus. Failure is non-fatal: the satchel then
            //      keeps reporting a clean "not wired".
            try {
                courierLayer = CourierFileLayer.start(
                    natsBridge, identity.nodeId(), new HouseholdStore(msg.jdbcUrl()));
                log.info("Between: CourierFileLayer started — household file transfer live");
            } catch (Exception e) {
                log.warn("Between: courier layer failed to start — "
                    + "courier satchel stays unwired", e);
            }

            // 13. Wave 2: Start NodeCapabilities, PlacementEngine, ResourceHeartbeat
            nodeCapabilities = new NodeCapabilities(identity.nodeId());
            placementEngine = new PlacementEngine();
            resourceHeartbeat = new ResourceHeartbeat(natsBridge, identity.nodeId(), placementEngine);

            // Publish our own capabilities immediately so the placement engine knows about us
            placementEngine.updateNodeSnapshot(nodeCapabilities.snapshot());

            // Subscribe to capability gossip from other nodes
            ResourceRegistry.get().setLocalNodeId(identity.nodeId());
            ResourceRegistry.get().updateSnapshot(nodeCapabilities.snapshot());

            natsBridge.subscribeBroadcast("capability", "announce", env -> {
                try {
                    var snap = MAPPER.convertValue(env.payload(), NodeCapabilities.Snapshot.class);
                    placementEngine.updateNodeSnapshot(snap);
                    ResourceRegistry.get().updateSnapshot(snap);
                    log.debug("Between: capability update from node {}", snap.nodeId());
                } catch (Exception e) {
                    log.warn("Between: failed to parse capability announcement: {}", e.getMessage());
                }
            });

            // Periodically publish our capabilities (every heartbeat)
            // Done inside HeartbeatTick handler below
            // Wave 3: Companion transit protocol
            transitProtocol = new CompanionTransitProtocol(natsBridge, identity.nodeId());

            // Wave 4: Room primary protocol
            roomPrimaryProtocol = new RoomPrimaryProtocol(
                natsBridge, identity.nodeId(), placementEngine, nodeCapabilities);

            // Mutation router: forwards mutations to primary, validates fencing tokens
            mutationRouter = new MutationRouter(natsBridge, identity.nodeId(), roomPrimaryProtocol);
            mutationRouter.startListening();
            wireMutationHandler(mutationRouter);

            // Room command bridge: forwards room commands to primary via NATS request/reply
            roomCommandBridge = new RoomCommandBridge(natsBridge, identity.nodeId());

            // Wave 5: Unified session management

            sessionService = new UnifiedSessionService(natsBridge, identity.nodeId());
            sessionService.startReplication();

            // Wave 6: Service checkpointing for state continuity
            checkpointer = new ServiceCheckpointer(natsBridge, identity.nodeId());
            checkpointer.startReplication();

            // Wave 7: Household scheduler
            householdScheduler = new HouseholdScheduler(natsBridge, identity.nodeId(), placementEngine);
            householdScheduler.startReplication();

            // Wave 8: Observability — service registry, crash dumps, watchdog, cost

            observability = new HouseholdObservability(natsBridge, identity.nodeId());
            observability.startReplication();
            observability.startWatchdog();

            log.info("Between: all 8 waves initialized (placement, heartbeat, transit, sessions, " +
                "checkpoints, scheduler, observability)");

            // W5 (audit 2026-07-11): spawn the remaining distributed layers next
            // to Room/Soul and wire their NATS subjects — these actors existed
            // but were never spawned, so their whole wire protocol was dead.
            crdtLayerActor = getContext().spawn(
                CrdtLayer.create(identity.nodeId()), "crdt-layer");
            memoryLayerActor = getContext().spawn(
                MemoryLayer.create(identity.nodeId()), "memory-layer");
            inferenceLayerActor = getContext().spawn(
                InferenceLayer.create(identity.nodeId()), "inference-layer");
            subscribeToCrdtMessages();
            subscribeToMemoryMessages();
            subscribeToInferenceLayerMessages();
            log.info("Between: Crdt/Memory/Inference layers spawned and wired");

            // W5: shared raw-JSON transport over NATS for the gossip + locker
            // channels (subjects wyrd.inference.capabilities /
            // wyrd.discovery.capabilities / wyrd.locker.<family>.*).
            BetweenLockerBridge.MessageTransport rawTransport =
                new BetweenLockerBridge.MessageTransport() {
                    @Override
                    public void publish(String subject, String json) {
                        natsBridge.publishRaw(subject,
                            json.getBytes(StandardCharsets.UTF_8));
                    }
                    @Override
                    public void subscribe(String subject,
                                          BetweenLockerBridge.MessageHandler handler) {
                        natsBridge.subscribeRaw(subject, bytes ->
                            handler.onMessage(new String(bytes, StandardCharsets.UTF_8)));
                    }
                };

            // W5 item 4: inference + MCP-service capability gossip, server side.
            // Daemons already publish/subscribe these subjects (DaemonGossipClient)
            // — until now the server never listened NOR announced.
            inferenceGossip = new InferenceGossip(rawTransport, identity.nodeId());
            inferenceGossip.subscribeCapabilities(cap -> {
                if (!cap.nodeId().equals(identity.nodeId())) {
                    log.debug("Between: inference gossip from {} ({} models, queue={})",
                        cap.nodeId(), cap.models().size(), cap.queueDepth());
                }
            });
            capabilityGossip = new PeerCapabilityGossip(rawTransport, identity.nodeId());
            capabilityGossip.subscribeCapabilities();
            log.info("Between: inference + discovery capability gossip active "
                + "(wyrd.inference.capabilities / wyrd.discovery.capabilities)");

            // W5 item 3: skill bridge — routes skill invocations across the mesh
            // (phone agents → home zone and back). Local execution is backed by
            // the always-on SkillBootstrap set.
            skillBridge = new BetweenSkillBridge(
                identity.nodeId(), msg.zoneId(), SkillBootstrap.create(Map.of()));
            skillBridge.setTransport(new BetweenSkillBridge.BetweenTransport() {
                @Override
                public void publish(String subject, byte[] payload) {
                    natsBridge.publishRaw(subject, payload);
                }
                @Override
                public void subscribe(String subject,
                                      BiConsumer<String, byte[]> handler) {
                    natsBridge.subscribeRaw(subject, bytes -> handler.accept(subject, bytes));
                }
            });
            log.info("Between: skill bridge active (skill.<node>.invoke + skill.broadcast.invoke)");

            // W5 item 5: Family Locker replication — connect the core-side
            // LockerSyncHub (fed by FamilyLocker.store/tombstone/authorize) to
            // the wyrd.locker.<family>.* subjects.
            lockerBridge = new BetweenLockerBridge(rawTransport);
            final var lockerBridgeRef = lockerBridge;
            LockerSyncHub.get().setTransport(
                (familyId, item) -> lockerBridgeRef.publishItem(
                    new BetweenLockerBridge.ItemMessage(
                        item.hash(), item.category(), item.label(), item.text(),
                        item.creatorDid(), item.significance(), item.timestamp()),
                    familyId),
                (familyId, ts) -> lockerBridgeRef.publishTombstone(
                    new BetweenLockerBridge.TombstoneMessage(
                        ts.itemHash(), ts.deletedBy(), ts.reason(), ts.timestamp()),
                    familyId),
                familyId -> {
                    lockerBridgeRef.subscribeItems(familyId, item ->
                        LockerSyncHub.get().applyRemoteItem(familyId,
                            new LockerSyncHub.RemoteItem(
                                item.hash(), item.category(), item.label(), item.text(),
                                item.creatorDid(), item.significance(), item.timestamp())));
                    lockerBridgeRef.subscribeTombstones(familyId, ts ->
                        LockerSyncHub.get().applyRemoteTombstone(familyId,
                            new LockerSyncHub.RemoteTombstone(
                                ts.itemHash(), ts.deletedBy(), ts.reason(), ts.timestamp())));
                });
            log.info("Between: Family Locker bridge active (wyrd.locker.<family>.items/.tombstones)");

            // W5 item 6: primary-side room event/state broadcast. This node only
            // hosts REAL RoomActors for rooms it is primary for (replicas get
            // RoomProxy, which never fires the event listener), so wrapping the
            // broadcast-all replicator gives exactly the primary-side half of the
            // room.event / room.state pair that replica ZoneGuardians already
            // subscribe to.
            primaryRoomEventListener = buildPrimaryRoomEventListener();

            log.info("Between: started successfully — listening for peers");

        } catch (Exception e) {
            log.error("Between: failed to start — falling back to single-node", e);
        }

        return this;
    }

    private Behavior<Command> onPeerDiscovered(PeerDiscovered msg) {
        if (identity == null) return this;
        if (msg.nodeId().equals(identity.nodeId())) return this; // ignore self

        log.info("Between: peer discovered: {} at {}", msg.nodeId(), msg.host());

        // Send directed hello_ack
        var payload = helloPayload();
        payload.put("type", "hello_ack");
        payload.put("accepted", true);
        natsBridge.send(msg.nodeId(), "cluster", "hello_ack", payload);

        return this;
    }

    private Behavior<Command> onEnvelopeReceived(EnvelopeReceived msg) {
        var envelope = msg.envelope();
        var payload = envelope.payload();
        var type = payload.has("type") ? payload.get("type").asText() : "unknown";

        switch (type) {
            case "hello" -> handleHello(envelope);
            case "hello_ack" -> handleHelloAck(envelope);
            case "heartbeat" -> handleHeartbeat(envelope);
            case "leaving" -> handleLeaving(envelope);
            case "ping" -> handlePing(envelope);
            case "pong" -> handlePong(envelope);
            case "relay_config" -> handleRelayConfig(envelope);
            default -> log.debug("Between: unknown message type: {}", type);
        }

        return this;
    }

    private Behavior<Command> onHeartbeatTick(HeartbeatTick msg) {
        if (natsBridge == null || !natsBridge.isConnected()) return this;

        var payload = MAPPER.createObjectNode();
        payload.put("type", "heartbeat");
        payload.put("nodeId", identity.nodeId());
        payload.put("uptimeMs", ManagementFactory_uptimeMs());
        payload.put("roomCount", 0); // TODO: get from ZoneGuardian
        payload.put("activeAgents", 0);
        // Mesh update protocol: advertise version in every heartbeat
        var appVer = AppVersion.get();
        payload.put("version", appVer.version());
        payload.put("wireProtocol", appVer.wireProtocol());

        natsBridge.broadcast("cluster", "heartbeat", payload);

        // Wave 2: Publish node capability snapshot on every heartbeat
        if (nodeCapabilities != null && placementEngine != null) {
            var capSnap = nodeCapabilities.snapshot();
            placementEngine.updateNodeSnapshot(capSnap);
            ResourceRegistry.get().updateSnapshot(capSnap);
            // Update peer latency from topology into ResourceRegistry
            for (var conn : topology.allConnections()) {
                if (conn.connected()) {
                    ResourceRegistry.get().updateLatency(conn.remoteNodeId(), conn.latencyMs());
                }
            }
            natsBridge.broadcast("capability", "announce", MAPPER.valueToTree(capSnap));

            // W5 item 4: announce inference capabilities on the shared
            // wyrd.inference.capabilities subject (daemons subscribe to this;
            // until 2026-07-11 only daemons ever published).
            if (inferenceGossip != null) {
                var models = new ArrayList<InferenceGossip.AvailableModel>();
                int slots = 0;
                if (capSnap.inferenceEndpoints() != null) {
                    for (var ep : capSnap.inferenceEndpoints()) {
                        models.add(new InferenceGossip.AvailableModel(
                            ep.modelName(), classifyModelTier(ep.modelName()),
                            ep.url(), ep.maxConcurrency(), 0));
                        slots += ep.maxConcurrency();
                    }
                }
                if (!models.isEmpty()) {
                    inferenceGossip.announceCapabilities(new InferenceGossip.InferenceCapability(
                        identity.nodeId(), models,
                        capSnap.gpuName() != null && !capSnap.gpuName().isBlank() ? 1 : 0,
                        Math.max(0, capSnap.gpuVramMb()),
                        slots, 0, 0.0,
                        Instant.now().getEpochSecond()));
                }

                // Feed the InferenceLayer routing table (self + broadcast to peers).
                if (inferenceLayerActor != null) {
                    var modelIds = models.stream()
                        .map(InferenceGossip.AvailableModel::modelId).toList();
                    var cpuLoad = Math.max(0.0, Math.min(1.0,
                        1.0 - capSnap.cpuIdlePct() / 100.0));
                    inferenceLayerActor.tell(new InferenceLayer.AdvertiseCapacity(
                        identity.nodeId(), modelIds, capSnap.ramFreeMb(),
                        cpuLoad, 0, slots));
                    var adv = MAPPER.createObjectNode();
                    adv.put("type", "advertise");
                    adv.put("nodeId", identity.nodeId());
                    adv.set("models", MAPPER.valueToTree(modelIds));
                    adv.put("freeMemoryMb", capSnap.ramFreeMb());
                    adv.put("cpuLoad", cpuLoad);
                    adv.put("activeRequests", 0);
                    adv.put("maxConcurrent", slots);
                    natsBridge.broadcast("inference", "advertise", adv);
                }
            }

            // W5 item 4: announce this node's MCP gateway services on
            // wyrd.discovery.capabilities once Main installs the supplier.
            if (capabilityGossip != null && mcpServiceIdsSupplier != null) {
                try {
                    capabilityGossip.announceCapabilities(mcpServiceIdsSupplier.get());
                } catch (Exception e) {
                    log.debug("Between: MCP capability announce failed: {}", e.getMessage());
                }
            }
        }

        // Evict stale player presences
        if (presenceLayer != null) {
            presenceLayer.evictStale();
        }

        // Check for timed-out peers
        var timeout = config.heartbeatInterval().multipliedBy(3);
        for (var conn : topology.allConnections()) {
            if (conn.connected() && conn.lastHeartbeat() != null) {
                var age = Duration.between(conn.lastHeartbeat(), Instant.now());
                if (age.compareTo(timeout) > 0) {
                    log.warn("Between: peer {} heartbeat timeout ({} > {})",
                        conn.remoteNodeId(), age, timeout);
                    topology.peerDisconnected(conn.remoteNodeId());
                    ResourceRegistry.get().removePeer(conn.remoteNodeId());
                    // Notify RoomLayer of peer timeout so it can release rooms
                    if (roomLayerActor != null) {
                        roomLayerActor.tell(new RoomLayer.PeerTimedOut(conn.remoteNodeId()));
                    }
                }
            }
        }

        return this;
    }

    private Behavior<Command> onProbeTick(ProbeTick msg) {
        if (natsBridge == null || !natsBridge.isConnected()) return this;

        probeSeq++;
        for (var conn : topology.allConnections()) {
            if (!conn.connected()) continue;

            var payload = MAPPER.createObjectNode();
            payload.put("type", "ping");
            payload.put("seq", probeSeq);
            payload.put("sentAt", Instant.now().toString());

            probeTimestamps.put(conn.remoteNodeId() + ":" + probeSeq, System.nanoTime());
            natsBridge.send(conn.remoteNodeId(), "probe", "ping", payload);
        }

        return this;
    }

    private Behavior<Command> onGetTopology(GetTopology msg) {
        var snapshot = new TopologySnapshot(
            identity != null ? identity.nodeId() : "unknown",
            topology.connectedNodeCount(),
            topology.allConnections(),
            topology.describe()
        );
        msg.replyTo().tell(snapshot);
        return this;
    }

    // --- Federation proxy handlers (forward to child FederationActor) ---

    private Behavior<Command> onGetFederationStatus(GetFederationStatus msg) {
        if (federationActor != null) {
            federationActor.tell(new FederationActor.GetStatus(msg.replyTo()));
        } else {
            msg.replyTo().tell(new FederationActor.StatusResult(
                "Federation not available (Between disabled)", 0));
        }
        return this;
    }

    /** F12: forward mesh-status request to FederationActor. */
    private Behavior<Command> onGetFederationMeshStatus(GetFederationMeshStatus msg) {
        if (federationActor != null) {
            federationActor.tell(new FederationActor.MeshStatus(msg.replyTo()));
        } else {
            msg.replyTo().tell(new FederationActor.MeshStatusResult(
                "<unknown>", List.of(), Instant.now()));
        }
        return this;
    }

    private Behavior<Command> onProposeFederation(ProposeFederation msg) {
        if (federationActor != null) {
            federationActor.tell(new FederationActor.Propose(msg.targetZoneId(), msg.replyTo()));
        } else {
            msg.replyTo().tell("Federation not available (Between disabled)");
        }
        return this;
    }

    private Behavior<Command> onFederationActivated(FederationActivated msg) {
        if (relayBridge != null && relayBridge.isConnected()) {
            relayBridge.subscribeRemoteZone(msg.remoteZoneId());
            log.info("Between: relay bridge subscribed to remote zone '{}' after federation activated",
                msg.remoteZoneId());
        }
        // a federated peer may be reachable over any
        // of this zone's legs — subscribe to it on every connected leg.
        for (var leg : additionalBridges) {
            if (leg.isConnected()) leg.subscribeRemoteZone(msg.remoteZoneId());
        }

        // Subscribe to remote zone's capability announcements on local NATS.
        // These arrive via RelayBridge → local NATS. We register them in
        // ResourceRegistry so InferenceRouter can discover cross-zone backends.
        // We intentionally skip PlacementEngine — remote nodes aren't placement targets.
        //
        // Cross-zone inference URLs must route through the relay (dual-homed gateway)
        // since the remote zone's nodes may be on an unreachable network.
        // We extract the relay host from WYRDSEKAI_RELAY_URL and rewrite inference URLs.
        var relayGatewayHost = extractRelayHost();

        var remoteCapPattern = "between." + msg.remoteZoneId() + ".*.*.capability.announce";
        try {
            natsBridge.subscribe(remoteCapPattern, env -> {
                try {
                    var original = MAPPER.convertValue(env.payload(),
                        NodeCapabilities.Snapshot.class);

                    // Rewrite inference endpoint URLs to use NATS req/reply across zones.
                    // This avoids needing an HTTP proxy on the relay (the original relayGatewayHost
                    // rewrite required opening port 8200 on the relay, which wasn't configured).
                    // Form: nats://{targetZone} — resolved by InferenceRouter to a NatsRemote backend.
                    var resolved = original;
                    if (original.inferenceEndpoints() != null && !original.inferenceEndpoints().isEmpty()) {
                        var natsUrl = "nats://" + msg.remoteZoneId();
                        var rewritten = original.inferenceEndpoints().stream()
                            .map(ep -> new NodeCapabilities.InferenceEndpoint(
                                ep.backendType(), ep.modelName(), natsUrl,
                                ep.maxConcurrency(), ep.ctxSize(),
                                ep.supportsTools(), ep.supportsStreaming()))
                            .toList();
                        resolved = new NodeCapabilities.Snapshot(
                            original.nodeId(), original.capabilities(), original.cpuCount(),
                            original.ramTotalMb(), original.ramFreeMb(), original.gpuName(), original.gpuVramMb(),
                            original.diskFreeMb(), original.cpuIdlePct(), original.inferenceBackend(),
                            original.inferenceModelLoaded(), rewritten, original.companionHosting(),
                            original.roomPrimaries(), original.batteryPct(), original.nodeState(),
                            relayGatewayHost, original.httpPort(),
                            original.hasSearchEngine(), original.hasOracleEngine(), original.timestamp());
                    }

                    ResourceRegistry.get().updateSnapshot(resolved);
                    // Only log at INFO on first discovery per node
                    if (knownCrossZoneNodes.add(resolved.nodeId())) {
                        log.info("Between: cross-zone node discovered: {} ({} inference endpoints, gateway={})",
                            resolved.nodeId(),
                            resolved.inferenceEndpoints() != null ? resolved.inferenceEndpoints().size() : 0,
                            relayGatewayHost);
                    }
                } catch (Exception e) {
                    log.debug("Between: failed to parse cross-zone capability: {}", e.getMessage());
                }
            });
            log.info("Between: subscribed to cross-zone capabilities for zone '{}' (gateway={})",
                msg.remoteZoneId(), relayGatewayHost);
        } catch (Exception e) {
            log.warn("Between: failed to subscribe to cross-zone capabilities: {}", e.getMessage());
        }

        return this;
    }

    private Behavior<Command> onAcceptFederation(AcceptFederation msg) {
        if (federationActor != null) {
            federationActor.tell(new FederationActor.Accept(msg.remoteZoneId(), msg.replyTo()));
            // Subscribe to remote zone's namespace on relay
            getContext().getSelf().tell(new FederationActivated(msg.remoteZoneId()));
        } else {
            msg.replyTo().tell("Federation not available (Between disabled)");
        }
        return this;
    }

    private Behavior<Command> onRevokeFederation(RevokeFederation msg) {
        if (federationActor != null) {
            federationActor.tell(new FederationActor.Revoke(msg.remoteZoneId(), msg.replyTo()));
        } else {
            msg.replyTo().tell("Federation not available (Between disabled)");
        }
        return this;
    }

    private Behavior<Command> onRequestTransit(RequestTransit msg) {
        if (federationActor != null) {
            federationActor.tell(new FederationActor.RequestTransit(
                msg.targetZoneId(), msg.agentId(), msg.agentName(), msg.replyTo()));
        } else {
            msg.replyTo().tell(new FederationActor.TransitResult(
                false, null, null, "Federation not available"));
        }
        return this;
    }

    private Behavior<Command> onListVisitors(ListVisitors msg) {
        if (federationActor != null) {
            federationActor.tell(new FederationActor.ListVisitors(msg.replyTo()));
        } else {
            msg.replyTo().tell("No visitors (federation not active)");
        }
        return this;
    }

    private Behavior<Command> onReplicateSoul(ReplicateSoul msg) {
        if (soulLayerActor != null) {
            soulLayerActor.tell(new SoulLayer.ReplicateAfterForge(msg.agentDid()));
        } else {
            log.debug("ReplicateSoul ignored — SoulLayer not active");
        }
        return this;
    }

    private Behavior<Command> onStartRoomLayer(StartRoomLayer msg) {
        if (natsBridge == null || identity == null) {
            log.warn("Cannot start RoomLayer — Between not initialized");
            return this;
        }

        if (roomLayerActor != null) {
            log.debug("RoomLayer already started, ignoring duplicate StartRoomLayer");
            return this;
        }

        roomLayerActor = getContext().spawn(RoomLayer.create(), "room-layer");
        roomLayerActor.tell(new RoomLayer.Start(
            natsBridge, zoneId, identity.nodeId(), msg.localRoomsSupplier()));

        // Subscribe to room-layer NATS messages
        subscribeToRoomMessages();

        log.info("Between: RoomLayer spawned");
        return this;
    }

    private Behavior<Command> onGetRoomView(GetRoomView msg) {
        if (roomLayerActor != null) {
            roomLayerActor.tell(new RoomLayer.GetView(msg.replyTo()));
        } else {
            msg.replyTo().tell(LocalRoomView.Snapshot.empty());
        }
        return this;
    }

    private Behavior<Command> onPublishPresence(PublishPresence msg) {
        if (presenceLayer != null) {
            presenceLayer.publishPresence(msg.presence());
        } else {
            log.debug("PublishPresence ignored — PresenceLayer not active");
        }
        return this;
    }

    private Behavior<Command> onPublishOffline(PublishOffline msg) {
        if (presenceLayer != null) {
            presenceLayer.publishOffline(msg.did());
        } else {
            log.debug("PublishOffline ignored — PresenceLayer not active");
        }
        return this;
    }

    private Behavior<Command> onGetPresenceLayer(GetPresenceLayer msg) {
        msg.replyTo().tell(presenceLayer);
        return this;
    }

    private Behavior<Command> onGetRoomEventReplicator(GetRoomEventReplicator msg) {
        // W5 item 6: prefer the composite listener (replicator + primary-side
        // room.event/state bridge broadcasts). Nullable if Between not started.
        msg.replyTo().tell(primaryRoomEventListener != null
            ? primaryRoomEventListener : roomEventReplicator);
        return this;
    }

    /**
     * W5 item 6: composite {@link RoomEventListener} handed to ZoneGuardian
     * (via {@link GetRoomEventReplicator}) and from there to every locally
     * spawned real RoomActor. Locally spawned real rooms exist only where this
     * node is primary (peers get RoomProxy), so every callback here is a
     * primary-side event: forward to the broadcast-all replicator as before,
     * AND publish on the room.event layer that replica proxies subscribe to
     * via {@code RoomCommandBridge.subscribeEvents}. State snapshots follow on
     * a per-room debounce so replica caches converge without per-event asks.
     */
    private RoomEventListener buildPrimaryRoomEventListener() {
        final var replicator = roomEventReplicator;
        final var cmdBridge = roomCommandBridge;
        final var system = getContext().getSystem();
        return (roomId, event) -> {
            replicator.onRoomEvent(roomId, event);
            if (cmdBridge == null) return;
            try {
                cmdBridge.broadcastEvent(roomId, event);
            } catch (Exception e) {
                log.debug("Between: room.event broadcast failed for {}: {}",
                    roomId, e.getMessage());
            }
            maybeBroadcastRoomState(roomId, cmdBridge, system);
        };
    }

    /** Debounced (2s/room) primary-side room.state snapshot broadcast. */
    private void maybeBroadcastRoomState(String roomId, RoomCommandBridge cmdBridge,
                                         ActorSystem<Void> system) {
        long now = System.currentTimeMillis();
        var last = lastStateBroadcastMs.get(roomId);
        if (last != null && now - last < 2_000) return;
        lastStateBroadcastMs.put(roomId, now); // per-room callbacks are single-threaded (RoomActor)
        var roomRef = RoomRegistry.get().ref(roomId);
        if (roomRef == null || roomRef.path().name().startsWith("room-proxy-")) return;
        AskPattern
            .<RoomCommand, RoomResponse>ask(
                roomRef,
                replyTo -> new RoomCommand.LookRoom("between-replicator", "en", replyTo),
                Duration.ofSeconds(3),
                system.scheduler())
            .whenComplete((resp, err) -> {
                if (err != null || !(resp instanceof RoomResponse.Ok ok)) return;
                try {
                    cmdBridge.broadcastState(roomId, ok.snapshot());
                } catch (Exception e) {
                    log.debug("Between: room.state broadcast failed for {}: {}",
                        roomId, e.getMessage());
                }
            });
    }

    private Behavior<Command> onGetPlacementEngine(GetPlacementEngine msg) {
        msg.replyTo().tell(placementEngine); // nullable if Between not started
        return this;
    }

    private Behavior<Command> onGetResourceHeartbeat(GetResourceHeartbeat msg) {
        msg.replyTo().tell(resourceHeartbeat); // nullable if Between not started
        return this;
    }

    private Behavior<Command> onGetNodeCapabilities(GetNodeCapabilities msg) {
        msg.replyTo().tell(nodeCapabilities); // nullable if Between not started
        return this;
    }

    private Behavior<Command> onGetTransitProtocol(GetTransitProtocol msg) {
        msg.replyTo().tell(transitProtocol); // nullable if Between not started
        return this;
    }

    private Behavior<Command> onGetRoomPrimaryProtocol(GetRoomPrimaryProtocol msg) {
        msg.replyTo().tell(roomPrimaryProtocol); // nullable if Between not started
        return this;
    }

    private Behavior<Command> onGetMutationRouter(GetMutationRouter msg) {
        msg.replyTo().tell(mutationRouter);
        return this;
    }

    /**
     * Definitive re-audit fix (#33-3): wire the primary-side mutation executor.
     *
     * <p>{@link MutationRouter#setMutationHandler} had no production caller, so a
     * mutation forwarded from a replica reached the primary, passed the fencing +
     * idempotency checks, then fell through to publishing a
     * {@code "No mutation handler configured"} failure — a silent dead end for
     * replica→primary room mutations. {@link RoomMutationExecutor} installs the
     * real executor: look up the local (real, non-proxy) {@code RoomActor}, run
     * the serialized command through {@link RoomCommandDispatcher} (the same
     * executor ZoneGuardian uses for cross-node room proxying), and publish the
     * outcome so the requesting replica's {@code forwardToPrimary} future
     * resolves.</p>
     */
    private void wireMutationHandler(MutationRouter router) {
        router.setMutationHandler(RoomMutationExecutor.forRegistry(router));
        log.info("MutationRouter: primary-side mutation handler wired (RoomCommandDispatcher)");
    }

    private Behavior<Command> onStartAccountReplication(StartAccountReplication msg) {
        if (natsBridge == null || identity == null) {
            log.warn("Cannot start account replication — Between not started");
            return this;
        }
        accountReplicator = new IdentityReplicator(
            natsBridge, identity.nodeId(), msg.authService(), msg.inviteService());
        accountReplicator.startReplication();
        log.info("Between: IdentityReplicator started (JetStream) — account data persisted in mesh");
        return this;
    }

    private Behavior<Command> onGetIdentityReplicator(GetIdentityReplicator msg) {
        msg.replyTo().tell(accountReplicator);
        return this;
    }

    private Behavior<Command> onGetSessionService(GetSessionService msg) {
        msg.replyTo().tell(sessionService);
        return this;
    }

    private Behavior<Command> onGetObservability(GetObservability msg) {
        msg.replyTo().tell(observability);
        return this;
    }

    private Behavior<Command> onGetScheduler(GetScheduler msg) {
        msg.replyTo().tell(householdScheduler);
        return this;
    }

    private Behavior<Command> onBroadcastRelayConfig(BroadcastRelayConfig msg) {
        if (natsBridge == null || !natsBridge.isConnected()) return this;

        var payload = MAPPER.createObjectNode();
        payload.put("type", "relay_config");
        payload.put("relayUrl", msg.relayUrl() != null ? msg.relayUrl() : "");
        payload.put("relayToken", msg.relayToken() != null ? msg.relayToken() : "");
        payload.put("nodeId", identity != null ? identity.nodeId() : "unknown");
        natsBridge.broadcast("cluster", "relay_config", payload);
        log.info("Between: broadcast relay config update — {}", msg.relayUrl());
        return this;
    }

    // --- W5 distributed-layer handlers ---

    private Behavior<Command> onPropagateCrdtState(PropagateCrdtState msg) {
        if (crdtLayerActor != null) {
            crdtLayerActor.tell(new CrdtLayer.PropagateState(
                msg.roomId(), msg.stateType(), msg.delta()));
        }
        if (natsBridge != null && natsBridge.isConnected()) {
            var payload = MAPPER.createObjectNode();
            payload.put("type", "state");
            payload.put("roomId", msg.roomId());
            payload.put("stateType", msg.stateType());
            // Wall-clock ordering token: receivers apply deltas whose clock
            // exceeds their local one and then max up to it (LWW convergence
            // for this volatile cache layer).
            payload.put("vectorClock", System.currentTimeMillis());
            payload.set("delta", MAPPER.valueToTree(msg.delta()));
            natsBridge.broadcast("crdt", "state", payload);
        }
        return this;
    }

    private Behavior<Command> onShareMemory(ShareMemory msg) {
        if (memoryLayerActor != null) {
            memoryLayerActor.tell(new MemoryLayer.Store(
                msg.namespace(), msg.key(), msg.value(), msg.ttlSeconds()));
        }
        if (natsBridge != null && natsBridge.isConnected()) {
            var payload = MAPPER.createObjectNode();
            payload.put("type", "entry");
            payload.put("namespace", msg.namespace());
            payload.put("key", msg.key());
            payload.put("value", msg.value());
            payload.put("expiresAt", Instant.now().getEpochSecond() + msg.ttlSeconds());
            natsBridge.broadcast("memory", "entry", payload);
        }
        return this;
    }

    private void subscribeToCrdtMessages() {
        natsBridge.subscribeLayer("crdt", env -> {
            if (crdtLayerActor == null || identity.nodeId().equals(env.src())) return;
            var payload = env.payload();
            if (!"state".equals(payload.path("type").asText())) return;
            var delta = new HashMap<String, String>();
            payload.path("delta").fields().forEachRemaining(e ->
                delta.put(e.getKey(), e.getValue().asText()));
            crdtLayerActor.tell(new CrdtLayer.ReceiveState(
                env.src(),
                payload.path("roomId").asText(),
                payload.path("stateType").asText(),
                delta,
                payload.path("vectorClock").asLong()));
        });
    }

    private void subscribeToMemoryMessages() {
        natsBridge.subscribeLayer("memory", env -> {
            if (memoryLayerActor == null || identity.nodeId().equals(env.src())) return;
            var payload = env.payload();
            if (!"entry".equals(payload.path("type").asText())) return;
            memoryLayerActor.tell(new MemoryLayer.ReceiveEntry(
                env.src(),
                payload.path("namespace").asText(),
                payload.path("key").asText(),
                payload.path("value").asText(),
                payload.path("expiresAt").asLong()));
        });
    }

    private void subscribeToInferenceLayerMessages() {
        natsBridge.subscribeLayer("inference", env -> {
            if (inferenceLayerActor == null || identity.nodeId().equals(env.src())) return;
            var payload = env.payload();
            if (!"advertise".equals(payload.path("type").asText())) return;
            var models = new ArrayList<String>();
            payload.path("models").forEach(m -> models.add(m.asText()));
            inferenceLayerActor.tell(new InferenceLayer.AdvertiseCapacity(
                payload.path("nodeId").asText(env.src()),
                models,
                payload.path("freeMemoryMb").asLong(),
                payload.path("cpuLoad").asDouble(),
                payload.path("activeRequests").asInt(),
                payload.path("maxConcurrent").asInt(1)));
        });
    }

    /** Rough tier classification by model name for gossip announcements. */
    private static String classifyModelTier(String modelName) {
        if (modelName == null) return "default";
        var lower = modelName.toLowerCase();
        if (lower.matches(".*(7b|8b|9b|12b|14b|32b|70b).*")) return "large";
        if (lower.matches(".*(1\\.5b|2b|3b|4b).*")) return "medium";
        return "default";
    }

    private Behavior<Command> onPostStop(PostStop signal) {
        log.info("Between: shutting down");

        // Broadcast leaving
        if (natsBridge != null && natsBridge.isConnected() && identity != null) {
            var payload = MAPPER.createObjectNode();
            payload.put("type", "leaving");
            payload.put("nodeId", identity.nodeId());
            payload.put("reason", "shutdown");
            natsBridge.broadcast("cluster", "leaving", payload);
        }

        if (observability != null) observability.shutdown();
        if (householdScheduler != null) householdScheduler.shutdown();
        if (checkpointer != null) checkpointer.shutdown();
        if (roomPrimaryProtocol != null) roomPrimaryProtocol.shutdown();
        if (resourceHeartbeat != null) resourceHeartbeat.shutdown();
        if (relayBridge != null) relayBridge.close();
        for (var leg : additionalBridges) {
            try { leg.close(); } catch (Exception ignored) { /* best-effort */ }
        }
        additionalBridges.clear();
        if (mdns != null) mdns.close();
        if (natsBridge != null) natsBridge.close();
        if (natsServer != null) natsServer.stop();

        return this;
    }

    // --- Message handlers ---

    private void handleHello(BetweenEnvelope envelope) {
        var payload = envelope.payload();
        var peerNodeId = payload.get("nodeId").asText();
        var peerPublicKey = Base64.getDecoder().decode(payload.get("publicKey").asText());

        // Verify signature
        if (!envelope.verify(peerPublicKey)) {
            log.warn("Between: rejected hello from {} — invalid signature", peerNodeId);
            return;
        }

        // Store peer's public key
        peerPublicKeys.put(peerNodeId, peerPublicKey);

        // Extract capabilities
        var caps = new HashMap<String, String>();
        var capsNode = payload.get("capabilities");
        if (capsNode != null) {
            capsNode.fields().forEachRemaining(e ->
                caps.put(e.getKey(), e.getValue().asText()));
        }

        // Extract version info (mesh update protocol)
        String peerVersion = null;
        int peerWireProtocol = 0;
        var versionNode = payload.get("version");
        if (versionNode != null && versionNode.isObject()) {
            peerVersion = versionNode.has("app") ? versionNode.get("app").asText() : null;
            peerWireProtocol = versionNode.has("wireProtocol") ? versionNode.get("wireProtocol").asInt() : 0;
        }

        // Register in topology
        topology.peerConnected(peerNodeId, caps, peerVersion, peerWireProtocol);

        log.info("Between: peer {} connected (v{}, wire={}, {} CPUs, {} MB RAM)",
            peerNodeId,
            peerVersion != null ? peerVersion : "?",
            peerWireProtocol,
            caps.getOrDefault("cpuCount", "?"),
            caps.getOrDefault("memoryMb", "?"));

        // Respond with hello_ack
        var ackPayload = helloPayload();
        ackPayload.put("type", "hello_ack");
        ackPayload.put("accepted", true);
        natsBridge.send(peerNodeId, "cluster", "hello_ack", ackPayload);
    }

    private void handleHelloAck(BetweenEnvelope envelope) {
        var payload = envelope.payload();
        var peerNodeId = payload.get("nodeId").asText();
        var peerPublicKey = Base64.getDecoder().decode(payload.get("publicKey").asText());
        var accepted = payload.get("accepted").asBoolean();

        if (!accepted) {
            log.warn("Between: peer {} rejected our hello", peerNodeId);
            return;
        }

        // Verify and store
        if (!envelope.verify(peerPublicKey)) {
            log.warn("Between: rejected hello_ack from {} — invalid signature", peerNodeId);
            return;
        }

        peerPublicKeys.put(peerNodeId, peerPublicKey);

        var caps = new HashMap<String, String>();
        var capsNode = payload.get("capabilities");
        if (capsNode != null) {
            capsNode.fields().forEachRemaining(e ->
                caps.put(e.getKey(), e.getValue().asText()));
        }

        // Extract version info (mesh update protocol)
        String peerVersion = null;
        int peerWireProtocol = 0;
        var versionNode = payload.get("version");
        if (versionNode != null && versionNode.isObject()) {
            peerVersion = versionNode.has("app") ? versionNode.get("app").asText() : null;
            peerWireProtocol = versionNode.has("wireProtocol") ? versionNode.get("wireProtocol").asInt() : 0;
        }

        topology.peerConnected(peerNodeId, caps, peerVersion, peerWireProtocol);
        log.info("Between: peer {} acknowledged our hello (v{})", peerNodeId,
            peerVersion != null ? peerVersion : "?");
    }

    private void handleHeartbeat(BetweenEnvelope envelope) {
        var peerNodeId = envelope.payload().get("nodeId").asText();

        // Verify if we have the peer's key
        var peerKey = peerPublicKeys.get(peerNodeId);
        if (peerKey != null && !envelope.verify(peerKey)) {
            log.debug("Between: heartbeat from {} failed verification", peerNodeId);
            return;
        }

        // Extract version info from heartbeat (mesh update protocol)
        var versionStr = envelope.payload().has("version")
            ? envelope.payload().get("version").asText() : null;
        var wireProto = envelope.payload().has("wireProtocol")
            ? envelope.payload().get("wireProtocol").asInt() : 0;

        // If this peer is unknown, send a hello to trigger full handshake.
        // This handles the case where a node joins after our initial hello broadcast.
        if (!topology.isPeerConnected(peerNodeId)) {
            log.info("Between: unknown peer {} detected via heartbeat — sending hello", peerNodeId);
            broadcastHello();
        }

        topology.updateHeartbeat(peerNodeId, versionStr, wireProto);
    }

    private void handleLeaving(BetweenEnvelope envelope) {
        var peerNodeId = envelope.payload().get("nodeId").asText();
        log.info("Between: peer {} leaving ({})", peerNodeId,
            envelope.payload().has("reason") ? envelope.payload().get("reason").asText() : "unknown");
        topology.peerDisconnected(peerNodeId);
        // Notify RoomLayer so it can release rooms and claim orphans
        if (roomLayerActor != null) {
            roomLayerActor.tell(new RoomLayer.PeerTimedOut(peerNodeId));
        }
    }

    private void handlePing(BetweenEnvelope envelope) {
        if (natsBridge == null) return;

        var payload = MAPPER.createObjectNode();
        payload.put("type", "pong");
        payload.put("seq", envelope.payload().get("seq").asInt());
        payload.put("sentAt", envelope.payload().get("sentAt").asText());
        payload.put("receivedAt", Instant.now().toString());

        natsBridge.send(envelope.src(), "probe", "pong", payload);
    }

    private void handlePong(BetweenEnvelope envelope) {
        var seq = envelope.payload().get("seq").asInt();
        var key = envelope.src() + ":" + seq;
        var sentNanos = probeTimestamps.remove(key);

        if (sentNanos != null) {
            var rttMs = (System.nanoTime() - sentNanos) / 1_000_000.0;
            topology.updateLatency(envelope.src(), rttMs);
        }
    }

    private void handleRelayConfig(BetweenEnvelope envelope) {
        var payload = envelope.payload();
        var newRelayUrl = payload.has("relayUrl") ? payload.get("relayUrl").asText() : "";
        var newRelayToken = payload.has("relayToken") ? payload.get("relayToken").asText() : "";
        log.info("Between: received relay config update from {} — relay: {}",
            envelope.src(), newRelayUrl.isEmpty() ? "(disabled)" : newRelayUrl);

        // Peers can use this to update their own relay bridge or mDNS records.
        // For now, log the event. Phones connected via Between will see this
        // as a headline and can update their saved config.
    }

    /**
     * Extract the host portion from WYRDSEKAI_RELAY_URL for cross-zone inference gateway.
     * e.g. "nats://198.51.100.39:4222" → "198.51.100.39"
     */
    private String extractRelayHost() {
        var relayUrl = WyrdConfig.get().relayUrl();
        if (relayUrl == null || relayUrl.isEmpty()) return null;
        try {
            var uri = URI.create(relayUrl);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Helpers ---

    private void subscribeToClusterMessages() {
        var self = getContext().getSelf();

        // Subscribe to all cluster broadcasts
        natsBridge.subscribe(
            "between." + zoneId + ".*.*.cluster.>",
            env -> self.tell(new EnvelopeReceived(env))
        );

        // Subscribe to directed probe messages
        natsBridge.subscribe(
            "between." + zoneId + ".*." + identity.nodeId() + ".probe.>",
            env -> self.tell(new EnvelopeReceived(env))
        );

        // Subscribe to federation activation events (from FederationActor)
        try {
            natsBridge.rawConnection().createDispatcher(msg -> {
                var remoteZoneId = new String(msg.getData(), StandardCharsets.UTF_8);
                if (!remoteZoneId.isBlank()) {
                    self.tell(new FederationActivated(remoteZoneId.trim()));
                }
            }).subscribe("federation.local.activated");
        } catch (Exception e) {
            log.debug("Could not subscribe to federation.local.activated: {}", e.getMessage());
        }
    }

    private void subscribeToSoulMessages() {
        natsBridge.subscribeLayer("soul", env -> {
            var payload = env.payload();
            var type = payload.has("type") ? payload.get("type").asText() : "unknown";
            routeSoulMessage(type, payload, env.src());
        });
    }

    private void subscribeToRoomMessages() {
        natsBridge.subscribeLayer("rooms", env -> {
            var payload = env.payload();
            var type = payload.has("type") ? payload.get("type").asText() : "unknown";
            routeRoomMessage(type, payload, env.src());
        });
    }

    private void routeRoomMessage(String type, JsonNode payload,
                                   String fromNode) {
        if (roomLayerActor == null) return;

        switch (type) {
            case "room_announcement" -> {
                try {
                    var announcement = MAPPER.treeToValue(
                        payload.get("announcement"), NodeAnnouncement.class);
                    roomLayerActor.tell(new RoomLayer.AnnouncementReceived(announcement));
                } catch (Exception e) {
                    log.debug("Failed to deserialize room announcement: {}", e.getMessage());
                }
            }
            case "room_claim" -> {
                try {
                    var claim = MAPPER.treeToValue(
                        payload.get("claim"), RoomClaimMessage.class);
                    roomLayerActor.tell(new RoomLayer.ClaimReceived(claim));
                } catch (Exception e) {
                    log.debug("Failed to deserialize room claim: {}", e.getMessage());
                }
            }
            case "room_snapshot" -> {
                var roomId = payload.has("roomId") ? payload.get("roomId").asText() : null;
                var snapshotB64 = payload.has("snapshotData") ? payload.get("snapshotData").asText() : null;
                if (roomId != null && snapshotB64 != null) {
                    var data = Base64.getDecoder().decode(snapshotB64);
                    roomLayerActor.tell(new RoomLayer.SnapshotReceived(
                        roomId, fromNode, data, Instant.now()));
                }
            }
            default -> log.debug("Between: unknown room message type: {}", type);
        }
    }

    private void routeSoulMessage(String type, JsonNode payload,
                                   String fromNode) {
        if (soulLayerActor == null) return;

        switch (type) {
            case "announced", "gossip" -> {
                var did = payload.get("agentDid").asText();
                var version = payload.get("manifestVersion").asInt();
                var hash = payload.has("contentHash") ? payload.get("contentHash").asText() : "";
                var at = payload.has("announcedAt") ? payload.get("announcedAt").asLong() : 0L;
                soulLayerActor.tell(new SoulLayer.ReceivePresence(
                    fromNode, did, version, hash, at));
            }
            case "departed" -> {
                var did = payload.get("agentDid").asText();
                soulLayerActor.tell(new SoulLayer.ReceiveDeparture(fromNode, did));
            }
            case "migration" -> {
                var did = payload.get("agentDid").asText();
                var json = payload.get("manifestJson").asText();
                var version = payload.get("version").asInt();
                soulLayerActor.tell(new SoulLayer.ReceiveMigration(
                    fromNode, did, json, version));
            }
            case "trace" -> {
                try {
                    var trace = MAPPER.treeToValue(
                        payload.get("trace"),
                        StigmergicTrace.class);
                    soulLayerActor.tell(new SoulLayer.ReceiveTrace(fromNode, trace));
                } catch (Exception e) {
                    log.debug("Failed to deserialize soul trace: {}", e.getMessage());
                }
            }
            case "headline" -> {
                var familyId = payload.get("familyId").asText();
                var budDid = payload.get("budDid").asText();
                var summary = payload.has("summary") ? payload.get("summary").asText() : "";
                var itemCount = payload.has("itemCount") ? payload.get("itemCount").asInt() : 0;
                var timestamp = payload.has("timestamp") ? payload.get("timestamp").asLong() : 0L;
                var headline = new SoulLayer.HeadlineMessage(
                    budDid, summary, new double[0], itemCount, timestamp);
                soulLayerActor.tell(new SoulLayer.ReceiveHeadline(
                    fromNode, familyId, headline));
            }
            case "handoff" -> {
                var budDid = payload.get("budDid").asText();
                var json = payload.has("manifestJson") ? payload.get("manifestJson").asText() : "";
                var ts = payload.has("timestamp") ? payload.get("timestamp").asLong() : 0L;
                var handoffPayload = new SoulLayer.HandoffPayload(
                    budDid, json, List.of(), ts);
                soulLayerActor.tell(new SoulLayer.ReceiveWarmHandoff(fromNode, handoffPayload));
            }
            case "sync" -> {
                var familyId = payload.get("familyId").asText();
                var budDid = payload.get("budDid").asText();
                var ts = payload.has("timestamp") ? payload.get("timestamp").asLong() : 0L;
                var syncPayload = new SoulLayer.SleepSyncPayload(
                    familyId, budDid, List.of(), List.of(), ts);
                soulLayerActor.tell(new SoulLayer.ReceiveSleepSync(fromNode, syncPayload));
            }
            case "backup" -> {
                var did = payload.get("agentDid").asText();
                var json = payload.has("manifestJson") ? payload.get("manifestJson").asText() : "";
                var version = payload.has("version") ? payload.get("version").asInt() : 0;
                var hash = payload.has("contentHash") ? payload.get("contentHash").asText() : "";
                soulLayerActor.tell(new SoulLayer.ReceiveBackupReplication(
                    fromNode, did, json, version, hash));
            }
            case "delegate" -> {
                var requestId = payload.path("requestId").asText();
                var fromBudDid = payload.path("fromBudDid").asText();
                var message = payload.path("message").asText();
                var recentHistory = new ArrayList<String>();
                var historyNode = payload.path("recentHistory");
                if (historyNode.isArray()) {
                    for (var h : historyNode) recentHistory.add(h.asText());
                }
                var locale = payload.path("locale").asText("en");
                var delegatePayload = new SoulLayer.DelegateQueryPayload(
                    requestId, fromBudDid, message, recentHistory, locale,
                    payload.path("timestamp").asLong(System.currentTimeMillis()));
                soulLayerActor.tell(new SoulLayer.ReceiveDelegateQuery(fromNode, delegatePayload));
            }
            default -> log.debug("Between: unknown soul message type: {}", type);
        }
    }

    private void startMdnsDiscovery(String natsUrl) {
        try {
            // SECURITY: relay URL is broadcast (it's an address); relay TOKEN
            // is NEVER. Joining a household goes through an explicit consent
            // flow (knock + admin approve), not passive mDNS observation.
            //
            // Migrated to org.wyrdsekai.core.config.MdnsDiscovery so we share
            // the household-aware advertisement with `wyrd discover --lan` —
            // one mDNS service per node instead of two.
            var cfg = WyrdConfig.get();
            var hh = cfg.relayUser();
            var householdId = (hh != null && !hh.isBlank()) ? hh : "none";
            var advert = new MdnsDiscovery.Advertisement(
                identity.nodeId(),
                cfg.nodeName(),
                cfg.zoneId(),
                householdId,
                7070,                       // wyrd HTTP port — for discover --lan
                natsUrl,                    // cluster hint
                config.arteryPort(),        // cluster hint
                false,                       // hostsRelay (not yet wired)
                cfg.peerTrainingHost(),
                cfg.inferenceUrl() != null
            );
            mdns = MdnsDiscovery.defaultInstance();
            mdns.advertise(advert);
            // Listen for peers and forward to the existing PeerDiscovered handler.
            var self = getContext().getSelf();
            mdns.addListener(peer -> {
                var peerNodeId = peer.txt().get("nodeId");
                var peerNats   = peer.txt().get("natsUrl");
                var peerArtStr = peer.txt().get("arteryPort");
                if (peerNodeId == null || identity.nodeId().equals(peerNodeId)) return;
                int peerArt = peerArtStr != null
                    ? Integer.parseInt(peerArtStr)
                    : config.arteryPort();
                self.tell(new PeerDiscovered(
                    peerNodeId,
                    peerNats != null ? peerNats : "nats://" + peer.hostName() + ":4222",
                    peer.hostName(),
                    peerArt));
            });
        } catch (Exception e) {
            log.warn("Between: mDNS discovery failed — using seed nodes only", e);
        }
    }

    private void broadcastHello() {
        var payload = helloPayload();
        payload.put("type", "hello");
        natsBridge.broadcast("cluster", "hello", payload);
    }

    private ObjectNode helloPayload() {
        var payload = MAPPER.createObjectNode();
        payload.put("nodeId", identity.nodeId());
        payload.put("publicKey", identity.publicKeyBase64());
        payload.put("arteryHost", getArteryHost());
        payload.put("arteryPort", config.arteryPort());

        var caps = MAPPER.createObjectNode();
        caps.put("cpuCount", Runtime.getRuntime().availableProcessors());
        caps.put("memoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        caps.put("javaVersion", System.getProperty("java.version", "unknown"));
        caps.put("os", System.getProperty("os.name", "unknown") + " "
            + System.getProperty("os.version", ""));
        payload.set("capabilities", caps);

        // Mesh update protocol: full version info in hello handshake
        var appVer = AppVersion.get();
        var versionNode = MAPPER.createObjectNode();
        versionNode.put("app", appVer.version());
        versionNode.put("build", appVer.buildHash());
        versionNode.put("wireProtocol", appVer.wireProtocol());
        versionNode.put("buildTimestamp", appVer.buildTimestamp().toString());
        payload.set("version", versionNode);

        return payload;
    }

    private String getArteryHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private long ManagementFactory_uptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

}
