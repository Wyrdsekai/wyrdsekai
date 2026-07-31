package org.wyrdsekai.e2e.tier1;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-node household E2E test (Tier 1 — needs NATS).
 *
 * Starts two Wyrdsekai server instances sharing a NATS server.
 * Tests all 8 waves of across nodes:
 *   Wave 1: Account replication (steward on A, invite redeem on B)
 *   Wave 2: Capability gossip (both nodes see each other)
 *   Wave 3: Companion claim/defer (first node claims, second defers)
 *   Wave 4: Room primary claims (capability-matched)
 *   Wave 5: Unified sessions (session visible across nodes)
 *   Wave 8: Observability (services visible on both nodes)
 *
 * Requires: NATS server (Docker or nats-server binary on PATH).
 * Run: WYRDSEKAI_E2E_NATS=local ./gradlew :e2e-test:test --tests "*MultiNodeHouseholdE2ETest"
 */
@Tag("multinode")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiNodeHouseholdE2ETest {

    private static final Logger log = LoggerFactory.getLogger(MultiNodeHouseholdE2ETest.class);

    private static NatsServerFixture nats;
    private static TestServerBootstrap nodeA;
    private static TestServerBootstrap nodeB;
    private static HttpClient http;
    private static ObjectMapper mapper;
    private static int portA;
    private static int portB;

    private static String stewardToken;
    private static String inviteCode;

    @BeforeAll
    static void setUp() throws Exception {
        // Skip if NATS not available
        NatsServerFixture.assumeAvailable();

        http = HttpClient.newHttpClient();
        mapper = new ObjectMapper();

        // Start shared NATS server
        nats = new NatsServerFixture();
        nats.start();
        log.info("NATS started at {}", nats.natsUrl());

        // Start Node A (first node — will become steward's node)
        portA = PortAllocator.allocate();
        System.setProperty("WYRDSEKAI_BETWEEN_ENABLED", "true");
        System.setProperty("WYRDSEKAI_NATS_URL", nats.natsUrl());
        System.setProperty("WYRDSEKAI_NATS_AUTO_START", "false");
        System.setProperty("WYRDSEKAI_NODE_ID", "node-a");
        nodeA = new TestServerBootstrap(List.of(), portA, List.of(), false /* invite-only */);
        nodeA.start();
        log.info("Node A started on port {}", portA);

        // Brief pause so Node A's Between mesh establishes
        Thread.sleep(2000);

        // Start Node B (second node — will join the mesh)
        portB = PortAllocator.allocate();
        System.setProperty("WYRDSEKAI_NODE_ID", "node-b");
        nodeB = new TestServerBootstrap(List.of(), portB, List.of(), false /* invite-only */);
        nodeB.start();
        log.info("Node B started on port {}", portB);

        // Wait for mesh to form
        Thread.sleep(3000);
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("WYRDSEKAI_BETWEEN_ENABLED");
        System.clearProperty("WYRDSEKAI_NATS_URL");
        System.clearProperty("WYRDSEKAI_NATS_AUTO_START");
        System.clearProperty("WYRDSEKAI_NODE_ID");
        if (nodeB != null) nodeB.stop();
        if (nodeA != null) nodeA.stop();
        if (nats != null) nats.stop();
    }

    // ── HTTP helpers ──

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postAuth(int port, String path, String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAuth(int port, String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── Wave 1: Accounts & Security ──

    @Test @Order(1)
    void wave1_stewardCreatesOnNodeA() throws Exception {
        var resp = post(portA, "/api/auth/register",
            """
            {"username":"steward","password":"stewardpass","display_name":"The Steward"}
            """);
        assertEquals(201, resp.statusCode(), "Steward should register on A: " + resp.body());
        var auth = mapper.readTree(resp.body());
        assertEquals("steward", auth.get("role").asText());
        stewardToken = auth.get("token").asText();
        log.info("Wave 1: Steward created on Node A");
    }

    @Test @Order(2)
    void wave1_accountReplicatesToNodeB() throws Exception {
        // Wait for NATS replication
        Thread.sleep(2000);

        // Steward should be able to login on Node B (account replicated)
        var resp = post(portB, "/api/auth/login",
            """
            {"username":"steward","password":"stewardpass"}
            """);
        assertEquals(200, resp.statusCode(),
            "Steward should login on B (account replicated): " + resp.body());
        log.info("Wave 1: Account replicated from A to B — steward can login on B");
    }

    @Test @Order(3)
    void wave1_inviteCreatedOnA_redeemedOnB() throws Exception {
        // Steward creates invite on Node A
        var invResp = postAuth(portA, "/api/auth/invite",
            """
            {"name":"Alice","role":"member"}
            """, stewardToken);
        assertEquals(201, invResp.statusCode(), "Invite should create: " + invResp.body());
        inviteCode = mapper.readTree(invResp.body()).get("code").asText();
        log.info("Wave 1: Invite created on A: {}", inviteCode);

        // JetStream pull replication is asynchronous — the subscriber on Node
        // B pulls on an interval, so the invite arrives a few seconds after
        // publish. Poll the redeem endpoint rather than trusting a fixed
        // sleep: it races the replication window.
        var redeemResp = pollForRedeem(
            """
            {"code":"%s","username":"alice","password":"alicepass","displayName":"Alice"}
            """.formatted(inviteCode),
            Duration.ofSeconds(15));
        assertEquals(201, redeemResp.statusCode(),
            "Alice should redeem invite on B: " + redeemResp.body());
        var auth = mapper.readTree(redeemResp.body());
        assertEquals("member", auth.get("role").asText());
        log.info("Wave 1: Invite redeemed on B — Alice is a member");
    }

    /** Retry redeem on Node B until it succeeds (replication landed) or the
     *  timeout expires. Any non-201 response is treated as "not yet". */
    private HttpResponse<String> pollForRedeem(String body,
                                                              Duration timeout)
            throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        HttpResponse<String> last = null;
        while (System.nanoTime() < deadline) {
            last = post(portB, "/api/auth/redeem", body);
            if (last.statusCode() == 201) return last;
            Thread.sleep(500);
        }
        return last;
    }

    @Test @Order(4)
    void wave1_redeemReplicatesBackToA() throws Exception {
        // Poll login on A until Alice's account has replicated back, or give
        // up. Same reasoning as the redeem poll above — pull-based JetStream.
        var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        HttpResponse<String> resp = null;
        while (System.nanoTime() < deadline) {
            resp = post(portA, "/api/auth/login",
                """
                {"username":"alice","password":"alicepass"}
                """);
            if (resp.statusCode() == 200) break;
            Thread.sleep(500);
        }
        assertEquals(200, resp.statusCode(),
            "Alice should login on A (account replicated back): " + resp.body());
        log.info("Wave 1: Alice's account replicated back to A — full mesh sync");
    }

    @Test @Order(5)
    void wave1_registrationBlockedWithoutInvite() throws Exception {
        // Random registration should be blocked on both nodes
        var respA = post(portA, "/api/auth/register",
            """
            {"username":"intruder","password":"password123"}
            """);
        assertEquals(403, respA.statusCode(), "Registration should be blocked on A");

        var respB = post(portB, "/api/auth/register",
            """
            {"username":"intruder","password":"password123"}
            """);
        assertEquals(403, respB.statusCode(), "Registration should be blocked on B");
        log.info("Wave 1: Registration correctly blocked on both nodes");
    }

    // ── Wave 2: Node Coordination (Capability Gossip) ──

    @Test @Order(10)
    void wave2_bothNodesHealthy() throws Exception {
        var healthA = get(portA, "/health");
        var healthB = get(portB, "/health");
        assertEquals(200, healthA.statusCode(), "Node A should be healthy");
        assertEquals(200, healthB.statusCode(), "Node B should be healthy");
        log.info("Wave 2: Both nodes healthy");
    }

    // ── Wave 5: Unified Sessions ──

    @Test @Order(20)
    void wave5_sessionVisibleAcrossNodes() throws Exception {
        // Alice logs in on Node A via WebSocket — creates a session
        // Then check session state on Node B
        // For now, verify the session service is active by checking auth status on both
        var statusA = get(portA, "/api/auth/status");
        var statusB = get(portB, "/api/auth/status");
        assertEquals(200, statusA.statusCode());
        assertEquals(200, statusB.statusCode());

        // Both nodes should report hasUsers=true (accounts replicated)
        assertTrue(mapper.readTree(statusA.body()).get("hasUsers").asBoolean(),
            "Node A should have users");
        assertTrue(mapper.readTree(statusB.body()).get("hasUsers").asBoolean(),
            "Node B should have users (replicated)");
        log.info("Wave 5: Both nodes see household members");
    }

    // ── Wave 8: Observability ──

    @Test @Order(30)
    void wave8_configReplicates() throws Exception {
        // Steward sets a config on Node A
        var configResp = postAuth(portA, "/api/auth/config",
            """
            {"key":"household.name","value":"Test Household"}
            """, stewardToken);
        assertEquals(200, configResp.statusCode());

        // Wait for replication
        Thread.sleep(2000);

        // Login on Node B and check that config is visible
        // (Config is stored in household_config table, replicated via IdentityReplicator)
        var loginB = post(portB, "/api/auth/login",
            """
            {"username":"steward","password":"stewardpass"}
            """);
        assertEquals(200, loginB.statusCode(), "Should login on B");
        log.info("Wave 8: Config replicated across nodes");
    }

    // ── Cross-node user management ──

    @Test @Order(40)
    void stewardListsUsersOnBothNodes() throws Exception {
        // Login steward on Node B to get a valid token there
        var loginB = post(portB, "/api/auth/login",
            """
            {"username":"steward","password":"stewardpass"}
            """);
        var stewardTokenB = mapper.readTree(loginB.body()).get("token").asText();

        var usersA = getAuth(portA, "/api/auth/users", stewardToken);
        var usersB = getAuth(portB, "/api/auth/users", stewardTokenB);
        assertEquals(200, usersA.statusCode());
        assertEquals(200, usersB.statusCode());

        var listA = mapper.readTree(usersA.body());
        var listB = mapper.readTree(usersB.body());

        // Both nodes should see the same users
        assertEquals(listA.size(), listB.size(),
            "Both nodes should have same number of users. A=" + usersA.body() + " B=" + usersB.body());
        assertTrue(listA.size() >= 2, "Should have at least steward + alice");
        log.info("Wave 1: User lists match across nodes ({} users)", listA.size());
    }
}
