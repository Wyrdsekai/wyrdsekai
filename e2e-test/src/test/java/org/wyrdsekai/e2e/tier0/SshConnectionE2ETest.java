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
 * E2E tests for the SSH adapter — password auth, interaction, parity with telnet.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SshConnectionE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Greetings.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock", new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();

        // Create test user for SSH password auth
        var http = HttpClient.newHttpClient();
        http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"sshuser\",\"password\":\"sshpass\",\"displayName\":\"SSH User\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test @Order(1)
    void password_auth_connects() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "sshuser", "sshpass")) {
            var room = ssh.waitForText("Study", TIMEOUT);
            assertNotNull(room, "SSH password auth should land in The Study");
        }
    }

    @Test @Order(2)
    void invalid_password_rejected() {
        assertThrows(Exception.class, () -> {
            try (var ssh = TestSshClient.connectWithPassword(
                    "localhost", server.sshPort(), "sshuser", "wrongpass")) {
                // Should not reach here
            }
        }, "Invalid password should throw auth exception");
    }

    @Test @Order(3)
    void ssh_say_works() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "sshuser", "sshpass")) {
            ssh.waitForText("Study", TIMEOUT);
            Thread.sleep(1000); // Allow room state to settle
            ssh.sendLine("Hello from SSH!");
            var echo = ssh.waitForText("Hello from SSH!", TIMEOUT);
            assertNotNull(echo, "Speech should echo back over SSH");
        }
    }

    @Test @Order(4)
    void ssh_navigate_works() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "sshuser", "sshpass")) {
            ssh.waitForText("Study", TIMEOUT);
            Thread.sleep(1000); // Allow Study room to fully initialize
            ssh.sendLine("out");
            var nexus = ssh.waitForText("Nexus", TIMEOUT);
            assertNotNull(nexus, "Navigation from Study to Nexus should work over SSH");
        }
    }

    @Test @Order(5)
    void ssh_look_shows_room() throws Exception {
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), "sshuser", "sshpass")) {
            ssh.waitForText("Study", TIMEOUT);
            ssh.sendLine("look");
            var desc = ssh.waitForLine(
                l -> l.contains("hearth") || l.contains("desk") || l.contains("Study")
                    || l.contains("journal") || l.contains("dashboard"),
                TIMEOUT);
            assertNotNull(desc, "Look should show Study room description over SSH");
        }
    }

    @Test @Order(6)
    void cross_protocol_speech_telnet_to_ssh() throws Exception {
        // SSH lands in Study, telnet guest in Nexus — SSH must navigate to Nexus first.
        try (var telnet = TestTelnetClient.connect("localhost", server.telnetPort())) {
            telnet.waitForText("Wyrdsekai", TIMEOUT);
            telnet.loginAsGuest();
            telnet.waitForText("Nexus", TIMEOUT);

            try (var ssh = TestSshClient.connectWithPassword(
                    "localhost", server.sshPort(), "sshuser", "sshpass")) {
                ssh.waitForText("Study", TIMEOUT);
                // Navigate SSH user to Nexus to meet the telnet guest
                ssh.sendLine("out");
                ssh.waitForText("Nexus", TIMEOUT);
                Thread.sleep(1000); // Allow subscriptions to establish

                int sshMark = ssh.mark();
                telnet.sendLine("say Cross-protocol hello!");

                var heard = ssh.waitForText("Cross-protocol hello!", TIMEOUT);
                assertNotNull(heard, "SSH user should hear telnet user's speech");
            }
        }
    }

    @Test @Order(7)
    void cross_protocol_speech_ssh_to_telnet() throws Exception {
        // SSH lands in Study, telnet guest in Nexus — SSH must navigate to Nexus first.
        try (var telnet = TestTelnetClient.connect("localhost", server.telnetPort())) {
            telnet.waitForText("Wyrdsekai", TIMEOUT);
            telnet.loginAsGuest();
            telnet.waitForText("Nexus", TIMEOUT);

            try (var ssh = TestSshClient.connectWithPassword(
                    "localhost", server.sshPort(), "sshuser", "sshpass")) {
                ssh.waitForText("Study", TIMEOUT);
                ssh.sendLine("out");
                ssh.waitForText("Nexus", TIMEOUT);
                Thread.sleep(1000); // Allow subscriptions to establish

                int telnetMark = telnet.mark();
                ssh.sendLine("say Hello from the other side!");

                var heard = telnet.waitForText("Hello from the other side!", TIMEOUT);
                assertNotNull(heard, "Telnet user should hear SSH user's speech");
            }
        }
    }
}
