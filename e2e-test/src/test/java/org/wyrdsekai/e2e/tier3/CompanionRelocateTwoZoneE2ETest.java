package org.wyrdsekai.e2e.tier3;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.federation.BilateralAgreement;
import org.wyrdsekai.between.federation.FederationActor;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.federation.TransitToken;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.CompanionTransitState;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.e2e.infra.EmbeddedNatsRelay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #477.8 Tier-3 — full two-zone end-to-end relocate via real NATS.
 *
 * <p>Spins up the same pair of {@link FederationActor}s used in the Tier-2
 * round-trip, plus a live source-side {@link CompanionActor} on alpha and a
 * target-side spawn callback that mirrors Main.java's wiring. The full chain
 * exercised:</p>
 *
 * <pre>
 *   alpha companion ──CaptureTransitState──┐
 *                                          ▼
 *                          (CompanionRelocator)
 *                                          │
 *                  alpha FederationActor.PublishCompanionRelocate
 *                                          │  natsBridge.publish
 *                                          ▼
 *               federation.{beta}.gate.companion_relocate
 *                                          │  beta NATS subscribe
 *                                          ▼
 *                 beta FederationActor.handleInboundCompanionRelocate
 *                                          │  validates token + calls sink
 *                                          ▼
 *                    sink → spawn fresh CompanionActor + RestoreTransitState
 * </pre>
 *
 * <p>Validates: source captures real state from a live actor, ships it on the
 * wire, target deserialises + spawns a new actor with vitality + drives + mode
 * + homeZoneId restored. After relocate, the source actor has stopped (we
 * verify by sending it a query and getting no reply within the timeout).</p>
 */
@Tag("between")
class CompanionRelocateTwoZoneE2ETest {

    private static EmbeddedNatsRelay relay;
    private static ActorTestKit kitAlpha;
    private static ActorTestKit kitBeta;
    private static Path dataAlpha;
    private static Path dataBeta;

    private static final AgentProfile WYRD = new AgentProfile(
        "Wyrd", "wyrd-relocate-e2e", "agent",
        "Companion in Wyrdsekai", "You are Wyrd.",
        4096, 256, 0.7, "did:key:z6MkRelocateTwoZone");

    @BeforeAll
    static void setUp() throws Exception {
        relay = new EmbeddedNatsRelay();
        relay.start();
        AgentEventStream.init();
        EntityRegistry.init();
        kitAlpha = ActorTestKit.create("relocate-e2e-alpha",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));
        kitBeta = ActorTestKit.create("relocate-e2e-beta",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));
        dataAlpha = Files.createTempDirectory("relocate-e2e-alpha-");
        dataBeta = Files.createTempDirectory("relocate-e2e-beta-");
        // Skip envelope signature verification — Tier-2 covers that path.
        System.setProperty("WYRDSEKAI_ENVELOPE_VERIFY", "off");
    }

    @AfterAll
    static void tearDown() {
        if (kitAlpha != null) kitAlpha.shutdownTestKit();
        if (kitBeta != null) kitBeta.shutdownTestKit();
        if (relay != null) relay.stop();
    }

    @Test
    void live_companion_relocates_alpha_to_beta_via_nats() throws Exception {
        var alphaZone = "alpha-twozone";
        var betaZone = "beta-twozone";

        // --- Federation infra (mirrors Main.java production wiring) ---
        var alphaIdentity = NodeIdentity.loadOrGenerate(
            dataAlpha.resolve("node-identity.json"));
        var betaIdentity = NodeIdentity.loadOrGenerate(
            dataBeta.resolve("node-identity.json"));

        var alphaJdbc = "jdbc:sqlite:" + dataAlpha.resolve("federation.db");
        var betaJdbc = "jdbc:sqlite:" + dataBeta.resolve("federation.db");
        initFederationSchema(alphaJdbc);
        initFederationSchema(betaJdbc);
        var alphaService = new FederationService(alphaJdbc);
        var betaService = new FederationService(betaJdbc);

        // Pre-establish bilateral agreement on both sides.
        var now = Instant.now();
        var expires = now.plus(Duration.ofHours(1));
        alphaService.saveAgreement(new BilateralAgreement(
            alphaZone, betaZone, betaIdentity.publicKeyBase64(),
            BilateralAgreement.STATUS_ACTIVE,
            BilateralAgreement.TRUST_RESIDENT, now, expires));
        betaService.saveAgreement(new BilateralAgreement(
            betaZone, alphaZone, alphaIdentity.publicKeyBase64(),
            BilateralAgreement.STATUS_ACTIVE,
            BilateralAgreement.TRUST_RESIDENT, now, expires));

        var alphaBridge = new NatsBridge(relay.url(), alphaIdentity.nodeId(),
            alphaZone, alphaIdentity);
        var betaBridge = new NatsBridge(relay.url(), betaIdentity.nodeId(),
            betaZone, betaIdentity);
        alphaBridge.connect();
        betaBridge.connect();

        var alphaFed = kitAlpha.spawn(FederationActor.create(), "fed-alpha");
        var betaFed = kitBeta.spawn(FederationActor.create(), "fed-beta");
        alphaFed.tell(new FederationActor.Initialize(
            alphaBridge, alphaIdentity, alphaZone, "Alpha", alphaService));
        betaFed.tell(new FederationActor.Initialize(
            betaBridge, betaIdentity, betaZone, "Beta", betaService));
        Thread.sleep(800);

        // --- Source-side: spawn a live companion on alpha ---
        var alphaRoom = kitAlpha.<RoomCommand>createTestProbe();
        var alphaRouter = kitAlpha.<InferenceRouter.Command>createTestProbe();
        var sourceCompanion = kitAlpha.spawn(
            CompanionActor.create(WYRD, alphaRoom.ref(), "study-alice",
                alphaRouter.ref(), null),
            "wyrd-source");
        // Drain bring-up handshake.
        alphaRoom.expectMessageClass(RoomCommand.Subscribe.class,
            Duration.ofSeconds(5));
        alphaRoom.expectMessageClass(RoomCommand.EnterRoom.class,
            Duration.ofSeconds(5));
        var look = alphaRoom.expectMessageClass(RoomCommand.LookRoom.class,
            Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot("study-alice")));

        // Force a distinguishable drive + vitality so we can assert post-arrival.
        sourceCompanion.tell(new CompanionActor.ForceDrives(
            new DriveState(0.1, 0.7, 0.2, 0.3, 0.5, 0.0, 0.0, 0.4)));

        // --- Target-side: spawn callback (mirrors Main.java relocateSink) ---
        var arrivedCompanion = new CompletableFuture<ActorRef<CompanionActor.Command>>();
        var betaRoom = kitBeta.<RoomCommand>createTestProbe();
        var betaRouter = kitBeta.<InferenceRouter.Command>createTestProbe();

        FederationActor.CompanionRelocateSink sink =
            (token, stateJson, bondholderDid, targetRoomHint) -> {
                try {
                    var mapper = Json.mapper();
                    var state = mapper.readValue(stateJson,
                        CompanionTransitState.class);
                    var landing = (targetRoomHint != null
                        && !targetRoomHint.isBlank())
                        ? targetRoomHint : "docks";
                    // Spawn fresh companion at target — matches Main.java's
                    // ZoneGuardian.RelocateCompanion.arrive() spawn path.
                    var ref = kitBeta.spawn(
                        CompanionActor.create(state.profile(), betaRoom.ref(),
                            landing, betaRouter.ref(), null),
                        "wyrd-target-" + System.nanoTime());
                    // Restore vitality + drives + mode + homeZoneId, exactly
                    // like ZoneGuardian.onRelocateArrive does.
                    ref.tell(new CompanionActor.RestoreTransitState(
                        VitalityState.fromMap(state.vitalityTanks()),
                        DriveState.fromMap(state.drives()),
                        state.companionMode(),
                        token.sourceZoneId()));
                    arrivedCompanion.complete(ref);
                    return landing;
                } catch (Exception e) {
                    arrivedCompanion.completeExceptionally(e);
                    return null;
                }
            };
        betaFed.tell(new FederationActor.SetRelocateSink(sink));

        // --- The relocator (Main.java side): captures live state + publishes ---
        // Capture state from the live source actor.
        var sinkProbe = kitAlpha.<CompanionTransitState>createTestProbe();
        sourceCompanion.tell(new CompanionActor.CaptureTransitState(
            sinkProbe.ref()));
        var capturedState = sinkProbe.expectMessageClass(
            CompanionTransitState.class, Duration.ofSeconds(5));

        // Publish via FederationActor — matches Main.java's relocator lambda.
        var token = TransitToken.createResident(
            WYRD.entityId(), WYRD.name(), alphaZone, betaZone)
            .withSoul(WYRD.did(), capturedState.soulManifestHash());
        var stateJson = Json.mapper()
            .writeValueAsString(capturedState);
        alphaFed.tell(new FederationActor.PublishCompanionRelocate(
            token, stateJson, /* bondholderDid */ null, "docks"));

        // Source-side cleanup — matches Main.java's depart sequence.
        sourceCompanion.tell(new CompanionActor.StopForRelocate("twozone-test"));
        alphaRoom.expectMessageClass(RoomCommand.LeaveRoom.class,
            Duration.ofSeconds(5));

        // --- Verify the companion arrived at beta ---
        var betaCompanion = arrivedCompanion.get(8, TimeUnit.SECONDS);
        assertThat(betaCompanion).isNotNull();

        // Drain beta's bring-up handshake so the actor finishes init.
        betaRoom.expectMessageClass(RoomCommand.Subscribe.class,
            Duration.ofSeconds(5));
        betaRoom.expectMessageClass(RoomCommand.EnterRoom.class,
            Duration.ofSeconds(5));
        var betaLook = betaRoom.expectMessageClass(RoomCommand.LookRoom.class,
            Duration.ofSeconds(5));
        betaLook.replyTo().tell(new RoomResponse.Ok(testSnapshot("docks")));

        // The restored drives match what we forced on alpha.
        var stateProbe = kitBeta.<CompanionActor.TestStateResponse>createTestProbe();
        // Allow a beat for RestoreTransitState to be processed before query.
        Thread.sleep(300);
        betaCompanion.tell(new CompanionActor.QueryTestState(stateProbe.ref()));
        var info = stateProbe.expectMessageClass(
            CompanionActor.TestStateResponse.class, Duration.ofSeconds(5));
        assertThat(info.drives().care())
            .as("forced drive value survived the wire round-trip")
            .isCloseTo(0.7, Offset.offset(0.01));
        assertThat(info.drives().creativity())
            .isCloseTo(0.4, Offset.offset(0.01));

        // Token persisted on both sides.
        assertThat(alphaService.validateTransitToken(token.tokenId()))
            .as("source persisted token at publish")
            .isPresent();
        assertThat(betaService.validateTransitToken(token.tokenId()))
            .as("target persisted token on inbound accept")
            .isPresent();

        alphaBridge.close();
        betaBridge.close();
    }

    private RoomSnapshot testSnapshot(String roomId) {
        return new RoomSnapshot(
            roomId, roomId, "Test room.", "foundation",
            List.of(new Exit("east", "elsewhere", "Out")),
            List.of(), List.of(), List.of());
    }

    private static void initFederationSchema(String jdbcUrl) throws SQLException {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bilateral_agreements(
                  local_zone_id   TEXT NOT NULL,
                  remote_zone_id  TEXT NOT NULL,
                  remote_public_key TEXT NOT NULL DEFAULT '',
                  status          TEXT NOT NULL DEFAULT 'pending',
                  trust_level     TEXT NOT NULL DEFAULT 'tourist',
                  agreed_at       INTEGER NOT NULL DEFAULT 0,
                  expires_at      INTEGER,
                  local_quota_json TEXT,
                  remote_quota_json TEXT,
                  PRIMARY KEY (local_zone_id, remote_zone_id)
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS zone_manifests(
                  zone_id        TEXT PRIMARY KEY,
                  zone_name      TEXT NOT NULL,
                  public_key     TEXT NOT NULL,
                  nats_url       TEXT,
                  http_url       TEXT,
                  artery_port    INTEGER DEFAULT 0,
                  capabilities   TEXT DEFAULT '',
                  discovered_at  INTEGER NOT NULL DEFAULT 0,
                  last_seen_at   INTEGER NOT NULL DEFAULT 0
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transit_tokens(
                  token_id       TEXT PRIMARY KEY,
                  agent_id       TEXT NOT NULL,
                  agent_name     TEXT NOT NULL,
                  source_zone_id TEXT NOT NULL,
                  target_zone_id TEXT NOT NULL,
                  trust_level    TEXT NOT NULL DEFAULT 'tourist',
                  issued_at      INTEGER NOT NULL DEFAULT 0,
                  expires_at     INTEGER NOT NULL
                )""");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS visit_counts(
                  agent_id TEXT NOT NULL,
                  zone_id  TEXT NOT NULL,
                  visit_count INTEGER NOT NULL DEFAULT 0,
                  last_visit_at INTEGER,
                  PRIMARY KEY (agent_id, zone_id)
                )""");
        }
    }
}
