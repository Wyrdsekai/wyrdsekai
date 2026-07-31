package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 E2E tests for the user accounts system.
 * Covers AuthRoutes (register, login, status, adduser, users, link-device)
 * and the WebSocket identity resolution path via PairingService device tokens.
 *
 * <p>Uses a real Wyrdsekai server (TestServerBootstrap) with in-memory database.
 * Tests are ordered to control the first-user-is-steward invariant: the first
 * test registers the steward, subsequent tests use it.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserAccountsE2ETest {

    private static TestServerBootstrap server;
    private static HttpClient http;
    private static ObjectMapper mapper;

    /** Session token for the steward user, set by the first test. */
    private static String stewardToken;
    private static String stewardUserId;

    @BeforeAll
    static void setUp() throws Exception {
        server = new TestServerBootstrap(List.of());
        server.start();
        http = HttpClient.newHttpClient();
        mapper = new ObjectMapper();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    // ── HTTP Helpers ──

    private String baseUrl() {
        return server.baseUrl();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithBearer(String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Pair a device through the full pairing flow and return the device token.
     */
    private String pairDevice(String deviceName) throws Exception {
        var reqResp = post("/api/pair/request",
            "{\"deviceName\":\"" + deviceName + "\",\"deviceType\":\"phone\"}");
        assertEquals(201, reqResp.statusCode());
        var challengeId = mapper.readTree(reqResp.body()).get("challengeId").asText();

        var codeResp = get("/api/pair/code");
        assertEquals(200, codeResp.statusCode());
        var code = mapper.readTree(codeResp.body()).get("code").asText();

        var verifyResp = post("/api/pair/verify",
            "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}");
        assertEquals(200, verifyResp.statusCode());
        return mapper.readTree(verifyResp.body()).get("token").asText();
    }

    // ── Tests (ordered) ──

    @Test @Order(1)
    void statusShowsNoUsersOnFreshServer() throws Exception {
        var resp = get("/api/auth/status");
        assertEquals(200, resp.statusCode());
        var status = mapper.readTree(resp.body());
        assertFalse(status.get("hasUsers").asBoolean(),
            "Fresh server should have no users");
        assertTrue(status.get("openRegistration").asBoolean(),
            "Fresh server should allow open registration");
    }

    @Test @Order(2)
    void firstUserBecomesSteward() throws Exception {
        var resp = post("/api/auth/register",
            """
            {"username":"steward","password":"pass1234","display_name":"The Steward"}
            """);
        assertEquals(201, resp.statusCode(),
            "First registration should return 201: " + resp.body());
        var auth = mapper.readTree(resp.body());
        assertEquals("steward", auth.get("role").asText(),
            "First registered user should have role 'steward'");
        assertNotNull(auth.get("token").asText());
        assertNotNull(auth.get("userId").asText());

        // Store for subsequent tests
        stewardToken = auth.get("token").asText();
        stewardUserId = auth.get("userId").asText();
    }

    @Test @Order(3)
    void statusShowsUsersAfterRegistration() throws Exception {
        var resp = get("/api/auth/status");
        assertEquals(200, resp.statusCode());
        var status = mapper.readTree(resp.body());
        assertTrue(status.get("hasUsers").asBoolean(),
            "After registration, hasUsers should be true");
        // F4: openRegistration is true iff no users yet (isFirstUser()). The
        // steward-toggle "open_registration" config knob was removed because
        // once zones federate, every open door multiplies the attack
        // surface. Post-first-user, all subsequent accounts must come
        // through invite-redeem. See AuthService.isOpenRegistrationAllowed().
        assertFalse(status.get("openRegistration").asBoolean(),
            "After first user, household closes — invites only");
    }

    @Test @Order(4)
    void secondUserIsMember() throws Exception {
        // F4: post-first-steward, /api/auth/register is gated. Second user
        // must come through steward-issued invite redemption.
        var inviteResp = postWithBearer("/api/auth/invite",
            """
            {"name":"member1-invite","role":"member","expiryHours":24}
            """, stewardToken);
        assertEquals(201, inviteResp.statusCode(),
            "Steward should create invite: " + inviteResp.body());
        var inviteCode = mapper.readTree(inviteResp.body()).get("code").asText();

        var resp = post("/api/auth/redeem",
            """
            {"code":"%s","username":"member1","password":"pass1234","displayName":"A Member"}
            """.formatted(inviteCode));
        assertEquals(201, resp.statusCode(),
            "Invite redemption should return 201: " + resp.body());
        var auth = mapper.readTree(resp.body());
        assertEquals("member", auth.get("role").asText(),
            "Invite-redeemed user should have role 'member'");
    }

    @Test @Order(5)
    void loginReturnsRole() throws Exception {
        var resp = post("/api/auth/login",
            """
            {"username":"steward","password":"pass1234"}
            """);
        assertEquals(200, resp.statusCode());
        var auth = mapper.readTree(resp.body());
        assertNotNull(auth.get("token").asText());
        assertEquals("steward", auth.get("role").asText(),
            "Login response should include role");
        assertEquals("steward", auth.get("username").asText());

        // Refresh steward token for subsequent tests
        stewardToken = auth.get("token").asText();
    }

    @Test @Order(6)
    void loginWithWrongPasswordFails() throws Exception {
        var resp = post("/api/auth/login",
            """
            {"username":"steward","password":"wrong_pass"}
            """);
        assertEquals(401, resp.statusCode(), "Wrong password should return 401");
    }

    @Test @Order(7)
    void duplicateRegistrationFails() throws Exception {
        // F4: post-first-steward, /api/auth/register is gated to invite-only
        // BEFORE the duplicate-username check fires — so trying to re-register
        // an existing username via the open path returns 403 (invite required)
        // rather than 409 (conflict). The duplicate-check still fires inside
        // /api/auth/redeem; that's the path under test now.
        var inviteResp = postWithBearer("/api/auth/invite",
            """
            {"name":"dup-attempt","role":"member","expiryHours":1}
            """, stewardToken);
        assertEquals(201, inviteResp.statusCode(),
            "Steward should create invite: " + inviteResp.body());
        var inviteCode = mapper.readTree(inviteResp.body()).get("code").asText();

        var resp = post("/api/auth/redeem",
            """
            {"code":"%s","username":"steward","password":"otherpass","displayName":"Dupe"}
            """.formatted(inviteCode));
        assertEquals(409, resp.statusCode(),
            "Redeem with duplicate username should return 409: " + resp.body());
    }

    @Test @Order(10)
    void stewardCanAddUser() throws Exception {
        assertNotNull(stewardToken, "Steward token should be set by earlier test");

        var resp = postWithBearer("/api/auth/adduser",
            """
            {"username":"added_user","password":"pass1234","displayName":"Added User","role":"member"}
            """, stewardToken);
        assertEquals(201, resp.statusCode(),
            "Steward should be able to add user, got " + resp.statusCode() + ": " + resp.body());
        var added = mapper.readTree(resp.body());
        assertEquals("added_user", added.get("username").asText());
        assertEquals("member", added.get("role").asText());
    }

    @Test @Order(11)
    void memberCannotAddUser() throws Exception {
        // Login as member1 (registered in test order 4)
        var memberLogin = post("/api/auth/login",
            """
            {"username":"member1","password":"pass1234"}
            """);
        assertEquals(200, memberLogin.statusCode());
        var memberToken = mapper.readTree(memberLogin.body()).get("token").asText();

        var resp = postWithBearer("/api/auth/adduser",
            """
            {"username":"should_fail","password":"pass1234","displayName":"Nope"}
            """, memberToken);
        assertEquals(403, resp.statusCode(),
            "Member should not be allowed to add users");
    }

    @Test @Order(12)
    void unauthenticatedCannotAddUser() throws Exception {
        var resp = post("/api/auth/adduser",
            """
            {"username":"noauth","password":"pass1234","displayName":"No Auth"}
            """);
        assertEquals(401, resp.statusCode(),
            "Unauthenticated adduser should return 401");
    }

    @Test @Order(15)
    void stewardCanListUsers() throws Exception {
        assertNotNull(stewardToken, "Steward token should be set by earlier test");

        var resp = getWithBearer("/api/auth/users", stewardToken);
        assertEquals(200, resp.statusCode(),
            "Steward should be able to list users");
        var users = mapper.readTree(resp.body());
        assertTrue(users.isArray(), "Response should be an array");
        // We registered: steward, member1, added_user (at minimum)
        assertTrue(users.size() >= 3,
            "Should have at least 3 users, got " + users.size());

        boolean foundSteward = false;
        boolean foundMember = false;
        for (var user : users) {
            if ("steward".equals(user.get("username").asText())) {
                foundSteward = true;
                assertEquals("steward", user.get("role").asText());
            }
            if ("member1".equals(user.get("username").asText())) {
                foundMember = true;
                assertEquals("member", user.get("role").asText());
            }
        }
        assertTrue(foundSteward, "Should find 'steward' in user list");
        assertTrue(foundMember, "Should find 'member1' in user list");
    }

    @Test @Order(16)
    void memberCannotListUsers() throws Exception {
        var memberLogin = post("/api/auth/login",
            """
            {"username":"member1","password":"pass1234"}
            """);
        assertEquals(200, memberLogin.statusCode());
        var memberToken = mapper.readTree(memberLogin.body()).get("token").asText();

        var resp = getWithBearer("/api/auth/users", memberToken);
        assertEquals(403, resp.statusCode(),
            "Member should not be allowed to list users");
    }

    @Test @Order(17)
    void unauthenticatedCannotListUsers() throws Exception {
        var resp = get("/api/auth/users");
        assertEquals(401, resp.statusCode(),
            "Unauthenticated list users should return 401");
    }

    @Test @Order(20)
    void linkDeviceToUser() throws Exception {
        assertNotNull(stewardToken, "Steward token should be set by earlier test");

        // Pair a device
        var deviceToken = pairDevice("Link Phone");

        // Link the device to the steward's account
        var linkResp = postWithBearer("/api/auth/link-device",
            "{\"deviceToken\":\"" + deviceToken + "\"}", stewardToken);
        assertEquals(200, linkResp.statusCode(),
            "Linking device should succeed: " + linkResp.body());
        var linkResult = mapper.readTree(linkResp.body());
        assertEquals("linked", linkResult.get("status").asText());
        assertEquals(stewardUserId, linkResult.get("userId").asText());

        // Connect via WebSocket with the linked device token
        var roomState = connectWithDeviceToken(deviceToken);
        assertNotNull(roomState, "Should receive room_state on WS connect");

        // The player entity should use the linked user's display name
        assertEntityPresent(roomState, "The Steward", stewardUserId);
    }

    @Test @Order(21)
    void unlinkedDeviceConnectsAnonymously() throws Exception {
        // Pair a device without linking to any user
        var deviceToken = pairDevice("Anon Phone");

        var roomState = connectWithDeviceToken(deviceToken);
        assertNotNull(roomState, "Should receive room_state on WS connect");

        // Check that the player shows up with a device-prefixed identity
        var entities = roomState.path("room").path("entities");
        boolean foundDeviceEntity = false;
        for (var entity : entities) {
            var entityName = entity.path("name").asText();
            var entityId = entity.path("id").asText();
            if (entityId.startsWith("device-") || "Anon Phone".equals(entityName)) {
                foundDeviceEntity = true;
                break;
            }
        }
        assertTrue(foundDeviceEntity,
            "Unlinked device should connect with device-prefixed identity. Entities: " + entities);
    }

    @Test @Order(22)
    void persistentSessionViaDeviceLink() throws Exception {
        assertNotNull(stewardToken, "Steward token should be set by earlier test");

        // Create a new user for this test to avoid confusion with steward's existing sessions
        var createResp = postWithBearer("/api/auth/adduser",
            """
            {"username":"persistuser","password":"pass1234","displayName":"Persist User","role":"member"}
            """, stewardToken);
        assertEquals(201, createResp.statusCode());
        var persistAuth = mapper.readTree(createResp.body());
        var persistToken = persistAuth.get("token").asText();
        var persistUserId = persistAuth.get("userId").asText();

        // Pair and link device to this user
        var deviceToken = pairDevice("Persist Phone");
        var linkResp = postWithBearer("/api/auth/link-device",
            "{\"deviceToken\":\"" + deviceToken + "\"}", persistToken);
        assertEquals(200, linkResp.statusCode());

        // First connection
        var firstRoomState = connectWithDeviceToken(deviceToken);
        assertNotNull(firstRoomState, "First connection should get room_state");
        assertEntityPresent(firstRoomState, "Persist User", persistUserId);

        // Small delay to avoid actor name collision
        Thread.sleep(500);

        // Second connection with same device token
        var secondRoomState = connectWithDeviceToken(deviceToken);
        assertNotNull(secondRoomState, "Second connection should get room_state");
        assertEntityPresent(secondRoomState, "Persist User", persistUserId);
    }

    // ── WebSocket Helpers ──

    /**
     * Connect to the WebSocket with a device_token query param, wait for room_state,
     * close the connection, and return the room_state JSON.
     */
    private JsonNode connectWithDeviceToken(String deviceToken) throws Exception {
        var wsUrl = baseUrl().replace("http://", "ws://")
            + "/ws?device_token=" + deviceToken;
        var client = HttpClient.newHttpClient();
        var roomStateFuture = new CompletableFuture<JsonNode>();
        var wsFuture = new CompletableFuture<WebSocket>();

        client.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                private final StringBuilder buf = new StringBuilder();

                @Override
                public void onOpen(WebSocket webSocket) {
                    wsFuture.complete(webSocket);
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                    buf.append(data);
                    if (last) {
                        try {
                            var json = mapper.readTree(buf.toString());
                            if ("room_state".equals(json.path("type").asText())) {
                                roomStateFuture.complete(json);
                            }
                        } catch (Exception ignored) {}
                        buf.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }
            })
            .get(10, TimeUnit.SECONDS);

        var roomState = roomStateFuture.get(15, TimeUnit.SECONDS);

        // Close the connection
        try {
            var ws = wsFuture.getNow(null);
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "test done");
            }
        } catch (Exception ignored) {}

        return roomState;
    }

    /**
     * Assert that an entity with the given display name or ID is present in the room_state.
     */
    private void assertEntityPresent(JsonNode roomState, String expectedName, String expectedId) {
        var entities = roomState.path("room").path("entities");
        boolean found = false;
        for (var entity : entities) {
            if (expectedName.equals(entity.path("name").asText())
                || expectedId.equals(entity.path("id").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found,
            "Expected entity '" + expectedName + "' (id=" + expectedId
            + ") in room. Entities: " + entities);
    }
}
