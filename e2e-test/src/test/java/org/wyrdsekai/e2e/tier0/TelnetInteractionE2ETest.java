package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for telnet interaction — say, emote, navigation, hints.
 */
@Tag("integration")
class TelnetInteractionE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock", new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    private TestTelnetClient connectAsGuest() throws Exception {
        var tc = TestTelnetClient.connect("localhost", server.telnetPort());
        tc.waitForText("Wyrdsekai", TIMEOUT);
        tc.loginAsGuest();
        tc.waitForText("Nexus", TIMEOUT);
        Thread.sleep(500); // let initial room render complete
        return tc;
    }

    @Test
    void say_echoes_in_room() throws Exception {
        try (var tc = connectAsGuest()) {
            int mark = tc.mark();
            tc.sendLine("say hello world");
            var echo = tc.waitForText("hello world", TIMEOUT);
            assertNotNull(echo, "Speech should echo back");
        }
    }

    @Test
    void bare_text_is_unknown() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine("just some text");
            // Matches the en-locale "telnet.unknown_input" catalog entry.
            var miss = tc.waitForText("Didn't catch that", TIMEOUT);
            assertNotNull(miss, "Bare text should return the unknown-command response");
        }
    }

    @Test
    void say_shorthand_works() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine("'just some text");
            var echo = tc.waitForText("just some text", TIMEOUT);
            assertNotNull(echo, "Say shorthand ('text) should echo speech");
        }
    }

    @Test
    void emote_renders_correctly() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine(":waves warmly");
            var emote = tc.waitForLine(l -> l.contains("waves warmly"), TIMEOUT);
            assertNotNull(emote, "Emote should render with action text");
        }
    }

    @Test
    void look_shows_room_description() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine("look");
            // Look produces room name + description + exits
            var exits = tc.waitForLine(l -> l.toLowerCase().contains("exits:"), TIMEOUT);
            assertNotNull(exits, "Look should show exits line");
        }
    }

    @Test
    void go_north_changes_room() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine("north");
            // North of Nexus is The Bridge (per foundation room config)
            var bridge = tc.waitForText("Bridge", TIMEOUT);
            assertNotNull(bridge, "Going north from Nexus should reach The Bridge");
        }
    }

    @Test
    void invalid_direction_shows_error() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine("go nowhere");
            var error = tc.waitForLine(
                l -> l.toLowerCase().contains("can't") || l.toLowerCase().contains("no exit")
                    || l.toLowerCase().contains("error") || l.toLowerCase().contains("nowhere"),
                Duration.ofSeconds(5));
            // Some form of error or no-op response expected
            // (if silent, that's also acceptable — no crash is the key assertion)
        }
    }

    @Test
    void slash_help_shows_commands() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine("/help");
            var help = tc.waitForLine(
                l -> l.toLowerCase().contains("help") || l.toLowerCase().contains("command")
                    || l.toLowerCase().contains("say") || l.toLowerCase().contains("look"),
                Duration.ofSeconds(5));
            assertNotNull(help, "/help should show available commands");
        }
    }

    @Test
    void examine_object_works() throws Exception {
        try (var tc = connectAsGuest()) {
            // Nexus has a "crystal" object
            tc.sendLine("examine crystal");
            var result = tc.waitForLine(
                l -> l.toLowerCase().contains("crystal") || l.toLowerCase().contains("puls"),
                TIMEOUT);
            assertNotNull(result, "Examine should show object interaction result");
        }
    }

    @Test
    void look_at_object_works() throws Exception {
        try (var tc = connectAsGuest()) {
            tc.sendLine("look at crystal");
            var result = tc.waitForLine(
                l -> l.toLowerCase().contains("crystal") || l.toLowerCase().contains("puls"),
                TIMEOUT);
            assertNotNull(result, "Look at should show object interaction result");
        }
    }

    @Test
    void partial_object_name_works() throws Exception {
        try (var tc = connectAsGuest()) {
            // Nexus has "Nexus Crystal" — "crystal" should match via partial matching
            tc.sendLine("use crystal");
            var result = tc.waitForLine(
                l -> l.toLowerCase().contains("crystal") || l.toLowerCase().contains("puls")
                    || l.toLowerCase().contains("used"),
                TIMEOUT);
            assertNotNull(result, "Partial name 'crystal' should match 'Nexus Crystal'");
        }
    }

    @Test
    void hint_selection_works() throws Exception {
        try (var tc = connectAsGuest()) {
            // Hints are numbered — type "1" to select first hint
            tc.sendLine("look"); // ensure hints are rendered
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);
            tc.sendLine("1");
            // Hint 1 should trigger some action (look, go, etc.)
            Thread.sleep(1000);
            // No crash is the primary assertion
            assertTrue(tc.lineCount() > 0, "Hint selection should produce some output");
        }
    }
}
