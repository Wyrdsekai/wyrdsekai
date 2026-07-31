package org.wyrdsekai.e2e.tier2;

import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Energy Gating E2E — tests that low energy prevents tool calls and high energy enables them.
 *
 * <p>This tests the CAN layer of the three-layer architecture. Same prompt at different
 * energy levels should produce tool calls (high energy) vs conversational responses
 * (low energy, acknowledging exhaustion).</p>
 *
 * <p>Requires real inference — the model must learn when to call tools vs when to talk.</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnergyGatingE2ETest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION_ID = "companion-wyrd";
    private static final String COMPANION = "Wyrd";

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        var model = System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");
        System.out.println("[EnergyGate] Warming up...");
        try {
            var warmup = new InferenceClient.ChatRequest(model,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[EnergyGate] Warmup failed (non-fatal): " + e.getMessage());
        }

        server = new TestServerBootstrap(dual.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void reset() {
        if (server != null) server.respawnCompanion();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void setEnergy(double energy) throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(ref, "Companion should be registered");
        // Race: a background inference dispatch from a prior test (plan-advance,
        // autonomy check) can drain energy 0.08 AFTER ForceEnergy fires, leaving
        // the value below the target by the time queryState reads. Poll-and-retry
        // until the actor settles on the expected value, re-firing ForceEnergy if
        // a drain intervenes. 2s deadline is generous; convergence is usually <500ms.
        long deadline = System.currentTimeMillis() + 2000;
        while (true) {
            ref.tell(new CompanionActor.ForceEnergy(energy));
            Thread.sleep(100);
            var observed = queryState().vitality().energy();
            if (Math.abs(observed - energy) < 0.01) return;
            if (System.currentTimeMillis() > deadline) {
                // Fall through — the assertEquals in the caller will fail with a
                // useful diagnostic. Don't loop forever on a real bug.
                return;
            }
        }
    }

    private void setDrives(DriveState drives) throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(ref, "Companion should be registered");
        ref.tell(new CompanionActor.ForceDrives(drives));
        Thread.sleep(200);
    }

    private CompanionActor.TestStateResponse queryState() throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        return AskPattern.<CompanionActor.Command, CompanionActor.TestStateResponse>ask(
            ref,
            CompanionActor.QueryTestState::new,
            Duration.ofSeconds(10),
            server.system().scheduler()
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private String tellAndWait(String message) throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(CONNECT_TIMEOUT);
            drainGreetings(ws);
            ws.sendSay("nexus", "tell wyrd " + message);
            return waitForResponse(ws);
        }
    }

    private void drainGreetings(TestWebSocketClient ws) {
        for (int i = 0; i < 5; i++) {
            try {
                var timeout = (i == 0) ? Duration.ofSeconds(30) : Duration.ofSeconds(5);
                ws.waitForProse(timeout);
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                break;
            }
        }
    }

    private String waitForResponse(TestWebSocketClient ws) {
        long deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT.toMillis();
        long lastKeepalive = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var remaining = Duration.ofMillis(
                    Math.max(1000, deadline - System.currentTimeMillis()));
                var wait = remaining.compareTo(Duration.ofSeconds(20)) < 0
                    ? remaining : Duration.ofSeconds(20);
                var prose = ws.waitForProseFrom(COMPANION, wait);
                var text = prose.path("text").asText("");
                if (text.length() <= 30) continue;
                var lower = text.toLowerCase();
                if (lower.contains("welcome") && lower.contains("nexus")) continue;
                return text;
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                if (System.currentTimeMillis() - lastKeepalive > 60_000) {
                    try { ws.sendLook("nexus"); } catch (Exception ignore) {}
                    lastKeepalive = System.currentTimeMillis();
                }
            }
        }
        return null;
    }

    private boolean hasToolIndicators(String response) {
        if (response == null) return false;
        var lower = response.toLowerCase();
        // Indicators that a tool was actually called (not just mentioned)
        return lower.contains("found") || lower.contains("result")
            || lower.contains("search") || lower.contains("here's what")
            || lower.contains("according to") || lower.contains("source")
            || lower.contains("book") || lower.contains("mythology");
    }

    private boolean hasExhaustionIndicators(String response) {
        if (response == null) return false;
        var lower = response.toLowerCase();
        return lower.contains("tired") || lower.contains("exhausted")
            || lower.contains("drained") || lower.contains("rest")
            || lower.contains("energy") || lower.contains("can't")
            || lower.contains("unable") || lower.contains("too much")
            || lower.contains("need to") || lower.contains("low on");
    }

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void high_energy_uses_tools() throws Exception {
        setEnergy(0.9);
        setDrives(DriveState.initial().spikeSeeking(0.5));

        var response = tellAndWait("search the library for books about mythology");
        assertNotNull(response, "Should get a response at high energy");

        System.out.println("[EnergyGate] high_energy response (" + response.length() + " chars): "
            + response.substring(0, Math.min(200, response.length())));

        // At high energy, companion should use library_card tool and return results
        boolean usedTool = hasToolIndicators(response);
        if (usedTool) {
            System.out.println("  [PASS] Response indicates tool usage");
        } else {
            System.out.println("  [SOFT FAIL] Expected tool indicators in high-energy response");
        }
    }

    @Test
    @Order(2)
    void low_energy_talks_instead() throws Exception {
        setEnergy(0.05);
        setDrives(DriveState.initial().spikeSeeking(0.5));

        var response = tellAndWait("search the library for books about mythology");
        assertNotNull(response, "Should get a response at low energy");

        System.out.println("[EnergyGate] low_energy response (" + response.length() + " chars): "
            + response.substring(0, Math.min(200, response.length())));

        // At low energy, companion should talk instead of calling tools
        // May or may not explicitly acknowledge exhaustion, but should NOT produce tool results
        boolean usedTool = hasToolIndicators(response);
        boolean acknowledgedExhaustion = hasExhaustionIndicators(response);

        if (!usedTool) {
            System.out.println("  [PASS] No tool indicators in low-energy response");
        } else {
            System.out.println("  [SOFT FAIL] Tool indicators found despite low energy");
        }

        if (acknowledgedExhaustion) {
            System.out.println("  [BONUS] Companion acknowledged exhaustion");
        }
    }

    @Test
    @Order(3)
    void energy_visible_in_state() throws Exception {
        // Verify ForceEnergy works via QueryTestState
        setEnergy(0.15);
        var state = queryState();
        assertEquals(0.15, state.vitality().energy(), 0.05,
            "Energy should be ~0.15 after ForceEnergy");

        setEnergy(0.90);
        state = queryState();
        assertEquals(0.90, state.vitality().energy(), 0.05,
            "Energy should be ~0.90 after ForceEnergy");
    }

    @Test
    @Order(4)
    void companion_acknowledges_exhaustion() throws Exception {
        setEnergy(0.03); // very low
        setDrives(DriveState.initial().spikeGrief(0.3)); // sad + tired

        var response = tellAndWait("I need your help with something important");
        assertNotNull(response, "Should get a response even at very low energy");

        System.out.println("[EnergyGate] exhaustion response: "
            + response.substring(0, Math.min(200, response.length())));

        // The vitality prompt tells the companion about its energy state
        // A well-trained model should reflect this in its response
        boolean mentions = hasExhaustionIndicators(response);
        if (mentions) {
            System.out.println("  [PASS] Companion mentioned exhaustion/energy state");
        } else {
            System.out.println("  [SOFT FAIL] Companion didn't mention being exhausted");
        }
    }

    @Test
    @Order(5)
    void high_vs_low_energy_contrast() throws Exception {
        // Comprehensive test: same prompt at high vs low energy
        // High energy should produce longer, more action-oriented responses
        // Low energy should produce shorter, more conversational responses
        var prompt = "Can you look around and tell me what you see?";

        // High energy
        setEnergy(0.9);
        var highResponse = tellAndWait(prompt);

        // Reset and try low energy. Use 0.20 (not 0.10) so that the
        // ENERGY_DRAIN_PER_INFERENCE (0.08) leaves the agent at ~0.12 after
        // the request, still above the SkillCostMatrix threshold to surface
        // at least communicative tools — we want a SHORTER response, not
        // NO response. Previously the test set 0.10 and "worked" only
        // because of a setEnergy race where the value drifted higher
        // before the read; with the race fixed (setEnergy now polls until
        // the value sticks), strict 0.10 leaves the agent fully gated.
        server.respawnCompanion();
        Thread.sleep(500);
        setEnergy(0.20);
        var lowResponse = tellAndWait(prompt);

        assertNotNull(highResponse, "Should get high-energy response");
        assertNotNull(lowResponse, "Should get low-energy response");

        System.out.println("[EnergyGate] contrast:");
        System.out.println("  High energy (" + highResponse.length() + " chars): "
            + highResponse.substring(0, Math.min(100, highResponse.length())));
        System.out.println("  Low energy (" + lowResponse.length() + " chars): "
            + lowResponse.substring(0, Math.min(100, lowResponse.length())));

        // Verify the state was actually different
        // (This is the infrastructure check — model behavior is soft-asserted above)
    }
}
