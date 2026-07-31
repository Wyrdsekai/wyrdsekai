package org.wyrdsekai.e2e.infra;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.layer.IdentityReplicator;
import org.wyrdsekai.between.layer.UnifiedSessionService;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.bootstrap.CoreServices;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.core.home.HomeProxy;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.home.WardGrantSync;
import org.wyrdsekai.core.issue.IssueService;
import org.wyrdsekai.core.economy.ComputeUnitNormalizer;
import org.wyrdsekai.core.economy.CountingHouseActor;
import org.wyrdsekai.core.economy.CountingHouseCommand;
import org.wyrdsekai.core.economy.LedgerPersistence;
import org.wyrdsekai.core.economy.ResourceMeter;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.inference.CapabilityRegistry;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.DocumentIndexer;
import org.wyrdsekai.core.library.KnowledgePackIndexer;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.oracle.OraclePrediction;
import org.wyrdsekai.core.oracle.OraclePredictionCache;
import org.wyrdsekai.core.oracle.TemporalPatternExtractor;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.BridgeDataProviderImpl;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.RoomMetadataService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.soul.SqlSoulStore;
import org.wyrdsekai.core.update.UpdateConfig;
import org.wyrdsekai.scripting.loader.ScriptLoader;
import org.wyrdsekai.server.http.AuthRoutes;
import org.wyrdsekai.server.http.HealthRoutes;
import org.wyrdsekai.server.http.HomeRoutes;
import org.wyrdsekai.server.http.IssueRoutes;
import org.wyrdsekai.server.http.LibraryKnowledgeRoutes;
import org.wyrdsekai.server.http.McpRoutes;
import org.wyrdsekai.server.http.MetricsCollector;
import org.wyrdsekai.server.http.PairingRoutes;
import org.wyrdsekai.server.http.ResidentRoutes;
import org.wyrdsekai.server.http.SearchRoutes;
import org.wyrdsekai.server.http.StudyRoutes;
import org.wyrdsekai.server.http.UpdateRoutes;
import org.wyrdsekai.server.ssh.SshAdapter;
import org.wyrdsekai.server.telnet.TelnetAdapter;
import org.wyrdsekai.server.ws.WyrdWebSocket;
import org.wyrdsekai.server.ws.ZoneBridgeEndpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Programmatic server bootstrap for E2E tests.
 * Replicates the essential parts of Main.java:
 * in-memory SQLite, ActorSystem with ZoneGuardian, InferenceRouter,
 * Foundation rooms, Javalin on ephemeral port.
 *
 * <p>Usage:
 * <pre>{@code
 * var server = new TestServerBootstrap(inferenceBackends);
 * server.start();
 * // ... run tests against server.baseUrl() ...
 * server.stop();
 * }</pre>
 */
public final class TestServerBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TestServerBootstrap.class);

    private final List<InferenceBackend> inferenceBackends;
    private final List<ZoneGuardian.RoomSeed> extraSeeds;
    private final int port;
    private final boolean openRegistration;

    private ActorSystem<ZoneGuardian.Command> system;
    private Javalin app;
    private String jdbcUrl;
    @SuppressWarnings("FieldCanBeLocal")
    private Connection keepAliveConn;
    private Path dbFile; // temp SQLite file — cleaned up on stop
    private WyrdLuceneStore luceneStore;
    private Path luceneTempDir;
    private TelnetAdapter telnetAdapter;
    private SshAdapter sshAdapter;
    private int telnetPort;
    private int sshPort;
    // Stored for respawnCompanion()
    private ActorRef<InferenceRouter.Command> inferenceRouterRef;
    private ActorRef<CountingHouseCommand> countingHouseRef;
    private WorldDnaService worldDnaServiceRef;
    private HomeClient homeClientRef;

    /** Access the HomeClient wired into this bootstrap (for cross-zone tests). */
    public HomeClient homeClient() { return homeClientRef; }

    /**
     * Access the InferenceRouter actor wired into this bootstrap. Tests that
     * exercise scripted items end-to-end (e.g. the heavy-item OpenCode test)
     * need this so they can build an {@link org.wyrdsekai.core.item.ItemWorldApiProviderImpl}
     * that routes {@code world.llm.*} calls back through the same router the
     * companion would use. Returns {@code null} when no inference backends
     * were configured at startup.
     */
    public ActorRef<InferenceRouter.Command> inferenceRouter() { return inferenceRouterRef; }

    public TestServerBootstrap(List<InferenceBackend> inferenceBackends) {
        this(inferenceBackends, PortAllocator.allocate());
    }

    public TestServerBootstrap(List<InferenceBackend> inferenceBackends, int port) {
        this(inferenceBackends, port, List.of());
    }

    /**
     * Create a test server with additional room seeds beyond the default Foundation set.
     */
    public TestServerBootstrap(List<InferenceBackend> inferenceBackends, int port,
                               List<ZoneGuardian.RoomSeed> extraSeeds) {
        this(inferenceBackends, port, extraSeeds, true);
    }

    /**
     * Create a test server with configurable open registration.
     * @param openRegistration if false, invite-only mode (Wave 1 tests)
     */
    public TestServerBootstrap(List<InferenceBackend> inferenceBackends, int port,
                               List<ZoneGuardian.RoomSeed> extraSeeds, boolean openRegistration) {
        this.inferenceBackends = inferenceBackends;
        this.port = port;
        this.extraSeeds = extraSeeds != null ? extraSeeds : List.of();
        this.openRegistration = openRegistration;
    }

    /**
     * Start the test server.
     */
    @SuppressWarnings("resource")
    public void start() throws Exception {
        // File-based SQLite in temp directory (not in-memory — in-memory journals
        // fail under concurrent event persistence from multiple sessions).
        this.dbFile = Files.createTempFile("wyrd-e2e-", ".db");
        this.dbFile.toFile().deleteOnExit();
        jdbcUrl = "jdbc:sqlite:" + this.dbFile.toAbsolutePath();
        // Set system property so Pekko JDBC journal resolves ${wyrdsekai.db.path}
        System.setProperty("wyrdsekai.db.path", this.dbFile.toAbsolutePath().toString());
        // phase 2: enable telnet 'guest' in tests.
        // Production rejects 'guest'; ~24 E2E tests use TestTelnetClient.loginAsGuest
        // for ephemeral sessions — keep them working without per-test refactor.
        System.setProperty("wyrdsekai.test.allow_telnet_guest", "true");
        var dialect = new SqlDialect.SQLite();

        // Initialize schema + enable WAL mode for concurrent access
        keepAliveConn = DriverManager.getConnection(jdbcUrl);
        try (var stmt = keepAliveConn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        }
        initializeSchema(keepAliveConn);

        // Zone master secret — prod Main runs ZoneSecrets.bootstrapLocalZone at
        // boot; without it, 0.5a fail-closed private-journal encryption makes
        // POST /api/study/journal isPrivate:true return 500 in this harness.
        var zsZoneId = WyrdConfig.get().zoneId();
        if (!ZoneSecrets.service().has(zsZoneId)) {
            ZoneSecrets.service().generate(zsZoneId);
        }

        // Create services
        var authService = new AuthService(jdbcUrl, dialect);
        var wardService = new WardService(jdbcUrl, dialect);
        var inventoryService = new InventoryService(jdbcUrl, dialect);
        var metadataService = new RoomMetadataService(jdbcUrl, dialect);
        var bridgeDataProvider = new BridgeDataProviderImpl(
            wardService, metadataService, authService);

        // Moderation + sanction enforcement
        var moderationService = new ModerationService();
        var sanctionEnforcer = new SanctionEnforcer(moderationService);

        // Script loader — resolve from project root
        var scriptDir = resolveScriptDir();
        ScriptLoader scriptLoader = scriptDir != null
            ? new ScriptLoader(scriptDir, null)
            : null;

        // Pekko config for single-node test
        var config = pekkoConfig();

        // Combine foundation seeds with any extra seeds. Last-wins by
        // roomId so a test can pass a `nexus` extraSeed to override the
        // foundation copy — e.g. to add an exit pointing at a custom
        // room the test also seeds (OpenHandsE2ETest needs this so
        // `go east` from nexus reaches workshop). Without dedupe,
        // ZoneGuardian seeds both copies and the foundation one wins.
        var seedByRoomId = new LinkedHashMap<String,
            ZoneGuardian.RoomSeed>();
        for (var s : foundationRoomSeeds()) seedByRoomId.put(s.roomId(), s);
        for (var s : extraSeeds) seedByRoomId.put(s.roomId(), s);
        var allSeeds = new ArrayList<>(seedByRoomId.values());

        // SoulStore — SQLite for companion soul persistence (enables sleep)
        var soulStore = new SqlSoulStore(jdbcUrl);

        // Boot actor system with ZoneGuardian — send SetSoulStore FIRST (before seed timeout)
        system = ActorSystem.create(
            ZoneGuardian.create(scriptLoader, allSeeds,
                metadataService, bridgeDataProvider, sanctionEnforcer),
            "wyrdsekai-test", config);
        // Must arrive before 3s seed timeout spawns the default companion
        system.tell(new ZoneGuardian.SetSoulStore(soulStore));

        // WyrdLuceneStore — wire into ZoneGuardian before seed timeout (3s) fires
        luceneTempDir = Files.createTempDirectory("wyrd-lucene-test-");
        luceneStore = new WyrdLuceneStore(luceneTempDir, 384);
        luceneStore.ensureAllCollections();
        seedTestKnowledge(luceneStore);
        system.tell(new ZoneGuardian.SetLuceneStore(luceneStore));

        // Ensure the inference backend meets E2E requirements (ctx-size, health).
        // Old-school E2E: the test harness owns its infrastructure end-to-end.
        // See docker/docker-compose.e2e.yml (llama profile) for the local
        // backend; remote backends (e.g. WYRDSEKAI_INFERENCE_URL=http://home-server:8200)
        // are validated but not auto-managed.
        ensureInferenceBackend();

        // WebSearchService probes Searxng — depends on docker-compose being up.
        ensureSearxng();

        // Oracle — seed predictions from real data so task 5 has patterns to find.
        seedOraclePredictions();

        // Core singletons — same one-stop init as production Main.java. Tests
        // previously hand-mirrored this list and drifted from prod (most recent
        // miss: CrossZoneTellService, which silently degraded every agent tell
        // to a whisper-in-room and caused a full suite of invisible 600s
        // timeouts). Centralised here so the next drift is impossible.
        // Test isolation: resetForTests allows re-init per bootstrap.
        CoreServices.resetForTests();
        var e2eZoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
        CoreServices.init(e2eZoneId);

        // Naming service — test-owned. Each E2E run lives in a clean temp dir
        // (contacts + my-zones don't leak between tests). Backed by a fresh
        // Ed25519 keypair stored alongside the other test data.
        try {
            var testDataDir = Files.createTempDirectory("wyrd-e2e-naming-");
            testDataDir.toFile().deleteOnExit();
            var nodeIdentity = NodeIdentity.loadOrGenerate(
                testDataDir.resolve("node-identity.json"));
            CoreServices.initNaming(
                nodeIdentity.publicKeyBytes(), testDataDir, e2eZoneId);
        } catch (Exception e) {
            log.warn("Test naming service init failed — docks.js resolveZone will degrade: {}",
                e.getMessage());
        }

        // Counting House with JDBC ledger persistence
        var ledgerPersistence = new LedgerPersistence(jdbcUrl);
        var countingHouse = system.<CountingHouseCommand>systemActorOf(
            CountingHouseActor.create(ledgerPersistence), "counting-house", Props.empty());
        this.countingHouseRef = countingHouse;
        var computeNormalizer = new ComputeUnitNormalizer();
        var resourceMeter = new ResourceMeter(countingHouse, computeNormalizer);

        // Inference Router. CapabilityRegistry is derived from the backend list
        // so cap:quick (voice) → 4B and cap:reasoning (deep) → 9B route correctly
        // when both backends are registered. Production (Main.java) does the
        // same; without it, voice-pass post-processor falls back to whatever
        // single backend is healthy and we get the broken-voice memory-dump
        // shape that flaked yesterday's MemoryE2ETest assertions.
        ActorRef<InferenceRouter.Command> inferenceRouter = null;
        if (!inferenceBackends.isEmpty()) {
            var capabilityRegistry = CapabilityRegistry
                .fromBackends(inferenceBackends);
            inferenceRouter = system.systemActorOf(
                InferenceRouter.create(inferenceBackends, "test-model",
                    resourceMeter, capabilityRegistry, Duration.ofSeconds(120)),
                "inference-router", Props.empty());

            // Spawn companion agent
            var worldDnaService = new WorldDnaService(jdbcUrl, dialect);
            this.inferenceRouterRef = inferenceRouter;
            this.worldDnaServiceRef = worldDnaService;
            system.tell(new ZoneGuardian.SpawnCompanion(
                inferenceRouter, worldDnaService, null));
        }

        // Seed Foundation room wards and metadata
        for (var seed : allSeeds) {
            wardService.seedFoundationWards(seed.roomId());
            metadataService.register(seed.roomId(), seed.name(), "foundation", "system");
        }

        // Pairing service for device onboarding
        var pairingService = new PairingService(jdbcUrl, dialect,
            "test-household", "Test Household", "did:key:test",
            "ws://localhost:4222", "http://localhost:" + port);
        pairingService.initSchema();
        PairingService.register(pairingService);

        // Health routes
        var metricsCollector = new MetricsCollector();
        var healthRoutes = new HealthRoutes(metricsCollector);

        // Start Javalin — longer idle timeout for degradation/recovery tests
        // 600s idle timeout — scripted tool items with LLM calls can take 2-5 minutes
        // per plan step. 100s was causing WebSocket drops mid-execution.
        var wsHandler = new WyrdWebSocket(system, authService, wardService, inventoryService,
            null /* federationService */, pairingService, true /* allowAnonymous */, 600);

        // wiring — HomeRegistry + HomeClient + ward/grant mirror
        var homeStore = new HomeStore(jdbcUrl);
        var homeRegistry = system.systemActorOf(
            HomeRegistryActor.create(homeStore),
            "home-registry",
            Props.empty());
        var homeClient = new HomeClient(homeRegistry, system);
        this.homeClientRef = homeClient;
        HomeClients.set(homeClient);
        wsHandler.setHomeClient(homeClient);
        wardService.setGrantSync(new WardGrantSync(homeClient));
        HomeProxy.Holder.set(
            new HomeProxy.Local(homeClient, "test-zone"));
        var zoneBridge = new ZoneBridgeEndpoint(wsHandler, null); // null = no auth (household trust)
        var pairingRoutes = new PairingRoutes(pairingService, authService, null);
        var inviteService = new InviteService(jdbcUrl);
        // open registration is derived from
        // "no users exist", not a config toggle. Tests that need an open
        // first-user window simply start with an empty users table; tests
        // that need a closed door pre-create a user.
        var authRoutes = new AuthRoutes(authService, inviteService, pairingService, null);

        // Wire Between if enabled (multi-node E2E tests)
        var betweenEnabled = "true".equals(System.getProperty("WYRDSEKAI_BETWEEN_ENABLED"));
        if (betweenEnabled) {
            // Honor WYRDSEKAI_E2E_NATS_PORT so the harness can run on a host
            // that already has a live wyrdsekai mesh on the standard 4222.
            int e2eNatsPort = parsePort(System.getenv("WYRDSEKAI_E2E_NATS_PORT"), 4222);
            int e2eNatsMonitor = parsePort(System.getenv("WYRDSEKAI_E2E_NATS_MONITOR_PORT"), 8222);
            var natsUrl = System.getProperty("WYRDSEKAI_NATS_URL",
                "nats://127.0.0.1:" + e2eNatsPort);
            var nodeId = System.getProperty("WYRDSEKAI_NODE_ID", "test-" + port);
            var betweenCfg = new BetweenActor.BetweenConfig(
                true, natsUrl, false /* no auto-start */, "nats-server",
                e2eNatsPort, e2eNatsMonitor, false, List.of(),
                Duration.ofSeconds(10), Duration.ofSeconds(5), 0);
            @SuppressWarnings("unchecked")
            var betweenActor = system.systemActorOf(
                BetweenActor.create(), "between",
                Props.empty());
            var dataDir = Files.createTempDirectory("wyrd-between-" + nodeId);
            dataDir.toFile().deleteOnExit();
            betweenActor.tell(new BetweenActor.StartBetween(
                "test-zone", "Test Zone", dataDir, betweenCfg, jdbcUrl, null));
            // Wire account replication
            betweenActor.tell(new BetweenActor.StartAccountReplication(
                authService, inviteService));
            // Wire IdentityReplicator into AuthRoutes
            Thread.sleep(1000); // let Between initialize
            AskPattern
                .<BetweenActor.Command, IdentityReplicator>ask(
                    betweenActor,
                    ref -> new BetweenActor.GetIdentityReplicator(ref),
                    Duration.ofSeconds(5), system.scheduler())
                .whenComplete((repl, err) -> {
                    if (repl != null) authRoutes.setIdentityReplicator(repl);
                });
            // Wire session service into WyrdWebSocket
            AskPattern
                .<BetweenActor.Command, UnifiedSessionService>ask(
                    betweenActor,
                    ref -> new BetweenActor.GetSessionService(ref),
                    Duration.ofSeconds(5), system.scheduler())
                .whenComplete((ss, err) -> {
                    if (ss != null) wsHandler.setSessionService(ss);
                });
            wsHandler.setBetweenActor(betweenActor);
            log.info("Between enabled for test: node={} nats={}", nodeId, natsUrl);
        }

        app = Javalin.create(cfg -> {
            cfg.jetty.modifyWebSocketServletFactory(ws ->
                ws.setIdleTimeout(Duration.ofMinutes(15)));
            cfg.routes.ws("/ws", wsHandler);
            cfg.routes.ws("/ws/zone", zoneBridge);
            healthRoutes.register(cfg.routes);
            pairingRoutes.register(cfg.routes);
            authRoutes.register(cfg.routes);
            // MCP REST surface (/api/mcp/*) — the phone-client arm. Mirrors
            // Main.java so McpConformanceTest's /api/mcp/login resolves instead
            // of 404-ing (the route existed but the test server never mounted it).
            new McpRoutes(authService, system).register(cfg.routes);
            new HomeRoutes(homeRegistry, system).register(cfg.routes);
            new SearchRoutes(luceneStore).register(cfg.routes);
            // Library knowledge routes
            Path packsDir;
            try { packsDir = Files.createTempDirectory("test-packs-"); }
            catch (Exception e) { packsDir = Path.of("target/test-packs"); }
            packsDir.toFile().deleteOnExit();
            var knowledgeIndexer = new KnowledgePackIndexer(luceneStore);
            new LibraryKnowledgeRoutes(
                luceneStore, knowledgeIndexer, packsDir).register(cfg.routes);
            // /issue store + REST surface (no jdbc/log
            // capture in the test bootstrap; those fields degrade to null).
            Path issuesDir;
            try { issuesDir = Files.createTempDirectory("test-issues-"); }
            catch (Exception e) { issuesDir = Path.of("target/test-issues"); }
            issuesDir.toFile().deleteOnExit();
            IssueService.init(issuesDir, null, null);
            new IssueRoutes().register(cfg.routes);
            // Study routes — wire HomeClient so the consent-grant HTTP path
            // can issue Grants (StudyL2 tests exercise /consent/* endpoints).
            var studyService = new StudyService(
                luceneStore, homeClient);
            var docIndexer = new DocumentIndexer(studyService);
            new StudyRoutes(studyService, docIndexer).register(cfg.routes);
            // Resident bridge routes — uses default companion's entityId
            new ResidentRoutes(
                "companion-wyrd", "", system).register(cfg.routes);
            // Mesh update routes (no channel in tests — status only)
            var updateConfig = UpdateConfig.fromEnv();
            new UpdateRoutes(updateConfig, null).register(cfg.routes);

            // Backup routes
            Path testBackupDir;
            try { testBackupDir = Files.createTempDirectory("test-backups-"); }
            catch (Exception e) { testBackupDir = Path.of("target/test-backups"); }
            testBackupDir.toFile().deleteOnExit();
            var testOrchestrator = new BackupOrchestrator(testBackupDir);
            var testDbPath = dbFile;
            var testSearchDir = luceneTempDir;
            cfg.routes.post("/api/backup/snapshot", ctx -> {
                var result = testOrchestrator.snapshotAll(testDbPath, testSearchDir);
                if (result.isPresent()) {
                    var m = result.get();
                    ctx.json(Map.of(
                        "backupId", m.backupId(),
                        "location", m.location().toString(),
                        "sizeBytes", m.sizeBytes(),
                        "timestamp", m.timestamp().toString()));
                } else {
                    ctx.status(500).json(Map.of("error", "Backup failed"));
                }
            });
            cfg.routes.get("/api/backup/list", ctx -> {
                var dbSnapshots = testOrchestrator.listSnapshots();
                var searchSnapshots = testOrchestrator.listSearchSnapshots();
                ctx.json(Map.of(
                    "database", dbSnapshots.stream().map(s -> Map.of(
                        "backupId", s.backupId(),
                        "sizeBytes", s.sizeBytes(),
                        "timestamp", s.timestamp().toString())).toList(),
                    "search", searchSnapshots.stream().map(s -> Map.of(
                        "backupId", s.backupId(),
                        "sizeBytes", s.sizeBytes(),
                        "timestamp", s.timestamp().toString())).toList()));
            });
        });
        app.start(port);
        healthRoutes.setReady(true);

        // Start Telnet adapter on ephemeral port
        telnetPort = PortAllocator.allocate();
        try {
            telnetAdapter = new TelnetAdapter();
            telnetAdapter.start(telnetPort, system, authService, wardService, inventoryService);
            log.info("Test telnet adapter on port {}", telnetPort);
        } catch (Exception e) {
            log.warn("Could not start telnet adapter: {}", e.getMessage());
            telnetAdapter = null;
        }

        // Start SSH adapter on ephemeral port
        sshPort = PortAllocator.allocate();
        try {
            sshAdapter = new SshAdapter();
            sshAdapter.start(sshPort, system, authService, wardService, inventoryService);
            log.info("Test SSH adapter on port {}", sshPort);
        } catch (Exception e) {
            log.warn("Could not start SSH adapter: {}", e.getMessage());
            sshAdapter = null;
        }

        // Wait for ZoneGuardian seed timeout to fire and populate all rooms.
        // SEED_TIMEOUT is 3s; wait 4s to ensure CreateRoom commands complete.
        // Without this, tests that connect immediately race with seeding and
        // see empty rooms (no exits, no objects, no description).
        Thread.sleep(4000);

        log.info("Test server started on port {} (telnet={}, ssh={})", port, telnetPort, sshPort);
    }

    /**
     * Stop the test server and actor system.
     */
    public void stop() {
        if (telnetAdapter != null) {
            try { telnetAdapter.stop(); } catch (Exception e) {
                log.debug("Error stopping telnet: {}", e.getMessage());
            }
        }
        if (sshAdapter != null) {
            try { sshAdapter.stop(); } catch (Exception e) {
                log.debug("Error stopping SSH: {}", e.getMessage());
            }
        }
        if (luceneStore != null) {
            try {
                luceneStore.close();
            } catch (Exception e) {
                log.debug("Error closing WyrdLuceneStore: {}", e.getMessage());
            }
        }
        if (app != null) {
            try {
                app.stop();
            } catch (Exception e) {
                log.debug("Error stopping Javalin: {}", e.getMessage());
            }
        }
        if (system != null) {
            system.terminate();
            try {
                system.getWhenTerminated().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Error terminating actor system: {}", e.getMessage());
            }
        }
        // Clear static singletons so the next test class starts fresh
        RoomRegistry.get().clear();
    }

    @Override
    public void close() {
        stop();
    }

    public int port() {
        return port;
    }

    public int telnetPort() {
        return telnetPort;
    }

    public int sshPort() {
        return sshPort;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public ActorSystem<ZoneGuardian.Command> system() {
        return system;
    }

    /** Get the CountingHouse actor reference (for economy tests). */
    public ActorRef<CountingHouseCommand> countingHouse() {
        return countingHouseRef;
    }

    /**
     * Kill the current companion and spawn a fresh one with no working memory,
     * no conversation history, no plan state. Used between E2E tests to prevent
     * context bleed.
     */
    public void respawnCompanion() {
        if (system == null) return;

        // Reset companion state instead of respawning — keeps subscriptions intact,
        // clears working memory, plans, conversation, dynamic items.
        // This avoids timing races between actor stop/spawn and test WebSocket connect.
        var companionRef = ZoneGuardian.getCompanionRef(null, "companion-wyrd");
        if (companionRef != null) {
            companionRef.tell(new CompanionActor.ResetState());
        }

        // Wait for reset + cancellation of in-flight inference to complete
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log.info("Reset companion state for next test");
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public WyrdLuceneStore luceneStore() {
        return luceneStore;
    }

    // --- Foundation room seeds (same as Main.java) ---

    private static List<ZoneGuardian.RoomSeed> foundationRoomSeeds() {
        // Exit layout mirrors the production `foundation-rooms.json` so the
        // conformance suite can walk Study → out → Nexus → east → Docks
        // identically in embedded and live modes. The docks compass below
        // is the takeable object the take/drop round-trip relies on.
        return List.of(
            new ZoneGuardian.RoomSeed("nexus", "The Nexus",
                "A shimmering hub of connections — the heart of Wyrdsekai.",
                List.of(
                    new Exit("east", "docks", "The Docks"),
                    new Exit("south", "vault", "The Vault"),
                    new Exit("west", "terminal", "The Terminal"),
                    new Exit("north", "bridge", "The Bridge"),
                    new Exit("in", "study", "The Study")
                ),
                List.of(
                    new RoomObject("crystal", "Nexus Crystal",
                        "A softly glowing crystal that pulses with the rhythm of the world.",
                        false)
                )),
            new ZoneGuardian.RoomSeed("terminal", "The Terminal",
                "Glowing command interfaces line the walls.",
                List.of(new Exit("east", "nexus", "The Nexus")), List.of()),
            new ZoneGuardian.RoomSeed("vault", "The Vault",
                "A secure chamber for storing precious items.",
                List.of(new Exit("north", "nexus", "The Nexus")), List.of()),
            new ZoneGuardian.RoomSeed("docks", "The Docks",
                "A misty harbor where travelers arrive from distant zones.",
                List.of(new Exit("west", "nexus", "The Nexus")),
                List.of(
                    new RoomObject("compass", "compass",
                        "A worn brass compass, its needle pointing steadily toward the Nexus.",
                        true)
                )),
            new ZoneGuardian.RoomSeed("bridge", "The Bridge",
                "The command center for zone administration.",
                List.of(new Exit("south", "nexus", "The Nexus")), List.of())
        );
    }

    private static Path resolveScriptDir() {
        var candidates = List.of(
            Path.of("scripts/rooms"),
            Path.of("../scripts/rooms"),
            Path.of("../../scripts/rooms")
        );
        for (var p : candidates) {
            if (p.toFile().isDirectory()) return p;
        }
        return null;
    }

    private static int parsePort(String v, int dflt) {
        if (v == null || v.isBlank()) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    private static void initializeSchema(Connection conn) {
        try {
            var sql = new String(TestServerBootstrap.class
                .getResourceAsStream("/schema/sqlite-create-schema.sql")
                .readAllBytes());
            var cleaned = sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
            for (var statement : cleaned.split(";")) {
                var trimmed = statement.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("PRAGMA")) continue;
                try (var stmt = conn.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize test database schema", e);
        }
    }

    /**
     * Seed test knowledge into Lucene so item scripts (Library Card, etc.)
     * find real results during E2E tests.
     */
    private static void seedTestKnowledge(WyrdLuceneStore store) {
        store.insertKnowledge("myth-greek-zeus", "mythology",
            "Greek Mythology: Zeus and Mount Olympus",
            "Greek Mythology — Zeus and Mount Olympus. Zeus, king of the Greek gods, ruled from Mount Olympus. He wielded thunderbolts forged by the Cyclopes and was the father of many heroes including Heracles, Perseus, and Helen of Troy. The Olympian pantheon included his brothers Poseidon and Hades, and his wife Hera. This book covers ancient Greek mythology and its legendary gods.",
            "mythology", "mythology;greek;gods", null);
        store.insertKnowledge("myth-norse-thor", "mythology",
            "Norse Mythology: Thor and the Aesir",
            "Norse Mythology — Thor and the Aesir. Thor, the Norse thunder god, wielded the hammer Mjolnir and defended Asgard from the frost giants. Son of Odin the Allfather, Thor was beloved by mortals for his protection. The Norse cosmos included nine worlds connected by Yggdrasil, the world tree. This book explores Norse mythology and Viking legends.",
            "mythology", "mythology;norse;gods", null);
        store.insertKnowledge("myth-egypt-ra", "mythology",
            "Egyptian Mythology: Ra and the Underworld",
            "Egyptian Mythology — Ra and the Underworld. Ra, the sun god of ancient Egypt, sailed across the sky in his solar barque each day and journeyed through the underworld at night. The Egyptian pantheon included Osiris, Isis, Horus, and Anubis. The Book of the Dead guided souls through the afterlife. This book examines Egyptian mythology and its gods.",
            "mythology", "mythology;egyptian;gods", null);
        store.insertKnowledge("sci-quantum-basics", "science",
            "Quantum Computing: Fundamentals",
            "Quantum computing harnesses quantum mechanics — superposition, entanglement, and interference — to process information. Qubits can exist in multiple states simultaneously, enabling parallel computation. Key algorithms include Shor's (factoring) and Grover's (search).",
            "science", "quantum;computing;physics", null);
        store.insertKnowledge("sci-renewable-energy", "science",
            "Renewable Energy: Current State 2026",
            "Solar and wind power now account for over 30% of global electricity generation. Battery storage costs have dropped 90% since 2010. Offshore wind farms, green hydrogen, and next-generation perovskite solar cells are driving the transition from fossil fuels.",
            "science", "energy;renewable;climate", null);
        store.insertKnowledge("ai-research-2026", "technology",
            "AI Research Trends 2026",
            "Key AI research trends in 2026 include scaffolding over scaling (Symbolica's 144x amplification), emotion vectors in LLMs (Anthropic's 171 distinct states), temporal reasoning models (Time-R1), and the shift toward local-first AI with models running on phones and edge devices.",
            "technology", "AI;research;trends", null);
        store.commitAll();
        log.info("Seeded {} test knowledge chunks into Lucene", 6);
    }

    /**
     * Ensure the inference backend is up and configured correctly for E2E.
     *
     * <p>Two paths:</p>
     * <ul>
     *   <li><b>Remote backend</b> (WYRDSEKAI_INFERENCE_URL points at a
     *       non-localhost host): validate that it's healthy and exposes a
     *       large enough context window. Fail fast with an actionable error
     *       if not — the caller controls that container and must fix it.</li>
     *   <li><b>Local backend</b> (URL unset or localhost): if unhealthy, start
     *       the {@code llama-server} profile from {@code docker-compose.e2e.yml}.
     *       Old-school E2E: own the infrastructure end-to-end rather than
     *       assuming it.</li>
     * </ul>
     *
     * <p>Context-size validation is what caught the
     * {@code fullSleepCycleMemoryPipeline} failure — the shared 9B-drive
     * container on home-server had {@code --ctx-size 4096}, accumulated test state
     * blew past that, HTTP 400 came back, and the companion fell back to the
     * {@code inference_fail} canned response. Tests looked flaky; the actual
     * cause was container config. Validate at bootstrap, not inside individual
     * tests.</p>
     */
    private static void ensureInferenceBackend() {
        var backendType = E2eTestSupport.backendType();
        var url = E2eTestSupport.inferenceUrl(backendType);

        // If URL points at localhost and backend isn't healthy, try to bring it up.
        var isLocal = url.contains("localhost") || url.contains("127.0.0.1");
        var healthy = E2eTestSupport.isHealthy(url);

        // Resolve which compose profile + service to start based on backend type.
        // Keeping the mapping in one place so adding a new llama.cpp-based backend
        // (e.g. a phone-specific drive model) is a single-site edit.
        String profile = null;
        String service = null;
        switch (backendType) {
            case "llama-server", "llama" -> {
                profile = "llama";
                service = "llama-server";
            }
            case "llama-drive", "drive" -> {
                profile = "drive";
                service = "llama-drive";
            }
            default -> {
                // Non-llama.cpp backend (sglang, vllm) — no auto-start
                // path here; operators bring those up via `wyrd setup` or manual
                // compose commands. See SharedLlamaPool for sglang.
            }
        }

        if (!healthy && isLocal && profile != null) {
            log.info("Local {} not responding on {} — starting via docker-compose.e2e.yml (profile={})",
                service, url, profile);
            try {
                var compose = Path.of("docker", "docker-compose.e2e.yml");
                if (!compose.toFile().exists()) {
                    compose = Path.of("../docker", "docker-compose.e2e.yml");
                }
                if (compose.toFile().exists()) {
                    var proc = new ProcessBuilder("docker", "compose", "-f",
                        compose.toAbsolutePath().toString(),
                        "--profile", profile, "up", "-d", service)
                        .redirectErrorStream(true).start();
                    proc.waitFor(60, TimeUnit.SECONDS);
                    // Wait for health (model load can take 30-60s for 4B, up to
                    // 90s for 9B when cold-starting from disk).
                    int maxWaitIters = "llama-drive".equals(service) || "drive".equals(profile) ? 90 : 60;
                    for (int i = 0; i < maxWaitIters; i++) {
                        Thread.sleep(2000);
                        if (E2eTestSupport.isHealthy(url)) {
                            log.info("{} healthy after {}s", service, (i + 1) * 2);
                            healthy = true;
                            break;
                        }
                    }
                } else {
                    log.warn("docker-compose.e2e.yml not found — cannot auto-start inference backend");
                }
            } catch (Exception e) {
                log.warn("Failed to start {} via compose: {}", service, e.getMessage());
            }
        }

        // Backend must be healthy now (remote pre-existing, or we just started it locally).
        // If still not healthy, validateContextSize will surface the error as
        // a network warning; the first actual inference call will fail and
        // that's where individual tests report.
        if (healthy) {
            // Validate context size only for llama.cpp backends; SGLang doesn't expose /props.
            E2eTestSupport.validateContextSize(url);
        }
    }

    /**
     * Ensure Searxng is running for web search E2E tests.
     * Checks localhost:8888, starts docker-compose.e2e.yml if not available.
     */
    private static void ensureSearxng() {
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build();
            var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8888/"))
                .timeout(Duration.ofSeconds(3))
                .GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 500) {
                log.info("Searxng already running on localhost:8888");
                return;
            }
        } catch (Exception e) {
            // Not running — try to start it
        }

        log.info("Searxng not running — starting via docker-compose.e2e.yml");
        try {
            var compose = Path.of("docker", "docker-compose.e2e.yml");
            if (!compose.toFile().exists()) {
                compose = Path.of("../docker", "docker-compose.e2e.yml");
            }
            if (compose.toFile().exists()) {
                var proc = new ProcessBuilder("docker", "compose", "-f",
                    compose.toAbsolutePath().toString(), "up", "-d", "searxng")
                    .redirectErrorStream(true).start();
                proc.waitFor(30, TimeUnit.SECONDS);
                // Wait for Searxng to become healthy
                for (int i = 0; i < 15; i++) {
                    try {
                        Thread.sleep(2000);
                        var client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2)).build();
                        var req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8888/"))
                            .timeout(Duration.ofSeconds(2))
                            .GET().build();
                        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() < 500) {
                            log.info("Searxng started successfully after {}s", (i + 1) * 2);
                            return;
                        }
                    } catch (Exception ignore) {}
                }
                log.warn("Searxng did not become healthy in 30s — web search tests may fail");
            } else {
                log.warn("docker-compose.e2e.yml not found — web search tests may use DuckDuckGo fallback");
            }
        } catch (Exception e) {
            log.warn("Failed to start Searxng: {} — web search tests may use DuckDuckGo fallback", e.getMessage());
        }
    }

    /**
     * Populate OraclePredictionCache with predictions generated from the seeded
     * knowledge activity. In production, TemporalPatternExtractor runs during Forge
     * sleep cycles and populates this cache from activity logs. For E2E, we write
     * an activity log and run the extractor directly.
     */
    private static void seedOraclePredictions() {
        var cache = OraclePredictionCache.get();
        if (cache == null) return;

        // Write a minimal activity log for the extractor
        try {
            var logDir = Path.of(System.getProperty("java.io.tmpdir"),
                "wyrdsekai-e2e-activity");
            Files.createDirectories(logDir);
            var logFile = logDir.resolve("companion-wyrd.jsonl");

            // Generate realistic activity entries
            var now = Instant.now();
            var sb = new StringBuilder();
            for (int i = 0; i < 15; i++) {
                var ts = now.minus(Duration.ofHours(i * 2));
                sb.append("{\"type\":\"action\",\"action\":\"library_search\",\"ts\":\"").append(ts)
                  .append("\",\"room\":\"library\",\"agentId\":\"companion-wyrd\"}\n");
                if (i % 3 == 0) {
                    var ts2 = now.minus(Duration.ofHours(i * 2 + 1));
                    sb.append("{\"type\":\"action\",\"action\":\"web_search\",\"ts\":\"").append(ts2)
                      .append("\",\"room\":\"nexus\",\"agentId\":\"companion-wyrd\"}\n");
                }
            }
            Files.writeString(logFile, sb.toString());

            // Run extractor on the activity log
            var extractor = new TemporalPatternExtractor();
            var predictions = extractor.extract("companion-wyrd", logFile, 7);
            if (predictions != null && !predictions.isEmpty()) {
                cache.put("global", predictions);
                cache.put("companion-wyrd", predictions);
                log.info("Oracle: generated {} predictions from activity log via TemporalPatternExtractor",
                    predictions.size());
                return;
            }
        } catch (Exception e) {
            log.debug("TemporalPatternExtractor extraction failed ({}), using activity-based fallback",
                e.getMessage());
        }

        // Fallback: predictions that match what the extractor would produce from
        // the activity pattern above (library_search every 2h, web_search every 6h).
        cache.put("global", List.of(
            new OraclePrediction(
                "pred-activity-pattern",
                "Activity pattern detected: library searches occur regularly every 2 hours, "
                    + "suggesting a consistent research routine. Web searches cluster at 1/3 "
                    + "the frequency, indicating preference for local knowledge.",
                "pattern", 0.78, null,
                "15 library_search events over 30h, 5 web_search events",
                true),
            new OraclePrediction(
                "pred-room-preference",
                "Observation: 80% of activity originates from the library room. "
                    + "This agent has a strong room preference for knowledge-oriented spaces.",
                "pattern", 0.65, null,
                "Room distribution analysis over 30h window",
                false)
        ));
        cache.put("companion-wyrd", cache.get("global"));
        log.info("Oracle: seeded 2 activity-based predictions for E2E tests");
    }

    private Config pekkoConfig() {
        var arteryPort = PortAllocator.allocate();
        // Override cluster/artery settings for single-node test.
        // JDBC journal, Slick, and snapshot-store configs come from application.conf
        // (loaded via ConfigFactory.load()). The wyrdsekai.db.path system property
        // is set in start() before this method is called.
        // ConfigFactory.load() CACHES per classloader — in a multi-class test
        // JVM the first bootstrap's ${wyrdsekai.db.path} would stay baked in,
        // pointing every later class's journal/snapshot store at the FIRST
        // class's (deleted-on-stop) temp db → "no such table: snapshot" and
        // every RoomActor dies on recovery. Invalidate so THIS class's path
        // resolves fresh.
        ConfigFactory.invalidateCaches();
        return ConfigFactory.parseString("""
            pekko.actor.provider = cluster
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
              "org.wyrdsekai.core.economy.CountingHouseCommand" = jackson-json
              "org.wyrdsekai.core.economy.CountingHouseEvent" = jackson-json
              "org.wyrdsekai.core.economy.CountingHouseState" = jackson-json
              "org.wyrdsekai.core.soul.ForgeEvent" = jackson-json
              "org.wyrdsekai.core.soul.ForgeCommand" = jackson-json
              "org.wyrdsekai.core.soul.ForgeState" = jackson-json
            }
            pekko.remote.artery {
              canonical {
                hostname = "127.0.0.1"
                port = %d
              }
            }
            pekko.cluster {
              seed-nodes = ["pekko://wyrdsekai-test@127.0.0.1:%d"]
              downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
              sharding {
                state-store-mode = "ddata"
                passivation {
                  strategy = default-idle-strategy
                  default-idle-strategy.idle-entity.timeout = 2m
                }
              }
            }
            pekko.loglevel = WARNING
            """.formatted(arteryPort, arteryPort))
            .withFallback(ConfigFactory.load())
            .resolve();
    }
}
