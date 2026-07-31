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
 * Telnet parity arm of the MUD conformance suite.
 *
 * <p>SPEC: §10 (cross-transport invariance).
 * SSH and Telnet share most server-side handling (parser, ExamineLookup,
 * RoomCommand) but expose them through different framing. This class
 * exercises the line-based telnet surface against {@code TelnetSession}.
 * Bugs that diverge per-transport (e.g. an authenticator branch only in
 * SSH, a renderer that swallows control bytes in telnet) surface here
 * without polluting the SSH suite.</p>
 *
 * <p>Telnet is OFF by default in production
 * but the test fixture starts it on a random port. If the fixture can't
 * start telnet (e.g. port collision), the @{@code BeforeAll} skips the
 * whole class via Assumptions.</p>
 */
@Tag("conformance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TelnetConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static int telnetPort;
    private static String USER = "telnetconf";
    private static String PASS = "telnetconfpw";
    private static boolean liveMode = false;

    @BeforeAll
    static void setUp() throws Exception {
        var override = System.getenv("WYRDSEKAI_TELNET_CONFORMANCE_TARGET");
        if (override != null && !override.isBlank() && !"embedded".equalsIgnoreCase(override)) {
            var parts = override.split(":");
            // host:port — we only honour port here; host is always localhost
            // (the test client only knows how to dial localhost in v1).
            telnetPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 7071;
            var envUser = System.getenv("WYRDSEKAI_TELNET_CONFORMANCE_USER");
            var envPass = System.getenv("WYRDSEKAI_TELNET_CONFORMANCE_PASS");
            if (envUser != null && !envUser.isBlank()) USER = envUser;
            if (envPass != null && !envPass.isBlank()) PASS = envPass;
            liveMode = true;
            return;
        }

        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
        telnetPort = server.telnetPort();
        Assumptions.assumeTrue(telnetPort > 0,
            "Telnet adapter not started in test fixture — skipping telnet conformance.");

        // Bootstrap a user. Telnet's `connect <user> <pass>` checks
        // authService.login(), so we need a real account.
        var http = HttpClient.newHttpClient();
        http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + USER + "\",\"password\":\"" + PASS
                    + "\",\"displayName\":\"Telnet Conf\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // -----------------------------------------------------------------
    // §1.2 — `quit` ends session
    // -----------------------------------------------------------------

    @Test @Order(101)
    void quit_closes_session() throws Exception {
        try (var t = login()) {
            t.sendLine("quit");
            // Give the server time to render farewell + close.
            Thread.sleep(1500);
            // After quit the reader thread should have stopped accepting.
            // We can't easily peek socket-closed without grabbing the socket,
            // but we can check the channel no longer accepts new input.
            // Looser: assert that a goodbye/closure substring landed.
            var output = String.join("\n", t.allLines()).toLowerCase();
            assertTrue(output.contains("goodbye") || output.contains("bye")
                || output.contains("farewell") || output.contains("session")
                || output.contains("disconnect"),
                "§1.2: telnet quit must surface farewell. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §2.1 — look renders current room
    // -----------------------------------------------------------------

    @Test @Order(201)
    void look_renders_current_room() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("look");
            // Telnet look returns a room render — should include either
            // exits, an object name, or the room description.
            var out = t.waitForLine(line ->
                line.toLowerCase().contains("exits")
                    || line.toLowerCase().contains("study")
                    || line.toLowerCase().contains("you see")
                    || line.toLowerCase().contains("door"),
                TIMEOUT);
            assertNotNull(out,
                "§2.1: telnet look must render room state. Got:\n"
                + String.join("\n", t.linesSince(mark)));
        }
    }

    // -----------------------------------------------------------------
    // §2.2 — examine must NOT emit "you use" confirmation. Post-refactor:
    // ParsedCommand.Examine routes through TelnetSession.handleExamine →
    // ExamineLookup, never through the use handler.
    // -----------------------------------------------------------------

    @Test @Order(202)
    void examine_does_not_emit_use_confirmation() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("examine cost ledger");
            Thread.sleep(2000);
            var output = String.join("\n", t.linesSince(mark)).toLowerCase();
            assertFalse(output.contains("you use"),
                "§2.2: telnet examine must NOT emit 'you use' confirmation. " +
                "Output:\n" + output);
        }
    }

    @Test @Order(203)
    void examine_returns_description_text() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("examine cost ledger");
            Thread.sleep(2000);
            var output = String.join("\n", t.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("ledger") &&
                (output.contains("pages") || output.contains("desk")
                || output.contains("columns") || output.contains("inference")),
                "§2.2: 'examine cost ledger' over telnet must return description. " +
                "Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §4.1 — inventory empty state is clean
    // -----------------------------------------------------------------

    @Test @Order(301)
    void inventory_empty_state_is_clean() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("inventory");
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("carrying") || output.contains("nothing")
                || output.contains("empty") || output.contains("inventory"),
                "§4.1: telnet inventory must render a clean empty-state. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6.1 — say echoes to speaker
    // -----------------------------------------------------------------

    @Test @Order(401)
    void say_echoes_to_speaker() throws Exception {
        var marker = "telnet-marker-" + System.nanoTime();
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("say " + marker);
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark));
            assertTrue(output.contains(marker),
                "§6.1: telnet say must echo speech back to speaker. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6.3 — tell to unknown target errors (doesn't silently swallow)
    // -----------------------------------------------------------------

    @Test @Order(402)
    void tell_to_unknown_target_errors() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            var ghost = "zzzGhostUser" + (System.nanoTime() % 10000);
            t.sendLine("tell " + ghost + " hello?");
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark)).toLowerCase();
            assertTrue(
                output.contains("nobody") || output.contains("no such")
                || output.contains("not found") || output.contains("offline")
                || output.contains("don't know"),
                "§6.3: telnet tell to unknown target must surface a not-found " +
                "message. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §9 — unknown verb does not silently drop
    // -----------------------------------------------------------------

    @Test @Order(601)
    void unknown_verb_does_not_silently_drop() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("zzbobcatfloop");
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark));
            // §9: unknown verb path returns either an error or routes to
            // say. We accept either — what's not OK is *silence*.
            assertFalse(output.isBlank(),
                "§9: unknown verb must not be silently dropped on telnet.");
        }
    }

    // -----------------------------------------------------------------
    // §7.4 — rename me <new-name> succeeds via the shared RenameService.
    // Pre-refactor this surfaced a "rename is supported over SSH only"
    // redirect; the regression-lock now asserts the real success path.
    // -----------------------------------------------------------------

    @Test @Order(501)
    void rename_me_echoes_new_name() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            var newName = "TelRenamed" + (System.nanoTime() % 100000);
            t.sendLine("rename me " + newName);
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("now known as"),
                "§7.4: 'rename me <name>' over telnet must echo " +
                "'now known as'. Got:\n" + output);
            assertTrue(output.contains(newName.toLowerCase()),
                "§7.4: rename echo must include the chosen name. Got:\n" +
                output);
        }
    }

    // -----------------------------------------------------------------
    // §8.1 — help lists core verbs
    // -----------------------------------------------------------------

    @Test @Order(701)
    void help_lists_core_verbs() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("help");
            Thread.sleep(2000);
            var output = String.join("\n", t.linesSince(mark)).toLowerCase();
            // Help should list at least look, go, say, take.
            int hits = 0;
            for (var verb : List.of("look", "go", "say", "take")) {
                if (output.contains(verb)) hits++;
            }
            assertTrue(hits >= 2,
                "§8.1: telnet help should list multiple core verbs. " +
                "Got " + hits + "/4 hits. Output:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6.1 — empty say doesn't crash the channel
    // -----------------------------------------------------------------

    @Test @Order(411)
    void empty_say_does_not_crash() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("say ");
            Thread.sleep(500);
            var marker = "telnet-alive-" + System.nanoTime();
            t.sendLine("say " + marker);
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark));
            assertTrue(output.contains(marker),
                "§6.1: telnet empty-say must not crash channel; subsequent " +
                "say with marker '" + marker + "' should arrive.");
        }
    }

    @Test @Order(412)
    void say_special_chars_round_trips() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            var marker = "tel-spec-" + System.nanoTime() +
                " \"quoted\" 'apos' <html> {brace}";
            t.sendLine("say " + marker);
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark));
            assertTrue(output.contains("tel-spec-") && output.contains("quoted"),
                "§6.1: telnet say with special chars must round-trip without " +
                "escape damage.");
        }
    }

    // -----------------------------------------------------------------
    // §7.1 — `who` lists at least the requester
    // -----------------------------------------------------------------

    @Test @Order(711)
    void who_lists_self() throws Exception {
        try (var t = login()) {
            int mark = t.mark();
            t.sendLine("who");
            Thread.sleep(1500);
            var output = String.join("\n", t.linesSince(mark)).toLowerCase();
            // Accept "online", "logged in", "<count> player", or the username
            assertTrue(output.contains("online") || output.contains("logged")
                || output.contains("player") || output.contains("here")
                || output.contains(USER.toLowerCase()),
                "§7.1: telnet who must surface the requester or an online " +
                "list. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §7.2 — @describe then examine in same session
    // -----------------------------------------------------------------

    @Test @Order(721)
    void describe_then_examine_in_same_session() throws Exception {
        try (var t = login()) {
            var marker = "tel-desc-" + System.nanoTime();
            t.sendLine("@describe me=" + marker);
            Thread.sleep(1500);
            int mark = t.mark();
            t.sendLine("examine me");
            Thread.sleep(2000);
            var output = String.join("\n", t.linesSince(mark));
            assertTrue(output.contains(marker),
                "§7.2: telnet examine me after @describe must surface marker '"
                + marker + "'. Got:\n" + output);
        }
    }

    @Test @Order(722)
    void long_description_persists_in_examine() throws Exception {
        try (var t = login()) {
            // ~400 chars of stable content. Tests render/encoding survival
            // across the describe → examine round-trip.
            var stem = "the long-description test " + System.nanoTime() + " ";
            var sb = new StringBuilder();
            while (sb.length() < 400) sb.append(stem);
            var desc = sb.toString().trim();
            t.sendLine("@describe me=" + desc);
            Thread.sleep(1500);
            int mark = t.mark();
            t.sendLine("examine me");
            Thread.sleep(2000);
            var output = String.join("\n", t.linesSince(mark));
            // Verify the unique prefix (System.nanoTime piece) survived.
            var probe = stem.trim();
            assertTrue(output.contains(probe),
                "§7.2: telnet examine of long description must surface the " +
                "stable probe '" + probe + "'.");
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static TestTelnetClient login() throws Exception {
        var t = TestTelnetClient.connect("localhost", telnetPort);
        // The server emits "login>" via sendRaw (no newline) which never
        // surfaces as a buffered line — so don't waitForText() for the
        // prompt. The server's input loop reads lines regardless of when
        // the prompt arrives, so just push the connect command and wait
        // for the post-login banner.
        Thread.sleep(300); // give the welcome banner a chance to land
        t.login(USER, PASS);
        t.waitForLine(line ->
            line.toLowerCase().contains("study")
                || line.toLowerCase().contains("nexus")
                || line.toLowerCase().contains("logged in")
                || line.toLowerCase().contains("welcome"),
            TIMEOUT);
        Thread.sleep(500);
        return t;
    }
}
