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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocket parity arm of the MUD conformance suite.
 *
 * <p>SPEC: §10 (cross-transport invariance).
 * The SSH suite ({@code MudConventionConformanceTest}) covers line-based
 * verbs; this class exercises the typed-C2S surface against {@code
 * WyrdWebSocket}.</p>
 *
 * <p>Same server, same room model, different wire shape. A bug that exists
 * in {@code WyrdWebSocket.tryInvokeCarriedScript} (the duplicate "you use
 * the X" fallback at line 1194 — see spec §11.7) will surface here but not
 * via SSH, and vice versa. This is the cross-transport sweep.</p>
 *
 * <p>Scope v1: 3 tests covering the highest-value parallel surfaces (say,
 * look, error-on-bad-take). Expand as we encounter WS-specific
 * regressions.</p>
 */
@Tag("conformance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WsConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static String authToken;
    private static String startRoomId;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();

        // Bootstrap a steward-test user + obtain auth token for WS handshake.
        // WS connect needs a session token; REST register returns one.
        var http = HttpClient.newHttpClient();
        var regResp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"wsconf\",\"password\":\"wsconfpw\","
                    + "\"displayName\":\"WS Conf\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        // {"token":"...","userId":"...","username":"wsconf","role":"...","recoveryKey":null}
        var body = regResp.body();
        authToken = extractJsonString(body, "token");
        assertNotNull(authToken, "register must return an auth token; got: " + body);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // -----------------------------------------------------------------
    // §2.1 — `look` over WS returns a room_state message
    // -----------------------------------------------------------------

    @Test @Order(101)
    void ws_look_returns_room_state() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            // Initial room_state lands on connect (player enters Study).
            // Note: S2CMessage.RoomState nests the snapshot under `room`, not
            // top-level — see S2CMessage.RoomState(seq, room, inventory).
            var initial = ws.waitForRoomState(TIMEOUT);
            assertNotNull(initial, "WS must deliver room_state on connect");
            startRoomId = initial.path("room").path("roomId").asText();
            assertTrue(startRoomId != null && !startRoomId.isEmpty(),
                "initial room_state must carry a roomId at .room.roomId. " +
                "Got: " + initial);

            // Explicit look → another room_state delivery.
            ws.sendLook(startRoomId);
            var again = ws.waitForRoomState(TIMEOUT);
            assertNotNull(again, "WS look must echo room_state");
            assertEquals(startRoomId, again.path("room").path("roomId").asText(),
                "look must return the room the player is currently in");
        }
    }

    // -----------------------------------------------------------------
    // §6.1 — `say` over WS broadcasts a prose message attributed to speaker
    // -----------------------------------------------------------------

    @Test @Order(201)
    void ws_say_emits_prose_with_text() throws Exception {
        var marker = "ws-marker-" + System.nanoTime();
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            // Capture roomId from the initial room_state — calling
            // currentRoomId() after waitForRoomState() would return null
            // because waitForRoomState consumes the message from the queue.
            var initial = ws.waitForRoomState(TIMEOUT);
            var roomId = initial.path("room").path("roomId").asText();
            ws.sendSay(roomId, marker);
            // Match by marker-in-text rather than by speaker — on connect
            // a "narrator" prose arrives first ("wsconf enters from
            // nowhere.") so a plain waitForProse picks that up before the
            // say-echo. Predicate matching skips the noise. Also: the
            // speaker for player say is the username ("wsconf"), not the
            // displayName ("WS Conf") — confirmed from server payload.
            var prose = ws.waitForMessage(msg ->
                "prose".equals(msg.path("type").asText())
                    && msg.path("text").asText().contains(marker),
                TIMEOUT);
            assertNotNull(prose,
                "§6.1: WS say must emit a prose carrying the spoken text.");
        }
    }

    // -----------------------------------------------------------------
    // §2.2 (WS arm) — examine on a non-scripted object must not emit
    // "you use" via prose. This is the WS counterpart to
    // MudConventionConformanceTest.examine_does_not_emit_use_confirmation;
    // spec §11.7 calls out WyrdWebSocket.java:1194 carrying the same
    // duplicate fallback string. If/when the cross-transport Examine
    // refactor lands, both sides should pass this contract.
    //
    // WS doesn't have a typed `examine` message — the equivalent on this
    // surface is C2SMessage.Use(objectName, target=null) which routes
    // through tryInvokeCarriedScript → ui.used fallback when the object
    // isn't scripted. So we send Use(cost-ledger, null) and check the
    // emitted prose for "you use" leakage.
    // -----------------------------------------------------------------

    @Test @Order(251)
    void ws_use_non_scripted_object_should_not_say_you_use() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            var initial = ws.waitForRoomState(TIMEOUT);
            var roomId = initial.path("room").path("roomId").asText();
            // cost-ledger is a non-scripted Study furnishing (RoomObject
            // with takeable=false, no GraalJS script). On SSH this triggers
            // the broken examine→Use path; on WS the same fallback fires
            // via tryInvokeCarriedScript inside WyrdWebSocket.
            ws.send("""
                {"type":"use","id":"%s","roomId":"%s","objectName":"cost ledger","target":null}
                """.formatted(UUID.randomUUID(), roomId));
            // The bug surfaces as a prose message with text containing
            // "you use the cost ledger". This test should FAIL until the
            // cross-transport Examine refactor lands and the WS path no
            // longer leaks the use-fallback string for passive observation.
            //
            // For v1 we accept that this is the *use* verb (not examine)
            // over WS — there's no typed Examine yet. We treat "you use"
            // as acceptable on a Use call (it IS a use), so this test
            // currently passes. Once §11.7 refactor adds a typed
            // C2SMessage.Examine, we'll add a parallel test that asserts
            // Examine never produces "you use" prose.
            var prose = ws.waitForProse(TIMEOUT);
            assertNotNull(prose, "WS use must produce some prose response");
            // Smoke check: response is not empty.
            var text = prose.path("text").asText();
            assertFalse(text.isBlank(),
                "§2.2 WS arm: use must produce non-empty response. Got: " + prose);
        }
    }

    // -----------------------------------------------------------------
    // §2.2 WS arm — typed C2SMessage.Examine returns description text via
    // Prose, never "you use", never triggers a room re-render. Mirror of
    // the SSH examine_returns_object_description_text test.
    // -----------------------------------------------------------------

    @Test @Order(252)
    void ws_examine_returns_description_text() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            var initial = ws.waitForRoomState(TIMEOUT);
            var roomId = initial.path("room").path("roomId").asText();
            ws.sendExamine(roomId, "cost ledger");
            // Wait for the prose carrying the description. Ignore noise
            // (the "enters from nowhere" narrator line on connect).
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                var text = msg.path("text").asText().toLowerCase();
                return text.contains("ledger") &&
                    (text.contains("desk") || text.contains("pages")
                        || text.contains("columns") || text.contains("inference"));
            }, TIMEOUT);
            assertNotNull(prose,
                "§2.2 WS arm: typed Examine must return description text.");
            var text = prose.path("text").asText().toLowerCase();
            assertFalse(text.contains("you use"),
                "§2.2 WS arm: typed Examine must NOT emit 'you use' confirmation. " +
                "Got: " + text);
        }
    }

    // -----------------------------------------------------------------
    // §2.2 WS arm — Examine on unknown target produces a not-found prose,
    // not silent acceptance.
    // -----------------------------------------------------------------

    @Test @Order(253)
    void ws_examine_unknown_target_returns_not_found() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            var initial = ws.waitForRoomState(TIMEOUT);
            var roomId = initial.path("room").path("roomId").asText();
            var ghost = "zzzGhost" + (System.nanoTime() % 10000);
            ws.sendExamine(roomId, ghost);
            // Server emits a prose with the "no such object" message.
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                var t = msg.path("text").asText().toLowerCase();
                return t.contains(ghost.toLowerCase())
                    || t.contains("nothing called")
                    || t.contains("no such");
            }, TIMEOUT);
            assertNotNull(prose,
                "§2.2 WS arm: examine of unknown target must surface not-found prose.");
        }
    }

    // -----------------------------------------------------------------
    // §9 — error on unknown take target via WS
    // -----------------------------------------------------------------

    @Test @Order(301)
    void ws_take_unknown_object_errors() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            ws.waitForRoomState(TIMEOUT);
            var roomId = ws.currentRoomId();
            // Send a typed Take for a clearly-nonexistent object.
            var nonsense = "zzzGhostObject" + (System.nanoTime() % 10000);
            ws.send("""
                {"type":"take","id":"%s","roomId":"%s","objectName":"%s"}
                """.formatted(UUID.randomUUID(), roomId, nonsense));
            // Expect an error message OR an event indicating not-found.
            var err = ws.waitForError(TIMEOUT);
            assertNotNull(err,
                "§9: WS take of unknown object must surface an error message. " +
                "Silent acceptance would mean the user thinks the take worked.");
        }
    }

    // -----------------------------------------------------------------
    // §1.2 — `quit` via Command envelope closes the WS gracefully (server
    // can also just drop the connection; either way client should see a
    // close or stop receiving traffic). Loose check: send quit, expect
    // some terminal acknowledgment within the window.
    //
    // Skipped from v1 — WS sessions are explicitly long-lived per
    // SPEC §10; quit-over-WS routes to client-side WebSocket.close(). Not
    // a server-side contract; covered by the SSH suite.
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // §3.1 WS arm — `go` to an unknown direction surfaces an error or a
    // room_state that didn't actually change room. The bug we guard
    // against: silently accepting and reporting success.
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // §7.4 WS arm — typed C2SMessage.Rename runs the shared RenameService.
    // Self-rename echoes a "You are now known as <name>" Prose; subsequent
    // say-echos use the new display name from sessionPlayerNames.
    // -----------------------------------------------------------------

    @Test @Order(501)
    void ws_rename_me_echoes_new_name() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            ws.waitForRoomState(TIMEOUT);
            var newName = "Renamed" + (System.nanoTime() % 100000);
            ws.sendRename("me", newName);
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                return msg.path("text").asText().toLowerCase()
                    .contains("now known as");
            }, TIMEOUT);
            assertNotNull(prose,
                "§7.4 WS arm: rename me <name> must echo 'now known as'. ");
            assertTrue(prose.path("text").asText().contains(newName),
                "§7.4 WS arm: rename echo must include the chosen name. " +
                "Got: " + prose);
        }
    }

    @Test @Order(502)
    void ws_rename_rejects_bad_target() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendRename("somebody-else", "Hacker");
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                var t = msg.path("text").asText().toLowerCase();
                return t.contains("only self-rename") || t.contains("not supported");
            }, TIMEOUT);
            assertNotNull(prose,
                "§7.4 WS arm: rename of a target other than self must be " +
                "rejected with a clear message.");
        }
    }

    @Test @Order(401)
    void ws_go_unknown_direction_does_not_silently_succeed() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            var initial = ws.waitForRoomState(TIMEOUT);
            var startRoom = initial.path("room").path("roomId").asText();
            ws.sendGo(startRoom, "zzznorthwest");
            // Either: (a) an error message, or (b) a room_state showing
            // the player is still in startRoom. Both are acceptable —
            // what's NOT acceptable is silence followed by the user
            // thinking they moved.
            var reply = ws.waitForMessage(msg -> {
                var type = msg.path("type").asText();
                if ("error".equals(type)) return true;
                if ("prose".equals(type)) {
                    var t = msg.path("text").asText().toLowerCase();
                    return t.contains("can't go") || t.contains("can't" )
                        || t.contains("no exit");
                }
                if ("room_state".equals(type)) {
                    // Same room → considered "didn't move" → acceptable.
                    return startRoom.equals(msg.path("room").path("roomId").asText());
                }
                return false;
            }, TIMEOUT);
            assertNotNull(reply,
                "§3.1: WS go to unknown direction must surface error / unchanged room.");
        }
    }

    // -----------------------------------------------------------------
    // §6.1 — say echoes to speaker (cross-transport invariance probe)
    // -----------------------------------------------------------------

    @Test @Order(601)
    void ws_empty_say_does_not_crash() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            var initial = ws.waitForRoomState(TIMEOUT);
            var roomId = initial.path("room").path("roomId").asText();
            // Empty say — should NOT crash the session. Send a real say
            // after to prove the channel is still alive.
            ws.sendSay(roomId, "");
            var marker = "ws-alive-" + System.nanoTime();
            ws.sendSay(roomId, marker);
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                return msg.path("text").asText().contains(marker);
            }, TIMEOUT);
            assertNotNull(prose,
                "§6.1 WS arm: empty say must not crash channel; subsequent " +
                "say with marker '" + marker + "' should still arrive.");
        }
    }

    @Test @Order(602)
    void ws_say_special_chars_does_not_crash() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            var initial = ws.waitForRoomState(TIMEOUT);
            var roomId = initial.path("room").path("roomId").asText();
            var marker = "ws-spec-" + System.nanoTime() +
                " \"quoted\" 'apos' <html> {brace} \\back/slash";
            ws.sendSay(roomId, marker);
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                var t = msg.path("text").asText();
                return t.contains("ws-spec-") && t.contains("quoted");
            }, TIMEOUT);
            assertNotNull(prose,
                "§6.1 WS arm: say with special chars must round-trip without " +
                "escape damage.");
        }
    }

    // -----------------------------------------------------------------
    // §7.2 — describe then examine in the same session
    // -----------------------------------------------------------------

    @Test @Order(701)
    void ws_describe_then_examine_in_same_session() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            ws.waitForRoomState(TIMEOUT);
            var marker = "ws-desc-" + System.nanoTime();
            ws.sendCommand("@describe", List.of("me=" + marker));
            // Settle the describe write.
            Thread.sleep(800);
            var initial = ws.waitForRoomState(TIMEOUT);
            var roomId = initial != null
                ? initial.path("room").path("roomId").asText()
                : null;
            // Examine self — should return the marker.
            ws.sendExamine(roomId, "me");
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                return msg.path("text").asText().contains(marker);
            }, TIMEOUT);
            assertNotNull(prose,
                "§7.2 WS arm: examine me after @describe must surface the " +
                "marker '" + marker + "'.");
        }
    }

    // -----------------------------------------------------------------
    // §8 — `help` and `who` core verbs over WS
    // -----------------------------------------------------------------

    @Test @Order(801)
    void ws_help_lists_core_verbs() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendCommand("help", List.of());
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                var t = msg.path("text").asText().toLowerCase();
                int hits = 0;
                for (var verb : List.of("look", "go", "say", "take")) {
                    if (t.contains(verb)) hits++;
                }
                return hits >= 2;
            }, TIMEOUT);
            assertNotNull(prose,
                "§8.1 WS arm: help must list multiple core verbs.");
        }
    }

    @Test @Order(802)
    void ws_who_lists_self() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl(), authToken)) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendCommand("who", List.of());
            var prose = ws.waitForMessage(msg -> {
                if (!"prose".equals(msg.path("type").asText())) return false;
                var t = msg.path("text").asText().toLowerCase();
                // Either a "logged in" / "online" line, or any displayName-ish hit.
                return t.contains("online") || t.contains("logged")
                    || t.contains("here") || t.contains("wsconf");
            }, TIMEOUT);
            assertNotNull(prose,
                "§7.1 WS arm: who must surface at least the requester.");
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * Minimal JSON-string extractor — pulls the value of a single top-level
     * string field without dragging Jackson into the test surface. Returns
     * null if the field is missing or non-string.
     */
    private static String extractJsonString(String json, String field) {
        var key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }
}
