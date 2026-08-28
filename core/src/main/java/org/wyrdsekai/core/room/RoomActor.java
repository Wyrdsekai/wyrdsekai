package org.wyrdsekai.core.room;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.RecoveryCompleted;
import org.apache.pekko.persistence.typed.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.common.model.*;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.LocalCommandRouter;
import org.wyrdsekai.core.host.HostActionService;
import org.wyrdsekai.core.soul.ForgeRoomBridge;
import org.wyrdsekai.core.study.StudyShellBridge;
import org.wyrdsekai.core.ambient.AmbientRenderer;
import org.wyrdsekai.core.ambient.WorldClock;
import org.wyrdsekai.core.coding.CodingItemMetadata;
import org.wyrdsekai.core.coding.CodingItemRegistry;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.item.EquipmentService;
import org.wyrdsekai.core.item.EquipmentState;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.core.item.ItemProviderRegistry;
import org.wyrdsekai.core.item.ItemScriptResponse;
import org.wyrdsekai.core.item.ScriptedItemDef;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.core.oracle.OracleBridge;
import org.wyrdsekai.core.oracle.OracleForgeHook;
import org.wyrdsekai.core.parlor.ParlorManager;
import org.wyrdsekai.core.parlor.ParlorPresenceMode;
import org.wyrdsekai.core.persistence.ConfigApplyCoordinator;
import org.wyrdsekai.core.persistence.RoomMetadataService;
import org.wyrdsekai.core.story.PostureTemplates;
import org.wyrdsekai.scripting.api.BridgeDataProvider;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;
import org.wyrdsekai.scripting.loader.ScriptLoader;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * EventSourcedBehavior for a single room.
 * The roomId is the entity key for cluster sharding.
 *
 * Commands → validate → persist RoomEvent → update RoomState → notify subscribers.
 * Optionally runs room scripts (GraalJS) on events for dynamic behavior.
 *
 * Note: Previously used ReplicatedEventSourcedBehavior, but that does not stash
 * commands during persist (designed for concurrent multi-replica writes). On a
 * single node this caused SQLITE_CONSTRAINT_UNIQUE errors from duplicate sequence
 * numbers. Plain EventSourcedBehavior properly stashes commands during persist.
 *
 * Persistence IDs retain the "|local" suffix for backward compatibility with
 * existing journal entries written by the replicated variant.
 */
public class RoomActor extends EventSourcedBehavior<RoomCommand, RoomEvent, RoomState> {

    private static final Logger log = LoggerFactory.getLogger(RoomActor.class);

    private final String roomId;
    private final ActorContext<RoomCommand> context;
    private final Map<ActorRef<RoomNotification>, VisibilityLevel> subscribers = new HashMap<>();
    // entityId -> every live surface-subscription for that entity. One account
    // may hold several surfaces at once (CLI + SSH + web), and a directed event
    // (whisper) must reach all of them — not just the latest. Was a
    // last-write-wins single ref, which silently dropped whispers to every
    // surface but the most-recent one.
    private final Map<String, Set<ActorRef<RoomNotification>>> entitySubscribers = new HashMap<>();
    private final RoomScriptEngine scriptEngine; // nullable
    private final ScriptLoader scriptLoader; // nullable — kept for SetBehaviorScript
    private final RoomMetadataService metadataService; // nullable
    private final SanctionEnforcer sanctionEnforcer; // nullable
    private final RoomEventListener eventListener; // nullable — Phase 2 conversation protection
    private final TimerScheduler<RoomCommand> timers; // §31 script timers

    /**
     * §31 — script-scheduled timers currently live for this room
     * (timerId → hookName). Bounded by {@link #MAX_ROOM_TIMERS} so a
     * runaway script that schedules from its own timer hook can't
     * accumulate unbounded actor timers.
     */
    private final Map<String, String> activeTimers = new LinkedHashMap<>();
    private static final int MAX_ROOM_TIMERS = 16;

    private RoomActor(PersistenceId persistenceId, String roomId,
                      ActorContext<RoomCommand> context,
                      TimerScheduler<RoomCommand> timers,
                      ScriptLoader scriptLoader,
                      RoomMetadataService metadataService,
                      BridgeDataProvider bridgeDataProvider,
                      SanctionEnforcer sanctionEnforcer,
                      RoomEventListener eventListener) {
        super(persistenceId);
        this.roomId = roomId;
        this.context = context;
        this.timers = timers;
        this.scriptLoader = scriptLoader;
        this.metadataService = metadataService;
        this.sanctionEnforcer = sanctionEnforcer;
        this.eventListener = eventListener;
        this.scriptEngine = scriptLoader != null
            ? new RoomScriptEngine(roomId, scriptLoader, bridgeDataProvider)
            : null;
    }

    public static Behavior<RoomCommand> create(String roomId) {
        return create(roomId, null, null, null, null, null);
    }

    public static Behavior<RoomCommand> create(String roomId, ScriptLoader scriptLoader) {
        return create(roomId, scriptLoader, null, null, null, null);
    }

    public static Behavior<RoomCommand> create(String roomId, ScriptLoader scriptLoader,
                                                RoomMetadataService metadataService,
                                                BridgeDataProvider bridgeDataProvider) {
        return create(roomId, scriptLoader, metadataService, bridgeDataProvider, null, null);
    }

    public static Behavior<RoomCommand> create(String roomId, ScriptLoader scriptLoader,
                                                RoomMetadataService metadataService,
                                                BridgeDataProvider bridgeDataProvider,
                                                SanctionEnforcer sanctionEnforcer) {
        return create(roomId, scriptLoader, metadataService, bridgeDataProvider,
                      sanctionEnforcer, null);
    }

    /** Factory with event listener for Between replication (Phase 2 conversation protection). */
    public static Behavior<RoomCommand> create(String roomId, ScriptLoader scriptLoader,
                                                RoomMetadataService metadataService,
                                                BridgeDataProvider bridgeDataProvider,
                                                SanctionEnforcer sanctionEnforcer,
                                                RoomEventListener eventListener) {
        // Persistence ID uses "|local" suffix for backward compatibility with
        // existing journal entries written by the former ReplicatedEventSourcedBehavior.
        var persistenceId = PersistenceId.ofUniqueId("Room|" + roomId + "|local");
        return Behaviors.setup(actorCtx ->
            Behaviors.withTimers(timers ->
                new RoomActor(persistenceId, roomId, actorCtx, timers, scriptLoader,
                    metadataService, bridgeDataProvider, sanctionEnforcer, eventListener))
        );
    }

    @Override
    public RoomState emptyState() {
        return RoomState.empty(roomId);
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        // Snapshot every 50 state-changing events, keep 2 snapshots.
        // Transient events (Said, Emoted, Whispered, ObjectUsed) are no longer persisted,
        // so the journal only contains state mutations (Enter, Leave, Take, Drop, Create, etc.).
        return RetentionCriteria.snapshotEvery(50, 2);
    }

    @Override
    public CommandHandler<RoomCommand, RoomEvent, RoomState> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(RoomCommand.CreateRoom.class, this::onCreateRoom)
            .onCommand(RoomCommand.AddExit.class, this::onAddExit)
            .onCommand(RoomCommand.LookRoom.class, this::onLookRoom)
            .onCommand(RoomCommand.EnterRoom.class, this::onEnterRoom)
            .onCommand(RoomCommand.LeaveRoom.class, this::onLeaveRoom)
            .onCommand(RoomCommand.PurgeStaleCompanions.class, (state, cmd) -> onPurgeStaleCompanions(state))
            .onCommand(RoomCommand.SayInRoom.class, this::onSayInRoom)
            .onCommand(RoomCommand.EmoteInRoom.class, this::onEmoteInRoom)
            .onCommand(RoomCommand.WhisperInRoom.class, this::onWhisperInRoom)
            .onCommand(RoomCommand.TakeObject.class, this::onTakeObject)
            .onCommand(RoomCommand.DropObject.class, this::onDropObject)
            .onCommand(RoomCommand.UseObject.class, this::onUseObject)
            .onCommand(RoomCommand.SelectHint.class, this::onSelectHint)
            .onCommand(RoomCommand.UpdateHints.class, this::onUpdateHints)
            .onCommand(RoomCommand.Subscribe.class, this::onSubscribe)
            .onCommand(RoomCommand.Unsubscribe.class, this::onUnsubscribe)
            .onCommand(RoomCommand.Quarantine.class, this::onQuarantine)
            .onCommand(RoomCommand.Unquarantine.class, this::onUnquarantine)
            .onCommand(RoomCommand.GetSnapshot.class, this::onGetSnapshot)
            .onCommand(RoomCommand.SetBehaviorScript.class, this::onSetBehaviorScript)
            .onCommand(RoomCommand.UpdateEntityDescription.class, this::onUpdateEntityDescription)
            .onCommand(RoomCommand.UpdateEntityName.class, this::onUpdateEntityName)
            .onCommand(RoomCommand.SetPosture.class, this::onSetPosture)
            .onCommand(RoomCommand.ClearPosture.class, this::onClearPosture)
            .onCommand(RoomCommand.BroadcastRemoteEvent.class, this::onBroadcastRemoteEvent)
            .onCommand(RoomCommand.ItemBridgeAction.class, this::onItemBridgeAction)
            .onCommand(RoomCommand.TimerFired.class, this::onTimerFired)
            .onCommand(RoomCommand.InvokeScriptHook.class, this::onInvokeScriptHook)
            .onCommand(RoomCommand.GetToolDefinitions.class, this::onGetToolDefinitions)
            .build();
    }

    @Override
    public EventHandler<RoomState, RoomEvent> eventHandler() {
        return newEventHandlerBuilder()
            .forAnyState()
            .onEvent(RoomEvent.class, (state, event) -> state.apply(event.event()))
            .build();
    }

    @Override
    public SignalHandler<RoomState> signalHandler() {
        return newSignalHandlerBuilder()
            .onSignal(RecoveryCompleted.instance(), (state) -> {
                if (!state.name().isEmpty()) {
                    log.info("Room {} recovered: \"{}\" — {} exits, {} objects",
                        roomId, state.name(), state.exits().size(), state.objects().size());
                    // Every room teaches the map about itself as it comes up. The shared
                    // topology is built once at boot from the FOUNDATION seeds only, so a
                    // room made afterwards was invisible to `map` — and after a restart
                    // that was true of every companion-made room, however walkable it
                    // still was. Live 2026-08-22: `├── to-venture-briefing-room-1931->[?]`
                    // for a room with furnishings and a way back. Foundation, created and
                    // restored rooms all arrive through this signal, so one call here is
                    // the whole answer rather than a hook per creation path.
                    ZoneTopology.learnRoom(roomId, state.name(), state.zone(),
                        List.copyOf(state.exits().values()), null, null);
                    // Call onActivate lifecycle hook (§31)
                    if (scriptEngine != null) {
                        var emissions = scriptEngine.invokeActivate(state);
                        processEmissions(emissions);
                        // §31 — onActivate is where scripts schedule their
                        // periodic timers (world.scheduleTimer). Drain and
                        // start them now that the room is live.
                        drainTimerRequests();
                    }
                    context.getSelf().tell(new RoomCommand.PurgeStaleCompanions());
                } else {
                    log.debug("Room {} recovery complete (empty state, awaiting CreateRoom)", roomId);
                }
            })
            .build();
    }

    /** Ghost-presence cleanup (verify 2026-07-12): after a restart, rooms
     *  recover pre-restart companion/agent entities but the live actors
     *  respawn elsewhere and never send EntityLeft to the OLD room — `look`
     *  showed a phantom mia until she happened to revisit. Companions always
     *  re-enter explicitly, so their recovered presence is safe to purge. */
    private Effect<RoomEvent, RoomState> onPurgeStaleCompanions(RoomState state) {
        var stale = state.entities().values().stream()
            .filter(e -> "companion".equals(e.type()) || "agent".equals(e.type()))
            .toList();
        if (stale.isEmpty()) return Effect().none();
        var events = new ArrayList<RoomEvent>();
        for (var e : stale) {
            log.info("Room {}: purging stale recovered entity '{}' ({})",
                roomId, e.name(), e.id());
            events.add(new RoomEvent(new WorldEvent.EntityLeft(
                roomId, Instant.now(), e.id(), e.name(), "restart")));
        }
        return Effect().persist(events);
    }

    // --- Command handlers ---

    private Effect<RoomEvent, RoomState> onCreateRoom(RoomState state, RoomCommand.CreateRoom cmd) {
        // Room already exists (recovered from journal). Seeds evolve between
        // releases, and an existing world must CONVERGE on new foundation
        // furnishings without a fresh install (the grant stone shipped into
        // ward rooms that predated it — 2026-08-14). Backfill is deliberately
        // narrow: only objects this room has never held, and only
        // NON-TAKEABLE fixtures — a takeable seed object may legitimately be
        // in someone's inventory by now, and re-adding it would mint a
        // duplicate. Nothing is ever overwritten or removed here.
        if (!state.name().isEmpty()) {
            var backfillNow = Instant.now();
            var missing = new ArrayList<RoomEvent>();
            for (var obj : cmd.objects()) {
                if (obj.takeable()) continue;
                if (state.objects().containsKey(obj.id())) continue;
                missing.add(new RoomEvent(new WorldEvent.ObjectAdded(
                    roomId, backfillNow, obj.id(), obj.name(), obj.description(),
                    obj.takeable(), obj.state(),
                    obj.aliases() == null ? List.of() : obj.aliases())));
            }
            if (missing.isEmpty()) {
                log.debug("Room {} already initialized, skipping CreateRoom", roomId);
                cmd.replyTo().tell(new RoomResponse.Ok(state.toSnapshot()));
                return Effect().none();
            }
            log.info("Room {}: backfilled {} seed object(s) new since this world was built",
                roomId, missing.size());
            return Effect().persist(missing)
                .thenRun(newState -> cmd.replyTo().tell(
                    new RoomResponse.Ok(newState.toSnapshot())));
        }

        var now = Instant.now();
        var events = new ArrayList<RoomEvent>();

        // Room creation (with aliases)
        events.add(new RoomEvent(new WorldEvent.RoomCreated(
            roomId, now, cmd.name(), cmd.description(), cmd.zone(), cmd.aliases())));

        // Exits
        for (var exit : cmd.exits()) {
            events.add(new RoomEvent(new WorldEvent.ExitOpened(
                roomId, now, exit.direction(), exit.targetRoom(), exit.label())));
        }

        // Objects
        // pass the object's state map through ObjectAdded
        // so furnishings seeded with {sittable: true} preserve that flag after
        // event-replay (e.g. for the Sit hint to surface).
        for (var obj : cmd.objects()) {
            events.add(new RoomEvent(new WorldEvent.ObjectAdded(
                roomId, now, obj.id(), obj.name(), obj.description(), obj.takeable(),
                obj.state(), obj.aliases() == null ? List.of() : obj.aliases())));
        }

        // Rich hints derived from exits and objects
        var hints = new ArrayList<Hint>();
        hints.add(new Hint("Tell me about this place", "describe_room", "look"));
        for (var exit : cmd.exits()) {
            hints.add(new Hint("Go " + exit.direction(), "navigate_" + exit.direction(),
                "go:" + exit.direction()));
        }
        for (var obj : cmd.objects()) {
            // Examine is universal — every visible object has a description.
            // Always offer it so players can discover what an item is before using it.
            hints.add(new Hint("Examine " + obj.name(), "examine_" + obj.id(), "examine:" + obj.name()));
            if (obj.takeable()) {
                hints.add(new Hint("Take " + obj.name(), "take_" + obj.id(), "take:" + obj.name()));
                // Items-as-tools contract — takeable coding artifacts (agent-built
                // tools) surface their script-declared commands too, so a placed
                // tool is discoverable without picking it up first.
                appendScriptCommandHints(hints, obj.id(), obj.name());
            } else {
                // Non-takeable: treat as an interactable furnishing — offer Use.
                // Scripted items handle their own onUse; non-scripted ones get a friendly fallback.
                hints.add(new Hint("Use " + obj.name(), "use_" + obj.id(), "use:" + obj.name()));
                // Phase 2 — surface any script-declared sub-verbs as their own menu
                // entries. Dispatched as `use:<name>|<args>` so the script's invoke
                // receives params.args = "<args>". Defensive lookup: try the object's
                // id, then its name normalized to underscores.
                appendScriptCommandHints(hints, obj.id(), obj.name());
            }
        }
        events.add(new RoomEvent(new WorldEvent.HintsUpdated(roomId, now, hints)));

        return Effect().persist(events)
            .thenRun(newState -> {
                cmd.replyTo().tell(new RoomResponse.Ok(newState.toSnapshot()));
                // Register room metadata for enumeration
                if (metadataService != null) {
                    metadataService.register(roomId, cmd.name(), cmd.zone(), "system");
                }
                log.info("Room created: {} ({}) — {} exits, {} objects, {} hints",
                    cmd.name(), roomId, cmd.exits().size(),
                    cmd.objects().size(), hints.size());
                // THE ONE PLACE EVERY NEW ROOM PASSES. The map was taught about new rooms
                // from RoomCreator — but the companion's create_room_from_template goes
                // through ZoneGuardian.CreateNewRoom, which never touches RoomCreator, so
                // on the home node 2026-08-23 07:19 story_fable was made, walkable, and
                // still `->[?]` on the map. Callers come and go; creation itself does not.
                ZoneTopology.learnRoom(roomId, cmd.name(), cmd.zone(),
                    List.copyOf(newState.exits().values()), null, null);
            });
    }

    /**
     * Phase 2 — surface script-declared sub-verbs as action-menu entries.
     *
     * <p>A scripted item's {@code exports.manifest.commands = [{label, args}]} array
     * lets it advertise context-specific verbs beyond the generic Examine/Use pair
     * (e.g. pinboard exposes "Read pinboard summary" and "Read pinboard details").
     * Each declared command becomes a separate hint dispatched as
     * {@code use:<itemName>|<args>}; the {@code case "use"} branch in
     * {@link #onSelectHint} splits on {@code |} and passes the args to the script's
     * {@code invoke(params)} via {@code UseObject.target}.</p>
     *
     * <p>Lookup tries the object's id first, then a normalized form of the name —
     * the in-room object name (e.g. "pinboard") may not match the manifest name
     * (e.g. "bondholder_pinboard"); when neither matches we simply emit no extra
     * hints, leaving the generic Examine/Use pair intact.</p>
     */
    private static void appendScriptCommandHints(List<Hint> hints, String objId, String objName) {
        var loader = ScriptedItemLoader.get();
        var def = loader.get(objId).orElse(null);
        if (def == null) {
            var normalized = objName == null ? "" : objName.toLowerCase().replace(' ', '_');
            if (!normalized.isEmpty()) def = loader.get(normalized).orElse(null);
        }
        if (def == null || def.manifest() == null) return;
        var commands = def.manifest().commands();
        if (commands == null || commands.isEmpty()) return;
        for (var c : commands) {
            var args = c.args() == null ? "" : c.args();
            // Skip the boot-shim's derived default ({label:"Use <Pretty Name>",
            // args:""}) — it's byte-identical in intent to the generic Use hint
            // the caller already adds, and rendering both reads as a glitch.
            // Real no-arg commands ("Read your usage") keep their entries.
            if (args.isEmpty() && c.label() != null
                    && c.label().equalsIgnoreCase("Use " + objName)) {
                continue;
            }
            var dispatch = args.isEmpty()
                ? "use:" + objName
                : "use:" + objName + "|" + args;
            hints.add(new Hint(c.label(), "use_" + objId + "_" + args, dispatch));
        }
    }

    private Effect<RoomEvent, RoomState> onAddExit(RoomState state, RoomCommand.AddExit cmd) {
        if (state.name().isEmpty()) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_found",
                ScriptMessageCatalog.forLang("en").get("err.room_not_initialized")));
            return Effect().none();
        }
        // Idempotent: skip if exit in this direction already exists
        if (state.exits().containsKey(cmd.direction())) {
            cmd.replyTo().tell(new RoomResponse.Ok(state.toSnapshot()));
            return Effect().none();
        }
        var event = new WorldEvent.ExitOpened(
            roomId, Instant.now(), cmd.direction(), cmd.targetRoom(), cmd.label());
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.Ok(newState.toSnapshot()));
                log.info("Exit added to room {}: {} → {}", roomId, cmd.direction(), cmd.targetRoom());
            });
    }

    private Effect<RoomEvent, RoomState> onLookRoom(RoomState state, RoomCommand.LookRoom cmd) {
        setLocaleContext(cmd.locale());
        var snapshot = state.toSnapshot();

        // Translate room name and description via i18n catalog
        var catalog = ScriptMessageCatalog.forLang(cmd.locale());
        var nameKey = roomId + ".name";
        var descKey = roomId + ".description";
        var name = catalog.hasKey(nameKey) ? catalog.get(nameKey) : snapshot.name();
        var baseDesc = catalog.hasKey(descKey) ? catalog.get(descKey) : snapshot.description();
        var desc = baseDesc;

        // Theme overlay — restyle the authored description under the active zone
        // aesthetic so a themed zone actually reads themed, not just carries a label.
        // Two tiers: (1) a lazily-baked LLM rewrite in the theme's voice (rich,
        // genuine new prose, cached per room×theme by ThemedDescriptionService) when
        // one is ready; else (2) the deterministic ZoneAestheticDescriber restyle
        // (lexicon substitution + atmosphere line) — instant, no inference, and the
        // stand-in shown on the first look while the LLM rewrite bakes in the
        // background. Safe no-op for the default aesthetic or an uninitialised
        // service (unit tests). Applied before the phase ambient so time-of-day
        // flavour layers on top of the theme.
        var aestheticSvc = ZoneAestheticService.get();
        if (aestheticSvc != null) {
            var aesthetic = aestheticSvc.effectiveAesthetic(roomId);
            var themedSvc = ThemedDescriptionService.get();
            var llmRewrite = themedSvc != null
                ? themedSvc.resolve(roomId, baseDesc, aesthetic, cmd.locale()) : null;
            if (llmRewrite != null) {
                // The LLM rewrite restyles the prose in the theme's voice but
                // doesn't carry the deterministic atmosphere marker. Append it so
                // an LLM-rewritten room reads as recognizably themed as the
                // deterministic-restyle rooms do (e.g. the Study gets the same
                // "Neon bleed…" line the Bridge shows under cyberpunk).
                var atmosphere = ZoneAestheticDescriber.atmosphereLine(
                    aesthetic != null ? aesthetic.name() : null, cmd.locale());
                desc = (atmosphere == null || atmosphere.isBlank())
                    ? llmRewrite
                    : llmRewrite + "\n\n" + atmosphere;
            } else {
                desc = ZoneAestheticDescriber.restyle(baseDesc, aesthetic, cmd.locale());
            }
        }

        // Layer 5 — overlay phase-specific ambient text after
        // the room's own description. Parallel to PostureTemplates: pure
        // function, no actor state, locale-aware. Skipped silently when no
        // {@link org.wyrdsekai.core.ambient.WorldClock} has run yet for this
        // zone (e.g. unit tests that don't spawn ZoneGuardian).
        var zoneId = (snapshot.zone() != null && !snapshot.zone().isBlank())
            ? snapshot.zone()
            : System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
        var phase = WorldClock.currentPhase(zoneId);
        if (phase != null) {
            var ambient = AmbientRenderer.descriptor(
                roomId, phase, cmd.locale());
            if (ambient != null && !ambient.isBlank()) {
                desc = desc + "\n\n" + ambient;
            }
        }

        var hints = buildCurrentHints(state, cmd.locale(), cmd.entityId());

        snapshot = new RoomSnapshot(
            snapshot.roomId(), name, desc, snapshot.zone(), snapshot.aliases(),
            snapshot.exits(), snapshot.entities(), snapshot.objects(), hints);

        cmd.replyTo().tell(new RoomResponse.Ok(snapshot));
        return Effect().none();
    }

    private Effect<RoomEvent, RoomState> onEnterRoom(RoomState state, RoomCommand.EnterRoom cmd) {
        var catalog = ScriptMessageCatalog.forLang(cmd.locale());
        // Sanction check: banned entities cannot enter any room
        if (sanctionEnforcer != null && !sanctionEnforcer.canEnterRoom(cmd.entityId(), roomId)) {
            cmd.replyTo().tell(new RoomResponse.Rejected("sanctioned",
                catalog.get("err.sanctioned_enter")));
            return Effect().none();
        }

        // Parental controls: a household rule may bar this member from the room.
        // No-op ALLOW when the service isn't wired (tests, bare boots).
        var parental = ParentalControlService.get();
        if (parental != null && !parental.canEnterRoom(cmd.entityId(), roomId)) {
            cmd.replyTo().tell(new RoomResponse.Rejected("parental_block",
                "That door doesn't open for you — a household rule holds it closed."));
            return Effect().none();
        }

        // Quarantine check (§4.2)
        if (isQuarantined(state) && !"agent".equals(cmd.entityType())) {
            cmd.replyTo().tell(new RoomResponse.Rejected("quarantined",
                catalog.get("err.quarantined")));
            return Effect().none();
        }

        // Capacity check (§2.8, §71)
        var capacity = getRoomCapacity(state);
        if (!capacity.canAddEntity(state.entities().size())) {
            cmd.replyTo().tell(new RoomResponse.Rejected("at_capacity",
                catalog.get("err.room_full")));
            return Effect().none();
        }
        if ("agent".equals(cmd.entityType()) && !capacity.canAddAgent(
                (int) state.entities().values().stream()
                    .filter(e -> "agent".equals(e.type())).count())) {
            cmd.replyTo().tell(new RoomResponse.Rejected("at_capacity",
                catalog.get("err.too_many_agents")));
            return Effect().none();
        }

        // Parlor DoS cap (§2.8.1) — reject before persist if the room is a
        // managed Parlor at MAX_OCCUPANTS. Existing occupants re-entering
        // (e.g. reconnect) are always admitted.
        var parlor = ParlorManager.get();
        if (parlor != null && parlor.isManaged(roomId)) {
            var snap = parlor.snapshot(roomId);
            if (snap.isPresent()
                    && snap.get().occupancy() >= ParlorPresenceMode.MAX_OCCUPANTS
                    && !snap.get().occupants().contains(cmd.entityId())) {
                cmd.replyTo().tell(new RoomResponse.Rejected("at_capacity",
                    catalog.get("err.room_full")));
                return Effect().none();
            }
        }

        // Multi-surface presence: one account = many channels backing ONE
        // in-world entity. If this entity is already present in the room (via
        // another live channel — CLI while SSH is here, etc.), a second channel
        // attaching is NOT a new arrival. Suppress the "X arrives." broadcast
        // and the onEnter hook on that transition; the first channel
        // (absent→present) still announces. Mirror of the quit-side guard that
        // keeps a single channel-detach from falsely announcing a departure.
        boolean alreadyPresent = state.entities().containsKey(cmd.entityId());

        var event = new WorldEvent.EntityEntered(
            roomId, Instant.now(), cmd.entityId(), cmd.entityName(),
            cmd.entityType(), cmd.fromDirection(),
            cmd.description() != null ? cmd.description() : "");
        // Persistent — entity presence is part of world state.
        // On restart, the room recovers who was here.
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                if (!alreadyPresent) {
                    notifySubscribers(event);
                }
                cmd.replyTo().tell(new RoomResponse.Ok(localizedSnapshot(newState, cmd.locale())));
                updateParlorOnEnter(cmd.entityId());
                if (!alreadyPresent) {
                    runScriptHook(newState, cmd.locale(), "onEnter", cmd.entityId(), cmd.entityName(), cmd.fromDirection());
                }
            });
    }

    /**
     * Notify the Parlor auto-scaler that an entity entered, when this room
     * is under Parlor management (§2.8). Fires mode-transition narration
     * as a {@link WorldEvent.Said} from the {@code narrator} speaker, so
     * it reads as diegetic chat to everyone subscribed to the room.
     */
    private void updateParlorOnEnter(String entityId) {
        var parlor = ParlorManager.get();
        if (parlor == null || !parlor.isManaged(roomId)) return;
        parlor.entered(roomId, entityId, narration -> {
            var narrEvent = new WorldEvent.Said(
                roomId, Instant.now(), "narrator", "narrator", narration.text());
            notifySubscribers(narrEvent);
        });
    }

    /** Counterpart to {@link #updateParlorOnEnter}. */
    private void updateParlorOnLeave(String entityId) {
        var parlor = ParlorManager.get();
        if (parlor == null || !parlor.isManaged(roomId)) return;
        parlor.left(roomId, entityId, narration -> {
            var narrEvent = new WorldEvent.Said(
                roomId, Instant.now(), "narrator", "narrator", narration.text());
            notifySubscribers(narrEvent);
        });
    }

    private Effect<RoomEvent, RoomState> onLeaveRoom(RoomState state, RoomCommand.LeaveRoom cmd) {
        // Proceed even if entity not tracked (handles reconnection edge cases)
        var event = new WorldEvent.EntityLeft(
            roomId, Instant.now(), cmd.entityId(), cmd.entityName(), cmd.direction());
        // Persistent — the world remembers who left.
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.Ok(newState.toSnapshot()));
                updateParlorOnLeave(cmd.entityId());
                // Symmetric to onEnter's onEnter hook — scripts that track
                // departures (recorder mixin, engine-room) get told.
                runScriptHook(newState, "en", "onLeave",
                    cmd.entityId(), cmd.entityName(), cmd.direction());
            });
    }

    private Effect<RoomEvent, RoomState> onSayInRoom(RoomState state, RoomCommand.SayInRoom cmd) {
        // Sanction check: muted entities cannot speak
        if (sanctionEnforcer != null && !sanctionEnforcer.canSpeak(cmd.entityId())) {
            var catalog = ScriptMessageCatalog.forLang(cmd.locale());
            cmd.replyTo().tell(new RoomResponse.Rejected("muted",
                catalog.get("err.muted")));
            return Effect().none();
        }

        var event = new WorldEvent.Said(
            roomId, Instant.now(), cmd.entityId(), cmd.entityName(), cmd.text(), cmd.locale(),
            cmd.attachments());
        // Transient — Said doesn't change room state, no need to persist.
        // Reply Narrated, not Ok(snapshot): the Said event already reaches all
        // subscribers via notifySubscribers (delivered as Prose). Returning
        // Ok(snapshot) would make ClientSessionActor push a RoomState that
        // redraws the room over the speech line.
        notifySubscribers(event);
        cmd.replyTo().tell(new RoomResponse.Narrated());

        // Speech-triggered object activation: if the first word matches any
        // object name (visible or invisible), route to onUse instead of onSay.
        var trigger = matchTriggerWord(state, cmd.text());
        if (trigger != null) {
            // 4th arg: the actor's display name, so scripts can narrate
            // "steward runs a hand over the anvil" instead of a raw user id
            // (the-forge, 2026-07-04 audit). Extra args are invisible to
            // scripts declaring the older 3-param onUse.
            runScriptHook(state, cmd.locale(), "onUse",
                cmd.entityId(), trigger.objectName(), trigger.remainder(),
                cmd.entityName());
        } else {
            runScriptHook(state, cmd.locale(), "onSay",
                cmd.entityId(), cmd.entityName(), cmd.text());
        }
        return Effect().none();
    }

    /**
     * Check if the first word of speech matches any object name in the room.
     * Enables speech-triggered object activation ("computer, show status").
     */
    private TriggerMatch matchTriggerWord(RoomState state, String text) {
        if (text == null || text.isBlank()) return null;
        var trimmed = text.strip();

        // Parse "word, rest" or "word rest"
        String firstWord, remainder;
        var commaIdx = trimmed.indexOf(',');
        var spaceIdx = trimmed.indexOf(' ');

        if (commaIdx > 0 && (spaceIdx < 0 || commaIdx < spaceIdx)) {
            firstWord = trimmed.substring(0, commaIdx).strip().toLowerCase();
            remainder = trimmed.substring(commaIdx + 1).strip();
        } else if (spaceIdx > 0) {
            firstWord = trimmed.substring(0, spaceIdx).strip().toLowerCase();
            remainder = trimmed.substring(spaceIdx + 1).strip();
        } else {
            firstWord = trimmed.toLowerCase();
            remainder = "";
        }

        for (var obj : state.objects().values()) {
            if (obj.name().toLowerCase().equals(firstWord)) {
                return new TriggerMatch(obj.name(), remainder);
            }
        }
        return null;
    }

    private record TriggerMatch(String objectName, String remainder) {}

    private Effect<RoomEvent, RoomState> onEmoteInRoom(RoomState state, RoomCommand.EmoteInRoom cmd) {
        // Sanction check: muted entities cannot emote
        if (sanctionEnforcer != null && !sanctionEnforcer.canSpeak(cmd.entityId())) {
            var catalog = ScriptMessageCatalog.forLang(cmd.locale());
            cmd.replyTo().tell(new RoomResponse.Rejected("muted",
                catalog.get("err.muted")));
            return Effect().none();
        }

        var event = new WorldEvent.Emoted(
            roomId, Instant.now(), cmd.entityId(), cmd.entityName(), cmd.text());
        // Transient — Emoted doesn't change room state. Same reasoning as
        // onSayInRoom: the Emoted event reaches subscribers as Prose, and an
        // Ok(snapshot) reply would force a redundant room redraw.
        notifySubscribers(event);
        cmd.replyTo().tell(new RoomResponse.Narrated());
        runScriptHook(state, cmd.locale(), "onEmote",
            cmd.entityId(), cmd.entityName(), cmd.text());
        return Effect().none();
    }

    private Effect<RoomEvent, RoomState> onWhisperInRoom(RoomState state, RoomCommand.WhisperInRoom cmd) {
        var catalog = ScriptMessageCatalog.forLang(cmd.locale());
        // Sanction check: muted entities cannot whisper
        if (sanctionEnforcer != null && !sanctionEnforcer.canSpeak(cmd.entityId())) {
            cmd.replyTo().tell(new RoomResponse.Rejected("muted",
                catalog.get("err.muted")));
            return Effect().none();
        }

        // Verify target is in the room
        if (!state.entities().containsKey(cmd.targetEntityId())) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_found",
                catalog.get("err.person_not_here")));
            return Effect().none();
        }

        var event = new WorldEvent.Whispered(
            roomId, Instant.now(), cmd.entityId(), cmd.entityName(),
            cmd.targetEntityId(), cmd.text());
        // Transient — Whispered doesn't change room state
        notifySubscribers(event);
        cmd.replyTo().tell(new RoomResponse.Ok(localizedSnapshot(state, cmd.locale())));
        return Effect().none();
    }

    /**
     * Resolve a room object by alias or name, with MUD-style ordinal support.
     * Supports "N.query" syntax: "2.sword" → second object matching "sword".
     *
     * Resolution order (via AliasResolver):
     * 1. Exact alias match (case-insensitive)
     * 2. Exact name match (case-insensitive)
     * 3. Partial alias match (contains)
     * 4. Partial name match (contains)
     */
    private Optional<RoomObject> resolveObject(Collection<RoomObject> objects, String query) {
        return AliasResolver.resolveObject(objects, query);
    }

    /**
     * Exact-tier resolution only — name or alias equality, never the partial
     * "contains" tiers. Used by the use-command prefix splitter, where fuzzy
     * matching on a prefix would steal words that belong to the args.
     */
    private static Optional<RoomObject> resolveObjectExact(
            Collection<RoomObject> objects, String query) {
        for (var o : objects) {
            if (o.name() != null && o.name().equalsIgnoreCase(query)) return Optional.of(o);
        }
        for (var o : objects) {
            var aliases = o.aliases();
            if (aliases == null) continue;
            for (var a : aliases) {
                if (a != null && a.equalsIgnoreCase(query)) return Optional.of(o);
            }
        }
        return Optional.empty();
    }

    private Effect<RoomEvent, RoomState> onTakeObject(RoomState state, RoomCommand.TakeObject cmd) {
        var catalog = ScriptMessageCatalog.forLang(cmd.locale());
        // Restrict the candidate set to takeable objects. Rooms can end up
        // holding multiple objects with the same name (e.g. a dropped pinned
        // furnishing alongside the room's own pickable item) — resolving
        // across the whole set would non-deterministically pick whichever
        // came first and reject the take as not_takeable, even though a
        // legitimate takeable match exists.
        var takeable = state.objects().values().stream()
            .filter(RoomObject::takeable).toList();
        var obj = resolveObject(takeable, cmd.objectName());
        if (obj.isEmpty()) {
            // Fall back to full lookup so we can return a more specific error
            // (not_takeable vs not_found) when the name does exist but isn't
            // takeable — otherwise "take compass" on a room with only a
            // pinned Compass would confusingly say "no such object".
            var any = resolveObject(state.objects().values(), cmd.objectName());
            if (any.isEmpty()) {
                cmd.replyTo().tell(new RoomResponse.Rejected("not_found",
                    catalog.get("err.no_such_object", cmd.objectName())));
            } else {
                cmd.replyTo().tell(new RoomResponse.Rejected("not_takeable",
                    catalog.get("err.not_takeable")));
            }
            return Effect().none();
        }
        var takenObject = obj.get();
        var event = new WorldEvent.ObjectTaken(
            roomId, Instant.now(), cmd.entityId(), takenObject.id(), takenObject.name());
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.ObjectTakenOk(localizedSnapshot(newState, cmd.locale()), takenObject));
                runScriptHook(newState, cmd.locale(), "onTake", cmd.entityId(), takenObject.name(), takenObject.id());
            });
    }

    private Effect<RoomEvent, RoomState> onDropObject(RoomState state, RoomCommand.DropObject cmd) {
        // Belt-and-braces: the client-side drop handlers use findTakeableByName
        // so they can't target a pinned scripted furnishing — but room state
        // is durable and a malformed/old caller could still push a
        // takeable=false object here and corrupt the room. Reject at the
        // room boundary.
        if (!cmd.takeable()) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_takeable",
                "Cannot drop '" + cmd.objectName() + "' — pinned/scripted items stay bound to their source."));
            return Effect().none();
        }
        var event = new WorldEvent.ObjectDropped(
            roomId, Instant.now(), cmd.entityId(), cmd.objectId(), cmd.objectName(),
            cmd.description(), cmd.takeable());
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.Ok(localizedSnapshot(newState, cmd.locale())));
                runScriptHook(newState, cmd.locale(), "onDrop", cmd.entityId(), cmd.objectName(), cmd.objectId());
            });
    }

    private Effect<RoomEvent, RoomState> onUseObject(RoomState state, RoomCommand.UseObject cmd) {
        // Item args get mangled two ways by CommandParser: (a) multi-word args
        // ride inside objectName ("scroll set KEY=VALUE"), and (b) the eager
        // " on " split treats the word "on" in an item verb as the use-on
        // separator ("maintenance dial mode on <reason>" → object="maintenance
        // dial mode", target="<reason>"). BOTH drop the real args (second-node). Fix:
        // when objectName does NOT exactly resolve to an object, reconstruct the
        // full typed phrase (re-joining any " on " target) and find the LONGEST
        // leading word-span that IS an object; the remainder becomes the target.
        // Guard on exact-non-resolution so a legitimate `use key on door` (where
        // "key" exactly resolves) keeps the parser's object/target split intact.
        if (cmd.objectName() != null && cmd.objectName().contains(" ")
                && resolveObjectExact(state.objects().values(), cmd.objectName()).isEmpty()) {
            var full = (cmd.target() != null && !cmd.target().isBlank())
                ? cmd.objectName().trim() + " on " + cmd.target().trim()
                : cmd.objectName().trim();
            var words = full.split("\\s+");
            boolean split = false;
            for (int i = words.length - 1; i >= 1; i--) {
                var prefix = String.join(" ", Arrays.copyOfRange(words, 0, i));
                if (resolveObjectExact(state.objects().values(), prefix).isPresent()) {
                    var remainder = String.join(" ", Arrays.copyOfRange(words, i, words.length));
                    cmd = new RoomCommand.UseObject(cmd.entityId(), prefix,
                        remainder, cmd.locale(), cmd.replyTo());
                    split = true;
                    break;
                }
            }
            // Inventory fallback (second-node 2026-07-08): the scan above only sees ROOM objects, so
            // `use <inventory-item> <query>` (e.g. a crafted web-searcher) never split — the whole
            // phrase was treated as one object name → "No such object". Fire ONLY when no room-object
            // prefix matched AND fuzzy room resolution also finds nothing (so it isn't a phrase like
            // "the card catalog over there" that fuzzy-matches a room object): then take the FIRST
            // token as the item and the rest as args so downstream inventory resolution + invoke()
            // receive the query.
            if (!split && cmd.objectName() != null && cmd.objectName().contains(" ")
                    && resolveObject(state.objects().values(), cmd.objectName()).isEmpty()) {
                var sp = cmd.objectName().indexOf(' ');
                cmd = new RoomCommand.UseObject(cmd.entityId(),
                    cmd.objectName().substring(0, sp),
                    cmd.objectName().substring(sp + 1).trim(),
                    cmd.locale(), cmd.replyTo());
            }
        }
        var catalog = ScriptMessageCatalog.forLang(cmd.locale());
        var obj = resolveObject(state.objects().values(), cmd.objectName());

        // If no object found, check if it's an entity (player or agent) in the room
        if (obj.isEmpty()) {
            var queryLower = cmd.objectName().toLowerCase();
            // Check for self-reference
            if ("me".equals(queryLower) || "self".equals(queryLower) || "myself".equals(queryLower)) {
                var entity = state.entities().get(cmd.entityId());
                if (entity != null) {
                    emitEntityDescription(entity, true);
                    // Narrated, not Ok(snapshot): emitEntityDescription publishes
                    // a Said event that reaches the client as Prose. An
                    // Ok(snapshot) here would trigger a full room redraw on top
                    // of the description line.
                    cmd.replyTo().tell(new RoomResponse.Narrated());
                    runScriptHook(state, cmd.locale(), "onExamine", cmd.entityId(), cmd.entityId(), "self");
                    return Effect().none();
                }
            }
            // Check for other entities by name
            var matchedEntity = state.entities().values().stream()
                .filter(e -> e.name().toLowerCase().contains(queryLower)
                    || queryLower.contains(e.name().toLowerCase()))
                .findFirst();
            if (matchedEntity.isPresent()) {
                var e = matchedEntity.get();
                emitEntityDescription(e, false);
                cmd.replyTo().tell(new RoomResponse.Narrated());
                runScriptHook(state, cmd.locale(), "onExamine", cmd.entityId(), e.id(), e.name());
                return Effect().none();
            }

            cmd.replyTo().tell(new RoomResponse.Rejected("not_found",
                catalog.get("err.no_such_object", cmd.objectName())));
            return Effect().none();
        }

        // Resolve the entity name for the event (not the UUID)
        var entityName = state.entities().containsKey(cmd.entityId())
            ? state.entities().get(cmd.entityId()).name() : cmd.entityId();
        var event = new WorldEvent.ObjectUsed(
            roomId, Instant.now(), entityName, obj.get().id(),
            obj.get().name(), cmd.target(), catalog.get("ui.used", obj.get().name()));
        // Transient — ObjectUsed doesn't change room state. Reply Narrated, not
        // Ok(snapshot): the ObjectUsed event already reaches subscribers via
        // notifySubscribers, and any state mutation a script performs is
        // broadcast as its own WorldEvent. Returning Ok(snapshot) here makes
        // client sessions push a redundant RoomState that clobbers the
        // narration line.
        notifySubscribers(event);
        cmd.replyTo().tell(new RoomResponse.Narrated());

        // / Phase D — coding items
        // (codex / artifact placed by CodingTaskItemBridge) route their
        // use commands through LocalCommandRouter instead of the room
        // script's onUse hook. The metadata side-registry tells us
        // whether this object is a coding item; if it is, dispatch the
        // verb to the matching backend's namespace handler and narrate
        // the response in-room. Falls through to onUse if the object
        // isn't a coding item.
        var codingMeta = CodingItemRegistry
            .get().lookup(obj.get().id()).orElse(null);
        if (codingMeta != null) {
            dispatchCodingItemUse(cmd, obj.get(), codingMeta);
            return Effect().none();
        }

        // Furnishing-as-scripted-item ( follow-through):
        // a room furnishing whose id (or normalized name) matches a loaded
        // scripts/items/*.js def IS that item — invoke its script with a real
        // provider so `use roster ledger` actually runs roster logic instead
        // of falling through to the room script's (usually absent) onUse
        // branch. This is the same resolution appendScriptCommandHints uses
        // for the discovery menu, so an object's hints and its behavior can
        // never disagree. Falls through to onUse when nothing matches.
        var furnishingDef = resolveFurnishingItem(obj.get());
        if (furnishingDef != null) {
            invokeScriptedFurnishing(cmd, obj.get(), furnishingDef);
            return Effect().none();
        }

        runScriptHook(state, cmd.locale(), "onUse", cmd.entityId(), obj.get().name(),
            cmd.target(), entityName);
        return Effect().none();
    }

    /**
     * Route a {@code use codex-X examine} or {@code use artifact-Y run}
     * to the right handler and narrate the response into the room.
     *
     * <p>Two paths, in priority order:</p>
     * <ol>
     *   <li><b>Items-as-tools (preferred)</b>: when the bridge registered
     *       the artifact's GraalJS source with
     *       {@link org.wyrdsekai.core.item.ScriptedItemLoader}, the
     *       metadata carries a {@code scriptedItemId}. We invoke that
     *       item via {@link
     *       org.wyrdsekai.scripting.sandbox.ItemScriptExecutor} and
     *       narrate the script's return value back into the room. This
     *       is the canonical {@code use <item>} path — same engine that
     *       runs every other scripted item in the world.</li>
     *   <li><b>Backend router (fallback)</b>: legacy path via
     *       {@link org.wyrdsekai.core.agent.LocalCommandRouter}. Kept
     *       for codings that didn't produce a parseable manifest, and
     *       for backend-specific verbs (examine/diff/log) that aren't
     *       expressible as item invocations.</li>
     * </ol>
     */
    private void dispatchCodingItemUse(RoomCommand.UseObject cmd, RoomObject obj,
                                        CodingItemMetadata meta) {
        if (meta.hasScriptedItem()) {
            invokeScriptedCodingItem(cmd, obj, meta);
            return;
        }
        var verb = (cmd.target() == null || cmd.target().isBlank())
            ? meta.defaultVerb()
            : cmd.target().trim().toLowerCase();
        var command = meta.backend() + "." + verb;
        var args = List.of(meta.artifactId().toString());
        var router = LocalCommandRouter.get();
        log.debug("RoomActor: routing use {} -> {}", obj.id(), command);
        router.execute(cmd.entityId(), command, args, Map.of(),
            response -> narrateCodingResponse(cmd.locale(), response));
    }

    /**
     * Invoke a coding-artifact-derived scripted item via {@link
     * org.wyrdsekai.scripting.sandbox.ItemScriptExecutor} and narrate
     * the return value as {@code Said(narrator, ...)} so the player
     * sees the item's output in the room transcript.
     *
     * <p>Provider-handling: resolves the acting player's own provider via
     * {@link #providerFor}, which carries the household's library, model and keys and —
     * since 2026-08-21 — a room voice, so {@code world.agent.speak} lands here too.
     *
     * <p>This javadoc described an empty-result stub long after the code had stopped
     * using one, and on 2026-08-21 that stale paragraph sent a reader (me) chasing the
     * wrong cause for a real bug. The real bug was one line below: the params.</p>
     */
    private void invokeScriptedCodingItem(RoomCommand.UseObject cmd, RoomObject obj,
                                           CodingItemMetadata meta) {
        var scriptedId = meta.scriptedItemId();
        var def = ScriptedItemLoader.get().get(scriptedId).orElse(null);
        if (def == null) {
            // Registered but evicted — fall back to the router path so
            // the player gets some response rather than silence.
            log.warn("RoomActor: scripted item '{}' missing from loader; "
                + "falling back to router path for {}", scriptedId, obj.id());
            var router = LocalCommandRouter.get();
            var verb = (cmd.target() == null || cmd.target().isBlank())
                ? meta.defaultVerb() : cmd.target().trim().toLowerCase();
            router.execute(cmd.entityId(), meta.backend() + "." + verb,
                List.of(meta.artifactId().toString()),
                Map.of(),
                response -> narrateCodingResponse(cmd.locale(), response));
            return;
        }
        // Every spelling, from the ONE shared builder — and the acting entity, and the
        // room. This path set `query` alone, so `params.args` was undefined for any
        // backend-authored item used from the FLOOR rather than out of someone's hands.
        // The contract promises args, so goose writes against args, and the item
        // answered "no arguments supplied" for a command that plainly had some
        // (live 2026-08-21: `use weather_lookup cambridge ma`).
        //
        // Third invocation path for the same feature. CarriedItemUse.params exists so
        // there is one answer to "what does a script receive" — it just was not called
        // here.
        var params = new LinkedHashMap<String, Object>(
            CarriedItemUse.params(cmd.entityId(),
                cmd.target() == null ? "" : cmd.target().trim(), cmd.locale()));
        params.put("roomId", roomId);
        log.debug("RoomActor: invoking scripted item '{}' for use of {}",
            scriptedId, obj.id());
        try (var executor = new ItemScriptExecutor()) {
            var result = executor.execute(scriptedId, def.scriptSource(), params,
                providerFor(cmd.entityId(), cmd.locale()));
            narrateScriptResult(cmd.locale(), scriptedId, result);
        } catch (Exception e) {
            log.warn("RoomActor: scripted item '{}' threw: {}", scriptedId, e.toString());
            var event = new WorldEvent.Said(roomId, Instant.now(),
                "narrator", "narrator",
                "[" + scriptedId + "] script error: " + e.getMessage(),
                cmd.locale(), List.of());
            notifySubscribers(event);
        }
    }

    /**
     * Resolve a used furnishing to its backing scripted item, if any.
     * Same two-step lookup as {@link #appendScriptCommandHints}: object id
     * first (canonical — {@code manifest.name == RoomObject.id}), then the
     * normalized display name ("roster ledger" → "roster_ledger") for items
     * authored against the friendly name. Null when the object has no
     * scripted backing.
     */
    private static ScriptedItemDef resolveFurnishingItem(RoomObject obj) {
        var loader = ScriptedItemLoader.get();
        var def = loader.get(obj.id()).orElse(null);
        if (def == null) {
            var normalized = obj.name() == null ? "" : obj.name().toLowerCase().replace(' ', '_');
            if (!normalized.isEmpty()) def = loader.get(normalized).orElse(null);
        }
        // Hook-only items (e.g. leather_chair.js — an onUse hook, no invoke())
        // can't run through execute(); pre-empting the room script for them
        // would narrate a "no invoke()" error instead of behavior. Let them
        // fall through to the room's onUse hook, their actual surface.
        if (def != null && !ScriptedItemLoader.hasEntrypoint(def.scriptSource())) {
            return null;
        }
        return def;
    }

    /**
     * The acting entity's live {@code world.*} provider when the server layer
     * has registered one ({@link ItemProviderRegistry}), else the stub —
     * matching this path's pre-registry behavior so tests and bare boots
     * keep working.
     */
    private ItemWorldApiProvider providerFor(String entityId, String locale) {
        var provider = ItemProviderRegistry.forEntity(entityId);
        if (provider == null) return StubItemWorldApiProvider.INSTANCE;
        // An item that speaks has somewhere to speak. The carried-item paths were given
        // this and the ROOM-PLACED path was not — so the same item, used off the floor
        // instead of out of your hands, went silent again. Same feature, fifth surface,
        // for the second time in one day.
        CarriedItemUse.attachRoomVoice(provider, roomId, entityId);
        CarriedItemUse.attachLocale(provider, locale);
        return provider;
    }

    /**
     * Run a furnishing's backing scripted item and narrate its result into
     * the room. Params carry every key the item corpus reads — {@code args}
     * / {@code text} (recipes_console-style verbs), {@code target} /
     * {@code query} (carried/coding conventions), and the acting entity —
     * so one furnishing script works regardless of which convention its
     * author followed.
     */
    private void invokeScriptedFurnishing(RoomCommand.UseObject cmd, RoomObject obj,
                                          ScriptedItemDef def) {
        var target = cmd.target() == null ? "" : cmd.target().trim();
        // The shared builder for the spellings every item may read, plus the two this
        // path alone adds. It hand-rolled all of them until 2026-08-21 — it happened to
        // be CORRECT, which is worse than being wrong, because a second definition that
        // agrees today is a second definition that can stop agreeing tomorrow.
        var params = new LinkedHashMap<String, Object>(
            CarriedItemUse.params(cmd.entityId(), target, cmd.locale()));
        // recipes_console-style furnishings read `text`; only this path serves them.
        params.put("text", target);
        // Room context: the per-player provider has no room binding, so
        // world.room.id() is empty on this path — but the ROOM is invoking.
        // Room-scoped items (sigil, warden post) read params.roomId first.
        params.put("roomId", roomId);
        var provider = providerFor(cmd.entityId(), cmd.locale());
        // INFO not debug (2026-07-18): when a furnishing answers empty, WHICH
        // provider class served it is the whole diagnosis — the stub and the
        // visitor provider both produce polite empties that look like data.
        log.info("RoomActor: invoking furnishing item '{}' for use of {} by {} (provider={})",
            def.manifest().name(), obj.id(), cmd.entityId(),
            provider.getClass().getSimpleName());
        try (var executor = new ItemScriptExecutor()) {
            var result = executor.execute(def.manifest().name(), def.scriptSource(), params,
                provider);
            narrateScriptResult(cmd.locale(), obj.name(), result);
        } catch (Exception e) {
            log.warn("RoomActor: furnishing item '{}' threw: {}",
                def.manifest().name(), e.toString());
            var event = new WorldEvent.Said(roomId, Instant.now(),
                "narrator", "narrator",
                "[" + obj.name() + "] script error: " + e.getMessage(),
                cmd.locale(), List.of());
            notifySubscribers(event);
        }
    }

    /**
     * Render a scripted item's {@code invoke()} return value as room
     * narration. Mirrors the rendering rules used by the workshop's
     * skill-execution path: prefer {@code summary}, fall back to a
     * compact JSON-ish dump.
     */
    private void narrateScriptResult(String locale, String scriptedId,
                                      Map<String, Object> result) {
        // A person typed a command. Silence is the one answer they cannot act on: it is
        // indistinguishable from a command that did not register. Live on staging
        // 2026-08-22, `use information_broker octopus` ran — the log shows the search, the
        // fetch and the model call — and the steward saw nothing at all, three times, and
        // there was no way to tell from the room whether the tool was broken, slow, or
        // imaginary. Whatever happened, say that it happened.
        if (result == null) {
            notifySubscribers(new WorldEvent.Said(roomId, Instant.now(),
                "narrator", "narrator",
                "[" + scriptedId + "] ran but returned nothing.", locale, List.of()));
            return;
        }
        // The item corpus is not uniform about its narration key: workshop skills return
        // `summary`, recipes_console-style consoles return `narrative`, carried items
        // return `response`/`text`, failures return `error`. ONE list, shared with
        // ItemScriptResponse — this path kept its own and so `narrative` rendered when an
        // item sat in the room and vanished when the same item was picked up.
        String text = ItemScriptResponse.firstTextField(result);
        if (text == null) {
            var pretty = new StringBuilder("[").append(scriptedId).append("]");
            for (var e : result.entrySet()) {
                pretty.append(' ').append(e.getKey()).append('=')
                    .append(String.valueOf(e.getValue()));
            }
            text = pretty.toString();
        }
        if (text == null || text.isBlank()) {
            text = "[" + scriptedId + "] ran but had nothing to say.";
        }
        // An item that wrote BOTH a summary and details meant the person to have both.
        // Live 2026-08-22: venture_scout put "scanned for unconventional patterns in X and
        // generated three radical business ideas with TAM estimates" in `summary` and the
        // three ideas themselves in `details`. The room read `summary` and the steward got
        // a description of the work instead of the work. Showing the payload costs a line;
        // withholding it costs the whole point of the tool.
        // Asked of the shared reader, not re-implemented here: this class exists because
        // the room once kept its own copy of the response contract and the two disagreed.
        var extra = ItemScriptResponse.detailText(result, text);
        if (extra != null) text = text + "\n" + extra;
        var event = new WorldEvent.Said(roomId, Instant.now(),
            "narrator", "narrator", text, locale, List.of());
        notifySubscribers(event);
    }

    /**
     * Translate an {@link org.wyrdsekai.common.protocol.S2CMessage}
     * response from the router into a {@link WorldEvent.Said}
     * narration emitted by the {@code narrator} speaker, so a player
     * typing {@code use codex-abc run} sees the {@code stdout} flow
     * back into the room transcript.
     */
    private void narrateCodingResponse(String locale,
            S2CMessage msg) {
        if (msg == null) return;
        String text;
        if (msg instanceof S2CMessage.Prose p) {
            text = p.text();
        } else if (msg instanceof S2CMessage.Error e) {
            text = "[" + e.code() + "] " + e.message();
        } else {
            // Not silently. A drop nobody can see is a drop nobody can debug.
            log.warn("RoomActor: coding response envelope {} has no narration — "
                + "the person who ran this sees nothing",
                msg.getClass().getSimpleName());
            text = "[coding] the backend answered in a shape this room cannot read ("
                + msg.getClass().getSimpleName() + ").";
        }
        if (text == null || text.isBlank()) return;
        var event = new WorldEvent.Said(roomId, Instant.now(),
            "narrator", "narrator", text, locale, List.of());
        notifySubscribers(event);
    }

    private Effect<RoomEvent, RoomState> onSelectHint(RoomState state, RoomCommand.SelectHint cmd) {
        var catalog = ScriptMessageCatalog.forLang(cmd.locale());
        // Rebuild the same hint list the client received from onLookRoom
        // (must pass entityId so caller-gated hints like Stand match what they saw)
        var hints = buildCurrentHints(state, cmd.locale(), cmd.entityId());
        if (cmd.index() < 0 || cmd.index() >= hints.size()) {
            cmd.replyTo().tell(new RoomResponse.Rejected("invalid_hint",
                catalog.get("err.invalid_hint")));
            return Effect().none();
        }
        var hint = hints.get(cmd.index());
        var action = hint.action();

        // Parse action type:param encoding
        var colonIdx = action.indexOf(':');
        var actionType = colonIdx >= 0 ? action.substring(0, colonIdx) : action;
        var actionParam = colonIdx >= 0 ? action.substring(colonIdx + 1) : "";

        return switch (actionType) {
            case "look" -> {
                // Treat as speech so the companion can narrate (not just a silent state refresh)
                var event = new WorldEvent.Said(
                    roomId, Instant.now(), cmd.entityId(), "player", hint.label(), cmd.locale());
                yield Effect().persist(new RoomEvent(event))
                    .thenRun(newState -> {
                        notifySubscribers(event);
                        cmd.replyTo().tell(new RoomResponse.Ok(localizedSnapshot(newState, cmd.locale())));
                        runScriptHook(newState, cmd.locale(), "onSay", cmd.entityId(), "player", hint.label());
                    });
            }
            case "go" -> {
                var exit = state.exits().get(actionParam);
                if (exit == null) {
                    cmd.replyTo().tell(new RoomResponse.Rejected("no_exit",
                        catalog.get("err.exit_gone")));
                    yield Effect().none();
                }
                cmd.replyTo().tell(new RoomResponse.HintAction("go", actionParam, exit.targetRoom()));
                yield Effect().none();
            }
            case "take" -> onTakeObject(state, new RoomCommand.TakeObject(cmd.entityId(), actionParam, cmd.locale(), cmd.replyTo()));
            case "use" -> {
                // Phase 2 encoding: actionParam may be "<name>|<args>" when the
                // hint comes from a script-declared `manifest.commands` entry.
                // Split on '|' so the script receives params.args via UseObject.target.
                String useName;
                String useArgs;
                var pipeIdx = actionParam.indexOf('|');
                if (pipeIdx >= 0) {
                    useName = actionParam.substring(0, pipeIdx);
                    useArgs = actionParam.substring(pipeIdx + 1);
                } else {
                    useName = actionParam;
                    useArgs = null;
                }
                yield onUseObject(state, new RoomCommand.UseObject(
                    cmd.entityId(), useName, useArgs, cmd.locale(), cmd.replyTo()));
            }
            case "examine" -> {
                // Look up object by name (case-insensitive) and narrate its description.
                // Distinct from `use` — examine reveals what the object IS so the user can
                // then decide whether/how to interact via `use`. Replies with
                // RoomResponse.Narrated (not Ok) so client sessions don't redraw the room
                // over the narration line — the Said event arrives separately as Prose.
                var obj = state.objects().values().stream()
                    .filter(o -> actionParam.equalsIgnoreCase(o.name()))
                    .findFirst().orElse(null);
                if (obj == null) {
                    cmd.replyTo().tell(new RoomResponse.Rejected("not_found",
                        "There is no " + actionParam + " here."));
                    yield Effect().none();
                }
                var description = (obj.description() == null || obj.description().isBlank())
                    ? "It's a " + obj.name() + ". You don't notice anything else about it."
                    : obj.description();
                var event = new WorldEvent.Said(
                    roomId, Instant.now(), cmd.entityId(), "narrator", description, cmd.locale());
                yield Effect().persist(new RoomEvent(event))
                    .thenRun(newState -> {
                        notifySubscribers(event);
                        cmd.replyTo().tell(new RoomResponse.Narrated());
                    });
            }
            case "say" -> {
                // Say action: use actionParam as the speech text, trigger room script onSay.
                // Reply Narrated, not Ok(snapshot) — the Said event reaches subscribers
                // as Prose, and an Ok(snapshot) here would redraw the room.
                var sayText = actionParam.isEmpty() ? hint.label() : actionParam;
                var event = new WorldEvent.Said(
                    roomId, Instant.now(), cmd.entityId(), "player", sayText, cmd.locale());
                yield Effect().persist(new RoomEvent(event))
                    .thenRun(newState -> {
                        notifySubscribers(event);
                        cmd.replyTo().tell(new RoomResponse.Narrated());
                        runScriptHook(newState, cmd.locale(), "onSay", cmd.entityId(), "player", sayText);
                    });
            }
            default -> {
                // Unknown action type: treat as speech with label. Narrated for the
                // same reason as the "say" branch — Said events are delivered as
                // Prose, no redraw needed.
                var event = new WorldEvent.Said(
                    roomId, Instant.now(), cmd.entityId(), "player", hint.label(), cmd.locale());
                yield Effect().persist(new RoomEvent(event))
                    .thenRun(newState -> {
                        notifySubscribers(event);
                        cmd.replyTo().tell(new RoomResponse.Narrated());
                    });
            }
        };
    }

    private Effect<RoomEvent, RoomState> onUpdateHints(RoomState state, RoomCommand.UpdateHints cmd) {
        if (state.name().isEmpty()) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_found",
                ScriptMessageCatalog.forLang("en").get("err.room_not_initialized")));
            return Effect().none();
        }
        var event = new WorldEvent.HintsUpdated(roomId, Instant.now(), cmd.hints());
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.Ok(newState.toSnapshot()));
            });
    }

    private Effect<RoomEvent, RoomState> onSubscribe(RoomState state, RoomCommand.Subscribe cmd) {
        subscribers.put(cmd.subscriber(), cmd.visibility());
        if (cmd.entityId() != null) {
            entitySubscribers
                .computeIfAbsent(cmd.entityId(), k -> new LinkedHashSet<>())
                .add(cmd.subscriber());
        }
        context.watchWith(cmd.subscriber(), new RoomCommand.Unsubscribe(cmd.subscriber()));
        return Effect().none();
    }

    private Effect<RoomEvent, RoomState> onUnsubscribe(RoomState state, RoomCommand.Unsubscribe cmd) {
        subscribers.remove(cmd.subscriber());
        entitySubscribers.values().forEach(s -> s.remove(cmd.subscriber()));
        entitySubscribers.values().removeIf(Set::isEmpty);
        return Effect().none();
    }

    // --- i18n hint generation ---

    /**
     * Build a snapshot with locale-aware hints.
     * All command handlers that return a snapshot to a client should use this
     * instead of raw state.toSnapshot(), so hints are always in the correct language.
     */
    private RoomSnapshot localizedSnapshot(RoomState state, String locale) {
        var snapshot = state.toSnapshot();
        var hints = buildCurrentHints(state, locale);
        return new RoomSnapshot(
            snapshot.roomId(), snapshot.name(), snapshot.description(), snapshot.zone(),
            snapshot.aliases(), snapshot.exits(), snapshot.entities(), snapshot.objects(), hints);
    }

    /**
     * Build the current hint list for a room + locale.
     * Used by both onLookRoom (to send to client) and onSelectHint (to resolve indices).
     * This is the single source of truth for hint ordering.
     */
    private List<Hint> buildCurrentHints(RoomState state, String locale) {
        return buildCurrentHints(state, locale, null);
    }

    /**
     * Build the hint list for the given caller. {@code callerId} gates per-entity
     * hints — currently the "Stand" hint, which only surfaces
     * when the asking entity itself has a non-null posture. Pass null for
     * broadcast snapshots where there is no specific caller.
     */
    private List<Hint> buildCurrentHints(RoomState state, String locale, String callerId) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        var hints = new ArrayList<Hint>();
        boolean hasScriptHints = false;
        if (scriptEngine != null) {
            scriptEngine.setLocale(locale);
            scriptEngine.syncState(state);
            var scriptHints = scriptEngine.getHints();
            if (scriptHints.isPresent()) {
                hints.addAll(scriptHints.get());
                hasScriptHints = true;
            }
        }
        if (!hasScriptHints) {
            hints.add(new Hint(catalog.get("ui.tell_about"), "describe_room", "look", "ui.tell_about"));
        }
        // Object hints are appended for EVERY room, scripted or not.
        //
        // They used to be gated behind !hasScriptHints, which meant a room whose script
        // defined getHints() lost every object affordance. The Study is the worst case:
        // its script offers a curated five (help / schedule / desk / shelves / west), so
        // its ten furnishings — the bond crystal, both ledgers, the pinboard, the
        // journal, the ward stone — appeared in `look` but had NO entry in `actions`.
        // They were reachable only by a player who already knew to type
        // "examine journal", which is precisely the knowledge a discovery menu exists
        // to supply. Curated hints now lead, and the objects follow them.
        appendObjectHints(hints, state, catalog);
        // sittable furnishings surface a Sit hint regardless
        // of whether the room is scripted (chairs are universal). Preposition is
        // derived from the object name; see {@link #sitPreposition}. The command
        // text matches the CommandParser.Sit recogniser.
        for (var obj : state.objects().values()) {
            if (obj.isFlag("sittable")) {
                var prep = sitPreposition(obj.name());
                hints.add(new Hint(
                    "Sit " + prep + " " + obj.name(),
                    "sit_" + obj.id(),
                    "sit " + prep + " " + obj.name(),
                    "ui.sit"));
            }
        }
        // Stand hint surfaces only when the asking entity
        // has a non-null posture. Broadcast snapshots (callerId == null) never
        // see this hint — it is, by definition, a per-entity affordance.
        if (callerId != null) {
            var caller = state.entities().get(callerId);
            if (caller != null && caller.posture() != null) {
                hints.add(new Hint("Stand up", "stand", "stand", "ui.stand"));
            }
        }
        // Always add translated navigation hints from exits
        for (var entry : state.exits().entrySet()) {
            var direction = entry.getKey();
            var dirKey = "ui." + direction;
            var dirName = catalog.hasKey(dirKey) ? catalog.get(dirKey) : direction;
            hints.add(new Hint(catalog.get("ui.go", dirName),
                "navigate_" + direction, "go:" + direction, "ui.go"));
        }
        // Final guard: two hints that dispatch the SAME action are one affordance wearing
        // two labels, and render as a glitch in a numbered menu. An item script's
        // no-arg command can collide with a room script's curated entry this way. First
        // wins, so the room's curated label ("Browse your Library") beats the derived one.
        return dedupeByAction(hints);
    }

    private static List<Hint> dedupeByAction(List<Hint> hints) {
        var seen = new HashSet<String>();
        var out = new ArrayList<Hint>(hints.size());
        for (var h : hints) {
            var key = h.action() == null ? null
                : h.action().strip().toLowerCase();
            if (key != null && !seen.add(key)) continue;
            out.add(h);
        }
        return out;
    }

    /**
     * Append Examine/Take/Use hints for every visible object, skipping any verb+object
     * pair a room script already offers. Dedupe resolves the script's action target
     * ("use:desk") against each object's id, name, and aliases, so the Study's curated
     * "Use desk" suppresses the derived "Use heavy desk" instead of doubling it.
     */
    private void appendObjectHints(List<Hint> hints, RoomState state,
                                    ScriptMessageCatalog catalog) {
        var covered = new HashSet<String>();
        for (var h : hints) {
            var action = h.action();
            if (action == null) continue;
            var colon = action.indexOf(':');
            if (colon <= 0) continue;
            var verb = action.substring(0, colon).strip().toLowerCase();
            var target = action.substring(colon + 1).strip().toLowerCase();
            // A script hint may address an object by id, name, or alias — resolve it
            // to the object's id so the derived hint for that same object is suppressed.
            for (var obj : state.objects().values()) {
                if (matchesObject(obj, target)) covered.add(verb + ":" + obj.id());
            }
        }
        for (var obj : state.objects().values()) {
            if (covered.add("examine:" + obj.id())) {
                hints.add(new Hint(catalog.get("ui.examine", obj.name()),
                    "examine_" + obj.id(), "examine:" + obj.name(), "ui.examine"));
            }
            if (obj.takeable()) {
                if (covered.add("take:" + obj.id())) {
                    hints.add(new Hint(catalog.get("ui.take", obj.name()),
                        "take_" + obj.id(), "take:" + obj.name(), "ui.take"));
                }
            } else if (covered.add("use:" + obj.id())) {
                // Non-takeable furnishings: offer Use for scripted interaction.
                hints.add(new Hint(catalog.get("ui.use", obj.name()),
                    "use_" + obj.id(), "use:" + obj.name(), "ui.use"));
            }
            // Items-as-tools contract — script-declared sub-verbs surface as their own
            // entries whether or not the object is takeable (see appendScriptCommandHints).
            appendScriptCommandHints(hints, obj.id(), obj.name());
        }
    }

    /** True when {@code target} names this object by id, name, or any alias. */
    private static boolean matchesObject(RoomObject obj, String target) {
        if (target == null || target.isBlank()) return false;
        if (target.equalsIgnoreCase(obj.id()) || target.equalsIgnoreCase(obj.name())) return true;
        if (obj.aliases() != null) {
            for (var a : obj.aliases()) {
                if (target.equalsIgnoreCase(a)) return true;
            }
        }
        return false;
    }

    /**
     * Heuristic preposition for sit hint label. Defaults to "at" (works for
     * tables, hearths, podiums). Recognises seat-like nouns ("in"), surface-like
     * nouns ("on"). The preposition is mainly cosmetic — CommandParser accepts
     * any of "sit at/on/in {target}" identically.
     */
    static String sitPreposition(String objectName) {
        if (objectName == null) return "at";
        var lower = objectName.toLowerCase(Locale.ROOT);
        if (lower.contains("chair") || lower.contains("couch") || lower.contains("sofa")
                || lower.contains("seat") || lower.contains("armchair")) {
            return "in";
        }
        if (lower.contains("bench") || lower.contains("floor") || lower.contains("mat")
                || lower.contains("stool") || lower.contains("cushion") || lower.contains("rug")) {
            return "on";
        }
        return "at";
    }

    // --- Script integration ---

    private void runScriptHook(RoomState state, String locale, String hookName, Object... args) {
        if (scriptEngine == null) return;
        try {
            setLocaleContext(locale);
            scriptEngine.setLocale(locale);
            scriptEngine.syncState(state);
            // ultrareview merged_bug_004 / #419 — every hook invoked in this
            // file passes the acting entity id as args[0] (onSay, onUse, onTake,
            // onDrop, onExamine, onEnter, onEmote). Threading it into WorldApi
            // is required for any per-user hook (writeJournalEntry, voice
            // mirror set/freeze/revert, etc.); without this, scripts that read
            // currentEntityId silently get null and short-circuit with
            // "[No user identity available]".
            if (args != null && args.length > 0 && args[0] instanceof String entityId
                    && !entityId.isBlank()) {
                scriptEngine.setCurrentEntityId(entityId);
            }
            var emissions = scriptEngine.invokeHook(hookName, args);
            processEmissions(emissions);
            // §31 — scripts may call world.scheduleTimer from ANY hook.
            // Drain after every invocation so the request actually lands.
            drainTimerRequests();
        } catch (Exception e) {
            log.error("Script hook {} failed in room {}: {}", hookName, roomId, e.getMessage());
        }
    }

    // --- §31 script timers ---

    /**
     * Drain pending {@code world.scheduleTimer} requests from the script
     * engine and start them on the actor's TimerScheduler. Re-scheduling an
     * existing timerId replaces its interval; new timers beyond
     * {@link #MAX_ROOM_TIMERS} are dropped with a warning (runaway guard —
     * a timer hook is allowed to schedule more timers, but not without bound).
     */
    private void drainTimerRequests() {
        if (scriptEngine == null || timers == null) return;
        for (var req : scriptEngine.consumeTimerRequests()) {
            boolean replacing = activeTimers.containsKey(req.timerId());
            if (!replacing && activeTimers.size() >= MAX_ROOM_TIMERS) {
                log.warn("Room {} timer cap ({}) reached — dropping timer '{}' → {}",
                    roomId, MAX_ROOM_TIMERS, req.timerId(), req.hookName());
                continue;
            }
            activeTimers.put(req.timerId(), req.hookName());
            timers.startTimerWithFixedDelay(
                "room-timer-" + req.timerId(),
                new RoomCommand.TimerFired(req.timerId(), req.hookName()),
                Duration.ofSeconds(req.intervalSeconds()));
            log.info("Room {} {} script timer '{}' every {}s → {}",
                roomId, replacing ? "rescheduled" : "scheduled",
                req.timerId(), req.intervalSeconds(), req.hookName());
        }
    }

    /** Cancel a script timer (world.cancelTimer emission, or engine gone). */
    private void cancelRoomTimer(String timerId) {
        if (timers != null) {
            timers.cancel("room-timer-" + timerId);
        }
        activeTimers.remove(timerId);
    }

    /** §31 — a script-scheduled timer fired: run its hook, process emissions. */
    private Effect<RoomEvent, RoomState> onTimerFired(RoomState state, RoomCommand.TimerFired cmd) {
        if (scriptEngine == null) {
            cancelRoomTimer(cmd.timerId());
            return Effect().none();
        }
        try {
            var emissions = scriptEngine.invokeTimer(state, cmd.timerId(), cmd.hookName());
            processEmissions(emissions);
            drainTimerRequests();
        } catch (Exception e) {
            log.error("Timer hook {} ({}) failed in room {}: {}",
                cmd.hookName(), cmd.timerId(), roomId, e.getMessage());
        }
        return Effect().none();
    }

    /**
     * Run a named script hook on behalf of an external caller (CompanionActor's
     * workbench/dispatch outcomes, room-declared tool calls). Replies with the
     * narration the hook emitted so tool loops receive real findings.
     */
    private Effect<RoomEvent, RoomState> onInvokeScriptHook(
            RoomState state, RoomCommand.InvokeScriptHook cmd) {
        var narration = new StringBuilder();
        if (scriptEngine != null) {
            try {
                scriptEngine.syncState(state);
                var args = cmd.args() == null ? new Object[0] : cmd.args().toArray();
                if (args.length > 0 && args[0] instanceof String entityId
                        && !entityId.isBlank()) {
                    scriptEngine.setCurrentEntityId(entityId);
                }
                var emissions = scriptEngine.invokeHook(cmd.hookName(), args);
                for (var emission : emissions) {
                    if ("narrate".equals(emission.eventType())) {
                        var text = String.valueOf(emission.data().getOrDefault("text", ""));
                        if (!text.isEmpty()) {
                            if (narration.length() > 0) narration.append('\n');
                            narration.append(text);
                        }
                    }
                }
                processEmissions(emissions);
                drainTimerRequests();
            } catch (Exception e) {
                log.error("InvokeScriptHook {} failed in room {}: {}",
                    cmd.hookName(), roomId, e.getMessage());
            }
        }
        if (cmd.replyTo() != null) {
            cmd.replyTo().tell(new RoomResponse.HookRan(narration.toString()));
        }
        return Effect().none();
    }

    /** Room-scoped agent tools — query the script's getToolDefinitions(). */
    private Effect<RoomEvent, RoomState> onGetToolDefinitions(
            RoomState state, RoomCommand.GetToolDefinitions cmd) {
        var tools = List.<Map<String, Object>>of();
        if (scriptEngine != null) {
            try {
                scriptEngine.syncState(state);
                tools = scriptEngine.getToolDefinitions();
            } catch (Exception e) {
                log.debug("getToolDefinitions failed in room {}: {}", roomId, e.getMessage());
            }
        }
        cmd.replyTo().tell(new RoomResponse.ToolDefinitions(tools));
        return Effect().none();
    }

    /** Set I18n thread-local locale for the current command processing. */
    private void setLocaleContext(String locale) {
        if (locale != null && !locale.isEmpty()) {
            I18n.setLocale(Locale.forLanguageTag(locale));
        }
    }

    @SuppressWarnings("unchecked")
    private void processEmissions(List<RoomScriptEngine.ScriptEmission> emissions) {
        for (var emission : emissions) {
            switch (emission.eventType()) {
                case "narrate" -> {
                    var text = String.valueOf(emission.data().getOrDefault("text", ""));
                    if (!text.isEmpty()) {
                        var event = new WorldEvent.Said(
                            roomId, Instant.now(), "narrator", "narrator", text);
                        notifySubscribers(event);
                    }
                }
                case "description_changed" -> {
                    var text = String.valueOf(emission.data().getOrDefault("text", ""));
                    var reason = String.valueOf(emission.data().getOrDefault("reason", "script"));
                    if (!text.isEmpty()) {
                        var event = new WorldEvent.DescriptionChanged(
                            roomId, Instant.now(), text, reason);
                        notifySubscribers(event);
                    }
                }
                case "hints_updated" -> {
                    var hintsData = emission.data().get("hints");
                    if (hintsData instanceof List<?> hintList) {
                        var hints = parseHintsFromEmission((List<Map<String, Object>>) hintList);
                        var event = new WorldEvent.HintsUpdated(
                            roomId, Instant.now(), hints);
                        notifySubscribers(event);
                    }
                }
                case "property_changed" -> {
                    var key = String.valueOf(emission.data().getOrDefault("key", ""));
                    var value = String.valueOf(emission.data().getOrDefault("value", ""));
                    if (!key.isEmpty()) {
                        // Persist via the item-bridge SetProperty path (self-send)
                        // instead of notify-only. WorldApi re-syncs its property
                        // map from RoomState before every hook, so a notify-only
                        // property "write" evaporated by the next invocation —
                        // world.setProperty/getProperty across calls (workshop's
                        // craft_session, recorder's log, the Oracle's predictions)
                        // silently never worked (audit 2026-07-11).
                        context.getSelf().tell(new RoomCommand.ItemBridgeAction(
                            "script",
                            new RoomCommand.ItemBridgeSubAction.SetProperty(key, value)));
                    }
                }
                case "object_added" -> {
                    var objId = String.valueOf(emission.data().getOrDefault("objectId", ""));
                    var objName = String.valueOf(emission.data().getOrDefault("objectName", ""));
                    var objDesc = String.valueOf(emission.data().getOrDefault("description", ""));
                    var takeable = "true".equals(String.valueOf(emission.data().getOrDefault("takeable", "false")));
                    if (!objId.isEmpty() && !objName.isEmpty()) {
                        var event = new WorldEvent.ObjectAdded(
                            roomId, Instant.now(), objId, objName, objDesc, takeable);
                        notifySubscribers(event);
                    }
                }
                case "object_removed" -> {
                    var objId = String.valueOf(emission.data().getOrDefault("objectId", ""));
                    if (!objId.isEmpty()) {
                        var event = new WorldEvent.ObjectTaken(
                            roomId, Instant.now(), "script", objId, objId);
                        notifySubscribers(event);
                    }
                }
                case "entity_removed" -> {
                    var entityId = String.valueOf(emission.data().getOrDefault("entityId", ""));
                    if (!entityId.isEmpty()) {
                        var event = new WorldEvent.EntityLeft(
                            roomId, Instant.now(), entityId, entityId, "removed");
                        notifySubscribers(event);
                    }
                }
                case "timer_cancelled" -> {
                    // §31 — world.cancelTimer(id): stop the actor-side timer.
                    var timerId = String.valueOf(emission.data().getOrDefault("timerId", ""));
                    if (!timerId.isEmpty()) {
                        cancelRoomTimer(timerId);
                        log.info("Room {} script timer '{}' cancelled", roomId, timerId);
                    }
                }
                case "broadcast" -> {
                    // std/behavior/announcer.js — announce to everyone attached to
                    // this room. Delivered as narrator speech: local surfaces get
                    // it directly and the Between replication listener carries it
                    // to remote subscribers. (There is no cross-ROOM fanout wire;
                    // the mixin's contract is "announce beyond the walls", and
                    // room subscribers — including remote nodes — are that reach.)
                    var text = String.valueOf(emission.data().getOrDefault("text", ""));
                    if (!text.isEmpty()) {
                        notifySubscribers(new WorldEvent.Said(
                            roomId, Instant.now(), "narrator", "narrator", text));
                    }
                }
                case "oracle_action" -> {
                    var action = String.valueOf(emission.data().getOrDefault("action", ""));
                    var actorId = String.valueOf(emission.data().getOrDefault("entityId", ""));
                    handleOracleAction(action, actorId);
                }
                case "exit_locked" -> {
                    var direction = String.valueOf(emission.data().getOrDefault("direction", ""));
                    if (!direction.isEmpty()) {
                        var event = new WorldEvent.ExitClosed(roomId, Instant.now(), direction);
                        notifySubscribers(event);
                    }
                }
                case "exit_unlocked" -> {
                    var direction = String.valueOf(emission.data().getOrDefault("direction", ""));
                    if (!direction.isEmpty()) {
                        log.info("Exit {} unlocked in room {} by script", direction, roomId);
                    }
                }
                case "vitality_suggested" -> {
                    var entityId = String.valueOf(emission.data().getOrDefault("entityId", ""));
                    var tank = String.valueOf(emission.data().getOrDefault("tank", ""));
                    var delta = Double.parseDouble(
                        String.valueOf(emission.data().getOrDefault("delta", "0")));
                    var reason = String.valueOf(emission.data().getOrDefault("reason", "script"));
                    if (!entityId.isEmpty() && !tank.isEmpty()) {
                        var event = new WorldEvent.VitalitySuggested(
                            roomId, Instant.now(), entityId, tank, delta, reason);
                        notifySubscribers(event);
                    }
                }
                case "exit_creation_requested" -> {
                    var direction = String.valueOf(emission.data().getOrDefault("direction", ""));
                    var targetRoomId = String.valueOf(emission.data().getOrDefault("targetRoomId", ""));
                    var label = String.valueOf(emission.data().getOrDefault("label", direction));
                    if (!direction.isEmpty() && !targetRoomId.isEmpty()) {
                        // Self-send AddExit command for proper persistence via command handler
                        var sink = context.spawnAnonymous(
                            Behaviors.<RoomResponse>receiveMessage(r -> Behaviors.stopped()));
                        context.getSelf().tell(new RoomCommand.AddExit(
                            direction, targetRoomId, label, sink));
                    }
                }
                case "exit_removal_requested" -> {
                    var direction = String.valueOf(emission.data().getOrDefault("direction", ""));
                    if (!direction.isEmpty()) {
                        // Notify as ExitClosed (ephemeral — full persistence
                        // requires a RemoveExit command, which we add as needed)
                        var event = new WorldEvent.ExitClosed(roomId, Instant.now(), direction);
                        notifySubscribers(event);
                    }
                }
                case "room_creation_requested" -> {
                    // Room creation requires ClusterSharding — forward as PropertyChanged
                    // notification so subscribers (ZoneGuardian) can create the room.
                    // The script has already been gated to Foundation rooms in WorldApi.
                    var newRoomId = String.valueOf(emission.data().getOrDefault("newRoomId", ""));
                    var name = String.valueOf(emission.data().getOrDefault("name", ""));
                    if (!newRoomId.isEmpty() && !name.isEmpty()) {
                        log.info("Room creation requested from {}: {} ({})",
                            roomId, name, newRoomId);
                        var event = new WorldEvent.PropertyChanged(
                            roomId, Instant.now(),
                            "room_creation_request",
                            null,
                            newRoomId + "|" + name + "|" +
                                emission.data().getOrDefault("description", "") + "|" +
                                emission.data().getOrDefault("zone", "player"));
                        notifySubscribers(event);
                    }
                }
                // Phase 4: in-world config apply. Scroll of Settings in a
                // Study emits this after a config write when the steward
                // requests the change take effect. We signal via
                // ConfigApplyCoordinator (out-of-process), which self-exits
                // so systemd's Restart=on-failure brings us back with the
                // new EnvironmentFile loaded.
                case "config_apply_requested" -> {
                    log.info("config_apply_requested received in room {} — "
                        + "scheduling controlled restart", roomId);
                    ConfigApplyCoordinator.requestRestart(
                        "scroll-of-settings in " + roomId);
                }
                // Room scripts emit {verb, target, actor} command events for
                // operations that need real backing (The Forge's soul verbs).
                // Without a registered consumer these used to vanish into the
                // debug log — pure theater. Bounded handlers only (local DB
                // reads, fire-and-forget tells); results narrate into the room.
                case "command" -> {
                    var verb = String.valueOf(emission.data().getOrDefault("verb", ""));
                    var target = String.valueOf(emission.data().getOrDefault("target", ""));
                    var actor = String.valueOf(emission.data().getOrDefault("actor", ""));
                    if (ForgeRoomBridge.canHandle(verb)) {
                        for (var line : ForgeRoomBridge.handle(verb, target, actor, roomId)) {
                            notifySubscribers(new WorldEvent.Said(
                                roomId, Instant.now(), "narrator", "narrator", line));
                        }
                    } else if (HostActionService.canHandle(verb)) {
                        // Study launchers (WorldApi.launchApp/launchFile/launchUrl)
                        // emit these; the service enforces the steward allowlists.
                        for (var line : HostActionService.handle(verb, target, actor)) {
                            notifySubscribers(new WorldEvent.Said(
                                roomId, Instant.now(), "narrator", "narrator", line));
                        }
                    } else if (StudyShellBridge.canHandle(verb)) {
                        // Study shelf surface (W1): fs_mount/fs_unmount write the
                        // host-side mount table the "skill" MCP service resolves
                        // shelf paths against; take imports a mounted file into
                        // the acting entity's inventory.
                        for (var line : StudyShellBridge.handle(verb, emission.data(), roomId)) {
                            notifySubscribers(new WorldEvent.Said(
                                roomId, Instant.now(), "narrator", "narrator", line));
                        }
                    } else {
                        // Honesty over theater (audit 2026-07-11): room scripts narrate
                        // confident confirmations ("Scaling request submitted...") for verbs
                        // NOTHING consumes — deploy/scale/watch_feed/list_alerts/lock_door/
                        // take all vanished here. Until real backing exists, say so in-room
                        // rather than letting the script's promise stand as a false success.
                        log.info("Unhandled command verb '{}' in room {} — narrating honestly",
                            verb, roomId);
                        notifySubscribers(new WorldEvent.Said(
                            roomId, Instant.now(), "narrator", "narrator",
                            "(Nothing in this household is wired to carry out '" + verb
                            + "' yet — the request was heard, but no machinery received it.)"));
                    }
                }
                default -> log.debug("Unhandled script emission: {} in room {}",
                    emission.eventType(), roomId);
            }
        }
    }

    /**
     * Wire a room script's {@code oracle_action} emission (the Oracle room's
     * "say train") to the REAL training path — the same
     * {@link OracleForgeHook#onForgeSleep} cycle the Forge runs during sleep
     * (train on accumulated events, then anticipate). Results land back in the
     * room asynchronously via self-sent narrate/set-property commands, so the
     * fresh predictions are durable in {@code oracle_predictions} where the
     * room script reads them. When no Oracle engine is running, say so
     * honestly instead of letting the script's "training in progress" stand.
     */
    private void handleOracleAction(String action, String actorId) {
        if (!"train".equals(action)) {
            log.debug("Unhandled oracle_action '{}' in room {}", action, roomId);
            return;
        }
        var self = context.getSelf();
        OracleBridge bridge = null;
        try {
            bridge = OracleBridge.getInstance();
        } catch (Exception e) {
            log.debug("OracleBridge unavailable: {}", e.getMessage());
        }
        if (bridge == null) {
            self.tell(new RoomCommand.ItemBridgeAction("oracle",
                new RoomCommand.ItemBridgeSubAction.Narrate(
                    "(The Oracle engine isn't running in this household right now — "
                    + "training happens during the next sleep cycle. "
                    + "The request has been noted.)")));
            return;
        }
        var userId = (actorId == null || actorId.isBlank()) ? "local" : actorId;
        log.info("Room {} oracle_action train — running Oracle cycle for '{}'", roomId, userId);
        new OracleForgeHook(bridge).onForgeSleep(userId, List.of(roomId))
            .thenAccept(predictions -> {
                if (predictions == null || predictions.isEmpty()) {
                    self.tell(new RoomCommand.ItemBridgeAction("oracle",
                        new RoomCommand.ItemBridgeSubAction.Narrate(
                            "The Oracle engine ran, but no new predictions surfaced — "
                            + "it needs more accumulated events to learn from.")));
                    return;
                }
                var json = OracleForgeHook.predictionsToJson(predictions);
                self.tell(new RoomCommand.ItemBridgeAction("oracle",
                    new RoomCommand.ItemBridgeSubAction.SetProperty(
                        "oracle_predictions", json)));
                self.tell(new RoomCommand.ItemBridgeAction("oracle",
                    new RoomCommand.ItemBridgeSubAction.Narrate(
                        "The Oracle finishes training — " + predictions.size()
                        + " fresh prediction(s) surface in the lens. Say 'predictions' to see them.")));
            });
    }

    private List<Hint> parseHintsFromEmission(List<Map<String, Object>> hintList) {
        var hints = new ArrayList<Hint>();
        for (var h : hintList) {
            var label = String.valueOf(h.getOrDefault("label", ""));
            var intent = String.valueOf(h.getOrDefault("intent", ""));
            var action = String.valueOf(h.getOrDefault("action", ""));
            if (!label.isEmpty()) {
                hints.add(new Hint(label, intent, action));
            }
        }
        return hints;
    }

    /** Whether this room is currently under quarantine (§4.2). */
    private boolean isQuarantined(RoomState state) {
        return "true".equals(state.properties().get("quarantine"));
    }

    /** Quarantine a room — persists as PropertyChanged event. */
    private Effect<RoomEvent, RoomState> onQuarantine(RoomState state, RoomCommand.Quarantine cmd) {
        // §4.2: only a warden, wizard, or the zone steward may quarantine. The
        // requester's entityId is a CLAIM (it can arrive over the multi-node
        // command-forward path), so verify it against the node authority set —
        // fail-closed. Without this the primary trusted any claimed entityId.
        if (!RoomAuthority.canQuarantine(cmd.entityId())) {
            log.warn("Quarantine DENIED for room {} — requester '{}' lacks authority",
                roomId, cmd.entityId());
            cmd.replyTo().tell(new RoomResponse.Rejected("not_authorized",
                "Only a warden, wizard, or the zone steward may quarantine a room."));
            return Effect().none();
        }
        if (isQuarantined(state)) {
            cmd.replyTo().tell(new RoomResponse.Rejected("already_quarantined",
                ScriptMessageCatalog.forLang("en").get("err.already_quarantined")));
            return Effect().none();
        }

        var event = new WorldEvent.PropertyChanged(
            roomId, Instant.now(), "quarantine", null, "true");
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.Ok(newState.toSnapshot()));
                log.info("Room {} quarantined by {} — reason: {}",
                    roomId, cmd.entityId(), cmd.reason());
            });
    }

    /** Lift quarantine on a room. */
    private Effect<RoomEvent, RoomState> onUnquarantine(RoomState state, RoomCommand.Unquarantine cmd) {
        // Same authority gate as quarantine (§4.2) — lifting is equally privileged.
        if (!RoomAuthority.canQuarantine(cmd.entityId())) {
            log.warn("Unquarantine DENIED for room {} — requester '{}' lacks authority",
                roomId, cmd.entityId());
            cmd.replyTo().tell(new RoomResponse.Rejected("not_authorized",
                "Only a warden, wizard, or the zone steward may lift a quarantine."));
            return Effect().none();
        }
        if (!isQuarantined(state)) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_quarantined",
                ScriptMessageCatalog.forLang("en").get("err.not_quarantined")));
            return Effect().none();
        }

        var event = new WorldEvent.PropertyChanged(
            roomId, Instant.now(), "quarantine", "true", null);
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.Ok(newState.toSnapshot()));
                log.info("Room {} quarantine lifted by {}", roomId, cmd.entityId());
            });
    }

    /** Read-only snapshot query — no persistence, no side effects. */
    private Effect<RoomEvent, RoomState> onGetSnapshot(RoomState state, RoomCommand.GetSnapshot cmd) {
        cmd.replyTo().tell(state.toSnapshot());
        return Effect().none();
    }

    private Effect<RoomEvent, RoomState> onUpdateEntityDescription(
            RoomState state, RoomCommand.UpdateEntityDescription cmd) {
        var entity = state.entities().get(cmd.entityId());
        if (entity == null) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_found", "Entity not in room"));
            return Effect().none();
        }
        // Update entity in-memory (no event — description is transient, restored on re-enter)
        var newEntities = new HashMap<>(state.entities());
        newEntities.put(cmd.entityId(),
            new Entity(entity.id(), entity.name(), entity.type(), cmd.description(), entity.did()));
        var newState = new RoomState(state.roomId(), state.name(), state.description(), state.zone(),
            state.exits(), Map.copyOf(newEntities), state.objects(), state.hints(), state.properties());
        cmd.replyTo().tell(new RoomResponse.Ok(localizedSnapshot(newState, "en")));
        return Effect().none();
    }

    /**
     * Rename an entity in this room.
     *
     * <p>Updates {@link RoomState#entities()} in-memory so subsequent renders
     * (look, who, examine) reflect the new name. Persistence of the underlying
     * identity is the caller's responsibility (e.g. WyrdShellCommand updates
     * authService.displayName for players; companion rename writes to the
     * SoulManifest). Like {@code UpdateEntityDescription}, no event is
     * persisted — the canonical name lives outside the room state.</p>
     *
     * <p>Empty / blank new name is rejected.</p>
     */
    private Effect<RoomEvent, RoomState> onUpdateEntityName(
            RoomState state, RoomCommand.UpdateEntityName cmd) {
        if (cmd.newName() == null || cmd.newName().isBlank()) {
            cmd.replyTo().tell(new RoomResponse.Rejected("invalid_name",
                "Name cannot be empty"));
            return Effect().none();
        }
        var entity = state.entities().get(cmd.entityId());
        if (entity == null) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_found", "Entity not in room"));
            return Effect().none();
        }
        var trimmedName = cmd.newName().trim();
        var newEntities = new HashMap<>(state.entities());
        newEntities.put(cmd.entityId(),
            new Entity(entity.id(), trimmedName, entity.type(), entity.description(), entity.did()));
        var newState = new RoomState(state.roomId(), state.name(), state.description(), state.zone(),
            state.exits(), Map.copyOf(newEntities), state.objects(), state.hints(), state.properties());
        cmd.replyTo().tell(new RoomResponse.Ok(localizedSnapshot(newState, "en")));
        return Effect().none();
    }

    /**
     * set an entity's posture in this room. Persists
     * {@link WorldEvent.PostureChanged}; replay updates the entity's posture
     * field via {@link RoomState#apply}. Reply is {@link RoomResponse.Narrated} —
     * body change is narrated via the broadcast event, no full snapshot push.
     */
    private Effect<RoomEvent, RoomState> onSetPosture(
            RoomState state, RoomCommand.SetPosture cmd) {
        if (cmd.posture() == null) {
            cmd.replyTo().tell(new RoomResponse.Rejected("invalid_posture",
                "Posture must not be null (use ClearPosture to remove)"));
            return Effect().none();
        }
        var entity = state.entities().get(cmd.entityId());
        if (entity == null) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_found", "Entity not in room"));
            return Effect().none();
        }
        var now = Instant.now();

        // enrich the incoming posture if the atObject
        // matches a sittable room object with a custom sitDescriptor /
        // sitBodyLanguage in its state map. This keeps the SSH/Telnet/CLI
        // dispatchers dumb (they just pass a generic descriptor) and lets
        // furnishing authors declare per-object body narration declaratively.
        var posture = cmd.posture();
        if (posture.atObject() != null && !posture.atObject().isBlank()) {
            final var atObjectQuery = posture.atObject();
            var matchedObj = state.objects().values().stream()
                .filter(o -> o.isFlag("sittable") && matchesObjectAlias(o, atObjectQuery))
                .findFirst();
            if (matchedObj.isPresent()) {
                var obj = matchedObj.get();
                var customDesc = obj.state().get("sitDescriptor");
                if (customDesc != null && !customDesc.isBlank()) {
                    var enrichedDesc = entity.name() + " " + customDesc;
                    posture = new Posture(
                        posture.verb(), obj.id(), enrichedDesc,
                        posture.setAt() == null ? now : posture.setAt(),
                        posture.innerImprint());
                }
                // Emit body-language line as Emoted (so observers see it as
                // distinct from speech). .D.4 / B.6.polish:
                // run through PostureTemplates so {actor} / {they} / {their}
                // placeholders inflect correctly, AND apply legacyNameSwap as
                // a fallback for templates that still use bare "they" subject
                // (gives correct 3rd-person-singular verb forms).
                var bodyLang = obj.state().get("sitBodyLanguage");
                if (bodyLang != null && !bodyLang.isBlank()) {
                    var substituted = PostureTemplates.substitute(
                        bodyLang, entity.name(),
                        PostureTemplates.Pronouns.DEFAULT);
                    // If the template didn't use placeholders, fall back to
                    // legacy "they"-swap with grammar correction.
                    if (substituted.equals(bodyLang) && substituted.contains("they")) {
                        substituted = PostureTemplates.legacyNameSwap(
                            bodyLang, entity.name());
                    }
                    notifySubscribers(new WorldEvent.Emoted(
                        roomId, now, entity.id(), entity.name(), substituted));
                }
            }
        }

        var event = new WorldEvent.PostureChanged(
            roomId, now,
            entity.id(), entity.name(),
            entity.posture(), posture);
        notifySubscribers(event);
        cmd.replyTo().tell(new RoomResponse.Narrated());
        return Effect().persist(new RoomEvent(event));
    }

    /**
     * Match a room object by id, name, or alias against a user-typed string
     * (case-insensitive). Used by {@link #onSetPosture} to find the sittable
     * furnishing the user named.
     */
    private static boolean matchesObjectAlias(RoomObject obj, String query) {
        if (obj == null || query == null) return false;
        var lower = query.trim().toLowerCase(Locale.ROOT);
        if (obj.id() != null && obj.id().toLowerCase(Locale.ROOT).equals(lower)) return true;
        if (obj.name() != null && obj.name().toLowerCase(Locale.ROOT).contains(lower)) return true;
        if (lower.contains(obj.name() == null ? "" : obj.name().toLowerCase(Locale.ROOT))) return true;
        if (obj.aliases() != null) {
            for (var alias : obj.aliases()) {
                if (alias != null && alias.toLowerCase(Locale.ROOT).equals(lower)) return true;
            }
        }
        return false;
    }

    /**
     * clear an entity's posture. Idempotent ack when the
     * entity is already in the default state.
     */
    private Effect<RoomEvent, RoomState> onClearPosture(
            RoomState state, RoomCommand.ClearPosture cmd) {
        var entity = state.entities().get(cmd.entityId());
        if (entity == null) {
            cmd.replyTo().tell(new RoomResponse.Rejected("not_found", "Entity not in room"));
            return Effect().none();
        }
        if (entity.posture() == null) {
            cmd.replyTo().tell(new RoomResponse.Narrated());
            return Effect().none();
        }
        var event = new WorldEvent.PostureChanged(
            roomId, Instant.now(),
            entity.id(), entity.name(),
            entity.posture(), null);
        notifySubscribers(event);
        cmd.replyTo().tell(new RoomResponse.Narrated());
        return Effect().persist(new RoomEvent(event));
    }

    /**
     * Set or update a room's behavior script. Writes the script to the user scripts directory
     * and invalidates the ScriptLoader cache so the next hook invocation picks it up.
     * Persists as a PropertyChanged event for recovery.
     */
    private Effect<RoomEvent, RoomState> onSetBehaviorScript(RoomState state,
                                                               RoomCommand.SetBehaviorScript cmd) {
        if (scriptLoader == null) {
            cmd.replyTo().tell(new RoomResponse.Rejected("no_script_loader",
                "Script system not available"));
            return Effect().none();
        }

        // Write script to user scripts directory
        try {
            // ScriptLoader checks userScriptsDir first, so writing there takes priority
            var userDir = scriptLoader.getUserScriptsDir();
            if (userDir == null) {
                cmd.replyTo().tell(new RoomResponse.Rejected("no_user_scripts_dir",
                    "User scripts directory not configured"));
                return Effect().none();
            }

            Files.createDirectories(userDir);
            var scriptToWrite = cmd.script();
            if (cmd.append()) {
                // Mixin install (std/behavior): concatenate AFTER the room's
                // current script so the mixin's assignment-style hook chaining
                // (`onEnter = function(...)`) wraps the existing hooks. Dedup
                // guard: if the current script already carries the mixin's
                // first line (its header comment), this is a re-install — skip
                // the write so hooks don't fire twice.
                var existing = scriptLoader.load(roomId);
                var firstLine = cmd.script().lines().findFirst().orElse("");
                if (existing != null && !firstLine.isBlank()
                        && existing.contains(firstLine)) {
                    log.info("Room '{}' already has script starting '{}' — skipping duplicate append",
                        roomId, firstLine);
                    cmd.replyTo().tell(new RoomResponse.Ok(state.toSnapshot()));
                    return Effect().none();
                }
                scriptToWrite = (existing == null || existing.isBlank())
                    ? cmd.script()
                    : existing + "\n\n" + cmd.script();
            }
            Files.writeString(userDir.resolve(roomId + ".js"), scriptToWrite);
            scriptLoader.invalidate(roomId);

            log.info("Behavior script {} for room '{}' by '{}' ({} bytes)",
                cmd.append() ? "appended" : "set",
                roomId, cmd.requesterId(), scriptToWrite.length());
        } catch (IOException e) {
            cmd.replyTo().tell(new RoomResponse.Rejected("script_write_failed",
                "Failed to write script: " + e.getMessage()));
            return Effect().none();
        }

        // Persist as property for recovery
        var event = new WorldEvent.PropertyChanged(
            roomId, Instant.now(), "behavior_script_set", null, "true");
        return Effect().persist(new RoomEvent(event))
            .thenRun(newState -> {
                notifySubscribers(event);
                cmd.replyTo().tell(new RoomResponse.Ok(newState.toSnapshot()));
            });
    }

    /** Get room capacity from properties, or defaults. */
    private RoomCapacity getRoomCapacity(RoomState state) {
        var maxEntities = parseIntProp(state.properties(), "capacity.maxEntities", 50);
        var maxAgents = parseIntProp(state.properties(), "capacity.maxAgents", 10);
        var maxObjects = parseIntProp(state.properties(), "capacity.maxObjects", 100);
        return new RoomCapacity(maxEntities, maxAgents, maxObjects);
    }

    private int parseIntProp(Map<String, String> props, String key, int defaultValue) {
        var val = props.get(key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Emit a narration describing an entity when looked at.
     * If the entity has a description, narrate "name: description".
     * Otherwise, prompt the viewer to set one (if self) or show a default.
     */
    private void emitEntityDescription(Entity entity, boolean isSelf) {
        var catalog = ScriptMessageCatalog.forLang("en");
        String text;
        if (entity.description() != null && !entity.description().isBlank()) {
            text = catalog.get("entity.description.has", entity.name(), entity.description());
        } else if (isSelf) {
            text = catalog.get("entity.description.self_none", entity.name());
        } else {
            text = catalog.get("entity.description.other_none", entity.name());
        }

        // Append visible equipped items (aspects with appearance)
        var equipService = EquipmentService.get();
        if (equipService != null) {
            var equipped = equipService.getEquipped(entity.id());
            var visibleItems = equipped.stream()
                .filter(e -> e.selfDescription() != null && !e.selfDescription().isBlank())
                .map(EquipmentState.EquippedItem::selfDescription)
                .toList();
            if (!visibleItems.isEmpty()) {
                text += " " + entity.name() + " is " + String.join(", ", visibleItems) + ".";
            }
        }

        var event = new WorldEvent.Said(roomId, Instant.now(), "narrator", "narrator", text);
        notifySubscribers(event);
    }

    /**
     * Broadcast a remote event to local subscribers without modifying room state.
     * Used by Between to forward events from other nodes.
     */
    private Effect<RoomEvent, RoomState> onBroadcastRemoteEvent(
            RoomState state, RoomCommand.BroadcastRemoteEvent cmd) {
        // Deliver to local session subscribers ONLY — do NOT call notifySubscribers()
        // because that would trigger the RoomEventReplicator which publishes back to NATS,
        // creating an infinite loop (BroadcastRemoteEvent → replicator → NATS → BroadcastRemoteEvent).
        notifySessionSubscribers(cmd.event());
        return Effect().none();
    }

    /**
     * handle a fire-and-forget action issued
     * by an item script via {@code world.room.*}. Each sub-action maps to a
     * concrete {@link WorldEvent} variant. State-modifying sub-actions
     * persist; transient ones (emit / narrate) just notify subscribers.
     */
    private Effect<RoomEvent, RoomState> onItemBridgeAction(
            RoomState state, RoomCommand.ItemBridgeAction cmd) {
        var caller = cmd.callerEntityId() == null ? "script" : cmd.callerEntityId();
        var now = Instant.now();
        var sub = cmd.action();
        if (sub instanceof RoomCommand.ItemBridgeSubAction.Emit emit) {
            var event = new WorldEvent.ScriptTriggered(
                roomId, now, emit.eventType(), caller,
                emit.data() == null ? Map.of() : emit.data());
            notifySubscribers(event);
            return Effect().none();
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.Narrate narrate) {
            var event = new WorldEvent.Said(
                roomId, now, caller, "narrator", narrate.text(), "en", null);
            notifySubscribers(event);
            return Effect().none();
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.AddObject add) {
            // A room is addressed by NAME, so two objects sharing one make both
            // unaddressable. Live 2026-08-20: two backend artifacts were both placed as
            // "codex"; `get codex` took one, a second `get codex` left two in hand, and
            // `use codex` then answered "No such object". Enforced here rather than at
            // each caller, because the room is the only thing that knows what is already
            // in it — and every source of objects gets the guarantee for free.
            // Exclude the object's OWN entry: re-adding an id is an UPDATE, and treating
            // its existing name as taken renamed it on every update — which broke
            // `use <furnishing> <args>` by shifting where the name ended and the args
            // began (caught by RoomActorFurnishingItemTest before this shipped).
            var taken = state.objects() == null ? List.<String>of()
                : state.objects().values().stream()
                    .filter(o -> o.id() == null || !o.id().equals(add.id()))
                    .map(RoomObject::name).toList();
            var name = ObjectNaming.unique(add.name(), taken, add.id());
            if (!name.equals(add.name())) {
                log.info("Room {}: '{}' is taken — placing as '{}'",
                    roomId, add.name(), name);
            }
            var event = new WorldEvent.ObjectAdded(
                roomId, now, add.id(), name, add.description(), add.takeable());
            return Effect().persist(new RoomEvent(event))
                .thenRun(newState -> notifySubscribers(event));
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.RemoveObject rem) {
            var event = new WorldEvent.ObjectTaken(
                roomId, now, caller, rem.id(), rem.id());
            return Effect().persist(new RoomEvent(event))
                .thenRun(newState -> notifySubscribers(event));
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.SetProperty setp) {
            var oldVal = state.properties() == null ? "" : String.valueOf(state.properties().getOrDefault(setp.key(), ""));
            var event = new WorldEvent.PropertyChanged(
                roomId, now, setp.key(), oldVal,
                setp.value() == null ? "" : setp.value());
            return Effect().persist(new RoomEvent(event))
                .thenRun(newState -> notifySubscribers(event));
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.UpdateDescription upd) {
            var event = new WorldEvent.DescriptionChanged(
                roomId, now, upd.text() == null ? "" : upd.text(), "item_script");
            return Effect().persist(new RoomEvent(event))
                .thenRun(newState -> notifySubscribers(event));
        }
        // scripted body-state writes
        if (sub instanceof RoomCommand.ItemBridgeSubAction.SetPosture setP) {
            if (setP.posture() == null) {
                log.debug("SetPosture sub-action with null posture from {}", caller);
                return Effect().none();
            }
            var entity = state.entities().get(setP.entityId());
            if (entity == null) {
                log.debug("SetPosture sub-action for unknown entity {}", setP.entityId());
                return Effect().none();
            }
            var event = new WorldEvent.PostureChanged(
                roomId, now, entity.id(), entity.name(),
                entity.posture(), setP.posture());
            return Effect().persist(new RoomEvent(event))
                .thenRun(newState -> notifySubscribers(event));
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.ClearPosture clearP) {
            var entity = state.entities().get(clearP.entityId());
            if (entity == null || entity.posture() == null) {
                return Effect().none();
            }
            var event = new WorldEvent.PostureChanged(
                roomId, now, entity.id(), entity.name(),
                entity.posture(), null);
            return Effect().persist(new RoomEvent(event))
                .thenRun(newState -> notifySubscribers(event));
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.LookAt look) {
            var actor = state.entities().get(look.actorId());
            var target = state.entities().get(look.targetId());
            var actorName = actor != null ? actor.name() : look.actorId();
            var targetName = target != null ? target.name() : look.targetId();
            var event = new WorldEvent.LookedAt(
                roomId, now, look.actorId(), actorName,
                look.targetId(), targetName, look.manner());
            notifySubscribers(event);
            return Effect().none();
        }
        if (sub instanceof RoomCommand.ItemBridgeSubAction.BroadcastBodyLanguage body) {
            var actor = state.entities().get(body.actorId());
            var actorName = actor != null ? actor.name() : body.actorId();
            var event = new WorldEvent.Emoted(
                roomId, now, body.actorId(), actorName, body.text());
            notifySubscribers(event);
            return Effect().none();
        }
        log.warn("Unknown ItemBridgeSubAction: {}", sub == null ? "null" : sub.getClass().getSimpleName());
        return Effect().none();
    }

    private void notifySubscribers(WorldEvent event) {
        // Phase 2: notify Between replication listener (event-sourced / write-through)
        if (eventListener != null) {
            try {
                eventListener.onRoomEvent(roomId, event);
            } catch (Exception e) {
                log.debug("Event listener failed for room {}: {}", roomId, e.getMessage());
            }
        }
        notifySessionSubscribers(event);
    }

    /** Deliver event to local session subscribers only (no replication listener). */
    private void notifySessionSubscribers(WorldEvent event) {
        var eventVisibility = VisibilityLevel.defaultFor(event);
        var notification = new RoomNotification(event);

        if (event instanceof WorldEvent.Whispered whispered) {
            // Directed: deliver only to sender, target, and PRIVILEGED+ subscribers.
            // Fan out across every surface the sender/target currently hold so the
            // whisper lands on all their channels, not just the most-recent one.
            var directed = new HashSet<ActorRef<RoomNotification>>();
            directed.addAll(entitySubscribers.getOrDefault(whispered.entityId(), Set.of()));
            directed.addAll(entitySubscribers.getOrDefault(whispered.targetEntityId(), Set.of()));
            for (var ref : directed) ref.tell(notification);
            // Also notify privileged subscribers (wardens, system actors)
            for (var entry : subscribers.entrySet()) {
                if (entry.getValue().canSee(VisibilityLevel.PRIVILEGED)
                        && !directed.contains(entry.getKey())) {
                    entry.getKey().tell(notification);
                }
            }
        } else {
            for (var entry : subscribers.entrySet()) {
                if (entry.getValue().canSee(eventVisibility)) {
                    entry.getKey().tell(notification);
                }
            }
        }
    }
}
