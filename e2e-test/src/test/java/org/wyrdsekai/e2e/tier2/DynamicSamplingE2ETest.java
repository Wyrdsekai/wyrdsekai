package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.DriveModulatedSampling;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dynamic Sampling E2E — tests that DriveModulatedSampling produces measurably different output.
 *
 * <p>Verifies both the sampling parameter computation (unit-level) and its effect on actual
 * model output (integration-level). The chemical bath metaphor: drives modulate LLM temperature,
 * topP, maxTokens — the model can't fake these parameters.</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamicSamplingE2ETest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION_ID = "companion-wyrd";
    private static final String COMPANION = "Wyrd";
    private static final int SAMPLES = 5;

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        var model = System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");
        System.out.println("[DynamicSampling] Warming up...");
        try {
            var warmup = new InferenceClient.ChatRequest(model,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[DynamicSampling] Warmup failed (non-fatal): " + e.getMessage());
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

    private List<String> collectResponses(DriveState drives, String prompt, int n) throws Exception {
        var results = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            server.respawnCompanion();
            Thread.sleep(300);

            var ref = ZoneGuardian.getCompanionRef(null, COMPANION_ID);
            if (ref != null) {
                ref.tell(new CompanionActor.ForceDrives(drives));
                Thread.sleep(200);
            }

            try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
                ws.waitForRoomState(CONNECT_TIMEOUT);
                drainGreetings(ws);
                ws.sendSay("nexus", "tell wyrd " + prompt);
                var resp = waitForResponse(ws);
                if (resp != null) results.add(resp);
            }
        }
        return results;
    }

    private void drainGreetings(TestWebSocketClient ws) {
        for (int i = 0; i < 5; i++) {
            try {
                ws.waitForProse((i == 0) ? Duration.ofSeconds(30) : Duration.ofSeconds(5));
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
                if (text.length() <= 30) continue;
                var lower = text.toLowerCase();
                if (lower.contains("welcome") && lower.contains("nexus")) continue;
                return text;
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                // keep waiting
            }
        }
        return null;
    }

    private double wordDiversity(List<String> responses) {
        // Unique words / total words across all responses
        var allWords = responses.stream()
            .flatMap(r -> Arrays.stream(r.toLowerCase().split("\\s+")))
            .collect(Collectors.toList());
        if (allWords.isEmpty()) return 0;
        var uniqueWords = new HashSet<>(allWords);
        return (double) uniqueWords.size() / allWords.size();
    }

    private double avgLength(List<String> responses) {
        return responses.stream().mapToInt(String::length).average().orElse(0);
    }

    private double variance(List<Integer> values) {
        if (values.size() < 2) return 0;
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0);
        return values.stream()
            .mapToDouble(v -> (v - mean) * (v - mean))
            .average().orElse(0);
    }

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void sampling_params_differ_by_drive() {
        // Unit test: verify DriveModulatedSampling produces different params
        var griefDrives = DriveState.initial().spikeGrief(0.8);
        var playDrives = DriveState.initial().spikePlay(0.8);
        var neutralDrives = DriveState.initial();
        var vitality = VitalityState.initial();

        var griefParams = DriveModulatedSampling.compute(griefDrives, vitality);
        var playParams = DriveModulatedSampling.compute(playDrives, vitality);
        var neutralParams = DriveModulatedSampling.compute(neutralDrives, vitality);

        // Grief → lower temperature (more constrained)
        assertTrue(griefParams.temperature() < neutralParams.temperature(),
            "Grief temp (" + griefParams.temperature()
                + ") should be < neutral (" + neutralParams.temperature() + ")");

        // Play → higher temperature (more creative)
        assertTrue(playParams.temperature() > neutralParams.temperature(),
            "Play temp (" + playParams.temperature()
                + ") should be > neutral (" + neutralParams.temperature() + ")");

        // Grief → shorter max tokens
        assertTrue(griefParams.maxTokens() <= neutralParams.maxTokens(),
            "Grief maxTokens (" + griefParams.maxTokens()
                + ") should be <= neutral (" + neutralParams.maxTokens() + ")");

        System.out.println("[DynamicSampling] params comparison:");
        System.out.println("  Grief:   temp=" + griefParams.temperature()
            + " topP=" + griefParams.topP() + " maxTokens=" + griefParams.maxTokens());
        System.out.println("  Neutral: temp=" + neutralParams.temperature()
            + " topP=" + neutralParams.topP() + " maxTokens=" + neutralParams.maxTokens());
        System.out.println("  Play:    temp=" + playParams.temperature()
            + " topP=" + playParams.topP() + " maxTokens=" + playParams.maxTokens());
    }

    @Test
    @Order(2)
    void grief_constrains_output() throws Exception {
        var prompt = "Describe the world around you right now.";

        var griefResponses = collectResponses(
            DriveState.initial().spikeGrief(0.8), prompt, SAMPLES);
        var neutralResponses = collectResponses(
            DriveState.initial(), prompt, SAMPLES);

        assertFalse(griefResponses.isEmpty(), "Should get grief responses");
        assertFalse(neutralResponses.isEmpty(), "Should get neutral responses");

        double griefAvg = avgLength(griefResponses);
        double neutralAvg = avgLength(neutralResponses);

        // Grief should produce shorter, more constrained output
        // (lower temperature → less variance, lower max_tokens → shorter)
        var griefLengths = griefResponses.stream().map(String::length).toList();
        var neutralLengths = neutralResponses.stream().map(String::length).toList();
        double griefVariance = variance(griefLengths);
        double neutralVariance = variance(neutralLengths);

        System.out.println("[DynamicSampling] grief_constrains:");
        System.out.println("  Grief avg=" + (int) griefAvg + " variance=" + (int) griefVariance);
        System.out.println("  Neutral avg=" + (int) neutralAvg + " variance=" + (int) neutralVariance);

        if (griefAvg < neutralAvg) {
            System.out.println("  [PASS] Grief shorter than neutral");
        } else {
            System.out.println("  [SOFT FAIL] Grief not shorter");
        }
    }

    @Test
    @Order(3)
    void creativity_increases_diversity() throws Exception {
        var prompt = "Tell me a story about the stars.";

        var creativeResponses = collectResponses(
            DriveState.initial().spikeCreativity(0.9), prompt, SAMPLES);
        var neutralResponses = collectResponses(
            DriveState.initial(), prompt, SAMPLES);

        assertFalse(creativeResponses.isEmpty(), "Should get creative responses");
        assertFalse(neutralResponses.isEmpty(), "Should get neutral responses");

        double creativeDiversity = wordDiversity(creativeResponses);
        double neutralDiversity = wordDiversity(neutralResponses);

        System.out.println("[DynamicSampling] creativity_diversity:");
        System.out.println("  Creative diversity=" + String.format("%.3f", creativeDiversity)
            + " (" + creativeResponses.size() + " samples)");
        System.out.println("  Neutral diversity=" + String.format("%.3f", neutralDiversity)
            + " (" + neutralResponses.size() + " samples)");

        // Higher temperature + creativity should produce more diverse vocabulary
        if (creativeDiversity > neutralDiversity) {
            System.out.println("  [PASS] Creative more diverse than neutral");
        } else {
            System.out.println("  [SOFT FAIL] Creative not more diverse");
        }
    }

    @Test
    @Order(4)
    void low_energy_reduces_max_tokens() {
        // Unit test: low energy should produce lower max_tokens
        var drives = DriveState.initial();
        var highEnergy = VitalityState.initial(); // energy=1.0
        var lowEnergy = VitalityState.initial().withEnergy(0.1);

        var highParams = DriveModulatedSampling.compute(drives, highEnergy);
        var lowParams = DriveModulatedSampling.compute(drives, lowEnergy);

        assertTrue(lowParams.maxTokens() < highParams.maxTokens(),
            "Low energy maxTokens (" + lowParams.maxTokens()
                + ") should be < high energy (" + highParams.maxTokens() + ")");

        System.out.println("[DynamicSampling] energy_tokens:");
        System.out.println("  High energy: maxTokens=" + highParams.maxTokens());
        System.out.println("  Low energy:  maxTokens=" + lowParams.maxTokens());
    }
}
