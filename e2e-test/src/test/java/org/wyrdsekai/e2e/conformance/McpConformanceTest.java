package org.wyrdsekai.e2e.conformance;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REST `/api/mcp/*` conformance arm — the surface used by the phone clients
 * (RN + KMP, see {@code clients/rn/src/server/ServerClient.ts} and
 * {@code clients/kmp/.../ServerClient.kt}). Both clients POST free-text
 * commands to {@code /api/mcp/do}; the server-side router must resolve
 * examine / rename / whisper via the same shared services the line
 * transports use.
 *
 * <p>SPEC: (cross-transport invariance). MCP REST
 * is the fifth transport; this arm catches regressions like "examine X
 * from phone falls through to say".</p>
 */
@Tag("conformance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static String baseUrl;
    private static String token;
    private static String USER = "mcpconf";
    private static String PASS = "mcpconfpw";
    private static boolean liveMode = false;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() throws Exception {
        var override = System.getenv("WYRDSEKAI_MCP_CONFORMANCE_URL");
        if (override != null && !override.isBlank()) {
            baseUrl = override;
            var envUser = System.getenv("WYRDSEKAI_MCP_CONFORMANCE_USER");
            var envPass = System.getenv("WYRDSEKAI_MCP_CONFORMANCE_PASS");
            if (envUser != null && !envUser.isBlank()) USER = envUser;
            if (envPass != null && !envPass.isBlank()) PASS = envPass;
            liveMode = true;
        } else {
            wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
            wireMock.start();
            wireMock.stubChatCompletion("Acknowledged.", 30, 20);
            server = new TestServerBootstrap(List.of(
                new InferenceBackend.LlamaServer("wiremock",
                    new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
            server.start();
            baseUrl = server.baseUrl();
            // Bootstrap test user via REST register (same path the real phone uses).
            http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + USER + "\",\"password\":\"" + PASS
                        + "\",\"displayName\":\"MCP Conf\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        }
        // MCP login to obtain a token for /api/mcp/do.
        var login = http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/mcp/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + USER + "\",\"password\":\"" + PASS + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(),
            "MCP login must succeed for conformance tests. Got: " + login.body());
        token = extractJsonString(login.body(), "token");
        assertNotNull(token, "MCP login must return a token. Got: " + login.body());
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // -----------------------------------------------------------------
    // §2.2 — examine via /api/mcp/do uses shared ExamineLookup
    // -----------------------------------------------------------------

    @Test @Order(101)
    void mcp_examine_returns_object_description() throws Exception {
        var resp = doCommand("examine cost ledger");
        var msg = extractJsonString(resp, "message");
        assertNotNull(msg, "§2.2 MCP arm: do{examine cost ledger} must return prose. Got: " + resp);
        assertTrue(msg.toLowerCase().contains("ledger"),
            "§2.2: MCP examine must surface object description. Got: " + msg);
        assertFalse(msg.toLowerCase().contains("you use"),
            "§2.2: MCP examine must not trigger 'you use' fallback. Got: " + msg);
    }

    @Test @Order(102)
    void mcp_examine_unknown_returns_not_found() throws Exception {
        var resp = doCommand("examine zzzbobcatfloop");
        var msg = extractJsonString(resp, "message");
        assertNotNull(msg);
        assertTrue(msg.toLowerCase().contains("nothing called"),
            "§2.2: MCP examine of unknown target must return 'nothing called X here'. Got: " + msg);
    }

    @Test @Order(103)
    void mcp_look_at_resolves_like_examine() throws Exception {
        var resp = doCommand("look at cost ledger");
        var msg = extractJsonString(resp, "message");
        assertNotNull(msg);
        assertTrue(msg.toLowerCase().contains("ledger"),
            "§2.2: MCP 'look at X' must resolve like examine. Got: " + msg);
    }

    // -----------------------------------------------------------------
    // §7.4 — rename me via /api/mcp/do hits shared RenameService
    // -----------------------------------------------------------------

    @Test @Order(201)
    void mcp_rename_me_echoes_new_name() throws Exception {
        var newName = "McpRenamed" + (System.nanoTime() % 100000);
        var resp = doCommand("rename me " + newName);
        var msg = extractJsonString(resp, "message");
        assertNotNull(msg);
        assertTrue(msg.toLowerCase().contains("now known as"),
            "§7.4: MCP 'rename me X' must echo 'now known as'. Got: " + msg);
        assertTrue(msg.contains(newName),
            "§7.4: MCP rename echo must include the chosen name. Got: " + msg);
        // Restore.
        restoreUser();
    }

    @Test @Order(202)
    void mcp_rename_invalid_name_rejected() throws Exception {
        var resp = doCommand("rename me ");
        var ok = extractJsonString(resp, "ok");
        // Either "ok":false with an error, or message contains "Usage:"
        var msg = extractJsonString(resp, "message");
        var err = extractJsonString(resp, "error");
        assertTrue((err != null && !err.isBlank())
            || (msg != null && msg.toLowerCase().contains("usage"))
            || "false".equals(ok),
            "§7.4: MCP rename with empty name must be rejected. Got: " + resp);
    }

    // -----------------------------------------------------------------
    // §6.1 — say still works (regression-lock the existing path)
    // -----------------------------------------------------------------

    @Test @Order(301)
    void mcp_say_still_works_after_examine_branch_added() throws Exception {
        var marker = "mcp-say-" + System.nanoTime();
        var resp = doCommand("say " + marker);
        // /api/mcp/do say returns "ok" with no specific message — assert the call succeeded.
        var ok = extractJsonString(resp, "ok");
        assertEquals("true", ok,
            "§6.1: MCP say must still succeed after adding examine/rename branches. " +
            "Got: " + resp);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static String doCommand(String command) throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/mcp/do"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"command\":\"" + command.replace("\"", "\\\"") + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private static void restoreUser() throws Exception {
        // Use the test-reset hook so we don't recursively depend on rename.
        http.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/auth/test-reset"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + USER + "\",\"displayName\":\"" + USER + "\","
                + "\"description\":\"\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extractJsonString(String json, String field) {
        var key = "\"" + field + "\":";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        // Skip whitespace, optional quote.
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start < json.length() && json.charAt(start) == '"') {
            int s = start + 1;
            int end = s;
            var sb = new StringBuilder();
            while (end < json.length() && json.charAt(end) != '"') {
                if (json.charAt(end) == '\\' && end + 1 < json.length()) {
                    char esc = json.charAt(end + 1);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(esc);
                    }
                    end += 2;
                } else {
                    sb.append(json.charAt(end));
                    end++;
                }
            }
            return sb.toString();
        }
        // Bare value (true/false/number).
        int end = start;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end).trim();
    }
}
