package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 E2E tests for Wave 1: Household Security.
 * Tests invite-only registration, invite create/redeem/revoke, and config management.
 *
 * Uses its own server instance with open registration NOT pre-enabled,
 * so the default invite-only behavior is tested.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HouseholdSecurityE2ETest {

    private static TestServerBootstrap server;
    private static HttpClient http;
    private static ObjectMapper mapper;
    private static String stewardToken;
    private static String inviteCode;
    private static String inviteId;

    @BeforeAll
    static void setUp() throws Exception {
        server = new TestServerBootstrap(List.of(),
            PortAllocator.allocate(),
            List.of(), false /* invite-only */);
        server.start();
        http = HttpClient.newHttpClient();
        mapper = new ObjectMapper();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    private String baseUrl() { return server.baseUrl(); }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithBearer(String path, String body, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBearer(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── Tests ──

    @Test @Order(1)
    void firstUserBecomeSteward() throws Exception {
        // First user can always register (even with invite-only)
        var resp = post("/api/auth/register",
            """
            {"username":"steward1","password":"stewardpass","display_name":"The Steward"}
            """);
        assertEquals(201, resp.statusCode(), "First user should register: " + resp.body());
        var auth = mapper.readTree(resp.body());
        assertEquals("steward", auth.get("role").asText());
        stewardToken = auth.get("token").asText();
    }

    @Test @Order(2)
    void secondUserBlockedWithoutInvite() throws Exception {
        // After steward exists, open registration is not allowed (no config set)
        var resp = post("/api/auth/register",
            """
            {"username":"intruder","password":"password123","display_name":"Intruder"}
            """);
        assertEquals(403, resp.statusCode(), "Should be blocked: " + resp.body());
        assertTrue(resp.body().contains("invitation"), "Error should mention invitation");
    }

    @Test @Order(3)
    void stewardCreatesInvite() throws Exception {
        var resp = postWithBearer("/api/auth/invite",
            """
            {"name":"Alice","role":"member","expiryHours":24}
            """, stewardToken);
        assertEquals(201, resp.statusCode(), "Invite creation should succeed: " + resp.body());
        var invite = mapper.readTree(resp.body());
        inviteCode = invite.get("code").asText();
        inviteId = invite.get("id").asText();
        assertNotNull(inviteCode);
        assertEquals("Alice", invite.get("name").asText());
        assertEquals("member", invite.get("role").asText());
        // Code should be 6 words
        assertEquals(6, inviteCode.split("\\s+").length, "Invite code should be 6 words");
    }

    @Test @Order(4)
    void stewardListsInvites() throws Exception {
        var resp = getWithBearer("/api/auth/invites", stewardToken);
        assertEquals(200, resp.statusCode());
        var invites = mapper.readTree(resp.body());
        assertTrue(invites.isArray());
        assertTrue(invites.size() >= 1);
        var first = invites.get(0);
        assertEquals(inviteCode, first.get("code").asText());
        assertFalse(first.get("consumed").asBoolean());
    }

    @Test @Order(5)
    void memberRedeemsInvite() throws Exception {
        var resp = post("/api/auth/redeem",
            """
            {"code":"%s","username":"alice","password":"alicepass","displayName":"Alice"}
            """.formatted(inviteCode));
        assertEquals(201, resp.statusCode(), "Redeem should succeed: " + resp.body());
        var auth = mapper.readTree(resp.body());
        assertEquals("alice", auth.get("username").asText());
        assertEquals("member", auth.get("role").asText());
        assertNotNull(auth.get("token").asText());
    }

    @Test @Order(6)
    void inviteCodeCannotBeReused() throws Exception {
        var resp = post("/api/auth/redeem",
            """
            {"code":"%s","username":"bob","password":"bobpass","displayName":"Bob"}
            """.formatted(inviteCode));
        assertEquals(404, resp.statusCode(), "Reused code should fail: " + resp.body());
    }

    @Test @Order(7)
    void invalidInviteCodeFails() throws Exception {
        var resp = post("/api/auth/redeem",
            """
            {"code":"bogus code that does not exist at all","username":"eve","password":"evepass"}
            """);
        assertEquals(404, resp.statusCode(), "Invalid code should fail: " + resp.body());
    }

    @Test @Order(8)
    void stewardCreatesAndRevokesInvite() throws Exception {
        // Create
        var createResp = postWithBearer("/api/auth/invite",
            """
            {"name":"Bob","role":"guest"}
            """, stewardToken);
        assertEquals(201, createResp.statusCode());
        var bobInviteId = mapper.readTree(createResp.body()).get("id").asText();

        // Revoke
        var revokeResp = deleteWithBearer("/api/auth/invite/" + bobInviteId, stewardToken);
        assertEquals(200, revokeResp.statusCode());

        // Verify it's gone from pending
        var listResp = getWithBearer("/api/auth/invites", stewardToken);
        var invites = mapper.readTree(listResp.body());
        for (var inv : invites) {
            if (inv.get("id").asText().equals(bobInviteId)) {
                fail("Revoked invite should not appear in list");
            }
        }
    }

    @Test @Order(9)
    void memberCannotCreateInvite() throws Exception {
        // Login as alice (member)
        var loginResp = post("/api/auth/login",
            """
            {"username":"alice","password":"alicepass"}
            """);
        assertEquals(200, loginResp.statusCode());
        var aliceToken = mapper.readTree(loginResp.body()).get("token").asText();

        var resp = postWithBearer("/api/auth/invite",
            """
            {"name":"Charlie","role":"member"}
            """, aliceToken);
        assertEquals(403, resp.statusCode(), "Member should not create invites: " + resp.body());
    }

    @Test @Order(10)
    void stewardCanSetConfig() throws Exception {
        // F4: the household-config table still exists and the steward can
        // set values, but the {@code open_registration} key is no longer
        // honored — once federated, every open door multiplies attack
        // surface. AuthService.isOpenRegistrationAllowed() returns
        // isFirstUser() unconditionally; CONFIG_OPEN_REGISTRATION is dead.
        // This test now verifies (a) steward can write any config key, and
        // (b) post-first-steward, /api/auth/register stays 403 even after
        // setting open_registration=true (regression guard against
        // re-introducing the steward-toggle door).
        var resp = postWithBearer("/api/auth/config",
            """
            {"key":"open_registration","value":"true"}
            """, stewardToken);
        assertEquals(200, resp.statusCode(), "Steward should be able to set config");

        // Setting open_registration=true must NOT re-open registration.
        var regResp = post("/api/auth/register",
            """
            {"username":"openreg_user","password":"password123"}
            """);
        assertEquals(403, regResp.statusCode(),
            "open_registration config knob is dead post-F4 — register must stay invite-only: "
                + regResp.body());
    }

    @Test @Order(11)
    void stewardCanRemoveMember() throws Exception {
        // Create a throwaway member via invite
        var invResp = postWithBearer("/api/auth/invite",
            """
            {"name":"Throwaway","role":"member"}
            """, stewardToken);
        var throwawayCode = mapper.readTree(invResp.body()).get("code").asText();

        var redeemResp = post("/api/auth/redeem",
            """
            {"code":"%s","username":"throwaway","password":"throwpass"}
            """.formatted(throwawayCode));
        assertEquals(201, redeemResp.statusCode());
        var throwawayId = mapper.readTree(redeemResp.body()).get("userId").asText();

        // Remove
        var removeResp = postWithBearer("/api/auth/remove-user",
            """
            {"userId":"%s"}
            """.formatted(throwawayId), stewardToken);
        assertEquals(200, removeResp.statusCode());

        // Verify removed — login should fail
        var loginResp = post("/api/auth/login",
            """
            {"username":"throwaway","password":"throwpass"}
            """);
        assertEquals(401, loginResp.statusCode(), "Removed user should not login");
    }
}
