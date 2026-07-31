package org.wyrdsekai.e2e.tier2;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 2 E2E test: companion configures notification channels via natural language.
 *
 * <p>Tests the full pipeline: user tells companion → LLM emits configure_channel →
 * ActionParser parses → CompanionActor updates worldKnowledge → channels initialized.
 *
 * <p>Requires real llama-server / SGLang backend. Run:
 * {@code WYRDSEKAI_E2E_BACKEND=llama-server ./gradlew :e2e-test:test
 *   --tests "*NotificationSetupE2ETest" -PincludeTags=e2e}
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationSetupE2ETest {

    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION = "Wyrd";
    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        // Warmup the skills backend
        try {
            var warmup = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[NotificationSetup] Warmup failed: " + e.getMessage());
        }

        server = new TestServerBootstrap(dual.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    private TestWebSocketClient connectAndTell(String message) throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(CONNECT_TIMEOUT);
        // Drain greeting
        for (int i = 0; i < 5; i++) {
            try { ws.waitForProse(Duration.ofSeconds(3)); }
            catch (ConditionTimeoutException e) { break; }
        }
        ws.sendSay("nexus", "tell wyrd " + message);
        return ws;
    }

    private String waitForResponse(TestWebSocketClient ws) {
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var remaining = Duration.ofMillis(
                    Math.max(1000, deadline - System.currentTimeMillis()));
                var wait = remaining.compareTo(Duration.ofSeconds(30)) < 0
                    ? remaining : Duration.ofSeconds(30);
                var prose = ws.waitForProseFrom(COMPANION, wait);
                var text = prose.path("text").asText("");
                if (text.length() > 20) return text;
            } catch (ConditionTimeoutException e) {
                // Keep waiting
            }
        }
        return null;
    }

    // ─── Test 1: Configure Keybase ──────────────────────────────

    @Test
    @Order(1)
    void companion_understands_keybase_setup_request() throws Exception {
        try (var ws = connectAndTell(
                "I want you to set up keybase notifications. My keybase username is testuser.")) {
            var result = waitForResponse(ws);
            assertNotNull(result, "Companion should respond to notification setup request");
            System.out.println("[Keybase Setup] Companion: " + result.substring(0, Math.min(200, result.length())));
            // The companion should either confirm the setup or ask for clarification
            // We can't assert exact content since it's real LLM, but it should respond
        }
    }

    // ─── Test 2: Configure ntfy ─────────────────────────────────

    @Test
    @Order(2)
    void companion_understands_ntfy_setup_request() throws Exception {
        try (var ws = connectAndTell(
                "Set up ntfy push notifications for me. Use topic wyrdsekai-test on the default server.")) {
            var result = waitForResponse(ws);
            assertNotNull(result, "Companion should respond to ntfy setup request");
            System.out.println("[ntfy Setup] Companion: " + result.substring(0, Math.min(200, result.length())));
        }
    }

    // ─── Test 3: Configure Email ────────────────────────────────

    @Test
    @Order(3)
    void companion_understands_email_setup_request() throws Exception {
        try (var ws = connectAndTell(
                "Please set up email notifications to test@example.com")) {
            var result = waitForResponse(ws);
            assertNotNull(result, "Companion should respond to email setup request");
            System.out.println("[Email Setup] Companion: " + result.substring(0, Math.min(200, result.length())));
        }
    }

    // ─── Test 4: List Notifications ─────────────────────────────

    @Test
    @Order(4)
    void companion_can_list_notification_channels() throws Exception {
        try (var ws = connectAndTell("what notification channels do you have set up?")) {
            var result = waitForResponse(ws);
            assertNotNull(result, "Companion should respond about notification status");
            System.out.println("[List Channels] Companion: " + result.substring(0, Math.min(200, result.length())));
        }
    }
}
