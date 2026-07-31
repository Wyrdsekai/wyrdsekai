package org.wyrdsekai.core.bootstrap;

import org.wyrdsekai.core.agent.ContextAccessManager;

import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.AgentCostTracker;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.CrossZonePeekService;
import org.wyrdsekai.core.agent.CrossZoneTellService;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.agent.GovernorEventMonitor;
import org.wyrdsekai.core.agent.LocalCommandRouter;
import org.wyrdsekai.core.agent.NotificationService;
import org.wyrdsekai.core.agent.WebSearchService;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.coding.CodingBackendBootstrap;
import org.wyrdsekai.core.coding.CodingTaskItemBridge;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.context.PersonalContextAggregator;
import org.wyrdsekai.core.economy.AttestationService;
import org.wyrdsekai.core.economy.CrossZoneExchange;
import org.wyrdsekai.core.economy.EstateManager;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.economy.TradingPostService;
import org.wyrdsekai.core.event.InProcessEventBus;
import org.wyrdsekai.core.external.o.PhaseOAdaptersBootstrap;
import org.wyrdsekai.core.external.p.PhasePAdaptersBootstrap;
import org.wyrdsekai.core.external.q.PhaseQAdaptersBootstrap;
import org.wyrdsekai.core.external.r.PhaseRAdaptersBootstrap;
import org.wyrdsekai.core.external.s.PhaseSAdaptersBootstrap;
import org.wyrdsekai.core.external.u.PhaseUAdaptersBootstrap;
import org.wyrdsekai.core.external.v.PhaseVAdaptersBootstrap;
import org.wyrdsekai.core.external.w.PhaseWAdaptersBootstrap;
import org.wyrdsekai.core.familiar.CrossZoneCopyService;
import org.wyrdsekai.core.governance.CouncilService;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.naming.BlockListService;
import org.wyrdsekai.core.naming.ZoneAddressResolverService;
import org.wyrdsekai.core.nostr.NostrAdapterBootstrap;
import org.wyrdsekai.core.nostr.RelayPoolScheduler;
import org.wyrdsekai.core.parlor.ParlorManager;
import org.wyrdsekai.core.release.MoralDefaultsVerifier;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.ThemedDescriptionService;
import org.wyrdsekai.core.room.ZoneAestheticService;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.soul.AttendantSessionTracker;
import org.wyrdsekai.core.soul.RepairLedger;
import org.wyrdsekai.core.soul.RepairModeTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Centralised initialisation of core singletons that every Wyrdsekai entry point
 * needs — the production {@code Main} binary, the test harness ({@code TestServerBootstrap}),
 * integration tests, and any future entry point (e.g. phone node, admin CLI).
 *
 * <p><b>Why this exists.</b> Before this class, each entry point initialised ~20
 * static singletons by hand. Test bootstraps drifted from {@code Main.java} over
 * time; the most recent incident was {@link org.wyrdsekai.core.agent.CrossZoneTellService}
 * silently missing from {@code TestServerBootstrap}, which caused every E2E
 * {@code tell} to degrade to an invisible whisper-in-room. The symptom was a
 * multi-hour batch of 600s test timeouts with no error, no warning, and no hint
 * of the real cause. This class exists so that kind of drift cannot recur: one
 * entry point, called from everywhere.</p>
 *
 * <p><b>What it initialises.</b> Only services that are <i>always</i> needed
 * regardless of deployment shape:</p>
 * <ul>
 *   <li>Registries and streams — {@link org.wyrdsekai.core.agent.EntityRegistry},
 *       {@link org.wyrdsekai.core.agent.AgentEventStream},
 *       {@link org.wyrdsekai.core.event.InProcessEventBus}.</li>
 *   <li>Communication — {@link org.wyrdsekai.core.agent.CrossZoneTellService},
 *       {@link org.wyrdsekai.core.agent.NotificationService}.</li>
 *   <li>Economics — {@link org.wyrdsekai.core.economy.MeteringService},
 *       {@link org.wyrdsekai.core.agent.AgentCostTracker},
 *       {@link org.wyrdsekai.core.economy.TradingPostService},
 *       {@link org.wyrdsekai.core.economy.EstateManager},
 *       {@link org.wyrdsekai.core.economy.CrossZoneExchange},
 *       {@link org.wyrdsekai.core.economy.AttestationService}.</li>
 *   <li>Governance &amp; observation — {@link org.wyrdsekai.core.governance.CouncilService},
 *       {@link org.wyrdsekai.core.agent.GovernorEventMonitor},
 *       {@link org.wyrdsekai.core.agent.ActivityLogger},
 *       {@link org.wyrdsekai.core.context.PersonalContextAggregator}.</li>
 *   <li>Agent capabilities — {@link org.wyrdsekai.core.agent.WebSearchService},
 *       {@link org.wyrdsekai.core.room.ZoneAestheticService}.</li>
 * </ul>
 *
 * <p><b>What it does NOT initialise.</b> Services with deployment-specific
 * configuration — {@code OracleBridge} (URL), {@code WatcherService} (paths),
 * {@code IngestPipeline} (extractor registration), voice services,
 * {@code EventBusPluginLoader} — stay in the entry point. If a service migrates
 * to parameterless config, move it here.</p>
 *
 * <p><b>Order.</b> The sequence matches the production boot order in
 * {@code Main.java} at the time of extraction. Changing order carries the risk
 * of latent init-order dependencies; re-verify against {@code Main} if you
 * rearrange.</p>
 */
public final class CoreServices {

    private static final Logger log = LoggerFactory.getLogger(CoreServices.class);

    private static volatile boolean initialised = false;

    private CoreServices() {}

    /**
     * Initialise all core singletons. Idempotent — calling twice is a no-op and
     * logs at WARN (indicates competing entry points, which suggests a bug).
     *
     * @param localZoneId the zone id this node identifies as (for cross-zone tell
     *                    routing). Falls back to {@code "local"} if null/blank.
     */
    public static synchronized void init(String localZoneId) {
        if (initialised) {
            log.warn("CoreServices.init() called more than once — ignoring. "
                + "Check that only one entry point bootstraps core services.");
            return;
        }
        var zoneId = (localZoneId == null || localZoneId.isBlank()) ? "local" : localZoneId;

        // Wave 5.2: boot-time moral-defaults
        // attestation. Runs FIRST — before any companion-touching service —
        // so the resulting MORAL_DEFAULTS_TAMPERED state is available the
        // moment any agent boots. The check is non-fatal: a tampered build
        // STILL boots, but every agent surfaces the tamper state in voice
        // register via introspect_protections.
        MoralDefaultsVerifier.verifyAtBoot();

        // Wave 9a-PersistWire: restore substrate-tracker singletons
        // from the per-host JSON files. The four trackers
        // (RepairLedger, AttendantSessionTracker, RepairModeTracker,
        // and at agent boot the per-companion ProtectionFlagTracker)
        // hold load-bearing state — losing them on restart would mean
        // the agent forgets confirmed harms, in-progress Sanctuary
        // sessions, and current repair mode. Restore is fail-clean per
        // tracker; corrupt or missing files leave the singleton empty.
        try {
            var dataDir = WyrdConfig.get().dataDir();
            if (dataDir != null && !dataDir.isBlank()) {
                var substrateDir = Path.of(dataDir, "substrate");
                RepairLedger.get()
                    .restore(substrateDir.resolve("repair-ledger.json"));
                AttendantSessionTracker.get()
                    .restore(substrateDir.resolve("attendant-sessions.json"));
                RepairModeTracker.get()
                    .restore(substrateDir.resolve("repair-mode.json"));
                log.info("CoreServices: substrate trackers restored from {}",
                    substrateDir);
            }
        } catch (Exception e) {
            log.warn("CoreServices: substrate-tracker restore failed "
                + "(continuing with empty trackers): {}", e.getMessage());
        }

        // Registries first — downstream services depend on these.
        EntityRegistry.init();
        AgentEventStream.init();
        InProcessEventBus.init();

        // Wave 5.3c — NostrRelayPool query
        // cadence scheduler. Opt-in: starts only when both
        // WYRDSEKAI_NOSTR_ENABLED=true and a non-empty WYRDSEKAI_NOSTR_RELAYS
        // are set. Companions can then ask "am I attested in the federation?"
        // and get an actual answer via RelayPoolScheduler.latestForAgent.
        try {
            RelayPoolScheduler.initFromEnv();
        } catch (Exception e) {
            log.warn("CoreServices: RelayPoolScheduler init skipped: {}",
                e.getMessage());
        }

        // Wave 4.6b — Attendant NPC in the
        // foundation Sanctuary room. Not a CompanionActor (no inference,
        // no soul, no drives) — just a static entity so `look` shows
        // someone present and the room reads as "held" rather than
        // empty. Behavior surface is description-only; routing of agent
        // utterances into AttendantSession is handled inside CompanionActor.
        try {
            EntityRegistry.get().enter(
                "sanctuary-attendant",
                "Attendant",
                "npc",
                "sanctuary",
                List.of("attendant", "the attendant", "sanctuary-attendant"));
        } catch (Exception e) {
            log.warn("CoreServices: failed to seed Sanctuary Attendant: {}",
                e.getMessage());
        }

        // Process-wide command router.
        // Lazy-instantiated; this call just touches the singleton so the
        // log line lands in init order. Handler registration happens in
        // CodingBackendBootstrap (one CodingNamespaceHandler per backend)
        // and ad-hoc registrations from external zone bridges.
        LocalCommandRouter.get();

        // EmbeddingService — minilm-l6-v2 ONNX. Feeds the classifier arm
        // AND the Lucene hybrid-search encoder.
        // Without this, ClassifierArm silently returns unavailable and all
        // classifier-gated behavior falls back to regex/heuristics.
        EmbeddingService.init();

        // Communication.
        CrossZoneTellService.init(zoneId);
        CrossZoneCopyService.init(zoneId);
        // / Phase 2b — cross-zone world.peek routing. Caller
        // wired by relay integration after RelaySessionTransport connects (mirrors
        // CrossZoneTellService.setRelayPublisher pattern). Without a wired caller
        // the service no-ops gracefully — peeks return null + log warn.
        CrossZonePeekService.init(zoneId);
        NotificationService.init();
        // Phase 2.3 — initialize context-access permission manager so the
        // `request_access` action works in prod (was test-only init → get()==null
        // → "context access not available"). Screen/calendar/location access grants.
        ContextAccessManager.init();

        // Observation & audit.
        ActivityLogger.init();
        PersonalContextAggregator.init();

        // Governance & reputation.
        CouncilService.init();
        AttestationService.init();
        var governorMonitor = GovernorEventMonitor.init();
        governorMonitor.subscribe();

        // Economics.
        MeteringService.init();
        AgentCostTracker.init();
        TradingPostService.init();
        EstateManager.init();
        CrossZoneExchange.init();

        // Agent capabilities.
        WebSearchService.init();
        ZoneAestheticService.init();
        ThemedDescriptionService.init();

        // Library acquisition substrate.
        var libraryDir = libraryDataRoot();
        if (libraryDir != null) {
            LibraryServices.init(libraryDir);
        }

        // Parlor auto-scaler (§2.8). The global narration sink is a no-op —
        // per-room narration is handled by RoomActor via the extra-sink
        // overload, so transitions surface as diegetic Said events inside
        // each managed Parlor. Register the foundation "parlor" as managed;
        // per-relay Parlors (§2.8.5) will register themselves as they spawn.
        var parlor = ParlorManager.getOrInit(n -> {});
        parlor.register("parlor");

        // disk-loaded scripted items.
        // Scans scripts/items/*.js + ~/.wyrdsekai/items/*.js and starts a
        // WatchService daemon for hot-reload. Idempotent — no-op when called
        // a second time.
        try {
            var itemLoader = ScriptedItemLoader.get();
            itemLoader.bootScan();
            itemLoader.startWatching();
            // write the manifest_audit.json so the
            // steward can see every legacy item still loading under the
            // v1-default embodiment shim. Always writes (even when zero) so
            // the file exists as evidence the migration pass ran.
            try {
                var auditPath = SystemPaths.dataDir()
                    .resolve("manifest_audit.json");
                itemLoader.writeMigrationAudit(auditPath);
            } catch (Throwable inner) {
                log.warn("manifest_audit.json write skipped: {}", inner.getMessage());
            }
        } catch (Throwable t) {
            log.warn("ScriptedItemLoader bootScan/startWatching skipped: {}", t.getMessage());
        }

        // -§4.31 (Phase R) — AI/ML, smart-home
        // and media adapters. Idempotent registration; failures here MUST not
        // abort core init because every adapter degrades gracefully when its
        // credential slot is empty.
        try {
            PhaseRAdaptersBootstrap.init();
        } catch (Throwable t) {
            log.warn("Phase R adapters bootstrap skipped: {}", t.getMessage());
        }

        // / Phase O — communication adapters
        // (email, slack, discord, telegram, signal, matrix, whatsapp).
        // Each adapter resolves credentials from The Safe via CredentialResolver
        // at call-time; registration here is just the dispatch wiring.
        try {
            PhaseOAdaptersBootstrap.init();
        } catch (Throwable t) {
            log.warn("Phase O adapters bootstrap skipped: {}", t.getMessage());
        }

        // Phase Q external adapters.
        // Productivity (calendar/gdrive/notion/linear/asana/todoist) +
        // knowledge & research (arxiv/scholar/wikipedia/stackoverflow/wolfram).
        // Registered against the global ExternalAdapterRegistry so item
        // scripts can dispatch via world.<namespace>.<method>.
        try {
            PhaseQAdaptersBootstrap.init();
        } catch (Throwable t) {
            log.warn("Phase Q adapter bootstrap skipped: {}", t.getMessage());
        }

        // -§4.43 / Phase V — travel + commerce
        // external adapters. Registers 14 adapters into the
        // ExternalAdapterRegistry so item scripts can resolve world.amadeus.*,
        // world.shopify.*, etc. at execution time. No-op on second call.
        try {
            PhaseVAdaptersBootstrap.init();
        } catch (Throwable t) {
            log.warn("Phase V adapter bootstrap skipped: {}", t.getMessage());
        }

        // / Phase P — register social
        // + code-platform external adapters with the ExternalAdapterRegistry.
        // Idempotent. Each adapter resolves credentials lazily through the
        // CredentialResolver, so this is safe to call before The Safe is
        // online — calls without populated slots return credentials_missing.
        try {
            PhasePAdaptersBootstrap.init();
        } catch (Throwable t) {
            log.warn("Phase P adapters bootstrap skipped: {}", t.getMessage());
        }

        // register the Nostr Tier 5 adapter when
        // wyrdsekai.nostr.enabled = true. The default SeedResolver here returns
        // null, which yields {credential_missing} on every publish — to make
        // this functional, override the bootstrap with a SeedResolver that
        // reads the companion's Ed25519 seed from soul storage. Phase 2b will
        // land the production-grade resolver + inbound subscriptions.
        try {
            var rootCfg = ConfigFactory.load().getConfig("wyrdsekai");
            NostrAdapterBootstrap.init(
                rootCfg, did -> null);
        } catch (Throwable t) {
            log.warn("Nostr adapter bootstrap skipped: {}", t.getMessage());
        }

        // Coding backends ( / Phase 2b).
        // OpenCode is the default-on backend so complex items work out of
        // the box; CodePlane stays a Main-side wiring step because it
        // needs the legacy CommandRouter + CodeItemStore. The bootstrap is
        // a no-op when the typesafe-config block is missing, so test
        // harnesses that don't load application.conf still pass cleanly.
        try {
            CodingBackendBootstrap.init(
                ConfigFactory.load());
        } catch (Throwable t) {
            // Never fail core init on a coding-backend wiring slip; the
            // Workshop room degrades gracefully when no backend is
            // available.
            log.warn("Coding backend bootstrap skipped: {}", t.getMessage());
        }

        // / Phase B — subscribe the
        // CodingTaskItemBridge to AgentEventStream so terminal
        // ZoneBroadcasts emitted by CodingNamespaceHandler land as
        // RoomObject placements in the originating room. The
        // roomObjectPlacer callback resolves the room via RoomRegistry
        // and tells it an ItemBridgeAction(AddObject(...)) per
        // generated codex / artifact. SPEC §3.4 — items carry their
        // backend metadata via name/description for now (Phase D adds
        // a structured ItemRegistry).
        try {
            var stream = AgentEventStream.get();
            if (stream != null) {
                var bridge = new CodingTaskItemBridge(
                    BackendRegistry.get(),
                    placement -> {
                        var roomRef = RoomRegistry
                            .get().ref(placement.roomId());
                        if (roomRef == null) {
                            log.debug("CodingTaskItemBridge: no room actor for "
                                + "'{}'; dropping {} item placement(s)",
                                placement.roomId(), placement.objects().size());
                            return;
                        }
                        for (var obj : placement.objects()) {
                            roomRef.tell(new RoomCommand
                                .ItemBridgeAction(
                                    "system:coding-bridge",
                                    new RoomCommand
                                        .ItemBridgeSubAction.AddObject(
                                            obj.id(), obj.name(),
                                            obj.description(), obj.takeable())));
                        }
                    });
                stream.subscribe("system:coding-task-item-bridge", bridge);
                log.info("CodingTaskItemBridge subscribed to AgentEventStream");
            }
        } catch (Throwable t) {
            log.warn("CodingTaskItemBridge subscription skipped: {}", t.getMessage());
        }

        // -§4.41 (Phase U) — register the
        // health/gov/maps/weather external adapters. Idempotent; safe under
        // repeated init or shared with TestServerBootstrap.
        try {
            PhaseUAdaptersBootstrap.init();
        } catch (Throwable t) {
            log.warn("Phase U adapter bootstrap skipped: {}", t.getMessage());
        }

        // Phase S adapters — financial (§4.32) + telephony (§4.33). Idempotent;
        // safe to call from both Main + TestServerBootstrap. Adapters dispatch
        // dynamically through ExternalAdapterRegistry, so failure to register
        // simply means matching world.<ns>.* calls return adapter_unavailable.
        try {
            PhaseSAdaptersBootstrap.register();
        } catch (Throwable t) {
            log.warn("Phase S adapter bootstrap skipped: {}", t.getMessage());
        }

        // translation, asset libraries
        // book adapters. Registers 18 adapters under §4.44-§4.46. Idempotent.
        try {
            PhaseWAdaptersBootstrap.init();
        } catch (Throwable t) {
            log.warn("PhaseWAdaptersBootstrap skipped: {}", t.getMessage());
        }

        initialised = true;
        log.info("CoreServices initialised (zone={})", zoneId);
    }

    /**
     * Initialise the {@link org.wyrdsekai.core.naming.ZoneAddressResolverService}.
     * Separate from {@link #init(String)} because it requires the node's Ed25519
     * public key (from {@code NodeIdentity.publicKeyBytes()}) and the wyrdsekai
     * data directory — both of which are deployment-specific and not available
     * in every entry point (some fast test fixtures skip {@code NodeIdentity}
     * entirely). Call sites that never talk to the federation layer can skip
     * this step; the {@code BridgeDataProvider.resolveZone} default handles
     * the uninitialised case gracefully.
     *
     * <p>Callers should invoke this after {@link #init(String)}.</p>
     *
     * @param spkiBytes     SPKI-encoded Ed25519 public key
     * @param dataDir       wyrdsekai data directory (contacts + my-zones files)
     * @param legacyZoneId  current {@code WYRDSEKAI_ZONE_ID} env value (may be
     *                      null). Auto-registered in the local registry for
     *                      Phase-1 migration compat.
     */
    public static synchronized void initNaming(byte[] spkiBytes,
                                                Path dataDir,
                                                String legacyZoneId) {
        ZoneAddressResolverService.init(spkiBytes, dataDir, legacyZoneId);
        // Blocklist — consulted at federation envelope intake. Init after the
        // resolver because both read from the same data dir; one open/parse
        // failure shouldn't prevent the other from loading.
        BlockListService.init(dataDir);
    }

    /**
     * Test-only escape hatch: reset the initialised flag so a fresh test can
     * re-run {@code init}. Intentionally package-scoped only — production code
     * should never call this. Tests that need clean state should prefer
     * spinning up a fresh JVM where possible.
     */
    public static void resetForTests() {
        initialised = false;
        ZoneAddressResolverService.resetForTests();
    }

    /** True after {@link #init(String)} has run. */
    public static boolean isInitialised() {
        return initialised;
    }

    /**
     * Resolve the per-zone library substrate root —
     * {@code $WYRDSEKAI_DATA_DIR/library/} by default, or a tmpdir fallback when
     * the data dir isn't configured (typical in tests). Returns {@code null} if
     * the path can't be created; callers should treat that as "library
     * substrate disabled" and degrade gracefully.
     */
    private static Path libraryDataRoot() {
        try {
            var base = WyrdConfig.get().dataDir();
            var home = base != null && !base.isBlank()
                ? Path.of(base)
                : Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai");
            var libDir = home.resolve("library");
            Files.createDirectories(libDir);
            return libDir;
        } catch (IOException | RuntimeException e) {
            log.warn("Library data dir unreachable, acquisition substrate disabled: {}",
                e.getMessage());
            return null;
        }
    }
}
