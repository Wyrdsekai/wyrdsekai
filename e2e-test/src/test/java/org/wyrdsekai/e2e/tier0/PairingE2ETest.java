package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 E2E tests for the device pairing flow.
 * Uses a real Wyrdsekai server (TestServerBootstrap) with in-memory database.
 * Tests the full HTTP pairing lifecycle: request, verify, status, revoke.
 */
@Tag("integration")
class PairingE2ETest {

    private static TestServerBootstrap server;
    private static HttpClient http;
    private static ObjectMapper mapper;

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

    // ── Helpers ──

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

    private HttpResponse<String> delete(String path) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .DELETE()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBearer(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Register or login as steward to get a session token for steward-gated endpoints.
     */
    private String getStewardToken() throws Exception {
        // Try to register first (will succeed if no users yet)
        var regResp = post("/api/auth/register",
            """
            {"username":"pair_steward","password":"pass1234","display_name":"Pair Steward"}
            """);
        if (regResp.statusCode() == 201) {
            return mapper.readTree(regResp.body()).get("token").asText();
        }
        // Already registered — login
        var loginResp = post("/api/auth/login",
            """
            {"username":"pair_steward","password":"pass1234"}
            """);
        return mapper.readTree(loginResp.body()).get("token").asText();
    }

    // ── Tests ──

    /**
     * Full pairing flow: request code, get code, verify code, check status.
     */
    @Test
    void full_pairing_flow() throws Exception {
        // Step 1: Request pairing
        var reqResp = post("/api/pair/request",
            """
            {"deviceName":"Test Phone","deviceType":"phone","devicePublicKey":"pk-test"}
            """);
        assertEquals(201, reqResp.statusCode(), "Pair request should return 201");
        var reqBody = mapper.readTree(reqResp.body());
        var challengeId = reqBody.get("challengeId").asText();
        assertNotNull(challengeId);

        // Step 2: Get the pending code (steward reads it)
        var codeResp = get("/api/pair/code");
        assertEquals(200, codeResp.statusCode(), "Should have a pending code");
        var codeBody = mapper.readTree(codeResp.body());
        var code = codeBody.get("code").asText();
        assertNotNull(code);
        assertEquals(6, code.length(), "Code should be 6 digits");

        // Step 3: Verify the code
        var verifyResp = post("/api/pair/verify",
            "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}");
        assertEquals(200, verifyResp.statusCode(), "Verify should succeed");
        var verifyBody = mapper.readTree(verifyResp.body());
        var token = verifyBody.get("token").asText();
        assertTrue(token.startsWith("wyrd_dev_"), "Token should start with wyrd_dev_");
        assertEquals("test-household", verifyBody.get("householdId").asText());

        // Step 4: Check status with the token
        var statusResp = getWithBearer("/api/pair/status", token);
        assertEquals(200, statusResp.statusCode(), "Status should return 200 for valid token");
        var statusBody = mapper.readTree(statusResp.body());
        assertEquals("Test Phone", statusBody.get("name").asText());
    }

    /**
     * Wrong code is rejected with 403.
     */
    @Test
    void wrong_code_rejected() throws Exception {
        var reqResp = post("/api/pair/request",
            """
            {"deviceName":"Wrong Phone","deviceType":"phone"}
            """);
        var reqBody = mapper.readTree(reqResp.body());
        var challengeId = reqBody.get("challengeId").asText();

        // Submit a wrong code
        var verifyResp = post("/api/pair/verify",
            "{\"challengeId\":\"" + challengeId + "\",\"code\":\"000000\"}");
        assertEquals(403, verifyResp.statusCode(), "Wrong code should return 403");
    }

    /**
     * Household key pairing flow: generate key, pair with key, check status.
     */
    @Test
    void household_key_pairing() throws Exception {
        // Step 1: Generate a household key
        var genResp = post("/api/pair/household-key/generate", "");
        assertEquals(201, genResp.statusCode(), "Key generation should return 201");
        var genBody = mapper.readTree(genResp.body());
        var key = genBody.get("key").asText();
        assertTrue(key.startsWith("wyrd_hk_"), "Key should start with wyrd_hk_");

        // Step 2: Pair with the household key
        var pairResp = post("/api/pair/key",
            "{\"key\":\"" + key + "\",\"deviceName\":\"Headless Node\",\"deviceType\":\"server\"}");
        assertEquals(200, pairResp.statusCode(), "Key pairing should succeed");
        var pairBody = mapper.readTree(pairResp.body());
        var token = pairBody.get("token").asText();
        assertTrue(token.startsWith("wyrd_dev_"), "Token should start with wyrd_dev_");

        // Step 3: Check status
        var statusResp = getWithBearer("/api/pair/status", token);
        assertEquals(200, statusResp.statusCode(), "Status should return 200");
    }

    /**
     * Revoked device is rejected on status check (401).
     */
    @Test
    void revoked_device_rejected() throws Exception {
        // Need steward auth for device list and revoke endpoints
        var stewardToken = getStewardToken();

        // Pair a device
        var reqResp = post("/api/pair/request",
            """
            {"deviceName":"Revoke Target","deviceType":"phone"}
            """);
        var challengeId = mapper.readTree(reqResp.body()).get("challengeId").asText();

        var codeResp = get("/api/pair/code");
        var code = mapper.readTree(codeResp.body()).get("code").asText();

        var verifyResp = post("/api/pair/verify",
            "{\"challengeId\":\"" + challengeId + "\",\"code\":\"" + code + "\"}");
        var token = mapper.readTree(verifyResp.body()).get("token").asText();

        // Confirm it works
        assertEquals(200, getWithBearer("/api/pair/status", token).statusCode());

        // Get device ID from device list (steward-gated)
        var devicesResp = getWithBearer("/api/pair/devices", stewardToken);
        assertEquals(200, devicesResp.statusCode(), "Steward should be able to list devices");
        var devices = mapper.readTree(devicesResp.body());
        // Find the device named "Revoke Target"
        String deviceId = null;
        for (var d : devices) {
            if ("Revoke Target".equals(d.get("name").asText())) {
                deviceId = d.get("id").asText();
                break;
            }
        }
        assertNotNull(deviceId, "Should find the paired device in the list");

        // Revoke the device (steward-gated)
        var revokeResp = deleteWithBearer("/api/pair/devices/" + deviceId, stewardToken);
        assertEquals(200, revokeResp.statusCode());

        // Status check should now fail
        var statusResp = getWithBearer("/api/pair/status", token);
        assertEquals(401, statusResp.statusCode(), "Revoked device should get 401");
    }
}
