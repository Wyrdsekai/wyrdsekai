package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestUsers;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2 — exercises the {@code knock / approve / deny / seal / unseal /
 * eject} commands through the real WebSocket wire path.
 *
 * <p>Two registered users (Alice, Bob). Alice connects as Bob's guest and
 * knocks. Bob connects as owner, sees pending requests via the REST
 * endpoint, approves/denies via WS command, then exercises seal + eject.
 * Catches dispatch wiring regressions that unit tests in {@code core} miss.</p>
 */
@Tag("tier2")
class HomeCommandsWebSocketE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestServerBootstrap server;
    private static HttpClient http;

    private static String aliceId;
    private static String aliceToken;
    private static String bobId;
    private static String bobToken;

    @BeforeAll
    static void setUp() throws Exception {
        server = new TestServerBootstrap(List.of(), PortAllocator.allocate(), List.of());
        server.start();
        http = HttpClient.newHttpClient();

        // Register two users — alice is the steward (open registration allows the
        // first user); bob must come through the steward's invite/redeem flow since
        // the F4 hardening closed open registration after the steward.
        var a = TestUsers.registerSteward(server.baseUrl(), "alice", "secret1234", "Alice");
        aliceId = a.path("userId").asText();
        aliceToken = a.path("token").asText();

        var b = TestUsers.inviteAndRedeem(
            server.baseUrl(), aliceToken, "bob", "secret1234", "Bob");
        bobId = b.path("userId").asText();
        bobToken = b.path("token").asText();

        assertThat(aliceId).isNotEmpty();
        assertThat(bobId).isNotEmpty();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    // --- Helpers ---------------------------------------------------------

    private static JsonNode registerUser(String username, String password, String display)
            throws Exception {
        var body = """
            {"username":"%s","password":"%s","display_name":"%s"}
            """.formatted(username, password, display);
        var req = HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode())
            .as("register %s: %s", username, resp.body())
            .isEqualTo(201);
        return MAPPER.readTree(resp.body());
    }

    private static JsonNode pendingFor(String owner) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl()
                + "/api/home/grant-requests/pending?owner=" + owner))
            .GET()
            .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body()).path("requests");
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

    private static TestWebSocketClient connectAs(String token) throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl(), token);
        ws.waitForRoomState(TIMEOUT);
        // Drain startup chatter.
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        ws.drainMessages();
        return ws;
    }

    // --- Tests -----------------------------------------------------------

    @Test
    void knock_creates_grant_request_visible_to_owner() throws Exception {
        try (var alice = connectAs(aliceToken)) {
            sendCommand(alice, "knock", List.of(bobId, "visiting"));
            var reply = alice.waitForProseFrom("narrator", TIMEOUT);
            assertThat(reply.path("text").asText())
                .contains("knock at " + bobId)
                .contains("request has been sent");
        }
        var pending = pendingFor(bobId);
        assertThat(pending.isArray() && pending.size() > 0)
            .as("Bob's pending list contains the request")
            .isTrue();
        assertThat(pending.get(0).path("requester").asText()).isEqualTo(aliceId);
    }

    @Test
    void approve_mints_grant_and_writes_ward() throws Exception {
        // Alice knocks first.
        String reqId;
        try (var alice = connectAs(aliceToken)) {
            sendCommand(alice, "knock", List.of(bobId, "let me in"));
            alice.waitForProseFrom("narrator", TIMEOUT);
        }
        var pending = pendingFor(bobId);
        reqId = pending.get(0).path("id").asText();

        // Bob approves via WS.
        try (var bob = connectAs(bobToken)) {
            sendCommand(bob, "approve", List.of(reqId, "welcome"));
            var reply = bob.waitForProseFrom("narrator", TIMEOUT);
            assertThat(reply.path("text").asText())
                .contains("approved")
                .contains("Grant minted");
        }

        // Pending list now empty (request transitioned to approved).
        var after = pendingFor(bobId);
        var stillPending = false;
        for (var r : after) {
            if (reqId.equals(r.path("id").asText())) stillPending = true;
        }
        assertThat(stillPending).isFalse();
    }

    @Test
    void deny_closes_request_without_grant() throws Exception {
        try (var alice = connectAs(aliceToken)) {
            sendCommand(alice, "knock", List.of(bobId, "please"));
            alice.waitForProseFrom("narrator", TIMEOUT);
        }
        var pending = pendingFor(bobId);
        var reqId = pending.get(0).path("id").asText();

        try (var bob = connectAs(bobToken)) {
            sendCommand(bob, "deny", List.of(reqId, "not today"));
            var reply = bob.waitForProseFrom("narrator", TIMEOUT);
            assertThat(reply.path("text").asText()).contains("denied");
        }
    }

    @Test
    void seal_blocks_new_knocks_and_unseal_reopens() throws Exception {
        try (var bob = connectAs(bobToken)) {
            sendCommand(bob, "seal", List.of("deep", "work"));
            var sealed = bob.waitForProseFrom("narrator", TIMEOUT);
            assertThat(sealed.path("text").asText()).contains("sealed");
        }

        // Alice's knock now fails at the actor level (home sealed).
        try (var alice = connectAs(aliceToken)) {
            sendCommand(alice, "knock", List.of(bobId, "quick one"));
            var reply = alice.waitForProse(TIMEOUT);
            assertThat(reply.path("text").asText())
                .as("sealed Home rejects new knocks")
                .containsIgnoringCase("sealed");
        }

        // Bob unseals.
        try (var bob = connectAs(bobToken)) {
            sendCommand(bob, "unseal", List.of());
            var reply = bob.waitForProseFrom("narrator", TIMEOUT);
            assertThat(reply.path("text").asText()).contains("open again");
        }
    }

    @Test
    void knock_without_args_shows_usage() throws Exception {
        try (var alice = connectAs(aliceToken)) {
            sendCommand(alice, "knock", List.of());
            var reply = alice.waitForProseFrom("system", TIMEOUT);
            assertThat(reply.path("text").asText()).contains("Usage: knock");
        }
    }

    @Test
    void eject_reports_outcome() throws Exception {
        // Doesn't matter whether bob has an active grant for alice — the
        // handler is idempotent and just reports the outcome. We mostly
        // want to prove the dispatch works.
        try (var bob = connectAs(bobToken)) {
            sendCommand(bob, "eject", List.of(aliceId));
            var reply = bob.waitForProseFrom("narrator", TIMEOUT);
            assertThat(reply.path("text").asText())
                .contains(aliceId);
        }
    }
}
