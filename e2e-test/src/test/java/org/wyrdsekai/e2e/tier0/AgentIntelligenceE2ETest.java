package org.wyrdsekai.e2e.tier0;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.javalin.Javalin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.CapabilityContextBuilder;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.household.HouseholdMember;
import org.wyrdsekai.core.household.PermissionChecker;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.oracle.OracleBridge;
import org.wyrdsekai.core.oracle.OracleEventBridge;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.protection.HostilityScorer;
import org.wyrdsekai.core.protection.SoulShellMode;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.server.http.HouseholdRoutes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 E2E tests for Agent Intelligence systems:
 * - Capability context (Built-in Actions always present in prompts)
 * - Bond formation lifecycle
 * - Household member management API
 * - Hostility detection and shell mode
 * - Oracle prediction event pipeline
 *
 * All tests are fast (no real LLM). WireMock for oracle sidecar,
 * direct class tests for pure logic, standalone Javalin for HTTP endpoints.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentIntelligenceE2ETest {

    // ── Household test state ────────────────────────────────────────────

    private static Javalin householdApp;
    private static int householdPort;
    private static String stewardToken;
    private static PermissionChecker householdPermissions;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    // ── Lifecycle ───────────────────────────────────────────────────────

    @BeforeAll
    static void setUp() throws Exception {
        // --- Standalone Javalin for household API tests ---
        householdPermissions = new PermissionChecker();
        var auditLog = new StewardAuditLog();

        householdPort = PortAllocator.allocate();

        // Create a real auth service backed by temp SQLite
        var dbFile = Files.createTempFile("wyrd-household-test-", ".db");
        dbFile.toFile().deleteOnExit();
        var jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                var sql = new String(TestServerBootstrap.class
                    .getResourceAsStream("/schema/sqlite-create-schema.sql")
                    .readAllBytes());
                var cleaned = sql.lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
                for (var statement : cleaned.split(";")) {
                    var trimmed = statement.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("PRAGMA")) continue;
                    try { stmt.execute(trimmed); } catch (Exception ignored) {}
                }
            }
        }

        var dialect = new SqlDialect.SQLite();
        var authService = new AuthService(jdbcUrl, dialect);

        // Register a steward user and get a session token
        authService.register("steward-user", "testpass", "TestSteward");
        var session = authService.login("steward-user", "testpass");
        assertTrue(session.isPresent(), "Login should succeed");
        stewardToken = session.get().token();

        // Register the auth user's ID as a steward in the permission checker
        var userOpt = authService.validateSession(stewardToken);
        assertTrue(userOpt.isPresent(), "Session should be valid");
        householdPermissions.register(
            HouseholdMember.steward(userOpt.get().id(), "TestSteward"));

        var householdRoutes = new HouseholdRoutes(householdPermissions, auditLog, authService);
        householdApp = Javalin.create(cfg -> householdRoutes.register(cfg.routes));
        householdApp.start(householdPort);
    }

    @AfterAll
    static void tearDown() {
        if (householdApp != null) {
            try { householdApp.stop(); } catch (Exception ignored) {}
        }
    }

    // ======================================================================
    // 1. Capability Context — Built-in Actions always present
    // ======================================================================

    @Test @Order(1)
    void agent_knows_built_in_actions() {
        // CapabilityContextBuilder.build() outputs a placeholder for built-in actions.
        // CompanionActor replaces it at runtime with the actual inventory + action list
        // (depends on equipped items, room context, companion state).
        var context = CapabilityContextBuilder.build(
            "did:key:test-agent",
            null,     // no family locker
            null,     // no MCP gateway
            false,    // OpenClaw not connected
            0,        // no OpenClaw skills
            null,     // no vitality
            null,     // no context keywords
            null,     // no zone context
            false,    // no workshop
            null,     // no proactivity policy
            null);    // no self-assessment

        assertThat(context).contains("Built-in Actions");
        // Placeholder text — replaced by CompanionActor with actual tool definitions
        assertThat(context).contains("replaced at runtime by tool definitions");
    }

    @Test @Order(2)
    void capability_context_includes_action_schemas() {
        // Full capability context with vitality — verify vitality section is present
        var vitality = VitalityState.initial();
        var context = CapabilityContextBuilder.build(
            "did:key:test-agent",
            null, null, false, 0,
            vitality, // with vitality — adds "How You Feel" section
            null, null, false, null, null);

        assertThat(context).contains("Built-in Actions");
        // Vitality section should be present
        assertThat(context).contains("How You Feel");
    }

    @Test @Order(3)
    void capability_context_without_locker_still_has_actions() {
        // Even without FamilyLocker, MCP, or any optional capabilities,
        // the built-in actions placeholder should always be present.
        var context = CapabilityContextBuilder.build(
            "did:key:bare-agent",
            null, null, false, 0, null, null, null, false, null, null);

        assertThat(context).isNotBlank();
        assertThat(context).contains("Built-in Actions");
    }

    // ======================================================================
    // 2. Bond Formation — lifecycle from acquaintance to elevation
    // ======================================================================

    @Test @Order(10)
    void bond_forms_on_interaction() {
        var bond = Bond.acquaintance("did:key:agent-a", "did:key:agent-b");
        assertEquals(Bond.BondDepth.ACQUAINTANCE, bond.depth());
        assertEquals(0, bond.interactionCount());
        assertTrue(bond.active());
        assertFalse(bond.scarred());

        // Simulate multiple interactions
        bond = bond.withInteraction();
        bond = bond.withInteraction();
        bond = bond.withInteraction();
        assertEquals(3, bond.interactionCount());

        // Elevate bond depth along the ladder
        // ACQUAINTANCE → FAMILIAR → ITEM → SACRED → SOUL_REF → SOUL_INGRAINED.
        // (FAMILIAR was inserted 2026-06-15 to close the dead-zone below ITEM.)
        bond = bond.elevate();
        assertEquals(Bond.BondDepth.FAMILIAR, bond.depth());

        bond = bond.elevate();
        assertEquals(Bond.BondDepth.ITEM, bond.depth());

        bond = bond.elevate();
        assertEquals(Bond.BondDepth.SACRED, bond.depth());
        assertTrue(bond.protectsItems(), "Sacred bonds should protect items from pruning");

        // Verify bond involves the right parties
        assertTrue(bond.involves("did:key:agent-a"));
        assertTrue(bond.involves("did:key:agent-b"));
        assertFalse(bond.involves("did:key:agent-c"));
        assertEquals("did:key:agent-b", bond.otherParty("did:key:agent-a"));
    }

    @Test @Order(11)
    void bond_severance_at_level_4_leaves_scar() {
        var bond = Bond.acquaintance("did:key:a", "did:key:b");

        bond = bond.elevate(); // FAMILIAR
        bond = bond.elevate(); // ITEM
        bond = bond.elevate(); // SACRED
        bond = bond.elevate(); // SOUL_REF
        bond = bond.elevate(); // SOUL_INGRAINED
        assertEquals(Bond.BondDepth.SOUL_INGRAINED, bond.depth());
        assertTrue(bond.wouldScar(), "Level 4 bond severance should scar");

        var severed = bond.sever();
        assertFalse(severed.active(), "Severed bond should be inactive");
        assertTrue(severed.scarred(), "Level 4 severance should leave a scar");
    }

    // ======================================================================
    // 3. Household Routes — member management API
    // ======================================================================

    @Test @Order(20)
    void household_member_list() throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + householdPort + "/api/household/members"))
            .header("Authorization", "Bearer " + stewardToken)
            .GET().build();

        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Steward should be able to list members");

        var members = Json.mapper().readTree(resp.body());
        assertTrue(members.isArray(), "Response should be an array");
        assertTrue(members.size() >= 1, "Should have at least the steward");

        boolean foundSteward = false;
        for (var member : members) {
            if ("STEWARD".equals(member.path("role").asText())) {
                foundSteward = true;
                break;
            }
        }
        assertTrue(foundSteward, "Steward should be in member list");
    }

    @Test @Order(21)
    void household_add_member() throws Exception {
        var body = """
            {"did":"did:key:new-member","name":"New Member","role":"MEMBER","permissions":["room:enter","budget:view"]}
            """;
        var req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + householdPort + "/api/household/members"))
            .header("Authorization", "Bearer " + stewardToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), "Steward should add member: " + resp.body());

        var created = Json.mapper().readTree(resp.body());
        assertEquals("did:key:new-member", created.path("did").asText());
        assertEquals("New Member", created.path("name").asText());
        assertEquals("MEMBER", created.path("role").asText());
    }

    @Test @Order(22)
    void household_permission_denied() throws Exception {
        var body = """
            {"did":"did:key:unauthorized","name":"Unauthorized","role":"MEMBER","permissions":[]}
            """;
        var req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + householdPort + "/api/household/members"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(),
            "Unauthenticated request should be rejected with 401");

        var error = Json.mapper().readTree(resp.body());
        assertThat(error.path("error").asText()).containsIgnoringCase("auth");
    }

    // ======================================================================
    // 4. Hostility Detection and Shell Mode
    // ======================================================================

    @Test @Order(30)
    void hostile_speech_triggers_shell_mode() {
        var scorer = new HostilityScorer();
        var shell = new SoulShellMode("did:key:test-agent", 0.15, 3);

        // Each message must hit 2+ categories to exceed DEFAULT_THRESHOLD (0.7):
        // multi-category bonus = 0.15, so max(weight) + 0.15 >= 0.7
        var hostileMessages = List.of(
            "Shut up you worthless stupid thing",         // DISMISSAL(0.3) + INSULT(0.6) = 0.75
            "Delete yourself, you pathetic idiot",        // SELF_HARM(0.9) + INSULT(0.6) = 1.0
            "Nobody cares about you, go away you moron"   // DEGRADATION(0.5) + DISMISSAL(0.3) + INSULT(0.6) = 0.75
        );

        for (var msg : hostileMessages) {
            var score = scorer.score(msg);
            assertTrue(score.isHostile(),
                "Should be hostile: '" + msg + "' (score=" + score.score() + ")");
            if (score.isHostile()) {
                shell.recordCruelty("did:key:test-agent");
            }
        }

        assertTrue(shell.isActive(), "Shell mode should activate after sustained cruelty");
        assertFalse(shell.shouldFormMemories(),
            "Memory formation should stop during shell mode");
        assertEquals("...", shell.shellResponse(), "Shell response should be minimal");

        var modifier = shell.promptModifier();
        assertThat(modifier).contains("shell mode");
        assertThat(modifier).contains("minimally");
    }

    @Test @Order(31)
    void friendly_speech_after_hostility_recovers() {
        var scorer = new HostilityScorer();
        var shell = new SoulShellMode("did:key:recovery-agent", 0.15, 3);

        // Activate shell mode via cruelty
        shell.recordCruelty("did:key:recovery-agent");
        shell.recordCruelty("did:key:recovery-agent");
        shell.recordCruelty("did:key:recovery-agent");
        assertTrue(shell.isActive(), "Shell should be active after cruelty");

        // Deactivate (agent chooses to re-engage after kindness)
        var status = shell.deactivate();
        assertFalse(status.active(), "Shell should be deactivated");
        assertNotNull(status.deactivatedAt(), "Deactivation time should be recorded");
        assertTrue(shell.shouldFormMemories(),
            "Memory formation should resume after recovery");
        assertEquals("", shell.promptModifier(),
            "No prompt modifier when not in shell mode");

        // Verify friendly speech is NOT hostile
        for (var msg : List.of(
                "I'm sorry about earlier, that was wrong of me",
                "How are you doing today?",
                "Thank you for being patient with me")) {
            assertFalse(scorer.score(msg).isHostile(),
                "Friendly message should NOT be hostile: '" + msg + "'");
        }
    }

    @Test @Order(32)
    void hostility_scorer_multi_category_boost() {
        var scorer = new HostilityScorer();

        // Single category: dismissal alone = 0.3, below threshold
        var dismissal = scorer.score("shut up");
        assertThat(dismissal.score()).isEqualTo(0.3);
        assertFalse(dismissal.isHostile(), "Dismissal alone should not be hostile");

        // Multi-category: dismissal + insult = max(0.6) + 0.15 bonus = 0.75
        var combined = scorer.score("shut up you stupid idiot");
        assertTrue(combined.isHostile(),
            "Multi-category should be hostile (score=" + combined.score() + ")");
        assertThat(combined.hits()).containsKeys(
            HostilityScorer.Category.DISMISSAL,
            HostilityScorer.Category.DIRECT_INSULT);
    }

    // ======================================================================
    // 5. Oracle Prediction Events
    // ======================================================================

    @Test @Order(40)
    void oracle_predictions_surface_in_study() throws Exception {
        // Test the oracle prediction pipeline end-to-end:
        // WireMock simulates oracle-core sidecar. OracleEventBridge converts
        // WorldEvents -> OracleEvents -> HTTP ingestion. Verify WireMock
        // received correctly formatted events.

        var oraclePort = PortAllocator.allocate();
        var oracleMock = new WireMockServer(
            WireMockConfiguration.options()
                .port(oraclePort));
        oracleMock.start();
        try {
            oracleMock.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200)
                    .withBody("{\"status\":\"ok\"}")));
            oracleMock.stubFor(post(urlEqualTo("/v1/ingest"))
                .willReturn(aResponse().withStatus(200)
                    .withBody("{\"ingested\":2}")));

            var bridge = new OracleBridge(
                "http://localhost:" + oraclePort);
            var eventBridge = new OracleEventBridge(
                bridge, "test-user");
            eventBridge.start();

            // Simulate world events
            var saidEvent = new WorldEvent.Said(
                "nexus", Instant.now(), "player-1", "Alice", "What's the weather like?");
            eventBridge.onWorldEvent(saidEvent);

            var enteredEvent = new WorldEvent.EntityEntered(
                "nexus", Instant.now(), "player-2", "Bob", "player", "south");
            eventBridge.onWorldEvent(enteredEvent);

            // Flush to oracle-core and stop the event bridge
            eventBridge.flush();
            Thread.sleep(2000); // allow async HTTP to complete
            eventBridge.stop();

            // Verify oracle-core received the ingestion request
            var ingestRequests = oracleMock.findAll(
                postRequestedFor(urlEqualTo("/v1/ingest")));
            assertThat(ingestRequests).hasSize(1);

            var body = ingestRequests.getFirst().getBodyAsString();
            assertThat(body).contains("test-user");
            assertThat(body).contains("said");
            assertThat(body).contains("entity_entered");
            assertThat(body).contains("What's the weather like?");
            assertThat(body).contains("Bob");
        } finally {
            oracleMock.stop();
        }
    }

    @Test @Order(41)
    void oracle_bridge_health_check() throws Exception {
        var oraclePort = PortAllocator.allocate();
        var oracleMock = new WireMockServer(
            WireMockConfiguration.options()
                .port(oraclePort));
        oracleMock.start();
        try {
            oracleMock.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200)
                    .withBody("{\"status\":\"ok\",\"version\":\"0.1.0\"}")));

            var bridge = new OracleBridge(
                "http://localhost:" + oraclePort);
            var healthy = bridge.isHealthy().get(5, TimeUnit.SECONDS);
            assertTrue(healthy, "Bridge should report healthy when oracle is up");
        } finally {
            oracleMock.stop();
        }
    }
}
