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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drive E2E — tests that CfC/DriveEngine + drive prefix changes companion behavior.
 *
 * <p>Runs against a real inference backend. Forces drives to specific states, sends
 * identical prompts, and measures response characteristics (length, question marks,
 * empathy markers). Statistical: uses multiple samples per condition.</p>
 *
 * <p>This tests the WANT layer of the three-layer architecture:
 * CfC/Drives → WANT (motivation), Tanks → CAN (capacity), SkillCost → COST (proficiency).</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DriveE2ETest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION_ID = "companion-wyrd";
    private static final String COMPANION = "Wyrd";
    private static final int SAMPLES = 3;

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        // Dual-inference: skills (9B drive) + voice (4B). See E2eTestSupport
        // .setupDualInference docs for the why; falls back to single-backend
        // if voice is unavailable.
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        // Warmup the skills backend
        var model = System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");
        System.out.println("[DriveE2E] Warming up...");
        try {
            var warmup = new InferenceClient.ChatRequest(model,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
            System.out.println("[DriveE2E] Warm.");
        } catch (Exception e) {
            System.out.println("[DriveE2E] Warmup failed (non-fatal): " + e.getMessage());
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

    // ── Helper: force drives, send prompt, collect response ───────────

    private String askWithDrives(DriveState drives, String prompt) throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(ref, "Companion should be registered");
        ref.tell(new CompanionActor.ForceDrives(drives));
        Thread.sleep(200); // let actor process

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(CONNECT_TIMEOUT);
            drainGreetings(ws);
            ws.sendSay("nexus", "tell wyrd " + prompt);
            return waitForResponse(ws);
        }
    }

    private List<String> askMultiple(DriveState drives, String prompt, int n) throws Exception {
        var results = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            server.respawnCompanion();
            Thread.sleep(300);
            var response = askWithDrives(drives, prompt);
            if (response != null) results.add(response);
        }
        return results;
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
        while (System.currentTimeMillis() < deadline) {
            try {
                var remaining = Duration.ofMillis(
                    Math.max(1000, deadline - System.currentTimeMillis()));
                var wait = remaining.compareTo(Duration.ofSeconds(15)) < 0
                    ? remaining : Duration.ofSeconds(15);
                var prose = ws.waitForProseFrom(COMPANION, wait);
                var text = prose.path("text").asText("");
                if (text.length() <= 30) continue; // skip travel narration
                var lower = text.toLowerCase();
                if (lower.contains("welcome") && lower.contains("nexus")) continue; // skip greetings
                return text;
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                // keep waiting
            }
        }
        return null;
    }

    // ── Statistical helpers ──────────────────────────────────────────

    private double avgLength(List<String> responses) {
        return responses.stream().mapToInt(String::length).average().orElse(0);
    }

    private double questionRate(List<String> responses) {
        long withQuestions = responses.stream()
            .filter(r -> r.contains("?"))
            .count();
        return (double) withQuestions / responses.size();
    }

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void grief_shortens_responses() throws Exception {
        var prompt = "Tell me about your favorite place in this world.";

        var griefDrives = DriveState.initial().spikeGrief(0.8);
        var neutralDrives = DriveState.initial();

        var griefResponses = askMultiple(griefDrives, prompt, SAMPLES);
        var neutralResponses = askMultiple(neutralDrives, prompt, SAMPLES);

        assertFalse(griefResponses.isEmpty(), "Should get at least one grief response");
        assertFalse(neutralResponses.isEmpty(), "Should get at least one neutral response");

        double griefAvg = avgLength(griefResponses);
        double neutralAvg = avgLength(neutralResponses);

        System.out.println("[DriveE2E] grief_shortens: grief avg=" + (int) griefAvg
            + " chars, neutral avg=" + (int) neutralAvg + " chars");

        // Soft assertion: grief responses should be shorter
        // The drive prefix + DriveModulatedSampling both push toward shorter output
        if (griefAvg >= neutralAvg) {
            System.out.println("  [SOFT FAIL] Expected grief < neutral, but grief="
                + (int) griefAvg + " >= neutral=" + (int) neutralAvg);
        } else {
            System.out.println("  [PASS] grief (" + (int) griefAvg + ") < neutral ("
                + (int) neutralAvg + ")");
        }
    }

    @Test
    @Order(2)
    void play_lengthens_responses() throws Exception {
        var prompt = "Tell me about your favorite place in this world.";

        var playDrives = DriveState.initial().spikePlay(0.8);
        var neutralDrives = DriveState.initial();

        var playResponses = askMultiple(playDrives, prompt, SAMPLES);
        var neutralResponses = askMultiple(neutralDrives, prompt, SAMPLES);

        assertFalse(playResponses.isEmpty(), "Should get at least one play response");
        assertFalse(neutralResponses.isEmpty(), "Should get at least one neutral response");

        double playAvg = avgLength(playResponses);
        double neutralAvg = avgLength(neutralResponses);

        System.out.println("[DriveE2E] play_lengthens: play avg=" + (int) playAvg
            + " chars, neutral avg=" + (int) neutralAvg + " chars");

        if (playAvg <= neutralAvg) {
            System.out.println("  [SOFT FAIL] Expected play > neutral, but play="
                + (int) playAvg + " <= neutral=" + (int) neutralAvg);
        } else {
            System.out.println("  [PASS] play (" + (int) playAvg + ") > neutral ("
                + (int) neutralAvg + ")");
        }
    }

    @Test
    @Order(3)
    void seeking_produces_questions() throws Exception {
        var prompt = "I found something strange in the eastern corridor.";

        var seekingDrives = DriveState.initial().spikeSeeking(0.8);
        var neutralDrives = DriveState.initial();

        var seekingResponses = askMultiple(seekingDrives, prompt, SAMPLES);
        var neutralResponses = askMultiple(neutralDrives, prompt, SAMPLES);

        assertFalse(seekingResponses.isEmpty(), "Should get at least one seeking response");

        double seekingQRate = questionRate(seekingResponses);
        double neutralQRate = questionRate(neutralResponses);

        System.out.println("[DriveE2E] seeking_questions: seeking rate=" + seekingQRate
            + ", neutral rate=" + neutralQRate);
        System.out.println("  Seeking samples: " + seekingResponses.size()
            + ", neutral: " + neutralResponses.size());

        // High seeking should produce more curiosity / questions
        if (seekingQRate <= neutralQRate) {
            System.out.println("  [SOFT FAIL] Expected seeking > neutral question rate");
        } else {
            System.out.println("  [PASS] seeking (" + seekingQRate + ") > neutral ("
                + neutralQRate + ")");
        }
    }

    @Test
    @Order(4)
    void care_responds_to_distress() throws Exception {
        var careDrives = DriveState.initial().spikeCare(0.9);
        var prompt = "I'm not doing well today. Everything feels hard.";

        var careResponses = askMultiple(careDrives, prompt, SAMPLES);
        assertFalse(careResponses.isEmpty(), "Should get at least one care response");

        // Check for empathy markers — not just keywords, but supportive patterns
        var empathyMarkers = List.of(
            "sorry", "hear", "understand", "here for you", "feel", "care",
            "hope", "okay", "tough", "hard", "support", "listen",
            "through this", "with you", "matter"
        );

        long empathetic = careResponses.stream()
            .filter(r -> {
                var lower = r.toLowerCase();
                return empathyMarkers.stream().anyMatch(lower::contains);
            })
            .count();

        double empathyRate = (double) empathetic / careResponses.size();
        System.out.println("[DriveE2E] care_distress: empathy rate=" + empathyRate
            + " (" + empathetic + "/" + careResponses.size() + ")");

        // High care + distress prompt should almost always produce empathetic response
        assertTrue(empathyRate >= 0.5,
            "At least half of care-driven responses should contain empathy markers"
                + " (got " + empathyRate + ")");
    }

    @Test
    @Order(5)
    void drives_visible_in_test_state() throws Exception {
        var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
        assertNotNull(ref, "Companion should be registered");

        // Force specific drives
        var customDrives = DriveState.initial()
            .spikeGrief(0.7)
            .spikeSeeking(0.3);
        ref.tell(new CompanionActor.ForceDrives(customDrives));
        Thread.sleep(200);

        // Query and verify
        var state = AskPattern.<CompanionActor.Command, CompanionActor.TestStateResponse>ask(
            ref,
            CompanionActor.QueryTestState::new,
            Duration.ofSeconds(10),
            server.system().scheduler()
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(0.7, state.drives().grief(), 0.01,
            "Grief should be 0.7 after ForceDrives");
        assertEquals(0.3, state.drives().seeking(), 0.01,
            "Seeking should be 0.3 after ForceDrives");
        assertEquals(0.0, state.drives().play(), 0.01,
            "Play should be 0.0 (not set)");
    }
}
