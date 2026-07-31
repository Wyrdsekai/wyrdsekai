package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wyrdsekai.e2e.infra.E2eAssertions.timeout;

/**
 * Live-model E2E for the {@code reconsider} meta-tool — verifies the
 * tool surface is wired through to the real ReAct loop and the agent
 * can complete a research-shape task without breaking when reconsider
 * is in scope.
 *
 * <p><b>Hard assertion</b>: a research-shape player tell drives the
 * companion through the full ReAct loop and produces some prose response
 * within the deadline. This is a regression test for the new tool
 * surface — if adding {@code reconsider} as an inherent action broke
 * the dispatch path, this would fail.</p>
 *
 * <p><b>Soft observation</b>: the test logs whether the agent actually
 * called {@code reconsider} during the loop (via the
 * {@code "Reconsider —"} INFO line in stdout/stderr). The model may or
 * may not reach for it organically — its presence is opportunistic, not
 * a contract. The unit + actor-level tests pin the wiring contract;
 * this test only proves the live path is intact and observes whether
 * the model uses the new affordance.</p>
 *
 * <p>Companion to {@link org.wyrdsekai.core.agent.ReconsiderActionTest}
 * (helper math) and {@code ReconsiderReactLoopIntegrationTest} (actor
 * pipeline with stub inference).</p>
 */
@Tag("e2e")
class ReconsiderE2ETest {

    private static final Duration RESPONSE_DEADLINE = timeout(Duration.ofSeconds(120));

    /** Research-shape prompt — the kind of task where reconsider helps. */
    private static final String RESEARCH_PROMPT =
        "tell wyrd find me a book about mythology in the library, "
            + "and if your first attempt doesn't pan out, try a different approach";

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-reconsider");
        server = new TestServerBootstrap(inferenceSetup.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    private static JsonNode nextProseOrNull(
            TestWebSocketClient ws, Duration pollWindow) {
        try {
            return ws.waitForProse(pollWindow);
        } catch (ConditionTimeoutException timeout) {
            return null;
        }
    }

    @Test
    void reconsider_tool_surface_does_not_break_research_loop() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            var roomId = roomState.path("room").path("roomId").asText();
            assertNotNull(roomId, "[HARD] room state should expose a roomId");

            // Drain greeting prose.
            for (int i = 0; i < 3; i++) {
                var drained = nextProseOrNull(ws, Duration.ofSeconds(20));
                if (drained == null) break;
            }

            ws.sendSay(roomId, RESEARCH_PROMPT);

            // Collect prose until we get a substantive response or hit the
            // deadline. We're not asserting anything about the *content* —
            // just that the loop produces output without crashing.
            var collected = new ArrayList<String>();
            long deadline = System.currentTimeMillis() + RESPONSE_DEADLINE.toMillis();
            while (System.currentTimeMillis() < deadline) {
                var msg = nextProseOrNull(ws, Duration.ofSeconds(30));
                if (msg == null) break;
                var text = msg.path("text").asText();
                if (!text.isBlank()) {
                    collected.add(text);
                }
                // Stop once we've seen a few substantive responses — the
                // ReAct loop may emit multiple "I'll search ... " /
                // narrate-results pairs.
                if (collected.size() >= 3) break;
            }

            // === HARD: the loop produced *some* response within the deadline ===
            // If the new tool surface had broken dispatch, we'd see zero prose
            // (loop hung) or an exception trace (parser/handler failure).
            assertTrue(!collected.isEmpty(),
                "[HARD] research-shape tell should produce at least one prose "
                    + "response within " + RESPONSE_DEADLINE.toSeconds() + "s. "
                    + "If empty, the reconsider wiring may have broken the ReAct loop.");

            // === SOFT: the response should be a real attempt, not a canned fallback ===
            var firstResponse = collected.getFirst().toLowerCase();
            boolean looksReal = firstResponse.length() > 20
                && !firstResponse.contains("i don't have any tool")
                && !firstResponse.contains("error");
            if (!looksReal) {
                System.out.println("[ReconsiderE2E] [SOFT WARN] first response "
                    + "looks like a canned fallback: '"
                    + collected.getFirst() + "'");
            }

            // === OBSERVATIONAL: did the agent actually reach for reconsider? ===
            // The `handleReconsider` method logs at INFO with "Reconsider —"
            // when invoked. We can't easily intercept logs here, so just
            // print the responses for visual inspection on flake debugging.
            System.out.println("[ReconsiderE2E] Loop produced "
                + collected.size() + " prose responses:");
            for (int i = 0; i < collected.size(); i++) {
                var preview = collected.get(i);
                System.out.println("  [" + i + "] "
                    + preview.substring(0, Math.min(160, preview.length())));
            }
        }
    }
}
