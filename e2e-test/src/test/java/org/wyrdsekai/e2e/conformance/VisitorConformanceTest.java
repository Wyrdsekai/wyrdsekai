package org.wyrdsekai.e2e.conformance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.server.session.VirtualSessionHandler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance probes for the cross-zone visitor surface — the verb path
 * that runs when a foreign-zone player traverses to this zone via signed
 * transit token.
 *
 * <p>The production wiring opens a {@code VirtualSession} via a NATS
 * {@code session.open} envelope after token verification, then dispatches
 * subsequent commands through {@link VirtualSessionHandler#onCommand}.
 * That requires a peer zone + relay round-trip to exercise end-to-end,
 * which is what the tier-3 cross-zone E2E suite covers.</p>
 *
 * <p>This conformance arm targets the in-process verb-dispatch contract:
 * {@code examine}, {@code where}, {@code inventory}, etc. should resolve
 * the same way they do for SSH/WS/Telnet players (shared {@code ExamineLookup}
 * for examine; no {@code "you use"} fallback; etc.). It uses the
 * {@code testInjectSession} + {@code testDispatchInput} + {@code setTestEventCapture}
 * seams added to {@link VirtualSessionHandler} so the test can construct a
 * synthetic visitor session against the embedded fixture without bringing
 * up a peer zone.</p>
 *
 * <p>SPEC: (cross-transport invariance) — visitor
 * is the 4th transport.</p>
 */
@Tag("conformance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VisitorConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static VirtualSessionHandler handler;
    private static final ConcurrentLinkedQueue<ObjectNode> captured = new ConcurrentLinkedQueue<>();

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();

        // Build a VirtualSessionHandler bound to the same actor system + zone
        // as the fixture. NatsBridge / FederationService / WardService are
        // unused for the examine / inventory / where path; pass null. If a
        // future test exercises a path that needs them, plug a stub here.
        handler = new VirtualSessionHandler(
            null /* natsBridge */, "local",
            null /* federationService */,
            server.system(), null /* wardService */);

        handler.setTestEventCapture((targetZone, envelope) -> captured.offer(envelope));
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void clearCaptured() {
        captured.clear();
    }

    // -----------------------------------------------------------------
    // §2.2 — `examine <object>` resolves via shared ExamineLookup, never
    // via the `use` script fallback. Visitor surface MUST stay in parity.
    // -----------------------------------------------------------------

    @Test @Order(101)
    void visitor_examine_returns_object_description() throws Exception {
        var sessionId = "visit-" + System.nanoTime();
        handler.testInjectSession(sessionId, "did:peer:1", "Wanderer",
            "alpha", "docks");

        handler.testDispatchInput(sessionId, "examine compass");

        var prose = waitForProseContaining("brass", TIMEOUT);
        assertNotNull(prose,
            "§2.2: visitor examine of a known room object must surface " +
            "its description. Got events:\n" + dumpCaptured());
        assertFalse(prose.toLowerCase().contains("you use"),
            "§2.2: visitor examine must NOT trigger 'you use' fallback. " +
            "Got: " + prose);
    }

    @Test @Order(102)
    void visitor_examine_self_returns_name() throws Exception {
        var sessionId = "visit-" + System.nanoTime();
        handler.testInjectSession(sessionId, "did:peer:2", "Pilgrim",
            "alpha", "docks");

        handler.testDispatchInput(sessionId, "examine me");

        var prose = waitForProseContaining("Pilgrim", TIMEOUT);
        assertNotNull(prose,
            "§2.2: visitor 'examine me' must surface the visitor's display " +
            "name. Got events:\n" + dumpCaptured());
    }

    @Test @Order(103)
    void visitor_examine_unknown_target_returns_not_found() throws Exception {
        var sessionId = "visit-" + System.nanoTime();
        handler.testInjectSession(sessionId, "did:peer:3", "Stranger",
            "alpha", "docks");

        handler.testDispatchInput(sessionId, "examine zzzbobcatfloop");

        var prose = waitForProseContaining("nothing called", TIMEOUT);
        assertNotNull(prose,
            "§2.2: visitor examine of an unknown target must return a " +
            "clear 'nothing called X here' message. Got events:\n" +
            dumpCaptured());
    }

    // -----------------------------------------------------------------
    // §4.1 — inventory shows visitor's carried items (empty initially)
    // -----------------------------------------------------------------

    @Test @Order(201)
    void visitor_inventory_empty_state_is_clean() throws Exception {
        var sessionId = "visit-" + System.nanoTime();
        handler.testInjectSession(sessionId, "did:peer:4", "Drifter",
            "alpha", "docks");

        handler.testDispatchInput(sessionId, "/inventory");

        var prose = waitForProseContaining("nothing", TIMEOUT);
        assertNotNull(prose,
            "§4.1: visitor inventory empty-state must surface 'nothing' " +
            "or equivalent. Got events:\n" + dumpCaptured());
    }

    // -----------------------------------------------------------------
    // §7.1 — `where` surfaces current room + visiting-zone marker
    // -----------------------------------------------------------------

    @Test @Order(301)
    void visitor_where_includes_room_name() throws Exception {
        var sessionId = "visit-" + System.nanoTime();
        handler.testInjectSession(sessionId, "did:peer:5", "Voyager",
            "alpha", "docks");

        handler.testDispatchInput(sessionId, "where");

        var prose = waitForProseContaining("Docks", TIMEOUT);
        assertNotNull(prose,
            "§7.1: visitor 'where' must surface current room name. " +
            "Got events:\n" + dumpCaptured());
    }

    // §9 (unknown verb routes to say) — covered by SSH/WS/Telnet arms.
    // Skipped on visitor surface because the say-path tries to hit
    // {@code wardService.isAllowed} for room-level speak checks; the
    // test fixture's VirtualSessionHandler stub passes a null wardService.
    // Wiring a real ward stub would test the ward layer, not the visitor
    // verb dispatch contract this suite is about.

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /** Wait for any captured event whose data.text (or top-level text)
     *  contains the substring (case-insensitive). */
    private static String waitForProseContaining(String needle, Duration timeout) {
        var lower = needle.toLowerCase();
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (var ev : captured) {
                var text = extractText(ev);
                if (text != null && text.toLowerCase().contains(lower)) {
                    return text;
                }
            }
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return null;
            }
        }
        return null;
    }

    private static String extractText(ObjectNode ev) {
        var data = ev.get("data");
        if (data == null) return null;
        if (data.has("text")) return data.get("text").asText();
        if (data.has("message")) return data.get("message").asText();
        return data.toString();
    }

    private static String dumpCaptured() {
        var sb = new StringBuilder();
        for (var ev : captured) {
            sb.append("  ").append(ev.toString()).append('\n');
        }
        return sb.toString();
    }
}
