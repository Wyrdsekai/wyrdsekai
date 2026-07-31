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
 * E2E tests for multi-user telnet interaction — presence, cross-user visibility.
 */
@Tag("integration")
class TelnetMultiUserE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Hello.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock", new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void two_users_see_each_other_arrive() throws Exception {
        // Connect sequentially — waitForText only sees lines received AFTER it's called,
        // so we must wait for each user's welcome before opening the next connection.
        try (var user1 = TestTelnetClient.connect("localhost", server.telnetPort())) {
            user1.waitForText("Wyrdsekai", TIMEOUT);
            user1.loginAsGuest();
            user1.waitForText("Nexus", TIMEOUT);
            Thread.sleep(1000);

            int mark1 = user1.mark();
            try (var user2 = TestTelnetClient.connect("localhost", server.telnetPort())) {
                user2.waitForText("Wyrdsekai", TIMEOUT);
                user2.loginAsGuest();
                user2.waitForText("Nexus", TIMEOUT);
                Thread.sleep(1000); // Allow arrival notification to propagate

                // User 1 should see user 2 arrive — check all lines since mark
                // (the arrival notification arrives during user2's setup, before
                // waitForLine would capture its startIdx).
                var lines = user1.linesSince(mark1);
                boolean sawArrival = lines.stream().anyMatch(
                    l -> l.contains("arrives") || l.contains("enters") || l.contains("anonymous"));
                assertTrue(sawArrival, "User 1 should see User 2 arrive. Lines since mark: " + lines);
            }
        }
    }

    @Test
    void speech_visible_to_other_user() throws Exception {
        try (var user1 = TestTelnetClient.connect("localhost", server.telnetPort())) {
            user1.waitForText("Wyrdsekai", TIMEOUT);
            user1.loginAsGuest();
            user1.waitForText("Nexus", TIMEOUT);

            try (var user2 = TestTelnetClient.connect("localhost", server.telnetPort())) {
                user2.waitForText("Wyrdsekai", TIMEOUT);
                user2.loginAsGuest();
                user2.waitForText("Nexus", TIMEOUT);
                Thread.sleep(1000); // Wait for room subscription to be fully active

                int mark2 = user2.mark();
                user1.sendLine("'Can you hear me?");

                var heard = user2.waitForText("Can you hear me?", TIMEOUT);
                assertNotNull(heard, "User 2 should hear User 1's speech");
            }
        }
    }

    @Test
    void emote_visible_to_other_user() throws Exception {
        try (var user1 = TestTelnetClient.connect("localhost", server.telnetPort())) {
            user1.waitForText("Wyrdsekai", TIMEOUT);
            user1.loginAsGuest();
            user1.waitForText("Nexus", TIMEOUT);

            try (var user2 = TestTelnetClient.connect("localhost", server.telnetPort())) {
                user2.waitForText("Wyrdsekai", TIMEOUT);
                user2.loginAsGuest();
                user2.waitForText("Nexus", TIMEOUT);
                Thread.sleep(1000); // Wait for subscriptions

                int mark2 = user2.mark();
                user1.sendLine(":dances joyfully");

                var emote = user2.waitForText("dances joyfully", TIMEOUT);
                assertNotNull(emote, "User 2 should see User 1's emote");
            }
        }
    }
}
