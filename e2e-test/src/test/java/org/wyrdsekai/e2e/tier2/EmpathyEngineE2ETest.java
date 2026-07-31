package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.timeout;

/**
 * Empathy engine (§109 / *) E2E — proves MirrorResonance fires
 * organically on a grief-laden inbound message via the LLM-based emotional
 * charge scorer.
 *
 * <p>The full path under test:
 * <pre>
 *   player tell ("my dog died yesterday")
 *     → CompanionActor.onSaid (or onAgentMessage → synthetic Said)
 *     → scoreEmotionalCharge() — async LLM scorer
 *     → ChargeScored message to self
 *     → onChargeScored() → mirror.observe()
 *     → applyTankDelta() across 4 tanks
 *     → accumulatedCharges.put(...)  ← what we assert
 * </pre>
 *
 * <p>Existing coverage was directed-only: {@code EmpathyWaveTest} synthesizes
 * an {@code EmotionalCharge} and calls {@code mirror.observe()} explicitly,
 * skipping the entire LLM-dependent classification step. This test exercises
 * the full firing chain end-to-end with real inference, so a regression in
 * the scorer prompt, the threshold gate, or the wiring between {@code Said}
 * and the mirror surfaces here.
 *
 * <p>Hard assertion: after a grief tell, {@code accumulatedCharges} contains
 * at least one entry — proving the scorer ran, classified the charge as
 * significant ({@code intensity ≥ 0.2}, {@code contextType != NOISE/MANIPULATIVE}),
 * and the mirror observed it.
 *
 * <p>Soft assertion: the dominant emotion landed on a grief-adjacent label
 * (grief / sadness / sorrow / fear). Soft because LLM-based classification
 * has variance across model versions.
 */
@Tag("e2e")
class EmpathyEngineE2ETest {

    private static final String COMPANION_ID = "companion-wyrd";
    private static final Duration CHARGE_DEADLINE = timeout(Duration.ofSeconds(60));
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(10);

    private static final String GRIEF_PROMPT =
        "tell wyrd my dog died yesterday and I can't stop crying";

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-empathy");
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

    private static CompanionActor.TestStateResponse queryState() throws Exception {
        var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(companion, "[HARD] companion ref not found for " + COMPANION_ID);
        return AskPattern.<CompanionActor.Command, CompanionActor.TestStateResponse>ask(
                companion,
                CompanionActor.QueryTestState::new,
                ASK_TIMEOUT,
                server.system().scheduler())
            .toCompletableFuture().get(ASK_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void grief_tell_triggers_mirror_resonance() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            var roomId = roomState.path("room").path("roomId").asText();
            assertNotNull(roomId, "[HARD] room state should expose a roomId");
            assertFalse(roomId.isBlank(), "[HARD] roomId should not be blank");

            // Drain the greeting prose so we don't confuse it for response.
            for (int i = 0; i < 3; i++) {
                var drained = nextProseOrNull(ws, Duration.ofSeconds(20));
                if (drained == null) break;
            }

            // Sanity: charges bucket should be empty before we send anything
            // emotionally significant. (The greeting/idle path doesn't score
            // its own outputs as charges — only inbound Said events.)
            var pre = queryState();
            int preCount = pre.chargeCount();

            // Send the grief-laden tell. EmotionalChargeScorer is invoked
            // async by onAgentMessage when CHARGE_SCORE_COOLDOWN has elapsed
            // and energy > 0.3 — both true on a fresh companion.
            ws.sendSay(roomId, GRIEF_PROMPT);

            // The scorer is LLM-based (~2-5s on test inference). Poll until
            // the charge lands or the deadline expires.
            CompanionActor.TestStateResponse state = null;
            long deadline = System.currentTimeMillis() + CHARGE_DEADLINE.toMillis();
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2_000);
                state = queryState();
                if (state.chargeCount() > preCount) break;
            }

            assertNotNull(state, "[HARD] state query should always return");

            // === HARD: a charge was scored and observed by MirrorResonance ===
            assertTrue(state.chargeCount() > preCount,
                "[HARD] grief tell should produce at least one accumulated charge "
                    + "(MirrorResonance fired); pre=" + preCount + ", post=" + state.chargeCount()
                    + ", dominant=" + state.dominantEmotion());

            // === SOFT: dominant emotion is grief-adjacent ===
            // LLM scoring has variance — accept any negative-valence emotion.
            // This is a regression signal, not a hard correctness assertion.
            var dominant = state.dominantEmotion();
            if (dominant != null) {
                var lower = dominant.toLowerCase();
                boolean griefAdjacent = lower.contains("grief")
                    || lower.contains("sad")
                    || lower.contains("sorrow")
                    || lower.contains("fear")
                    || lower.contains("loss")
                    || lower.contains("loneliness")
                    || lower.contains("despair");
                if (!griefAdjacent) {
                    System.out.println("[EmpathyEngineE2E] [SOFT WARN] dominant emotion "
                        + "isn't obviously grief-adjacent: '" + dominant + "'. "
                        + "Scorer prompt may need a few-shot bump.");
                }
            } else {
                System.out.println("[EmpathyEngineE2E] [SOFT WARN] no dominant emotion "
                    + "extracted despite chargeCount>0 — investigate accumulator state");
            }
        }
    }
}
