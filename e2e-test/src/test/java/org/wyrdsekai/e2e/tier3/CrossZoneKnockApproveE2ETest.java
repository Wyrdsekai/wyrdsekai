package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.core.home.FederatedHomeProxy;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.core.home.HomeProxy;
import org.wyrdsekai.core.home.ZoneDirectory;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 3 — the real end-to-end cross-zone UX. Two {@link TestServerBootstrap}
 * instances (alpha + beta), each with their own HomeRegistry + HomeClient.
 * Alice (on alpha) connects by WebSocket and knocks on Bob (a DID hosted on
 * beta). The knock command dispatches through {@code handleKnock} →
 * {@link FederatedHomeProxy} → HTTP POST to beta's {@code /api/home/grant-requests}.
 * Bob (on beta) connects by WS and approves via the {@code approve} command.
 * Alice's held-grants list now contains a grant issued on beta.
 *
 * <p>Catches any drift between the knock/approve WS dispatch, the REST
 * endpoint shape, and the HomeRegistry grant lifecycle across two JVM-local
 * zones. Pairs with the Tier 2 {@code FederatedHomeProxyE2ETest} (which
 * tests the proxy without WS) and Tier 2 {@code HomeCommandsWebSocketE2ETest}
 * (which tests the WS dispatch on a single zone).</p>
 */
@Tag("tier3")
class CrossZoneKnockApproveE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestServerBootstrap alpha;
    private static TestServerBootstrap beta;
    private static HttpClient http;

    private static String aliceId;
    private static String aliceToken;
    private static String bobId;
    private static String bobToken;

    @BeforeAll
    static void setUp() throws Exception {
        alpha = new TestServerBootstrap(List.of(), PortAllocator.allocate(), List.of());
        beta = new TestServerBootstrap(List.of(), PortAllocator.allocate(), List.of());
        alpha.start();
        beta.start();
        http = HttpClient.newHttpClient();

        // Register alice on alpha, bob on beta.
        var a = registerUser(alpha.baseUrl(), "alice", "secret1234", "Alice");
        aliceId = a.path("userId").asText();
        aliceToken = a.path("token").asText();
        var b = registerUser(beta.baseUrl(), "bob", "secret1234", "Bob");
        bobId = b.path("userId").asText();
        bobToken = b.path("token").asText();

        // Install a FederatedHomeProxy rooted on alpha's client that routes
        // DIDs recognised as being on beta through HTTP. Bob's DID (his user
        // id) is explicitly mapped to the beta zone's base URL.
        var directory = new ZoneDirectory.StaticZoneDirectory("alpha")
            .mapDid(bobId, "beta")
            .mapZoneHttp("beta", beta.baseUrl());
        var proxy = new FederatedHomeProxy(
            new HomeProxy.Local(alpha.homeClient(), "alpha"), "alpha", directory);
        HomeProxy.Holder.set(proxy);
        HomeClients.set(alpha.homeClient());
    }

    @AfterAll
    static void tearDown() {
        if (alpha != null) alpha.stop();
        if (beta != null) beta.stop();
    }

    // --- Test ------------------------------------------------------------

    @Test
    void alice_knocks_bob_across_zones_and_bob_approves() throws Exception {
        // 1. Alice (alpha) knocks on Bob (beta) — proxy routes via HTTP.
        try (var aliceWs = connectAs(alpha.baseUrl(), aliceToken)) {
            sendCommand(aliceWs, "knock", List.of(bobId, "visiting"));
            var reply = aliceWs.waitForProseFrom("narrator", TIMEOUT);
            var text = reply.path("text").asText();
            assertThat(text)
                .as("knock narration mentions cross-zone")
                .contains("knock at " + bobId)
                .contains("request has been sent");
            assertThat(text)
                .as("narrator mentions remote zone")
                .contains("(zone beta)");
        }

        // 2. Request landed on beta's registry.
        var betaClient = beta.homeClient();
        var pending = betaClient.pendingForOwner(bobId);
        assertThat(pending).hasSize(1);
        var requestId = pending.get(0).id();
        assertThat(pending.get(0).requester()).isEqualTo(aliceId);

        // 3. Bob (beta) approves via WS command.
        try (var bobWs = connectAs(beta.baseUrl(), bobToken)) {
            sendCommand(bobWs, "approve", List.of(requestId, "come in"));
            var reply = bobWs.waitForProseFrom("narrator", TIMEOUT);
            assertThat(reply.path("text").asText())
                .contains("approved")
                .contains("Grant minted");
        }

        // 4. Alice's held-grants on beta's registry now contain the use-grant.
        var held = betaClient.listHeldBy(aliceId);
        assertThat(held).anySatisfy(g -> {
            assertThat(g.resource().toString())
                .isEqualTo("home://" + bobId + "/home-room");
            assertThat(g.capability()).isEqualTo(
                Capability.use);
            assertThat(g.isActive(Instant.now())).isTrue();
        });

        // 5. Beta's pending list for bob is now empty.
        assertThat(betaClient.pendingForOwner(bobId)).isEmpty();
    }

    // --- Helpers ---------------------------------------------------------

    private static JsonNode registerUser(String baseUrl, String username, String password,
                                           String display) throws Exception {
        var body = """
            {"username":"%s","password":"%s","display_name":"%s"}
            """.formatted(username, password, display);
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode())
            .as("register %s: %s", username, resp.body())
            .isEqualTo(201);
        return MAPPER.readTree(resp.body());
    }

    private static void sendCommand(TestWebSocketClient ws, String command, List<String> args) {
        var argsJson = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) argsJson.append(",");
            argsJson.append("\"").append(args.get(i).replace("\"", "\\\"")).append("\"");
        }
        argsJson.append("]");
        ws.send("""
            {"type":"command","id":"%s","command":"%s","args":%s}
            """.formatted(UUID.randomUUID(), command, argsJson));
    }

    private static TestWebSocketClient connectAs(String baseUrl, String token) throws Exception {
        var ws = TestWebSocketClient.connect(baseUrl, token);
        ws.waitForRoomState(TIMEOUT);
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        ws.drainMessages();
        return ws;
    }

}
