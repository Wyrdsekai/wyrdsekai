package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests verifying that companion agent behavior is visible to telnet users.
 * Covers: greeting on entry, response to speech, companion listed in room.
 * Uses WireMock for deterministic inference responses.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TelnetCompanionE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Greetings, traveler. The Nexus hums with potential.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test @Order(1)
    void companion_listed_in_room_on_look() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(1000);

            tc.sendLine("look");
            // The companion "Wyrd" should be listed as an entity in the room
            var wyrd = tc.waitForText("Wyrd", TIMEOUT);
            assertNotNull(wyrd, "Companion 'Wyrd' should be visible in the room");
        }
    }

    @Test @Order(2)
    void companion_greets_on_entry() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);

            // Companion should respond to the user's entry via onEnter script or proactivity.
            // WireMock returns "Greetings, traveler..." for any inference call.
            // Allow time for the companion's response cycle (debounce + inference + render).
            Thread.sleep(5000);
            var lines = tc.allLines();
            boolean sawGreeting = lines.stream().anyMatch(
                l -> l.toLowerCase().contains("greetings") ||
                     l.toLowerCase().contains("traveler") ||
                     l.toLowerCase().contains("nexus hums") ||
                     l.contains("Wyrd:"));
            assertTrue(sawGreeting,
                "Companion should greet the user. Lines received: " +
                lines.subList(Math.max(0, lines.size() - 10), lines.size()));
        }
    }

    @Test @Order(3)
    void companion_responds_to_speech() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(3000); // Wait for initial greeting cycle to complete

            int mark = tc.mark();
            tc.sendLine("'Hello Wyrd, how are you?");

            // Companion should respond — WireMock returns the same stubbed response
            // The response arrives as a Prose message rendered by TelnetRenderer.
            // Allow debounce (1s) + inference (instant with WireMock) + render.
            Thread.sleep(5000);
            var lines = tc.linesSince(mark);
            boolean sawResponse = lines.stream().anyMatch(
                l -> l.contains("Wyrd:") || l.contains("Greetings") ||
                     l.contains("traveler") || l.contains("Nexus hums"));
            assertTrue(sawResponse,
                "Companion should respond to user speech. Lines since mark: " + lines);
        }
    }

    @Test @Order(4)
    void companion_response_visible_via_ssh() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "sshcomp", "sshcomp")) {
            // This will fail if user doesn't exist — create via HTTP first
            fail("SSH companion test needs user pre-registration");
        } catch (Exception e) {
            // Expected — create user and retry
            var http = HttpClient.newHttpClient();
            http.send(HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"sshcomp\",\"password\":\"sshcomp\",\"displayName\":\"SSH User\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        }

        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "sshcomp", "sshcomp")) {
            ssh.waitForText("Study", TIMEOUT);
            // Navigate to Nexus where the companion is
            ssh.sendLine("out");
            ssh.waitForText("Nexus", TIMEOUT);
            Thread.sleep(3000); // Wait for greeting cycle

            int mark = ssh.mark();
            ssh.sendLine("'Hello from SSH!");
            Thread.sleep(5000); // Wait for companion response

            var lines = ssh.linesSince(mark);
            boolean sawResponse = lines.stream().anyMatch(
                l -> l.contains("Wyrd:") || l.contains("Greetings") ||
                     l.contains("traveler") || l.contains("Nexus hums"));
            assertTrue(sawResponse,
                "Companion should respond to SSH user speech. Lines since mark: " + lines);
        }
    }
}
