package org.wyrdsekai.e2e.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestUsers;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connection that drops without the person choosing it (a reload, a network blip) keeps their
 * place for a while: back in time, no one in the room sees them leave or arrive, and what was said
 * while they were away is replayed to them. Not back in time, or leaving on purpose, they leave as
 * before. Until 0.5.15 every drop was a departure and every return an arrival, and anything said in
 * between was lost.
 */
@Tag("conformance")
class ADroppedConnectionKeepsItsPlaceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String ROOM = "nexus";
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static String watcherToken;
    private static String drifterToken;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);
        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
        server.wsHandler().setReconnectHold(Duration.ofSeconds(4));
        watcherToken = TestUsers.registerStewardToken(server.baseUrl(), "heldwatcher", "heldwatcherpw", "Watcher Quill");
        drifterToken = TestUsers.inviteAndRedeem(server.baseUrl(), watcherToken, "helddrifter", "helddrifterpw",
            "Drifter Moss").get("token").asText();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    private static Predicate<JsonNode> aboutTheDrifter() {
        return m -> "prose".equals(m.path("type").asText()) && "narrator".equals(m.path("speaker").asText())
            && m.path("text").asText().contains("Drifter Moss");
    }

    private static Predicate<JsonNode> saying(String words) {
        return m -> "prose".equals(m.path("type").asText()) && m.path("text").asText().contains(words);
    }

    private static long lastSeq(List<JsonNode> seen) {
        long n = 0;
        for (var m : seen) n = Math.max(n, m.path("seq").asLong(0));
        return n;
    }

    private TestWebSocketClient watcher() throws Exception {
        var w = TestWebSocketClient.connect(server.baseUrl(), watcherToken, "en", ROOM);
        w.waitForRoomState(TIMEOUT);
        return w;
    }

    private TestWebSocketClient drifter(String clientKey) throws Exception {
        var d = TestWebSocketClient.connect(server.baseUrl(), drifterToken, "en", ROOM, clientKey);
        d.waitForRoomState(TIMEOUT);
        return d;
    }

    private static void settle(TestWebSocketClient w) throws Exception {
        Thread.sleep(600);
        w.drainMessages();
    }

    @Test
    void back_in_time_no_one_sees_a_departure_or_an_arrival_and_what_was_said_is_replayed() throws Exception {
        var key = UUID.randomUUID().toString();
        try (var watcher = watcher()) {
            var first = drifter(key);
            settle(watcher);
            Thread.sleep(300);
            long seen = lastSeq(first.drainMessages());

            first.drop();
            Thread.sleep(800);
            watcher.sendSay(ROOM, "the kettle is on");
            Thread.sleep(400);

            try (var back = drifter(key)) {
                back.send("{\"type\":\"reconnect\",\"id\":\"r1\",\"roomId\":\"" + ROOM + "\",\"lastSeenSeq\":" + seen + "}");
                assertThat(back.waitForMessage(saying("the kettle is on"), TIMEOUT))
                    .as("what was said while away is replayed").isNotNull();
                watcher.assertNoMessage(aboutTheDrifter(), Duration.ofSeconds(6));
            }
        }
    }

    @Test
    void not_back_in_time_they_leave() throws Exception {
        try (var watcher = watcher()) {
            var gone = drifter(UUID.randomUUID().toString());
            settle(watcher);

            gone.drop();

            assertThat(watcher.waitForMessage(aboutTheDrifter(), Duration.ofSeconds(12)))
                .as("the departure comes once the hold ends").isNotNull();
        }
    }

    @Test
    void leaving_on_purpose_is_not_held() throws Exception {
        try (var watcher = watcher()) {
            var leaving = drifter(UUID.randomUUID().toString());
            settle(watcher);

            leaving.sendCommand("quit", List.of());

            assertThat(watcher.waitForMessage(aboutTheDrifter(), Duration.ofSeconds(3)))
                .as("quit departs at once, inside the hold").isNotNull();
        }
    }

    @Test
    void a_tab_back_before_the_server_saw_its_old_socket_fail_takes_its_session_up() throws Exception {
        var key = UUID.randomUUID().toString();
        try (var watcher = watcher()) {
            var old = drifter(key);
            settle(watcher);

            try (var again = drifter(key)) {
                Thread.sleep(800);
                assertThat(old.closeCode()).as("the old socket is closed as moved").isEqualTo(4005);
                watcher.assertNoMessage(aboutTheDrifter(), Duration.ofSeconds(3));
                again.sendLook(ROOM);
                assertThat(again.waitForRoomState(TIMEOUT)).isNotNull();
            }
        }
    }

    @Test
    void a_second_tab_in_the_same_room_does_not_arrive_again() throws Exception {
        try (var watcher = watcher(); var one = drifter(UUID.randomUUID().toString())) {
            settle(watcher);

            try (var two = drifter(UUID.randomUUID().toString())) {
                watcher.assertNoMessage(aboutTheDrifter(), Duration.ofSeconds(2));
                assertThat(one.closeCode()).as("the first tab keeps its connection").isEqualTo(-1);
            }
            watcher.assertNoMessage(aboutTheDrifter(), Duration.ofSeconds(2));
        }
    }

    @Test
    void a_client_that_names_no_session_takes_up_its_held_place_by_its_kind() throws Exception {
        try (var watcher = watcher()) {
            var first = drifter(null);
            settle(watcher);

            first.drop();
            Thread.sleep(800);

            try (var back = drifter(null)) {
                watcher.assertNoMessage(aboutTheDrifter(), Duration.ofSeconds(6));
            }
        }
    }
}
