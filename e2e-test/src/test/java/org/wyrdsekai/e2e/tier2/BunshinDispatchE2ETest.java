package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Bunshin dispatch E2E — real inference through the full BunshinActor pipeline.
 *
 * <p>Verifies that when the companion dispatches a bunshin, the full runtime
 * path executes: {@code BunshinScheduler} grants a slot → {@code BunshinActor}
 * is spawned → runs iterative inference against the real LLM → emits a
 * {@code BunshinReport} → {@code CompanionActor.onBunshinReportReceived}
 * releases the slot, files the memory impression, and speaks a narration
 * beginning with "My bunshin …".
 *
 * <p>Uses the test-only {@code CompanionActor.TestDispatchBunshin} command to
 * bypass the LLM action-parser decision (the model may or may not choose to
 * emit a {@code dispatch_bunshin} action for any given prompt). Unit tests
 * cover the parser path; this test covers the runtime pipeline, which was
 * never exercised end-to-end with a live model before.
 *
 * <p>Hard assertions: companion announces the dispatch ("split myself") and
 * later narrates a bunshin outcome.
 * <p>Soft assertions: the narration matches the §8.5 first-person template
 * ("My bunshin came back" / "made progress" / "couldn't do the work" / ran
 * out of budget / called back).
 *
 * @see org.wyrdsekai.core.familiar.BunshinActor
 * @see org.wyrdsekai.core.familiar.BunshinScheduler
 */
@Tag("e2e")
class BunshinDispatchE2ETest {

    private static final String COMPANION_ID = "companion-wyrd";
    // Bunshin dispatch → spawn → inference loop → report can take a while.
    // 180s is generous; DISPATCH_WALL_CLOCK tightens the bunshin's own budget
    // so the inner loop exits even if the model is slow to emit a DONE marker.
    private static final Duration NARRATION_TIMEOUT = timeout(Duration.ofSeconds(180));
    private static final int DISPATCH_MAX_TOKENS = 192;
    private static final int DISPATCH_MAX_STEPS = 3;
    private static final int DISPATCH_WALL_CLOCK = 90;

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-bunshin");
        server = new TestServerBootstrap(inferenceSetup.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    /** Pull the next prose line, swallowing the awaitility timeout. */
    private static JsonNode nextProseOrNull(
            TestWebSocketClient ws, Duration pollWindow) {
        try {
            return ws.waitForProse(pollWindow);
        } catch (ConditionTimeoutException timeout) {
            return null;
        }
    }

    @Test
    void bunshin_dispatch_runs_to_narration() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            // Drain the greeting (real inference — can take 10-20s) and any
            // follow-on prose so we only see post-dispatch messages.
            for (int i = 0; i < 3; i++) {
                var drained = nextProseOrNull(ws, Duration.ofSeconds(30));
                if (drained == null) break;
            }

            // Fire the bunshin directly. The companion ref is registered by
            // ZoneGuardian at spawn time; same lookup other tier-2 tests use.
            var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
            assertNotNull(companion, "[HARD] companion actor ref not found for " + COMPANION_ID);
            companion.tell(new CompanionActor.TestDispatchBunshin(
                "In one sentence, name the most obvious exit from this room.",
                DISPATCH_MAX_TOKENS, DISPATCH_MAX_STEPS, DISPATCH_WALL_CLOCK));

            // Companion first says "I've split myself — a bunshin is now
            // focusing on…" right away. Then, when the bunshin returns, it
            // speaks the outcome narration. We need the *second* prose
            // message; the one that proves the round-trip closed.
            String initial = null;
            String narration = null;
            var seen = new ArrayList<String>();
            long deadline = System.currentTimeMillis() + NARRATION_TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline && narration == null) {
                var msg = nextProseOrNull(ws, Duration.ofSeconds(30));
                if (msg == null) continue;
                var text = msg.path("text").asText("");
                if (text.isBlank()) continue;
                seen.add(text.length() > 120 ? text.substring(0, 120) + "…" : text);
                var lower = text.toLowerCase();
                if (initial == null && lower.contains("split") && lower.contains("bunshin")) {
                    initial = text;
                    continue;
                }
                // Outcome narration phrases from CompanionActor §4058 switch.
                if (lower.contains("my bunshin came back")
                    || lower.contains("my bunshin made progress")
                    || lower.contains("my bunshin couldn")
                    || lower.contains("my bunshin ran out")
                    || lower.contains("called my bunshin back")) {
                    narration = text;
                }
            }

            // === HARD: we saw the "split myself" opener (scheduler granted) ===
            assertNotNull(initial,
                "[HARD] companion should announce 'split myself' after dispatch; "
                    + "observed prose: " + seen);
            // === HARD: we saw the return narration (bunshin ran + reported back) ===
            assertNotNull(narration,
                "[HARD] companion should narrate bunshin outcome within "
                    + NARRATION_TIMEOUT.getSeconds() + "s; observed prose: " + seen);

            // === SOFT: narration is substantive, not an empty template ===
            if (narration.length() < 25) {
                System.out.println("[BunshinDispatchE2E] [SOFT WARN] narration is short ("
                    + narration.length() + " chars): " + narration);
            }
        }
    }
}
