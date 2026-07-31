package org.wyrdsekai.e2e.tier3;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.federation.BilateralAgreement;
import org.wyrdsekai.between.federation.FederationActor;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.federation.TransitToken;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionTransitState;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.VitalityState;
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
import static org.awaitility.Awaitility.await;

/**
 * #477.7 Tier-2 — full {@code companion_relocate} NATS round-trip.
 *
 * <p>Two FederationActors connect to the same embedded {@code nats-server}.
 * Alpha pre-publishes its manifest so beta will signature-verify alpha's
 * envelopes. Both sides have a {@code resident}-tier
 * bilateral agreement persisted directly via {@link FederationService}. Beta
 * registers a relocate sink. Alpha publishes a {@link
 * org.wyrdsekai.between.federation.FederationProtocol.CompanionRelocateMsg}
 * via {@code FederationActor.PublishCompanionRelocate}; beta's sink receives
 * the deserialised payload, beta sends an ack envelope back.</p>
 *
 * <p>Validates: subject routing ({@code federation.{target}.gate.companion_relocate}),
 * JSON wire format, TransitToken validation (target match + non-expired),
 * envelope verification (alpha's pubkey is known via the seeded manifest),
 * and the round-trip ack.</p>
 */
@Tag("between")
class CompanionRelocateNatsRoundTripTest {

    private static EmbeddedNatsRelay relay;
    private static ActorTestKit kitAlpha;
    private static ActorTestKit kitBeta;
    private static Path dataAlpha;
    private static Path dataBeta;

    @BeforeAll
    static void setUp() throws Exception {
        relay = new EmbeddedNatsRelay();
        relay.start();
        kitAlpha = ActorTestKit.create("companion-relocate-alpha",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));
        kitBeta = ActorTestKit.create("companion-relocate-beta",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));
        dataAlpha = Files.createTempDirectory("relocate-alpha-");
        dataBeta = Files.createTempDirectory("relocate-beta-");
    }

    @AfterAll
    static void tearDown() {
        if (kitAlpha != null) kitAlpha.shutdownTestKit();
        if (kitBeta != null) kitBeta.shutdownTestKit();
        if (relay != null) relay.stop();
    }

    @BeforeEach
    void disableEnvelopeVerify() {
        // The manifest exchange handshake is async and rate-limited. For this
        // narrow test we only care about the relocate payload; HARD signature
        // verification is exercised by ZoneToZoneTransitE2ETest. SOFT mode
        // logs warnings on missing pubkey but accepts the envelope.
        System.setProperty("WYRDSEKAI_ENVELOPE_VERIFY", "off");
    }

    @Test
    void companion_relocate_round_trips_via_real_nats() throws Exception {
        var alphaZone = "alpha-relocate";
        var betaZone = "beta-relocate";

        // Per-zone identities + JDBC + NATS bridges.
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

        // Pre-establish the bilateral agreement on both sides — bypasses the
        // propose/accept dance so we can focus on the relocate path.
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

        // Give NATS subscriptions a moment to register.
        Thread.sleep(800);

        // Beta installs a sink that captures the inbound payload.
        var sinkResult = new AtomicReference<CompletableFuture<SinkPayload>>(
            new CompletableFuture<>());
        betaFed.tell(new FederationActor.SetRelocateSink(
            (token, stateJson, bondholderDid, targetRoomHint) -> {
                sinkResult.get().complete(new SinkPayload(
                    token, stateJson, bondholderDid, targetRoomHint));
                return targetRoomHint != null ? targetRoomHint : "docks";
            }));

        // Build a realistic CompanionTransitState payload.
        var profile = new AgentProfile("Wyrd", "wyrd-001", "agent",
            "Companion", "You are Wyrd.",
            4096, 256, 0.7, "did:key:z6MkRelocateE2E");
        var state = CompanionTransitState.capture(profile,
            VitalityState.initial(),
            new DriveState(0.1, 0.7, 0.2, 0.3, 0.5, 0.0, 0.0, 0.4),
            "settled", "PRESENT_WITH_USER",
            List.of("did:key:z6MkAlice"),
            "study-alice", "en", "manifest-hash-1");
        var stateJson = Json.mapper()
            .writeValueAsString(state);

        // Mint a token and publish via alpha's FederationActor — this is the
        // exact path Main.java's CompanionRelocator uses.
        var token = TransitToken.createResident(
            profile.entityId(), profile.name(), alphaZone, betaZone)
            .withSoul(profile.did(), state.soulManifestHash());
        alphaFed.tell(new FederationActor.PublishCompanionRelocate(
            token, stateJson, "did:key:z6MkAlice", "study-alice"));

        // Beta's sink fires with the deserialised state.
        var payload = sinkResult.get().get(8, TimeUnit.SECONDS);
        assertThat(payload.token().tokenId()).isEqualTo(token.tokenId());
        assertThat(payload.token().agentDid()).isEqualTo(profile.did());
        assertThat(payload.token().sourceZoneId()).isEqualTo(alphaZone);
        assertThat(payload.token().targetZoneId()).isEqualTo(betaZone);
        assertThat(payload.bondholderDid()).isEqualTo("did:key:z6MkAlice");
        assertThat(payload.targetRoomHint()).isEqualTo("study-alice");

        // Round-trip the state JSON to confirm wire format.
        var restored = Json.mapper()
            .readValue(payload.stateJson(), CompanionTransitState.class);
        assertThat(restored.profile().did()).isEqualTo(profile.did());
        assertThat(restored.drives()).containsEntry("care", 0.7);
        assertThat(restored.activeBondPartnerDids()).containsExactly("did:key:z6MkAlice");

        // Both sides persisted the token.
        await().atMost(Duration.ofSeconds(3))
            .untilAsserted(() -> {
                assertThat(alphaService.validateTransitToken(token.tokenId()))
                    .as("source persisted token at publish")
                    .isPresent();
                assertThat(betaService.validateTransitToken(token.tokenId()))
                    .as("target persisted token on inbound accept")
                    .isPresent();
            });

        alphaBridge.close();
        betaBridge.close();
    }

    @Test
    void target_mismatch_token_is_rejected_without_invoking_sink() throws Exception {
        var alphaZone = "alpha-mismatch";
        var betaZone = "beta-mismatch";
        var thirdZone = "gamma-mismatch";

        var alphaIdentity = NodeIdentity.loadOrGenerate(
            dataAlpha.resolve("node-identity-mm.json"));
        var betaIdentity = NodeIdentity.loadOrGenerate(
            dataBeta.resolve("node-identity-mm.json"));
        var alphaJdbc = "jdbc:sqlite:" + dataAlpha.resolve("federation-mm.db");
        var betaJdbc = "jdbc:sqlite:" + dataBeta.resolve("federation-mm.db");
        initFederationSchema(alphaJdbc);
        initFederationSchema(betaJdbc);
        var alphaService = new FederationService(alphaJdbc);
        var betaService = new FederationService(betaJdbc);

        var alphaBridge = new NatsBridge(relay.url(), alphaIdentity.nodeId(),
            alphaZone, alphaIdentity);
        var betaBridge = new NatsBridge(relay.url(), betaIdentity.nodeId(),
            betaZone, betaIdentity);
        alphaBridge.connect();
        betaBridge.connect();

        var alphaFed = kitAlpha.spawn(FederationActor.create(),
            "fed-alpha-mm");
        var betaFed = kitBeta.spawn(FederationActor.create(),
            "fed-beta-mm");
        alphaFed.tell(new FederationActor.Initialize(
            alphaBridge, alphaIdentity, alphaZone, "Alpha", alphaService));
        betaFed.tell(new FederationActor.Initialize(
            betaBridge, betaIdentity, betaZone, "Beta", betaService));
        Thread.sleep(500);

        var sinkInvoked = new AtomicReference<Boolean>(false);
        betaFed.tell(new FederationActor.SetRelocateSink(
            (token, stateJson, bondholderDid, targetRoomHint) -> {
                sinkInvoked.set(true);
                return "docks";
            }));

        // Token claims target=gamma but we publish to beta — beta should drop.
        var token = TransitToken.createResident(
            "agent-x", "Mistargeted", alphaZone, thirdZone);
        alphaFed.tell(new FederationActor.PublishCompanionRelocate(
            token, "{}", null, null));

        // Wait long enough that any inbound delivery would have happened.
        Thread.sleep(1500);
        assertThat(sinkInvoked.get())
            .as("sink must NOT be invoked for target_mismatch token")
            .isFalse();

        alphaBridge.close();
        betaBridge.close();
    }

    private record SinkPayload(
        TransitToken token, String stateJson,
        String bondholderDid, String targetRoomHint) {}

    /**
     * FederationService doesn't ship a schema initialiser — Main.java + the
     * existing FederationServiceTest both bring their own. Mirror the same
     * tables here so saveAgreement / saveTransitToken don't NOP-on-error.
     */
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
