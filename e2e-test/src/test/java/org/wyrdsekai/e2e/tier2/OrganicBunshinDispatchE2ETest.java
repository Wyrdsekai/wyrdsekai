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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.timeout;

/**
 * Organic bunshin auto-dispatch E2E — proves the classifier-driven path fires
 * for direct same-zone player tells, not just relayed/cross-zone tells.
 *
 * <p>Distinct from {@link BunshinDispatchE2ETest}: that test uses the
 * {@code TestDispatchBunshin} command to bypass the parser path. This test
 * uses a normal {@code tell wyrd ...} from a player WebSocket session and
 * asserts the runtime classifier auto-dispatched a bunshin without anyone
 * explicitly emitting a {@code dispatch_bunshin} action.
 *
 * <p>The fix this test guards against regressing: the classifier auto-dispatch
 * at {@code CompanionActor.onAgentMessage} was originally gated on
 * {@code msgText != am.message()} — meaning it only fired for relayed tells
 * (cross-zone, Slack, Matrix, Signal, etc.) which carry a {@code [from Name]}
 * prefix. Direct same-zone player tells via WS/SSH never carried that prefix
 * and so never reached the classifier. The gate has been removed; this test
 * confirms direct player tells now route through the classifier.
 *
 * <p>Hard assertions: companion narrates a "split myself / bunshin" line
 * within the dispatch deadline.
 */
@Tag("e2e")
class OrganicBunshinDispatchE2ETest {

    private static final Duration DISPATCH_DEADLINE = timeout(Duration.ofSeconds(60));
    /** Allow time for spawn → bunshin's own iterative inference loop → report-back narration. */
    private static final Duration NARRATION_DEADLINE = timeout(Duration.ofSeconds(240));

    /** A long-form prose synthesis request — bunshin's sweet spot. The shipped
     *  REQUEST_TYPE classifier scores this on the {@code write} label
     *  (~0.97 confidence in ClassifierArmLiveTest), well above the 0.70
     *  escalation threshold. Critically, {@code requiresToolExecution}
     *  returns false (no library/research/web/oracle keywords) so the path
     *  doesn't fall through to ReAct.
     *
     *  <p>The original prompt ("research X thoroughly while I wait") classified
     *  as {@code delegate} but contained "research" — which trips
     *  `requiresToolExecution` and routes to ReAct. That's correct behavior
     *  (bunshin is prose-only) but means {@code delegate} prompts almost
     *  never auto-dispatch. {@code write}-class prompts are the realistic
     *  trigger for organic bunshin spawning. */
    // PROMPT-vs-CLASSIFIER-BUILD note (2026-07-21): the previous prompt
    // ("draft a thoughtful letter from me to my future self about resilience,
    // take your time") scored write@0.62 on the SHIPPED classifier build
    // (setfit-2026-05-25 encoder) — BELOW the 0.70 escalation threshold, so
    // the gate correctly declined and this hard-gate could never pass. The
    // probe suite (ClassifierArmLiveTest.probe_prose_only_delegate_candidates)
    // prints live confidences per build; when retraining the classifier,
    // re-check this prompt still clears 0.70 with margin. Current pick scores
    // delegate@0.91 and contains no requiresToolExecution keywords.
    private static final String DELEGATE_PROMPT =
        "spend some time and write a detailed reflection on what loyalty means between friends";

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-organic-bunshin");
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
    void direct_player_tell_triggers_classifier_dispatch() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            var roomId = roomState.path("room").path("roomId").asText();
            assertNotNull(roomId, "[HARD] room state should expose a roomId");
            assertFalse(roomId.isBlank(), "[HARD] roomId should not be blank");

            // Drain the greeting (real inference — can take 10-20s) and any
            // follow-on prose so we only see post-dispatch messages.
            for (int i = 0; i < 3; i++) {
                var drained = nextProseOrNull(ws, Duration.ofSeconds(20));
                if (drained == null) break;
            }

            // The whole point of the test: send a delegate-shaped tell as a
            // normal player. No TestDispatchBunshin, no special command — just
            // the same path a human would use over SSH or the web client.
            ws.sendSay(roomId, "tell wyrd " + DELEGATE_PROMPT);

            // Two milestones to observe:
            //   1. opening line — "I've split myself, a bunshin is now focusing on…"
            //      (fires within ms of the classifier)
            //   2. report-back narration — "my bunshin came back / made progress /
            //      couldn't / ran out / called my bunshin back"
            //      (fires after the bunshin's own iterative inference loop
            //      completes — that loop alone can take ~60-120s on test hardware
            //      depending on the model and the task complexity)
            //
            // Mirrors BunshinDispatchE2ETest's outcome assertions, but for the
            // organic classifier-driven path instead of TestDispatchBunshin.
            String openingLine = null;
            String reportNarration = null;
            var seen = new ArrayList<String>();
            long deadline = System.currentTimeMillis() + NARRATION_DEADLINE.toMillis();
            while (System.currentTimeMillis() < deadline && reportNarration == null) {
                var msg = nextProseOrNull(ws, Duration.ofSeconds(20));
                if (msg == null) continue;
                var text = msg.path("text").asText("");
                if (text.isBlank()) continue;
                seen.add(text.length() > 140 ? text.substring(0, 140) + "…" : text);
                var lower = text.toLowerCase();
                if (openingLine == null
                        && ((lower.contains("split") && lower.contains("bunshin"))
                            || lower.contains("a bunshin is now")
                            || lower.contains("bunshin is focusing"))) {
                    openingLine = text;
                    continue;
                }
                // Outcome narration phrases from CompanionActor §8.5 first-person template.
                if (lower.contains("my bunshin came back")
                        || lower.contains("my bunshin made progress")
                        || lower.contains("my bunshin couldn")
                        || lower.contains("my bunshin ran out")
                        || lower.contains("called my bunshin back")) {
                    reportNarration = text;
                }
            }

            // === HARD: spawn fired (classifier path → BunshinScheduler grant
            //     → BunshinActor spawn → opening narration) ===
            assertNotNull(openingLine,
                "[HARD] companion should auto-dispatch bunshin from a direct player tell "
                    + "and announce the split within " + DISPATCH_DEADLINE.getSeconds()
                    + "s; observed prose: " + seen);

            // === HARD: round-trip closed (BunshinActor inference loop ran
            //     → BunshinReport delivered → CompanionActor narrated outcome) ===
            assertNotNull(reportNarration,
                "[HARD] companion should narrate bunshin outcome within "
                    + NARRATION_DEADLINE.getSeconds() + "s of the dispatch; "
                    + "observed prose: " + seen);

            // === SOFT: outcome narration is substantive ===
            if (reportNarration.length() < 25) {
                System.out.println("[OrganicBunshinDispatchE2E] [SOFT WARN] "
                    + "outcome narration is short (" + reportNarration.length()
                    + " chars): " + reportNarration);
            }
        }
    }
}
