package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for standard MUD commands: give, score, shout, reply, follow, afk, brief,
 * read, targeted socials.
 */
@Tag("integration")
class MudCommandsE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
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
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // --- Command Parsing Tests ---

    @Test
    void parse_give_object_to_target() {
        var cmd = CommandParser.parse("give sword to Alice");
        assertInstanceOf(ParsedCommand.Give.class, cmd);
        var give = (ParsedCommand.Give) cmd;
        assertEquals("sword", give.objectName());
        assertEquals("Alice", give.targetName());
    }

    @Test
    void parse_score() {
        assertInstanceOf(ParsedCommand.Score.class, CommandParser.parse("score"));
        assertInstanceOf(ParsedCommand.Score.class, CommandParser.parse("vitals"));
        assertInstanceOf(ParsedCommand.Score.class, CommandParser.parse("stats"));
    }

    @Test
    void parse_shout() {
        var cmd = CommandParser.parse("shout Hello everyone!");
        assertInstanceOf(ParsedCommand.Shout.class, cmd);
        assertEquals("Hello everyone!", ((ParsedCommand.Shout) cmd).text());
    }

    @Test
    void parse_yell() {
        var cmd = CommandParser.parse("yell Watch out!");
        assertInstanceOf(ParsedCommand.Shout.class, cmd);
        assertEquals("Watch out!", ((ParsedCommand.Shout) cmd).text());
    }

    @Test
    void parse_reply() {
        var cmd = CommandParser.parse("reply Thanks for that!");
        assertInstanceOf(ParsedCommand.Reply.class, cmd);
        assertEquals("Thanks for that!", ((ParsedCommand.Reply) cmd).text());
    }

    @Test
    void parse_follow() {
        var cmd = CommandParser.parse("follow Ember");
        assertInstanceOf(ParsedCommand.Follow.class, cmd);
        assertEquals("Ember", ((ParsedCommand.Follow) cmd).targetName());
    }

    @Test
    void parse_afk() {
        var cmd = CommandParser.parse("afk");
        assertInstanceOf(ParsedCommand.Afk.class, cmd);

        cmd = CommandParser.parse("afk getting coffee");
        assertInstanceOf(ParsedCommand.Afk.class, cmd);
        assertEquals("getting coffee", ((ParsedCommand.Afk) cmd).message());
    }

    @Test
    void parse_brief() {
        assertInstanceOf(ParsedCommand.Brief.class, CommandParser.parse("brief"));
        assertInstanceOf(ParsedCommand.Brief.class, CommandParser.parse("verbose"));
    }

    @Test
    void parse_read_aliases_to_use() {
        var cmd = CommandParser.parse("read scroll");
        assertInstanceOf(ParsedCommand.Use.class, cmd);
        assertEquals("scroll", ((ParsedCommand.Use) cmd).objectName());
    }

    // --- Telnet E2E Tests ---

    @Test
    void telnet_score_shows_status() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            tc.sendLine("score");
            var result = tc.waitForLine(
                l -> l.contains("Status") || l.contains("status") || l.contains("==="),
                TIMEOUT);
            assertNotNull(result, "Score should show status information");
        }
    }

    @Test
    void telnet_brief_toggle() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            tc.sendLine("brief");
            var result = tc.waitForLine(
                l -> l.toLowerCase().contains("brief") || l.toLowerCase().contains("mode"),
                TIMEOUT);
            assertNotNull(result, "Brief should confirm toggle");
        }
    }

    @Test
    void telnet_afk_set_and_clear() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            tc.sendLine("afk getting coffee");
            var set = tc.waitForLine(
                l -> l.toLowerCase().contains("afk") || l.toLowerCase().contains("coffee"),
                TIMEOUT);
            assertNotNull(set, "Should confirm AFK status set");

            tc.sendLine("afk");
            var cleared = tc.waitForLine(
                l -> l.toLowerCase().contains("no longer") || l.toLowerCase().contains("cleared")
                    || l.toLowerCase().contains("afk"),
                TIMEOUT);
            assertNotNull(cleared, "Should confirm AFK status cleared");
        }
    }

    @Test
    void telnet_shout_visible_in_room() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            tc.sendLine("shout Hello world!");
            var result = tc.waitForLine(
                l -> l.contains("Hello world!") || l.contains("Shout") || l.contains("shout"),
                TIMEOUT);
            assertNotNull(result, "Shout should be visible in room");
        }
    }

    @Test
    void telnet_reply_no_target() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            tc.sendLine("reply hello");
            var result = tc.waitForLine(
                l -> l.toLowerCase().contains("no one") || l.toLowerCase().contains("recently")
                    || l.toLowerCase().contains("reply"),
                TIMEOUT);
            assertNotNull(result, "Reply with no recent tell should show error");
        }
    }
}
