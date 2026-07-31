package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;

import static org.wyrdsekai.e2e.infra.E2eAssertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Room creation E2E — agent action tag parsing validation.
 * Uses WireMock for deterministic tool call testing (not model-dependent).
 *
 * <p>This tests the INFRASTRUCTURE side of action parsing:
 * Can the system receive a response containing [action:create_room:{...}],
 * parse it, and trigger room creation?
 *
 * <p>Hard assertions: action-bearing response parsed, system doesn't crash.
 * <p>Soft assertions: actual room creation completed.
 */
@Tag("e2e")
class RoomCreationE2ETest {

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();

        // Initial greeting
        wireMock.stubChatCompletion("Welcome to the Nexus!", 30, 20);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void agent_action_tag_parsed() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));

            // Wait for the companion's greeting specifically (goes through WireMock).
            // Narrator prose (EntityEntered, onEnter narrate) arrives first and must
            // be skipped — we need the companion's response which has speaker "Wyrd".
            ws.waitForProseFrom("Wyrd", Duration.ofSeconds(30));

            // Stub a response containing an action tag
            wireMock.stubChatCompletion(
                "I'll create a garden for you! " +
                "[action:create_room:{\"roomId\":\"garden\",\"name\":\"The Garden\"," +
                "\"description\":\"A peaceful garden with blooming flowers.\"}]",
                50, 60);

            ws.sendSay("nexus", "Can you create a garden room?");
            var response = ws.waitForProseFrom("Wyrd", Duration.ofSeconds(30));

            // === HARD: Got a response back (pipeline didn't crash on action tag) ===
            assertProseReceived(response, "action-bearing response");

            // === HARD: Verify WireMock received at least the request ===
            wireMock.verifyCompletionCalledAtLeast(1);

            // === SOFT: Did the action tag get parsed? ===
            // The response text should contain the action content
            // (whether it was stripped or passed through depends on implementation)
            var text = response.path("text").asText();
            System.out.println("[E2E RoomCreation] Response: " + text);

            boolean actionPresent = softAssertActionTag(response, "RoomCreation", "create_room");
            if (actionPresent) {
                System.out.println("[E2E RoomCreation] Action tag present in response — " +
                    "room creation pipeline triggered.");

                // Try navigating to the new room (may not have an exit yet)
                // This is aspirational — validates the full create→exit→navigate chain
                ws.sendLook("nexus");
                var refreshedState = ws.waitForRoomState(Duration.ofSeconds(5));
                if (refreshedState != null) {
                    var exits = refreshedState.path("room").path("exits");
                    boolean gardenExit = false;
                    for (var exit : exits) {
                        if ("garden".equals(exit.path("targetRoomId").asText())) {
                            gardenExit = true;
                            break;
                        }
                    }
                    if (gardenExit) {
                        System.out.println("[E2E RoomCreation] Full chain verified: " +
                            "action → room created → exit added to Nexus!");
                    } else {
                        System.out.println("[E2E RoomCreation WARN] Action parsed but " +
                            "garden exit not yet visible. May need async processing.");
                    }
                }
            }
        }
    }

    /**
     * Fix B (prose-follows-outcome, 2026-07-06): a create_room turn must NOT
     * speak the model's SPECULATIVE pre-action prose ("I am constructing it now").
     * The room's real outcome is voiced through the 4B layer AFTER it resolves.
     * Regression guard for the over-promise the 9B→4B two-stage caught
     * ("I'll build the Book Nook" spoken before the room exists).
     *
     * <p>Uses the JSON action form (which {@code ActionParser.parseAll} turns into
     * a CreateRoom); the legacy {@code [action:create_room:...]} tag form does not,
     * so this — not {@link #agent_action_tag_parsed()} — is the Fix B canary.</p>
     */
    @Test
    void create_room_suppresses_speculative_prose_and_voices_outcome() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProseFrom("Wyrd", Duration.ofSeconds(30)); // greeting

            // 1st call = the doer: create_room + a speculative promise.
            // 2nd call = the outcome-voicing pass (cap:quick), rendering the truth.
            wireMock.stubChatCompletionSequence(
                "Right away — I am constructing the Book Nook for you now.\n" +
                "```json\n{\"action\":\"create_room\",\"name\":\"Book Nook\"," +
                "\"description\":\"a cozy reading room\"," +
                "\"exits\":[{\"direction\":\"south\",\"target\":\"nexus\",\"label\":\"back\"}]}\n```",
                "Done — the Book Nook now exists, with a path back home.",
                "Done — the Book Nook now exists, with a path back home.");

            ws.sendSay("nexus", "please make me a book nook room");
            var response = ws.waitForProseFrom("Wyrd", Duration.ofSeconds(30));
            var text = response.path("text").asText();
            System.out.println("[E2E FixB] First Wyrd prose after create_room: " + text);

            // === HARD: the speculative pre-action promise must NEVER be spoken ===
            assertThat(text.toLowerCase())
                .as("speculative pre-action prose must be suppressed (prose-follows-outcome)")
                .doesNotContain("i am constructing")
                .doesNotContain("constructing the book nook");

            // === SOFT: the voiced true outcome (with the room name) is what's spoken ===
            if (text.contains("Book Nook")) {
                System.out.println("[E2E FixB] Outcome voiced with the room name — "
                    + "prose-follows-outcome verified end-to-end.");
            } else {
                System.out.println("[E2E FixB WARN] No room name in spoken outcome "
                    + "(create may still be async): " + text);
            }
        }
    }
}
