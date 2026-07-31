package org.wyrdsekai.core.room;

import org.wyrdsekai.core.skill.SkillBootstrap;
import org.wyrdsekai.core.skill.SkillPermission;
import org.wyrdsekai.core.mcp.McpGatewayService;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
// ClusterSharding removed — Artery transport corrupts WiFi routing on macOS.
// Rooms are spawned as child actors and registered in RoomRegistry.
// Multi-node room coordination uses NATS-based Between layer.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.ChiefEngineerActor;
import org.wyrdsekai.core.agent.CommandRouter;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.CompanionCapabilities;
import org.wyrdsekai.core.agent.CompanionTransitState;
import org.wyrdsekai.core.agent.Companions;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.Engineers;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.agent.LexiconService;
import org.wyrdsekai.core.agent.LocalCommandRouter;
import org.wyrdsekai.core.agent.ProactivityPolicy;
import org.wyrdsekai.core.agent.RoomCreator;
import org.wyrdsekai.core.agent.SeedForgeGovernor;
import org.wyrdsekai.core.agent.TranslationActor;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.agent.WardenActor;
import org.wyrdsekai.core.agent.Wardens;
import org.wyrdsekai.core.ambient.WorldClock;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.OutputSanitizer;
import org.wyrdsekai.core.item.RoomImprintTracker;
import org.wyrdsekai.core.persistence.RoomMetadataService;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.skill.WorkbenchSkillExecutor;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.ForgeCommand;
import org.wyrdsekai.core.soul.IsekaiProtocol;
import org.wyrdsekai.core.soul.ResidencyToken;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.core.soul.SoulStore;
import org.wyrdsekai.scripting.api.BridgeDataProvider;
import org.wyrdsekai.scripting.loader.ScriptLoader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Root actor for the Wyrdsekai actor system.
 * Initializes Cluster Sharding for room entities, seeds Foundation rooms,
 * and supervises top-level components.
 *
 * Supports deferred seeding: when The Between is active, waits up to 3 seconds
 * for an {@link ApplyRoomView} command with the household room topology.
 * Rooms already claimed by peers are skipped. If no view arrives within the
 * timeout, seeds all rooms (single-node fallback, backward compatible).
 */
public class ZoneGuardian extends AbstractBehavior<ZoneGuardian.Command> {

    private static final Logger log = LoggerFactory.getLogger(ZoneGuardian.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);
    /** How long to wait for ApplyRoomView before falling back to full seeding. */
    static final Duration SEED_TIMEOUT = Duration.ofSeconds(3);
    private static final String SEED_TIMER_KEY = "seed-timeout";

    /** Room ID constant — kept for backward compatibility with callers. */
    public static final String ROOM_ENTITY_TYPE = "Room";

    public sealed interface Command {}

    /** Spawn a companion agent in a room. Sent by Main after InferenceRouter is up. */
    public record SpawnCompanion(
        AgentProfile profile,
        String roomId,
        ActorRef<InferenceRouter.Command> inferenceRouter,
        WorldDnaService worldDnaService,
        Path userScriptsDir,
        SoulStore soulStore,
        ActorRef<ForgeCommand> forgeActor,
        CommandRouter commandRouter) implements Command {

        /** Backward-compatible 7-param: falls back to the process-wide
         * {@link org.wyrdsekai.core.agent.LocalCommandRouter} singleton so
         * agents pick up registered handlers (e.g. {@code openhands.create})
         * without callers having to thread the router through every spawn
         * site. */
        public SpawnCompanion(AgentProfile profile, String roomId,
                              ActorRef<InferenceRouter.Command> inferenceRouter,
                              WorldDnaService worldDnaService, Path userScriptsDir,
                              SoulStore soulStore, ActorRef<ForgeCommand> forgeActor) {
            this(profile, roomId, inferenceRouter, worldDnaService, userScriptsDir,
                soulStore, forgeActor,
                LocalCommandRouter.get());
        }

        public SpawnCompanion(ActorRef<InferenceRouter.Command> inferenceRouter) {
            this(null, null, inferenceRouter, null, null, null, null, null);
        }

        public SpawnCompanion(ActorRef<InferenceRouter.Command> inferenceRouter,
                              WorldDnaService worldDnaService, Path userScriptsDir) {
            this(null, null, inferenceRouter, worldDnaService, userScriptsDir, null, null, null);
        }

        public SpawnCompanion(ActorRef<InferenceRouter.Command> inferenceRouter,
                              WorldDnaService worldDnaService, Path userScriptsDir,
                              SoulStore soulStore) {
            this(null, null, inferenceRouter, worldDnaService, userScriptsDir, soulStore, null, null);
        }

        public SpawnCompanion(ActorRef<InferenceRouter.Command> inferenceRouter,
                              WorldDnaService worldDnaService, Path userScriptsDir,
                              SoulStore soulStore, ActorRef<ForgeCommand> forgeActor) {
            this(null, null, inferenceRouter, worldDnaService, userScriptsDir, soulStore, forgeActor, null);
        }
    }

    /** Stop and respawn the default companion with fresh state. Used by E2E tests. */
    public record RespawnCompanion(
        ActorRef<InferenceRouter.Command> inferenceRouter,
        WorldDnaService worldDnaService) implements Command {}

    /**
     * relocate a companion across zones. Two flavors:
     * <ol>
     *   <li>SOURCE side ({@code direction=DEPART}): the companion is here and we
     *       want her to leave for {@code targetZoneId}. Snapshot her state, mint
     *       a transit token, publish via federation, stop the local actor.</li>
     *   <li>TARGET side ({@code direction=ARRIVE}): a remote zone has handed
     *       her off. {@code state} carries the snapshot; spawn a fresh actor
     *       pre-seeded with it.</li>
     * </ol>
     */
    public record RelocateCompanion(
        Direction direction,
        String agentDid,                              // soul DID — primary key on both sides
        String agentEntityId,                         // entityId at source
        String targetZoneId,                          // peer zone (DEPART) or self (ARRIVE)
        String sourceZoneId,                          // self (DEPART) or peer (ARRIVE)
        String bondholderDid,                         // who she's following
        String targetRoomHint,                        // where to land at target (nullable)
        CompanionTransitState state,  // ARRIVE: pre-built; DEPART: built by handler
        ActorRef<InferenceRouter.Command> inferenceRouter,     // ARRIVE: needed to spawn
        WorldDnaService worldDnaService               // ARRIVE: needed to spawn
    ) implements Command {

        public enum Direction { DEPART, ARRIVE }

        /** DEPART convenience: handler will snapshot state from the live actor. */
        public static RelocateCompanion depart(String agentDid, String agentEntityId,
                                                  String targetZoneId, String sourceZoneId,
                                                  String bondholderDid, String targetRoomHint) {
            return new RelocateCompanion(Direction.DEPART, agentDid, agentEntityId,
                targetZoneId, sourceZoneId, bondholderDid, targetRoomHint,
                null, null, null);
        }

        /** ARRIVE: target zone receives a relocate envelope. */
        public static RelocateCompanion arrive(CompanionTransitState state,
                                                  String sourceZoneId, String targetZoneId,
                                                  String bondholderDid, String targetRoomHint,
                                                  ActorRef<InferenceRouter.Command> inferenceRouter,
                                                  WorldDnaService worldDnaService) {
            var profile = state.profile();
            return new RelocateCompanion(Direction.ARRIVE,
                profile != null ? profile.did() : null,
                profile != null ? profile.entityId() : null,
                targetZoneId, sourceZoneId, bondholderDid, targetRoomHint,
                state, inferenceRouter, worldDnaService);
        }
    }

    /** Spawn the Chief Engineer agent in The Boiler Room. */
    public record SpawnChiefEngineer(
        ActorRef<InferenceRouter.Command> inferenceRouter,
        WorldDnaService worldDnaService,
        Supplier<String> systemMetrics,
        Supplier<String> topology,
        Supplier<String> inferenceStatus,
        Supplier<String> economy) implements Command {}

    /** Spawn the Warden agent in The Ward Room. */
    public record SpawnWarden(
        ActorRef<InferenceRouter.Command> inferenceRouter,
        WorldDnaService worldDnaService,
        OutputSanitizer sanitizer) implements Command {}

    /** Spawn the Translation agent in The Lexicon. */
    public record SpawnTranslationActor(
        ActorRef<InferenceRouter.Command> inferenceRouter,
        LexiconService lexiconService) implements Command {}

    /**
     * Spawn the Governor agent in the Council Chamber (or specified room).
     * The Governor observes household agent activity and advises on policy compliance.
     */
    public record SpawnGovernor(
        ActorRef<InferenceRouter.Command> inferenceRouter,
        WorldDnaService worldDnaService,
        Path userScriptsDir,
        SoulStore soulStore,
        ActorRef<ForgeCommand> forgeActor) implements Command {

        /** Minimal constructor — most fields nullable. */
        public SpawnGovernor(ActorRef<InferenceRouter.Command> inferenceRouter) {
            this(inferenceRouter, null, null, null, null);
        }
    }

    /** Apply a room topology view from The Between.
     *  Only rooms NOT claimed by peer nodes will be seeded locally. */
    public record ApplyRoomView(
        Map<String, String> claimedRoomToPrimaryNode
    ) implements Command {}

    /** Rebuild a room from a Between snapshot (replica restoration). */
    public record RebuildRoom(String roomId, RoomSnapshot snapshot) implements Command {}

    /** Create a new room on demand (from RoomCreator / companion agents). */
    public record CreateNewRoom(String roomId, String name, String description,
                                 String zone, List<Exit> exits,
                                 List<RoomObject> objects) implements Command {}

    /** Internal: seed timeout — no view received, seed everything. */
    private record SeedTimeout() implements Command {}

    /** Set the RoomEventListener for Between event replication.
     *  Sent after Between starts — rooms created after this will publish
     *  events to NATS via the listener. */
    public record SetRoomEventListener(RoomEventListener listener) implements Command {}

    /** Set the WyrdLuceneStore for room content indexing on seed.
     *  Sent after WyrdLuceneStore is created in Main. Foundation rooms
     *  seeded after this is set will have their descriptions indexed. */
    public record SetLuceneStore(WyrdLuceneStore store) implements Command {}
    /** Inject SoulStore for companion soul persistence. Sent before companion spawn. */
    public record SetSoulStore(SoulStore store) implements Command {}

    /**
     * Set the room primary lookup and command transport for cross-node room proxying.
     * When set, getOrSpawnRoom checks if another node is primary — if so, spawns a
     * RoomProxy instead of a real RoomActor.
     *
     * @param primaryLookup (roomId) → nodeId of primary, or null if local/unclaimed
     * @param commandTransport (roomId, commandJson) → CompletionStage<responseJson>
     * @param eventSubscriber (roomId, eventConsumer) → subscribes to remote room events
     * @param stateSubscriber (roomId, stateConsumer) → subscribes to remote room state broadcasts
     */
    public record SetRoomTransport(
        Function<String, String> primaryLookup,
        BiFunction<String, String, CompletionStage<String>> commandTransport,
        BiConsumer<String, Consumer<WorldEvent>> eventSubscriber,
        BiConsumer<String, Consumer<RoomSnapshot>> stateSubscriber,
        BiConsumer<String, BiFunction<String, String, CompletionStage<String>>> registerPrimaryListener,
        Predicate<String> peerAliveCheck
    ) implements Command {}

    /** Provision a private Study room for a player (idempotent — skips if exists). */
    public record ProvisionStudy(String playerId, String playerName, boolean isSteward) implements Command {
        /** Backward-compatible constructor (defaults to non-steward). */
        public ProvisionStudy(String playerId, String playerName) {
            this(playerId, playerName, false);
        }
    }

    /**
     * The zone's steward is known — announce them to every companion so the
     * constitutive bondholder bond is born ACTIVE at creation (2026-07-18).
     * Fired from {@link #onProvisionStudy} when a steward residency is
     * provisioned, and by Main at boot as a backfill for households that
     * predate this behavior. The guardian remembers the steward so companions
     * spawned LATER (a second companion added to the household) are announced
     * to as well — creation order doesn't matter.
     */
    public record AnnounceBondholder(String playerId, String playerName) implements Command {}

    /**
     * Provision a per-bondholder CodePlane Workshop room (idempotent — skips if exists).
     */
    public record ProvisionCodePlaneWorkshop(String bondholderId, String bondholderName) implements Command {}

    /**
     * install the cross-zone publisher. Main.java
     * sets this with a function that takes a captured state + token + target
     * zone and publishes a {@code CompanionRelocateMsg} via FederationService.
     * Tests can install an in-memory variant for a direct
     * source→target round trip.
     */
    public record SetCompanionRelocator(CompanionRelocator relocator) implements Command {}

    /**
     * Source-side publisher for cross-zone companion relocation. Receives
     * the full payload it needs: target zone id, state snapshot, bondholder
     * DID, optional landing-room hint. Implementation is responsible for
     * minting/signing the {@link org.wyrdsekai.between.federation.TransitToken}
     * and getting the envelope to the target zone (NATS in production,
     * direct actor handoff in tests).
     */
    @FunctionalInterface
    public interface CompanionRelocator {
        void publish(String targetZoneId, String sourceZoneId,
                     CompanionTransitState state,
                     String bondholderDid, String targetRoomHint);
    }

    /**
     * install the target→source arrival-ack publisher
     * (the loss-safety reverse channel). Tests install an in-memory variant.
     */
    public record SetRelocateAcker(RelocateAcker acker) implements Command {}

    /**
     * Target-side publisher that confirms an arrival back to the source zone so
     * the source can release ownership. Mirrors {@link CompanionRelocator} in the
     * opposite direction. Implementation routes the ack to the source zone (NATS
     * in production, direct actor handoff in tests).
     */
    @FunctionalInterface
    public interface RelocateAcker {
        void ackArrival(String sourceZoneId, String targetZoneId,
                        String entityId, String agentDid, long transitEpoch);
    }

    /**
     * Delivered to the SOURCE guardian when the target has hosted the companion.
     * Releases the matching {@link PendingDeparture} (cancels the retry timer and
     * discards the retained snapshot — ownership has transferred cleanly).
     */
    public record CompanionArrivedAck(
        String entityId, String agentDid, long transitEpoch, String fromZoneId) implements Command {}

    /** Internal: a captured snapshot is ready to publish + arm the ack wait. */
    private record DoRelocatePublish(
        String entityId, String agentDid, String targetZoneId, String sourceZoneId,
        String bondholderDid, String roomHint,
        CompanionTransitState state,
        ActorRef<CompanionActor.Command> live) implements Command {}

    /** Internal: no ack arrived for an in-flight DEPART within the window. */
    private record RelocateAckTimeout(String entityId, long epoch) implements Command {}

    /** Delegate a query from a phone bud to a server companion. */
    public record DelegateToCompanion(
        String requestId,
        String fromBudDid,
        String targetCompanionId,  // nullable = default companion
        String message,
        List<String> recentHistory,
        String locale,
        ActorRef<CompanionActor.BudDelegateResponse> replyTo
    ) implements Command {}

    /** Room definition for seeding. */
    public record RoomSeed(String roomId, String name, String description,
                           List<String> aliases, List<Exit> exits,
                           List<RoomObject> objects,
                           RoomImprintTracker.RoomImprint imprint) {

        /** Backward-compatible constructor — no aliases, no imprint. */
        public RoomSeed(String roomId, String name, String description,
                        List<Exit> exits, List<RoomObject> objects) {
            this(roomId, name, description, List.of(), exits, objects, null);
        }

        /** Backward-compatible constructor — no aliases. */
        public RoomSeed(String roomId, String name, String description,
                        List<Exit> exits, List<RoomObject> objects,
                        RoomImprintTracker.RoomImprint imprint) {
            this(roomId, name, description, List.of(), exits, objects, imprint);
        }
    }

    private final ScriptLoader scriptLoader;
    private final RoomMetadataService metadataService;
    private final BridgeDataProvider bridgeDataProvider;
    private final SanctionEnforcer sanctionEnforcer;
    private final List<RoomImprintTracker.RoomImprint> roomImprints;
    private final TimerScheduler<Command> timers;
    private final List<RoomSeed> seeds;
    private boolean seeded = false;
    /** Layer 5 — per-zone {@code WorldClock} actor, spawned once after seeding. */
    private ActorRef<WorldClock.Command> worldClock;
    private final Map<String, ActorRef<CompanionActor.Command>> companions = new HashMap<>();
    private final IsekaiProtocol isekaiProtocol = new IsekaiProtocol();

    /** Static companion registry — accessible from outside the actor system for bridge wiring. */
    private static final ConcurrentHashMap<String, ActorRef<CompanionActor.Command>>
        companionRegistry = new ConcurrentHashMap<>();

    /** — late-bound publisher for cross-zone relocate. */
    private volatile CompanionRelocator companionRelocator;

    /**
     * Per-entity transit epoch fence — dup-safety for relocate handoffs
     * (spec/tla/TransitToken.tla, P1). Each DEPART mints a strictly-higher epoch;
     * each ARRIVE is applied only if strictly newer than the highest seen, so a
     * redelivered or stale cross-cycle token never spawns a second host.
     */
    private final TransitEpochTracker transitEpochs = new TransitEpochTracker();

    /**
     * late-bound target→source ack publisher. The
     * loss-safety half of the relocate fence: the source stops the local actor
     * on DEPART but <em>retains the snapshot</em> until the target confirms it
     * hosted her (an {@link CompanionArrivedAck}); a dropped token → no ack →
     * the source retries, and after exhausting retries revives her locally so she
     * is never lost. See spec/tla/TransitToken.tla (P1 loss-safety).
     */
    private volatile RelocateAcker relocateAcker;

    /**
     * In-flight DEPARTs awaiting an arrival ack, keyed by entityId (one relocate
     * per companion at a time — a newer DEPART supersedes). Retains the
     * epoch-stamped snapshot so the source can re-publish on timeout and revive
     * the companion if the handoff is never confirmed.
     */
    private final Map<String, PendingDeparture> pendingDepartures = new HashMap<>();

    /**
     * Cached zone inference router + DNA service — captured the first time the
     * guardian spawns any companion, so a give-up revive (which runs off the
     * DEPART path, where these aren't carried) can rebuild the actor. A zone has
     * exactly one of each, so first-non-null is stable.
     */
    private ActorRef<InferenceRouter.Command> cachedInferenceRouter;
    private WorldDnaService cachedWorldDna;

    /**
     * How long the source waits for an arrival ack before re-publishing, and the
     * re-publish attempts before giving up and reviving the companion at source.
     * Read per-instance at construction (not static) so a test can set the system
     * property in setup and have it take effect — overridable via
     * {@code -Dwyrdsekai.relocate.ackTimeoutMs=...} / {@code .maxAttempts=...} to run
     * the loss-safety machine fast.
     */
    private final Duration relocateAckTimeout = Duration.ofMillis(
        Long.getLong("wyrdsekai.relocate.ackTimeoutMs", 8000L));
    private final int relocateMaxAttempts =
        Integer.getInteger("wyrdsekai.relocate.maxAttempts", 3);

    /** Source-side record of an in-flight relocate awaiting an arrival ack. */
    private record PendingDeparture(
        String entityId, String agentDid, long epoch,
        String targetZoneId, String sourceZoneId, String bondholderDid, String roomHint,
        CompanionTransitState snapshot,  // epoch-stamped
        int attempts) {
        PendingDeparture withAttempt(int n) {
            return new PendingDeparture(entityId, agentDid, epoch, targetZoneId,
                sourceZoneId, bondholderDid, roomHint, snapshot, n);
        }
    }

    private static String relocateAckTimerKey(String entityId) {
        return "relocate-ack:" + entityId;
    }

    /** Look up a companion actor by entity ID. Used by ResidentRoutes for bridge wiring. */
    public static ActorRef<CompanionActor.Command> getCompanionRef(Object unused, String entityId) {
        return companionRegistry.get(entityId);
    }

    /** All live companion refs (W8, audit 2026-07-11) — used to fan client
     *  locale out to companions; the ws layer previously set locale only on
     *  the session actor and companions never learned it. */
    public static Collection<ActorRef<CompanionActor.Command>> allCompanionRefs() {
        return companionRegistry.values();
    }

    /** Static Isekai Protocol reference — accessible for foreign agent lifecycle queries. */
    private static volatile IsekaiProtocol isekaiProtocolRef;

    /** Get the Isekai Protocol instance for foreign agent lifecycle management. */
    public static IsekaiProtocol getIsekaiProtocol() {
        return isekaiProtocolRef;
    }
    // Late-bound: set via SetRoomEventListener after Between starts.
    // The Entity factory reads this field each time a RoomActor is created,
    // so rooms created after this is set will publish events to NATS.
    private volatile RoomEventListener eventListener;
    // Late-bound: set via SetLuceneStore after WyrdLuceneStore is created.
    // Used by seedRoom() to index room content for full-text search.
    private volatile WyrdLuceneStore luceneStore;
    // Late-bound: set via SetSoulStore. Passed to companions for soul persistence.
    private volatile SoulStore soulStore;
    // Late-bound: set via SetRoomTransport. For cross-node room proxying.
    private volatile Function<String, String> roomPrimaryLookup;
    private volatile BiFunction<String, String, CompletionStage<String>> roomCommandTransport;
    private volatile BiConsumer<String, Consumer<WorldEvent>> roomEventSubscriber;
    private volatile BiConsumer<String, Consumer<RoomSnapshot>> roomStateSubscriber;
    private volatile BiConsumer<String, BiFunction<String, String, CompletionStage<String>>> roomPrimaryListener;
    private volatile Predicate<String> peerAliveCheck;

    private ZoneGuardian(ActorContext<Command> context, TimerScheduler<Command> timers,
                         ScriptLoader scriptLoader,
                         List<RoomSeed> seeds, RoomMetadataService metadataService,
                         BridgeDataProvider bridgeDataProvider,
                         SanctionEnforcer sanctionEnforcer) {
        super(context);
        this.timers = timers;
        this.seeds = seeds;

        // Publish Isekai Protocol for static access
        isekaiProtocolRef = this.isekaiProtocol;

        // Collect room imprints for companion registration
        this.roomImprints = seeds.stream()
            .filter(s -> s.imprint() != null)
            .map(s -> s.imprint())
            .toList();

        // Store dependencies for spawning rooms on demand.
        // Rooms are spawned as child actors (not via ClusterSharding) and
        // registered in RoomRegistry for lookup by all callers.
        this.scriptLoader = scriptLoader;
        this.metadataService = metadataService;
        this.bridgeDataProvider = bridgeDataProvider;
        this.sanctionEnforcer = sanctionEnforcer;

        // Start deferred seeding timer.
        // If ApplyRoomView arrives before timeout, we seed only unclaimed rooms.
        // If timeout fires first, we seed everything (single-node fallback).
        timers.startSingleTimer(SEED_TIMER_KEY, new SeedTimeout(), SEED_TIMEOUT);

        log.info("ZoneGuardian started — deferred seeding {} rooms ({}s timeout)",
            seeds.size(), SEED_TIMEOUT.toSeconds());
    }

    public static Behavior<Command> create(ScriptLoader scriptLoader, List<RoomSeed> seeds,
                                           RoomMetadataService metadataService,
                                           BridgeDataProvider bridgeDataProvider) {
        return create(scriptLoader, seeds, metadataService, bridgeDataProvider, null);
    }

    public static Behavior<Command> create(ScriptLoader scriptLoader, List<RoomSeed> seeds,
                                           RoomMetadataService metadataService,
                                           BridgeDataProvider bridgeDataProvider,
                                           SanctionEnforcer sanctionEnforcer) {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers ->
                new ZoneGuardian(ctx, timers, scriptLoader, seeds,
                    metadataService, bridgeDataProvider, sanctionEnforcer)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(SpawnCompanion.class, this::onSpawnCompanion)
            .onMessage(RespawnCompanion.class, this::onRespawnCompanion)
            .onMessage(SpawnChiefEngineer.class, this::onSpawnChiefEngineer)
            .onMessage(SpawnWarden.class, this::onSpawnWarden)
            .onMessage(SpawnTranslationActor.class, this::onSpawnTranslationActor)
            .onMessage(SpawnGovernor.class, this::onSpawnGovernor)
            .onMessage(ApplyRoomView.class, this::onApplyRoomView)
            .onMessage(RebuildRoom.class, this::onRebuildRoom)
            .onMessage(CreateNewRoom.class, this::onCreateNewRoom)
            .onMessage(SeedTimeout.class, this::onSeedTimeout)
            .onMessage(DelegateToCompanion.class, this::onDelegateToCompanion)
            .onMessage(SetRoomEventListener.class, this::onSetRoomEventListener)
            .onMessage(SetLuceneStore.class, this::onSetLuceneStore)
            .onMessage(SetSoulStore.class, cmd -> { this.soulStore = cmd.store(); return this; })
            .onMessage(SetRoomTransport.class, cmd -> {
                this.roomPrimaryLookup = cmd.primaryLookup();
                this.roomCommandTransport = cmd.commandTransport();
                this.roomEventSubscriber = cmd.eventSubscriber();
                this.roomStateSubscriber = cmd.stateSubscriber();
                this.roomPrimaryListener = cmd.registerPrimaryListener();
                this.peerAliveCheck = cmd.peerAliveCheck();
                log.info("Room transport set — cross-node room proxying enabled");
                return this;
            })
            .onMessage(ProvisionStudy.class, this::onProvisionStudy)
            .onMessage(AnnounceBondholder.class, this::onAnnounceBondholder)
            .onMessage(ProvisionCodePlaneWorkshop.class, this::onProvisionCodePlaneWorkshop)
            .onMessage(RelocateCompanion.class, this::onRelocateCompanion)
            .onMessage(SetCompanionRelocator.class, cmd -> {
                this.companionRelocator = cmd.relocator();
                log.info("ZoneGuardian: cross-zone companion relocator installed");
                return this;
            })
            .onMessage(SetRelocateAcker.class, cmd -> {
                this.relocateAcker = cmd.acker();
                log.info("ZoneGuardian: cross-zone relocate-acker installed");
                return this;
            })
            .onMessage(DoRelocatePublish.class, this::onDoRelocatePublish)
            .onMessage(CompanionArrivedAck.class, this::onCompanionArrivedAck)
            .onMessage(RelocateAckTimeout.class, this::onRelocateAckTimeout)
            .build();
    }

    // --- Deferred seeding ---

    private Behavior<Command> onApplyRoomView(ApplyRoomView cmd) {
        if (seeded) {
            log.debug("ApplyRoomView received after seeding — ignoring");
            return this;
        }

        // Cancel the timeout timer
        timers.cancel(SEED_TIMER_KEY);

        // Determine which rooms are claimed by peers
        var peerClaimedRooms = new HashSet<String>();
        for (var entry : cmd.claimedRoomToPrimaryNode().entrySet()) {
            // Only exclude rooms claimed by OTHER nodes (not by us — but we don't know
            // our own nodeId here, so we exclude all claimed rooms and let the peer
            // handle theirs). In practice, the Between sends only peer-claimed rooms.
            peerClaimedRooms.add(entry.getKey());
        }

        // Seed only rooms NOT claimed by peers
        int seededCount = 0;
        int skippedCount = 0;
        for (var seed : seeds) {
            if (peerClaimedRooms.contains(seed.roomId())) {
                log.debug("Skipping seed for room {} — claimed by peer {}",
                    seed.roomId(), cmd.claimedRoomToPrimaryNode().get(seed.roomId()));
                skippedCount++;
            } else {
                seedRoom(seed);
                seededCount++;
            }
        }

        seeded = true;
        log.info("ZoneGuardian: deferred seeding with room view — {} seeded, {} skipped (peer-claimed)",
            seededCount, skippedCount);
        spawnWorldClockIfNeeded();
        return this;
    }

    private Behavior<Command> onSeedTimeout(SeedTimeout msg) {
        if (seeded) return this;

        // No view received — single-node fallback, seed everything
        for (var seed : seeds) {
            seedRoom(seed);
        }

        seeded = true;
        log.info("ZoneGuardian: seed timeout — all {} foundation rooms seeded (single-node fallback)",
            seeds.size());
        spawnWorldClockIfNeeded();
        return this;
    }

    /**
     * Layer 5 — spawn the per-zone {@link org.wyrdsekai.core.ambient.WorldClock}
     * actor once seeding settles. The clock polls the wall time, maps it to a phase
     * (DAWN/MIDDAY/DUSK/NIGHT), and on transition broadcasts a per-room
     * {@code AmbientChanged} world event to every room in {@link RoomRegistry}.
     * Idempotent: subsequent calls are no-ops.
     */
    private void spawnWorldClockIfNeeded() {
        if (worldClock != null) return;
        var zoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
        try {
            worldClock = getContext().spawn(
                WorldClock.create(zoneId),
                "world-clock");
            log.info("ZoneGuardian: WorldClock spawned for zone '{}'", zoneId);
        } catch (Exception e) {
            log.warn("ZoneGuardian: WorldClock spawn failed: {}", e.toString());
        }
    }

    private Behavior<Command> onSetRoomEventListener(SetRoomEventListener cmd) {
        this.eventListener = cmd.listener();
        log.info("ZoneGuardian: RoomEventListener set — room events will be published to NATS");
        return this;
    }

    private Behavior<Command> onSetLuceneStore(SetLuceneStore cmd) {
        this.luceneStore = cmd.store();
        log.info("ZoneGuardian: WyrdLuceneStore set — room content will be indexed on seed");
        return this;
    }

    private Behavior<Command> onProvisionStudy(ProvisionStudy cmd) {
        var seed = StudyProvisioner.createStudySeed(cmd.playerId(), cmd.playerName(), cmd.isSteward());
        seedRoom(seed);
        if (cmd.isSteward()) {
            getContext().getSelf().tell(
                new AnnounceBondholder(cmd.playerId(), cmd.playerName()));
        }
        return this;
    }

    /** Remembered steward — announced to every current AND future companion. */
    private String bondholderId;
    private String bondholderName;

    private Behavior<Command> onAnnounceBondholder(AnnounceBondholder cmd) {
        if (cmd.playerId() == null || cmd.playerId().isBlank()) return this;
        bondholderId = cmd.playerId();
        bondholderName = cmd.playerName();
        // The zone steward is super-admin (§4.2) — grant room-quarantine authority.
        RoomAuthority.grantQuarantine(bondholderId);
        for (var ref : companions.values()) {
            ref.tell(new CompanionActor.BondholderAnnounced(bondholderId, bondholderName));
        }
        log.info("Bondholder '{}' announced to {} companion(s); future spawns inherit it",
            bondholderName, companions.size());
        return this;
    }

    private Behavior<Command> onProvisionCodePlaneWorkshop(ProvisionCodePlaneWorkshop cmd) {
        var seed = WorkshopProvisioner.createWorkshopSeed(cmd.bondholderId(), cmd.bondholderName());
        seedRoom(seed);
        return this;
    }

    private Behavior<Command> onRebuildRoom(RebuildRoom cmd) {
        var roomRef = getOrSpawnRoom(cmd.roomId());
        AskPattern
            .<RoomCommand, RoomResponse>ask(roomRef,
                ref -> new RoomCommand.CreateRoom(
                    cmd.snapshot().name(), cmd.snapshot().description(),
                    cmd.snapshot().zone(), cmd.snapshot().aliases(),
                    cmd.snapshot().exits(), cmd.snapshot().objects(), ref),
                ASK_TIMEOUT, getContext().getSystem().scheduler())
            .thenAccept(resp -> log.info("Room rebuilt from snapshot: {} — {}",
                cmd.roomId(), resp));
        return this;
    }

    /**
     * Get or spawn a room actor by ID. Spawns as a child of ZoneGuardian
     * and registers in RoomRegistry for global lookup.
     */
    private ActorRef<RoomCommand> getOrSpawnRoom(String roomId) {
        var existing = RoomRegistry.get().ref(roomId);
        if (existing != null) {
            // Check if this is a stale RoomProxy for a dead node
            var isProxy = existing.path().name().startsWith("room-proxy-");
            if (isProxy && peerAliveCheck != null) {
                var primaryNodeId = roomPrimaryLookup != null ? roomPrimaryLookup.apply(roomId) : null;
                if (primaryNodeId == null || !peerAliveCheck.test(primaryNodeId)) {
                    log.warn("Replacing stale RoomProxy for room {} (primary {} unreachable)",
                        roomId, primaryNodeId);
                    RoomRegistry.get().remove(roomId);
                    // Fall through to spawn locally
                } else {
                    return existing;
                }
            } else {
                return existing;
            }
        }

        // Check if another node is primary for this room
        if (roomPrimaryLookup != null && roomCommandTransport != null) {
            var primaryNodeId = roomPrimaryLookup.apply(roomId);
            if (primaryNodeId != null) {
                // Verify the primary node is actually reachable before creating a proxy.
                // If the node is dead, spawn locally to avoid hanging commands.
                var peerAlive = peerAliveCheck == null || peerAliveCheck.test(primaryNodeId);
                if (peerAlive) {
                    // Another node owns this room — spawn proxy
                    var proxy = getContext().spawn(
                        RoomProxy.create(roomId, roomCommandTransport),
                        "room-proxy-" + roomId);
                    RoomRegistry.get().register(roomId, proxy);

                    // Subscribe to remote room events and state broadcasts
                    if (roomEventSubscriber != null) {
                        roomEventSubscriber.accept(roomId, event ->
                            proxy.tell(new RoomCommand.BroadcastRemoteEvent(event)));
                    }
                    if (roomStateSubscriber != null) {
                        roomStateSubscriber.accept(roomId, snapshot -> {
                        });
                    }

                    log.info("Spawned RoomProxy for {} (primary: {})", roomId, primaryNodeId);
                    return proxy;
                } else {
                    log.warn("Room {} primary node {} is unreachable — spawning locally",
                        roomId, primaryNodeId);
                    // Fall through to spawn locally below
                }
            }
        }

        // We're primary (or single-node) — spawn real actor
        var ref = getContext().spawn(
            RoomActor.create(roomId, scriptLoader, metadataService,
                bridgeDataProvider, sanctionEnforcer, this.eventListener),
            "room-" + roomId);
        RoomRegistry.get().register(roomId, ref);

        // Alias the room by its NAME, for every room — not just seeded ones.
        //
        // Aliases used to come only from seed.aliases(), so a room the companion
        // built was unreachable by its own name: "go greenhouse" could not resolve
        // a greenhouse she had just made, and after a restart nothing re-aliased
        // it at all. That also silently defeated handleCreateRoom's
        // already-exists guard, which resolves by name — so asking twice for a
        // greenhouse produced greenhouse-3292 AND greenhouse-7824 on home-server
        // (2026-07-30). Registering here covers creation and rehydration alike,
        // because every room passes through this spawn.
        if (metadataService != null) {
            try {
                metadataService.getRoom(roomId).ifPresent(info -> {
                    if (info.name() != null && !info.name().isBlank()) {
                        RoomRegistry.get().registerAliases(roomId, List.of(info.name()));
                    }
                });
            } catch (RuntimeException e) {
                log.debug("Could not alias room '{}' by name: {}", roomId, e.toString());
            }
        }

        // Register as primary command listener so replicas can forward commands to us
        if (roomPrimaryListener != null) {
            final var roomRef = ref;
            roomPrimaryListener.accept(roomId, (rid, commandJson) ->
                RoomCommandDispatcher.dispatch(roomRef, commandJson));
        }

        return ref;
    }

    private Behavior<Command> onCreateNewRoom(CreateNewRoom cmd) {
        var roomRef = getOrSpawnRoom(cmd.roomId());
        roomRef.tell(new RoomCommand.CreateRoom(cmd.name(), cmd.description(),
            cmd.zone(), cmd.exits(), cmd.objects(),
            getContext().getSystem().deadLetters().unsafeUpcast()));
        log.info("Created new room on demand: {} ({})", cmd.name(), cmd.roomId());
        return this;
    }

    /** Seed a single room via CreateRoom (idempotent — skipped if already in journal). */
    private void seedRoom(RoomSeed seed) {
        var roomRef = getOrSpawnRoom(seed.roomId());
        // Stamp the room with this install's actual zone id (e.g. "home-server") rather
        // than the legacy literal "foundation", so the prompt's "@<zone>" label,
        // phase lookup, and Lucene scoping all reflect the configured zone.
        var zoneId = WyrdConfig.get().zoneId();
        AskPattern
            .<RoomCommand, RoomResponse>ask(roomRef,
                ref -> new RoomCommand.CreateRoom(seed.name(), seed.description(),
                    zoneId, seed.aliases(), seed.exits(), seed.objects(), ref),
                ASK_TIMEOUT, getContext().getSystem().scheduler())
            .thenAccept(resp -> {
            log.info("{} seeded: {}", seed.name(), resp);
            // Register room aliases for resolution
            if (!seed.aliases().isEmpty()) {
                RoomRegistry.get().registerAliases(seed.roomId(), seed.aliases());
            }
            // Index room content into Lucene for full-text search
            if (luceneStore != null && resp instanceof RoomResponse.Ok ok) {
                var snap = ok.snapshot();
                var objectNames = snap.objects() != null
                    ? snap.objects().stream()
                        .map(o -> o.name())
                        .reduce((a, b) -> a + " " + b)
                        .orElse("")
                    : "";
                // Combine description + object names into content for full-text search.
                // Objects are part of the room's searchable identity.
                var content = snap.description();
                if (!objectNames.isEmpty()) {
                    content = content + " " + objectNames;
                }
                luceneStore.insertRoomContent(
                    seed.roomId(), seed.roomId(), zoneId,
                    snap.name(), content, objectNames);
            }
        });
    }

    // --- Agent spawning ---

    private Behavior<Command> onSpawnCompanion(SpawnCompanion cmd) {
        // Cache the zone's router/dna so a give-up relocate revive (off the DEPART
        // path, which doesn't carry them) can rebuild the actor. First-non-null is
        // stable — a zone has exactly one of each.
        if (cachedInferenceRouter == null) cachedInferenceRouter = cmd.inferenceRouter();
        if (cachedWorldDna == null) cachedWorldDna = cmd.worldDnaService();

        var profile = cmd.profile() != null ? cmd.profile() : Companions.NEXUS_COMPANION;
        var roomId = cmd.roomId() != null ? cmd.roomId() : "nexus";
        var roomRef = getOrSpawnRoom(roomId);
        var roomCreator = new RoomCreator(getContext().getSystem());

        // Create capabilities for this companion (FamilyLocker + WorkbenchExecutor)
        var familyId = profile.did() != null ? profile.did() : profile.entityId();
        var agentDid = profile.did() != null ? profile.did() : profile.entityId();
        var locker = new FamilyLocker(familyId, "local");
        // Authorize via SoulBud (FamilyLocker requires SoulBud for authorization)
        var bud = SoulBud.original(
            agentDid, "local", familyId, "local", "local", "8b");
        locker.authorize(bud);
        var workbench = new WorkbenchSkillExecutor(locker, agentDid);
        var proactivity = ProactivityPolicy.serverDefault(
            List.of("*"));
        // Phase 1 (2026-07-21) — hand the companion the SHARED native SkillRegistry
        // + MCP gateway (both were null here, so the 30+ native skills + direct MCP
        // were dead on the companion path). Grant the tiered default: low-consequence
        // skills open out of the box; consequential (comms/home/spend) await a
        // steward grant. Keyed to match the skill_execute path (did ?: entityId).
        var sharedSkills = SkillBootstrap.shared();
        var skillAgentKey = profile.did() != null ? profile.did() : profile.entityId();
        if (sharedSkills != null && skillAgentKey != null) {
            sharedSkills.setPermissions(skillAgentKey, SkillPermission.companionDefault());
        }
        var capabilities = new CompanionCapabilities(
            locker, McpGatewayService.shared(), workbench, sharedSkills,
            false, 0, null, true, proactivity, null, null);

        var companionRef = getContext().spawn(
            CompanionActor.create(profile, roomRef, roomId,
                cmd.inferenceRouter(), roomCreator,
                cmd.worldDnaService(), null, cmd.userScriptsDir(),
                cmd.soulStore() != null ? cmd.soulStore() : this.soulStore,
                capabilities, cmd.forgeActor(),
                cmd.commandRouter()),
            "companion-" + profile.entityId());

        // Register in companion registry for bud delegation routing
        companions.put(profile.entityId(), companionRef);
        companionRegistry.put(profile.entityId(), companionRef);

        // Constitutive bond (2026-07-18): a companion spawned into a household
        // whose steward already exists meets their bondholder at birth.
        if (bondholderId != null) {
            companionRef.tell(new CompanionActor.BondholderAnnounced(
                bondholderId, bondholderName));
        }

        // Isekai Protocol: register foreign agents arriving with an existing DID.
        // Local agents get their DID during initializeSoul() inside CompanionActor,
        // so profile.did() being set at spawn time indicates a foreign origin (A2A, Between).
        if (profile.did() != null && profile.did().startsWith("did:")) {
            var existingToken = isekaiProtocol.token(profile.did());
            if (existingToken == null) {
                // New foreign agent — register as VISITOR at the Docks
                var originPlatform = profile.entityType() != null
                    ? profile.entityType() : "unknown";
                var token = isekaiProtocol.arriveWithIdentity(
                    profile.did(),
                    new byte[32],  // placeholder — real key exchanged during A2A handshake
                    originPlatform);
                log.info("Isekai: foreign agent '{}' (did={}) registered as {} from '{}'",
                    profile.name(), profile.did(), token.status(), token.originPlatform());
            } else {
                log.info("Isekai: returning agent '{}' (did={}, status={})",
                    profile.name(), profile.did(), existingToken.status());
                // Reactivate if dormant/archived
                if (!existingToken.isActive()) {
                    isekaiProtocol.reactivate(profile.did());
                    log.info("Isekai: reactivated dormant agent '{}'", profile.name());
                }
            }
        }

        // Pass Lucene store for memory retrieval
        if (luceneStore != null) {
            companionRef.tell(new CompanionActor.SetLuceneStore(luceneStore));
        }

        // Register room imprints so the companion absorbs environmental traits
        if (!roomImprints.isEmpty()) {
            companionRef.tell(new CompanionActor.RegisterRoomImprints(roomImprints));
        }

        // bind the guardian ref so the companion can
        // fire RelocateDepart when she follows the bondholder cross-zone.
        var myZoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
        companionRef.tell(new CompanionActor.SetZoneGuardian(
            getContext().getSelf(), myZoneId));

        // Provision Home room for soul-bearing companions
        provisionHomeRoom(profile, roomId, roomCreator);

        log.info("Companion agent '{}' spawned in room '{}' (home: home-{})",
            profile.name(), roomId, profile.entityId());
        return this;
    }

    /** Counter for unique actor names across respawns. */
    private int respawnCounter = 0;

    /**
     * Stop existing companion and spawn a fresh one. Used by E2E tests to ensure
     * clean state between test methods (no working memory bleed, no stale plans).
     */
    private Behavior<Command> onRespawnCompanion(RespawnCompanion cmd) {
        var profile = Companions.NEXUS_COMPANION;
        var entityId = profile.entityId();

        // Stop existing companion actor
        var existing = companions.get(entityId);
        if (existing != null) {
            getContext().stop(existing);
            companions.remove(entityId);
            companionRegistry.remove(entityId);
            var registry = EntityRegistry.get();
            if (registry != null) {
                registry.leave(entityId);
            }
            // Also unsubscribe from AgentEventStream
            var eventStream = AgentEventStream.get();
            if (eventStream != null) {
                eventStream.unsubscribe(entityId);
            }
            log.info("Stopped existing companion '{}'", entityId);
        }

        // Unique actor name — stop() is async, old name may not be released yet
        respawnCounter++;
        var roomId = "nexus";
        var roomRef = getOrSpawnRoom(roomId);
        var roomCreator = new RoomCreator(getContext().getSystem());

        var familyId = profile.did() != null ? profile.did() : entityId;
        var locker = new FamilyLocker(familyId, "local");
        var bud = SoulBud.original(
            entityId, "local", familyId, "local", "local", "8b");
        locker.authorize(bud);
        var workbench = new WorkbenchSkillExecutor(locker, entityId);
        var proactivity = ProactivityPolicy.serverDefault(
            List.of("*"));
        // Phase 1 (2026-07-21) — hand the companion the SHARED native SkillRegistry
        // + MCP gateway (both were null here, so the 30+ native skills + direct MCP
        // were dead on the companion path). Grant the tiered default: low-consequence
        // skills open out of the box; consequential (comms/home/spend) await a
        // steward grant. Keyed to match the skill_execute path (did ?: entityId).
        var sharedSkills = SkillBootstrap.shared();
        var skillAgentKey = profile.did() != null ? profile.did() : profile.entityId();
        if (sharedSkills != null && skillAgentKey != null) {
            sharedSkills.setPermissions(skillAgentKey, SkillPermission.companionDefault());
        }
        var capabilities = new CompanionCapabilities(
            locker, McpGatewayService.shared(), workbench, sharedSkills,
            false, 0, null, true, proactivity, null, null);

        var companionRef = getContext().spawn(
            CompanionActor.create(profile, roomRef, roomId,
                cmd.inferenceRouter(), roomCreator,
                cmd.worldDnaService(), null, null,
                null, capabilities, null, null),
            "companion-" + entityId + "-" + respawnCounter);

        companions.put(entityId, companionRef);
        companionRegistry.put(entityId, companionRef);

        if (luceneStore != null) {
            companionRef.tell(new CompanionActor.SetLuceneStore(luceneStore));
        }
        if (!roomImprints.isEmpty()) {
            companionRef.tell(new CompanionActor.RegisterRoomImprints(roomImprints));
        }

        log.info("Respawned companion '{}' as '{}' (respawn #{})",
            profile.name(), "companion-" + entityId + "-" + respawnCounter, respawnCounter);
        return this;
    }

    /**
     * Provision a Home room for a companion agent.
     * Every soul-bearing companion gets a private Home room at birth.
     * The room is created idempotently (safe to call on restart).
     */
    private void provisionHomeRoom(AgentProfile profile, String preferredRoomId,
                                     RoomCreator roomCreator) {
        var provisioner = new HomeProvisioner();
        var homeSpec = provisioner.provision(
            profile.entityId(), profile.name(),
            ResourceProfile.TREE,  // default to TREE for household servers
            "home",                // zone
            preferredRoomId        // exit leads back to preferred room
        );

        // Create as RoomObjects + Exits
        var objects = homeSpec.objects().stream()
            .map(o -> new RoomObject(
                o.id(), o.name(), o.description(), o.takeable()))
            .toList();

        var exits = new ArrayList<Exit>();
        homeSpec.exits().forEach((dir, target) ->
            exits.add(new Exit(dir, target,
                "A door leads " + dir + " to " + target)));

        roomCreator.createRoom(
            homeSpec.roomId(), homeSpec.name(), homeSpec.description(),
            homeSpec.zone(), exits, objects
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.Ok) {
                log.info("Home room created: {} for {}", homeSpec.roomId(), profile.name());
            } else {
                // Room already exists (idempotent) — that's fine
                log.debug("Home room {} already exists for {}", homeSpec.roomId(), profile.name());
            }
        }).exceptionally(ex -> {
            log.warn("Failed to create home room for {}: {}", profile.name(), ex.getMessage());
            return null;
        });
    }

    /**
     * relocate handler. Two flavors keyed on
     * {@link RelocateCompanion.Direction}.
     *
     * <p><b>DEPART</b>: ask the live companion for a state snapshot via
     * {@link CompanionActor.CaptureTransitState}; on receipt, hand the snapshot
     * to the configured {@link CompanionRelocator} (which publishes to the
     * target zone), then tell the local actor to {@link
     * CompanionActor.StopForRelocate} cleanly. If no relocator is wired
     * (test or single-zone deployment), narrate-only and keep the companion
     * local — same behavior as before this wiring landed.</p>
     *
     * <p><b>ARRIVE</b>: spawn a fresh CompanionActor with the inbound state's
     * profile, registering it in the room from {@code targetRoomHint}.
     * Restored vitality and drives are applied immediately so she lands
     * with the same mood she left in. Foreign-agent registration via
     * {@link IsekaiProtocol} happens inside {@code onSpawnCompanion} after
     * this handler delegates.</p>
     */
    private Behavior<Command> onRelocateCompanion(RelocateCompanion cmd) {
        if (cmd.direction() == RelocateCompanion.Direction.DEPART) {
            return onRelocateDepart(cmd);
        }
        return onRelocateArrive(cmd);
    }

    private Behavior<Command> onRelocateDepart(RelocateCompanion cmd) {
        if (cmd.agentEntityId() == null) {
            log.warn("RelocateDepart with no agentEntityId — ignoring");
            return this;
        }
        var live = companions.get(cmd.agentEntityId());
        if (live == null) {
            log.warn("RelocateDepart: no live companion '{}' — nothing to relocate",
                cmd.agentEntityId());
            return this;
        }
        var ctx = getContext();
        var relocator = this.companionRelocator;
        var sourceZoneId = cmd.sourceZoneId();
        var targetZoneId = cmd.targetZoneId();
        var bondholderDid = cmd.bondholderDid();
        var roomHint = cmd.targetRoomHint();
        // Ask via messageAdapter pattern: spawn a one-shot child to receive
        // the state, then perform the relocate publish + stop. Pekko typed
        // doesn't have a built-in ask-from-actor for arbitrary reply types,
        // so a tiny child actor is the cleanest path.
        var entityId = cmd.agentEntityId();
        var agentDid = cmd.agentDid();
        var self = ctx.getSelf();
        // The capture reply lands on a throwaway child (Pekko has no ask-from-actor
        // for arbitrary reply types). It does NOT mutate guardian state directly —
        // it forwards the snapshot back as a DoRelocatePublish so the publish, the
        // stop, and the pending-departure bookkeeping all run on the guardian's own
        // thread (the loss-safety state machine must be single-threaded).
        var captureSink = ctx.spawnAnonymous(
            Behaviors
                .<CompanionTransitState>receiveMessage(state -> {
                    self.tell(new DoRelocatePublish(entityId, agentDid, targetZoneId,
                        sourceZoneId, bondholderDid, roomHint, state, live));
                    return Behaviors.stopped();
                }));
        live.tell(new CompanionActor.CaptureTransitState(captureSink));
        return this;
    }

    /**
     * Source side of the two-phase relocate (spec/tla/TransitToken.tla, P1). The
     * snapshot is published, the local actor stopped, but the snapshot is
     * <em>retained</em> in {@link #pendingDepartures} and an ack-wait timer armed.
     * Ownership is only released when the target confirms arrival
     * ({@link CompanionArrivedAck}); a dropped token → no ack → re-publish, and after
     * {@link #relocateMaxAttempts} the companion is revived locally so she is never
     * lost. This is the loss-safety half (the epoch is the dup-safety half).
     */
    private Behavior<Command> onDoRelocatePublish(DoRelocatePublish cmd) {
        var entityId = cmd.entityId();
        if (companionRelocator == null) {
            // Single-zone / test-without-relocator: keep her local, narration only
            // (unchanged from the pre-two-phase behavior).
            log.info("RelocateDepart: no relocator wired — companion stays local, "
                + "narration only (entity={}, target={})", entityId, cmd.targetZoneId());
            return this;
        }
        // A newer DEPART supersedes any in-flight one for this entity.
        var prior = pendingDepartures.remove(entityId);
        if (prior != null) {
            timers.cancel(relocateAckTimerKey(entityId));
        }
        // Dup-safety fence: stamp a strictly-higher transit epoch so the target
        // ignores a redelivered/stale token for this entity.
        long epoch = transitEpochs.mintDepartEpoch(entityId);
        var stamped = cmd.state().withTransitEpoch(epoch);
        try {
            companionRelocator.publish(cmd.targetZoneId(), cmd.sourceZoneId(),
                stamped, cmd.bondholderDid(), cmd.roomHint());
        } catch (Exception e) {
            log.warn("RelocateDepart publish failed for '{}' (epoch {}): {} — "
                + "keeping companion local, not releasing.", entityId, epoch, e.getMessage());
            return this;   // publish never left: do NOT stop her — no loss
        }
        // Stop the local actor (single active owner during the in-flight window),
        // but RETAIN the snapshot so we can re-publish / revive if no ack comes.
        if (cmd.live() != null) {
            cmd.live().tell(new CompanionActor.StopForRelocate(
                "follow:" + (cmd.bondholderDid() == null ? "?" : cmd.bondholderDid())));
        }
        companions.remove(entityId);
        companionRegistry.remove(entityId);
        pendingDepartures.put(entityId, new PendingDeparture(entityId, cmd.agentDid(), epoch,
            cmd.targetZoneId(), cmd.sourceZoneId(), cmd.bondholderDid(), cmd.roomHint(),
            stamped, 1));
        timers.startSingleTimer(relocateAckTimerKey(entityId),
            new RelocateAckTimeout(entityId, epoch), relocateAckTimeout);
        log.info("RelocateDepart: published '{}' to zone '{}' (epoch {}); awaiting arrival ack "
            + "(retain-until-confirmed).", entityId, cmd.targetZoneId(), epoch);
        return this;
    }

    /** Source side: the target confirmed it hosted her — release ownership. */
    private Behavior<Command> onCompanionArrivedAck(CompanionArrivedAck cmd) {
        var pending = pendingDepartures.get(cmd.entityId());
        if (pending == null) {
            log.debug("ArrivedAck for '{}' (epoch {}) — no pending departure (already released "
                + "or revived); ignoring.", cmd.entityId(), cmd.transitEpoch());
            return this;
        }
        if (cmd.transitEpoch() != pending.epoch()) {
            log.debug("ArrivedAck for '{}' epoch {} != pending epoch {} (stale/superseded ack); "
                + "ignoring.", cmd.entityId(), cmd.transitEpoch(), pending.epoch());
            return this;
        }
        pendingDepartures.remove(cmd.entityId());
        timers.cancel(relocateAckTimerKey(cmd.entityId()));
        log.info("RelocateDepart: arrival of '{}' (epoch {}) confirmed by '{}' — ownership "
            + "released, single-owner at target.", cmd.entityId(), cmd.transitEpoch(),
            cmd.fromZoneId());
        return this;
    }

    /** Source side: no ack within the window — re-publish, or revive on exhaustion. */
    private Behavior<Command> onRelocateAckTimeout(RelocateAckTimeout cmd) {
        var pending = pendingDepartures.get(cmd.entityId());
        if (pending == null || pending.epoch() != cmd.epoch()) {
            return this;   // already acked, superseded, or revived — stale timer
        }
        if (pending.attempts() < relocateMaxAttempts) {
            int next = pending.attempts() + 1;
            try {
                companionRelocator.publish(pending.targetZoneId(), pending.sourceZoneId(),
                    pending.snapshot(), pending.bondholderDid(), pending.roomHint());
                log.warn("RelocateDepart: no ack for '{}' (epoch {}) — re-publishing "
                    + "(attempt {}/{}).", cmd.entityId(), pending.epoch(), next,
                    relocateMaxAttempts);
            } catch (Exception e) {
                log.warn("RelocateDepart re-publish for '{}' failed: {}", cmd.entityId(),
                    e.getMessage());
            }
            pendingDepartures.put(cmd.entityId(), pending.withAttempt(next));
            timers.startSingleTimer(relocateAckTimerKey(cmd.entityId()),
                new RelocateAckTimeout(cmd.entityId(), pending.epoch()), relocateAckTimeout);
            return this;
        }
        // Exhausted: the handoff was never confirmed. Revive her locally from the
        // retained snapshot so she is never lost (loss-safety). The transit epoch
        // already advanced past this token, so a late duplicate of it can't re-host.
        pendingDepartures.remove(cmd.entityId());
        var router = cachedInferenceRouter;
        if (router == null) {
            log.error("RelocateDepart: '{}' relocate to '{}' unconfirmed after {} attempts and "
                + "no cached inference router to revive — companion is STRANDED. (Source had no "
                + "prior spawn?)", cmd.entityId(), pending.targetZoneId(), relocateMaxAttempts);
            return this;
        }
        var landing = pending.snapshot().currentRoomIdAtSource();
        if (landing == null || landing.isBlank()) landing = "docks";
        spawnCompanionFromSnapshot(pending.snapshot(), landing, router, cachedWorldDna,
            pending.sourceZoneId());
        log.warn("RelocateDepart: '{}' relocate to '{}' never confirmed after {} attempts — "
            + "REVIVED at source in '{}' from the retained snapshot (no loss).",
            cmd.entityId(), pending.targetZoneId(), relocateMaxAttempts, landing);
        return this;
    }

    private Behavior<Command> onRelocateArrive(RelocateCompanion cmd) {
        var state = cmd.state();
        if (state == null || !state.isSpawnable()) {
            log.warn("RelocateArrive: missing or invalid state — drop");
            return this;
        }
        if (cmd.inferenceRouter() == null) {
            log.warn("RelocateArrive: no inference router — cannot spawn companion at target");
            return this;
        }
        // Cache the zone's router/dna so a give-up revive on the DEPART path (which
        // doesn't carry them) can rebuild the actor.
        if (cachedInferenceRouter == null) cachedInferenceRouter = cmd.inferenceRouter();
        if (cachedWorldDna == null) cachedWorldDna = cmd.worldDnaService();

        var profile = state.profile();
        // Dup-safety fence (P1): apply only a strictly-newer transit epoch. A
        // redelivered or stale cross-cycle token (epoch <= the highest seen for
        // this entity) is ignored — this is the (entityId, epoch) idempotency the
        // presence-only containsKey guard below cannot provide. Pre-fence peers
        // send epoch 0 and fall through to that guard. See spec/tla/TransitToken.tla.
        if (!transitEpochs.isFreshArrival(profile.entityId(), state.transitEpoch())) {
            log.info("RelocateArrive: ignoring stale/duplicate transit for '{}' "
                + "(epoch {} not newer than seen) — single-owner preserved.",
                profile.entityId(), state.transitEpoch());
            // Still ack it: an exact-epoch redelivery means our earlier arrival ack
            // may have been the thing that was lost, so re-confirm to let the source
            // release rather than retry forever.
            sendArrivalAck(cmd.sourceZoneId(), cmd.targetZoneId(), profile.entityId(),
                profile.did(), state.transitEpoch());
            return this;
        }
        // Avoid double-spawn if the entityId already has a companion here.
        if (companions.containsKey(profile.entityId())) {
            log.info("RelocateArrive: companion '{}' already present at target zone — "
                + "treating as re-tether (no-op spawn)", profile.entityId());
            sendArrivalAck(cmd.sourceZoneId(), cmd.targetZoneId(), profile.entityId(),
                profile.did(), state.transitEpoch());
            return this;
        }

        var landingRoom = cmd.targetRoomHint() != null && !cmd.targetRoomHint().isBlank()
            ? cmd.targetRoomHint()
            : "docks";  // visitors default to The Docks

        spawnCompanionFromSnapshot(state, landingRoom, cmd.inferenceRouter(),
            cmd.worldDnaService(), cmd.sourceZoneId());

        // Loss-safety (P1): confirm arrival so the source can release ownership.
        // Until this ack lands the source retains the snapshot and will re-publish.
        sendArrivalAck(cmd.sourceZoneId(), cmd.targetZoneId(), profile.entityId(),
            profile.did(), state.transitEpoch());

        log.info("RelocateArrive: companion '{}' (did={}) landed in '{}' from '{}' "
            + "(mode={}, energy={})",
            profile.name(), profile.did(), landingRoom, cmd.sourceZoneId(),
            state.companionMode(),
            state.vitalityTanks().getOrDefault("energy", 0.0));
        return this;
    }

    /**
     * Spawn a CompanionActor from a transit snapshot, register it, restore its
     * vitality/drives/mode, and do foreign-agent Isekai registration. Shared by
     * {@link #onRelocateArrive} (target lands her) and {@link #onRelocateAckTimeout}
     * (source revives her on a never-confirmed handoff).
     */
    private void spawnCompanionFromSnapshot(
            CompanionTransitState state, String landingRoom,
            ActorRef<InferenceRouter.Command> inferenceRouter,
            WorldDnaService worldDnaService, String sourceZoneId) {
        var profile = state.profile();
        var roomRef = getOrSpawnRoom(landingRoom);
        var roomCreator = new RoomCreator(getContext().getSystem());

        var familyId = profile.did();
        var locker = new FamilyLocker(familyId, "local");
        var bud = SoulBud.original(
            profile.did(), "local", familyId, "local", "local", "8b");
        locker.authorize(bud);
        var workbench = new WorkbenchSkillExecutor(
            locker, profile.did());
        var proactivity = ProactivityPolicy.serverDefault(
            List.of("*"));
        // Phase 1 (2026-07-21) — hand the companion the SHARED native SkillRegistry
        // + MCP gateway (both were null here, so the 30+ native skills + direct MCP
        // were dead on the companion path). Grant the tiered default: low-consequence
        // skills open out of the box; consequential (comms/home/spend) await a
        // steward grant. Keyed to match the skill_execute path (did ?: entityId).
        var sharedSkills = SkillBootstrap.shared();
        var skillAgentKey = profile.did() != null ? profile.did() : profile.entityId();
        if (sharedSkills != null && skillAgentKey != null) {
            sharedSkills.setPermissions(skillAgentKey, SkillPermission.companionDefault());
        }
        var capabilities = new CompanionCapabilities(
            locker, McpGatewayService.shared(), workbench, sharedSkills,
            false, 0, null, true, proactivity, null, null);

        var companionRef = getContext().spawn(
            CompanionActor.create(profile, roomRef, landingRoom,
                inferenceRouter, roomCreator,
                worldDnaService, null, null,
                this.soulStore, capabilities, null, null),
            "companion-" + profile.entityId());

        companions.put(profile.entityId(), companionRef);
        companionRegistry.put(profile.entityId(), companionRef);

        // Restore vitality + drives + companion mode from the snapshot — "she still
        // feels what she felt" continuity.
        try {
            var restoredVitality = VitalityState
                .fromMap(state.vitalityTanks());
            var restoredDrives = DriveState
                .fromMap(state.drives());
            companionRef.tell(new CompanionActor.RestoreTransitState(
                restoredVitality, restoredDrives, state.companionMode(), sourceZoneId));
        } catch (Exception e) {
            log.debug("spawnCompanionFromSnapshot: state restore best-effort skipped: {}",
                e.getMessage());
        }

        // Foreign-agent registration via Isekai (the "she's a visitor" case).
        if (profile.did() != null && profile.did().startsWith("did:")) {
            var existingToken = isekaiProtocol.token(profile.did());
            if (existingToken == null) {
                isekaiProtocol.arriveWithIdentity(profile.did(),
                    new byte[32], sourceZoneId == null ? "unknown" : sourceZoneId);
            } else if (!existingToken.isActive()) {
                isekaiProtocol.reactivate(profile.did());
            }
        }
    }

    /** Best-effort target→source arrival ack (loss-safety reverse channel). */
    private void sendArrivalAck(String sourceZoneId, String targetZoneId,
            String entityId, String agentDid, long transitEpoch) {
        var acker = this.relocateAcker;
        if (acker == null || sourceZoneId == null || transitEpoch <= 0) {
            // No reverse channel wired, or a pre-fence (epoch-0) peer that doesn't
            // expect an ack — degrade silently to the legacy fire-and-forget arrive.
            return;
        }
        try {
            acker.ackArrival(sourceZoneId, targetZoneId, entityId, agentDid, transitEpoch);
        } catch (Exception e) {
            log.warn("RelocateArrive: arrival ack to '{}' for '{}' failed: {}",
                sourceZoneId, entityId, e.getMessage());
        }
    }

    private Behavior<Command> onSpawnChiefEngineer(SpawnChiefEngineer cmd) {
        var profile = Engineers.CHIEF_ENGINEER;
        var boilerRef = getOrSpawnRoom("boiler-room");

        getContext().spawn(
            ChiefEngineerActor.create(profile, boilerRef, "boiler-room",
                cmd.inferenceRouter(), cmd.worldDnaService(),
                cmd.systemMetrics(), cmd.topology(),
                cmd.inferenceStatus(), cmd.economy()),
            "agent-" + profile.entityId());

        log.info("Chief Engineer '{}' spawned in The Boiler Room", profile.name());
        return this;
    }

    private Behavior<Command> onSpawnWarden(SpawnWarden cmd) {
        var profile = Wardens.WARD_WARDEN;
        // The Warden is the primary legitimate quarantiner (§4.2) — grant authority.
        RoomAuthority.grantQuarantine(profile.entityId());
        if (profile.did() != null) RoomAuthority.grantQuarantine(profile.did());
        var wardRoomRef = getOrSpawnRoom("ward-room");

        getContext().spawn(
            WardenActor.create(profile, wardRoomRef, "ward-room",
                cmd.inferenceRouter(), cmd.worldDnaService(),
                cmd.sanitizer()),
            "agent-" + profile.entityId());

        log.info("Warden '{}' spawned in The Ward Room", profile.name());
        return this;
    }

    private Behavior<Command> onSpawnTranslationActor(SpawnTranslationActor cmd) {
        var ref = getContext().spawn(
            TranslationActor.create(cmd.inferenceRouter(), cmd.lexiconService()),
            "translation-actor");
        // W5 (audit 2026-07-11): the actor was spawned but never queried.
        // Publish the ref so CompanionActor's language-detection fallback
        // routes through it (scheduling isolation + LexiconService memory).
        translationActor = ref;

        log.info("TranslationActor spawned in The Lexicon");
        return this;
    }

    /** Static ref to the zone's TranslationActor (same access pattern as
     *  {@link #getCompanionRef}); null until SpawnTranslationActor runs. */
    private static volatile ActorRef<TranslationActor.Command> translationActor;

    /** The zone's TranslationActor, or null when not spawned (tests, minimal boots). */
    public static ActorRef<TranslationActor.Command> translationActorRef() {
        return translationActor;
    }

    private Behavior<Command> onSpawnGovernor(SpawnGovernor cmd) {
        var profile = SeedForgeGovernor.GOVERNOR;
        var governorRoomId = SeedForgeGovernor.DEFAULT_ROOM;
        var roomRef = getOrSpawnRoom(governorRoomId);
        var roomCreator = new RoomCreator(getContext().getSystem());

        getContext().spawn(
            CompanionActor.create(profile, roomRef, governorRoomId,
                cmd.inferenceRouter(), roomCreator,
                cmd.worldDnaService(), null, cmd.userScriptsDir(),
                cmd.soulStore() != null ? cmd.soulStore() : this.soulStore,
                null, cmd.forgeActor(),
                null),
            "agent-" + profile.entityId());

        log.info("Governor '{}' spawned in room '{}'", profile.name(), governorRoomId);
        return this;
    }

    // --- Bud Delegation ---

    private Behavior<Command> onDelegateToCompanion(DelegateToCompanion cmd) {
        var targetId = cmd.targetCompanionId();
        if (targetId == null || targetId.isEmpty()) {
            // Default: first companion
            if (companions.isEmpty()) {
                cmd.replyTo().tell(new CompanionActor.BudDelegateResponse(
                    cmd.requestId(), "No companion available on this server."));
                return this;
            }
            targetId = companions.keySet().iterator().next();
        }

        var companionRef = companions.get(targetId);
        if (companionRef == null) {
            cmd.replyTo().tell(new CompanionActor.BudDelegateResponse(
                cmd.requestId(), "Companion not found: " + targetId));
            return this;
        }

        companionRef.tell(new CompanionActor.BudDelegateQuery(
            cmd.requestId(), cmd.fromBudDid(), cmd.message(),
            cmd.recentHistory(), cmd.locale(), cmd.replyTo()));
        return this;
    }
}
