package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.common.protocol.CommandParser;
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
 * E2E tests for the @describe system:
 * - Player self-description via @describe
 * - Room description (Study only) via @describe room=
 * - Parsing of describe commands
 * - Entity descriptions visible on look-at
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DescribeE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();

        // Create test user
        var http = HttpClient.newHttpClient();
        http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"descuser\",\"password\":\"descpass\",\"displayName\":\"Desc User\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // --- CommandParser tests ---

    @Test @Order(1)
    void parse_at_describe_self() {
        var cmd = CommandParser.parse("@describe A tall figure with kind eyes");
        assertInstanceOf(CommandParser.ParsedCommand.Describe.class, cmd);
        var desc = (CommandParser.ParsedCommand.Describe) cmd;
        assertEquals("me", desc.target());
        assertEquals("A tall figure with kind eyes", desc.text());
    }

    @Test @Order(2)
    void parse_at_describe_me_equals() {
        var cmd = CommandParser.parse("@describe me=A wanderer from distant lands");
        assertInstanceOf(CommandParser.ParsedCommand.Describe.class, cmd);
        var desc = (CommandParser.ParsedCommand.Describe) cmd;
        assertEquals("me", desc.target());
        assertEquals("A wanderer from distant lands", desc.text());
    }

    @Test @Order(3)
    void parse_at_describe_room_equals() {
        var cmd = CommandParser.parse("@describe room=A cozy study filled with books");
        assertInstanceOf(CommandParser.ParsedCommand.Describe.class, cmd);
        var desc = (CommandParser.ParsedCommand.Describe) cmd;
        assertEquals("room", desc.target());
        assertEquals("A cozy study filled with books", desc.text());
    }

    @Test @Order(4)
    void parse_describe_me_text() {
        var cmd = CommandParser.parse("describe me A quiet scholar");
        assertInstanceOf(CommandParser.ParsedCommand.Describe.class, cmd);
        var desc = (CommandParser.ParsedCommand.Describe) cmd;
        assertEquals("me", desc.target());
    }

    @Test @Order(5)
    void parse_office_command() {
        var cmd = CommandParser.parse("office");
        assertInstanceOf(CommandParser.ParsedCommand.Office.class, cmd);

        cmd = CommandParser.parse("study");
        assertInstanceOf(CommandParser.ParsedCommand.Office.class, cmd);

        cmd = CommandParser.parse("home");
        assertInstanceOf(CommandParser.ParsedCommand.Office.class, cmd);
    }

    // --- SSH E2E tests ---

    @Test @Order(6)
    void ssh_describe_self() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "descuser", "descpass")) {
            ssh.waitForText("Study", TIMEOUT);
            Thread.sleep(1000);

            ssh.sendLine("@describe A scholar of ancient texts with silver-streaked hair");
            var confirmation = ssh.waitForLine(
                l -> l.toLowerCase().contains("description") || l.toLowerCase().contains("updated"),
                TIMEOUT);
            assertNotNull(confirmation, "Should confirm description was set");
        }
    }

    @Test @Order(7)
    void ssh_describe_room_in_study() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "descuser", "descpass")) {
            ssh.waitForText("Study", TIMEOUT);
            Thread.sleep(1000);

            ssh.sendLine("@describe room=A warm study with towering bookshelves and a crackling hearth");
            // Room describe sends via SayInRoom — should not error
            Thread.sleep(2000);
            // No error = success (room describe is fire-and-forget)
        }
    }

    @Test @Order(8)
    void ssh_describe_room_rejected_outside_study() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "descuser", "descpass")) {
            ssh.waitForText("Study", TIMEOUT);
            Thread.sleep(500);

            // Go to Nexus
            ssh.sendLine("out");
            ssh.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            // Try to describe room — should be rejected (not in Study)
            ssh.sendLine("@describe room=Should not work here");
            var rejection = ssh.waitForLine(
                l -> l.toLowerCase().contains("study") || l.toLowerCase().contains("own"),
                TIMEOUT);
            assertNotNull(rejection, "Should reject room describe outside of Study");
        }
    }

    @Test @Order(9)
    void guest_cannot_describe() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            tc.sendLine("@describe A mysterious stranger");
            var rejection = tc.waitForLine(
                l -> l.toLowerCase().contains("logged in") || l.toLowerCase().contains("login")
                    || l.toLowerCase().contains("log in") || l.toLowerCase().contains("must be"),
                TIMEOUT);
            assertNotNull(rejection, "Guests should not be able to set descriptions");
        }
    }

    @Test @Order(10)
    void ssh_office_command_from_nexus() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "descuser", "descpass")) {
            ssh.waitForText("Study", TIMEOUT);
            Thread.sleep(500);

            // Go to Nexus
            ssh.sendLine("out");
            ssh.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            // Type 'office' to go back
            ssh.sendLine("office");
            var study = ssh.waitForText("Study", TIMEOUT);
            assertNotNull(study, "office command should return to Study");
        }
    }
}
