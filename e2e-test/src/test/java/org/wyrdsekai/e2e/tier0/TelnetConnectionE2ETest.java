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
 * E2E tests for the telnet adapter — connection, authentication, GMCP.
 * No external dependencies (Tier 0).
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TelnetConnectionE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome, traveler.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock", new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test @Order(1)
    void connect_and_see_welcome() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            var welcome = tc.waitForText("Wyrdsekai", TIMEOUT);
            assertNotNull(welcome, "Should see welcome banner");
        }
    }

    @Test @Order(2)
    void guest_login_enters_nexus() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            var room = tc.waitForText("Nexus", TIMEOUT);
            assertNotNull(room, "Guest should land in The Nexus");
        }
    }

    @Test @Order(3)
    void create_account_via_telnet() throws Exception {
        // Runs BEFORE login_with_credentials so the telnet-created account is the
        // household's FIRST user (the steward). Open registration is only available
        // for the first user; after that the household is invite-only (F4), so a
        // second bare telnet account creation would be refused by design.
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.createAccount("newtelnet", "pass456");
            var room = tc.waitForText("Study", TIMEOUT);
            assertNotNull(room, "Newly created user should enter The Study");
        }
    }

    @Test @Order(4)
    void login_with_credentials() throws Exception {
        // Log in as the account created via telnet in create_account_via_telnet
        // (@Order 3) — the household steward. Verifies credential login lands the
        // user in their Study.
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.login("newtelnet", "pass456");
            var room = tc.waitForText("Study", TIMEOUT);
            assertNotNull(room, "Authenticated user should enter The Study");
        }
    }

    @Test @Order(5)
    void invalid_login_shows_error() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.login("nonexistent", "wrongpass");
            var error = tc.waitForLine(
                l -> l.toLowerCase().contains("invalid") || l.toLowerCase().contains("failed")
                    || l.toLowerCase().contains("error") || l.toLowerCase().contains("unknown"),
                TIMEOUT);
            assertNotNull(error, "Should see login error message");
        }
    }

    @Test @Order(6)
    void quit_disconnects_cleanly() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.sendLine("quit");
            // After quit, connection should close — no more data
            Thread.sleep(1000);
            // If we get here without exception, the quit was handled
        }
    }
}
