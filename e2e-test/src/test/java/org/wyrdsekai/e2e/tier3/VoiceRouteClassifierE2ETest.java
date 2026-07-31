package org.wyrdsekai.e2e.tier3;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-3 live probe for the #924 classifier-driven content routing
 * (CompanionActor voice-route, ~CompanionActor.java:4885). The contract:
 *
 * <ul>
 *   <li>A <b>TASK</b> tell (TASK_PRESENT head → {@code actionable}) is authored
 *       by the <b>9B drive</b> — the prompt assembles to {@code cap:full} and the
 *       turn reasons/decides/uses tools. The {@code [voice-route]} log reads
 *       {@code authored by 9B(...)}.</li>
 *   <li>A <b>SOCIAL / PRESENCE</b> tell (no task) is authored by the <b>4B
 *       voice</b> — the prompt assembles to {@code cap:quick}
 *       ({@link org.wyrdsekai.core.agent.AssembledPrompt#BACKEND_VOICE}) and the
 *       4B owns content + voice end-to-end. The log reads
 *       {@code authored by 4B-VOICE(cap:quick)}.</li>
 * </ul>
 *
 * <p>There is no queryable routing hook on the reply — the only observable is the
 * {@code [voice-route]} log line emitted once per reactive turn at
 * CompanionActor.java:4952. We capture it with a Logback {@link ListAppender}
 * on the {@code CompanionActor} logger and read the {@code authored by …}
 * field, matched to the turn via the logged {@code trigger='…'} preview.</p>
 *
 * <p>The log reflects the routing <i>decision</i> (which tier/assembler), driven
 * directly by the {@code triageModel} the classifier sets — so this validates the
 * change under test even when {@code cap:quick} physically falls back to the
 * drive backend. Dual inference (drive :8200 + voice :8201) is set up per the
 * tier-3 convention so the 4B genuinely serves the social turns.</p>
 *
 * <p>Routing is statistical (an ONNX head), so the gate is best-of-3 needing ≥2
 * per category — a single misclassification doesn't fail the contract, a
 * systematic mis-route does. Run on home-server:</p>
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8200 \
 *     ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.VoiceRouteClassifierE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class VoiceRouteClassifierE2ETest {

    private static final Duration COLLECT_WINDOW = Duration.ofSeconds(90);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION = "Wyrd";
    private static final String MODEL =
        System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    /** Actionable requests — TASK_PRESENT should fire → 9B authors. */
    private static final List<String> TASK_TELLS = List.of(
        "Make me a numbered list of the eight planets in order from the sun.",
        "Create an item called a brass key and leave it here in the room.",
        "Search the library for anything about tide pools and summarize it for me.");

    /** Pure social / presence — no task → 4B owns content + voice. */
    private static final List<String> SOCIAL_TELLS = List.of(
        "I'm just really glad to be sitting here with you right now.",
        "Hey, how are you feeling today?",
        "I missed you while I was away. It's good to see you again.");

    private static TestServerBootstrap server;

    private Logger companionLogger;
    private ListAppender<ILoggingEvent> voiceRoute;

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);

        server = new TestServerBootstrap(dual.backends());
        server.start();

        try {
            var warmup = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup).get(120_000, TimeUnit.MILLISECONDS);
            System.out.println("[voice-route-e2e] warm.");
        } catch (Exception e) {
            System.out.println("[voice-route-e2e] warmup failed (non-fatal): " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void attach() throws Exception {
        companionLogger = (Logger) LoggerFactory.getLogger(
            "org.wyrdsekai.core.agent.CompanionActor");
        companionLogger.setLevel(Level.INFO);
        voiceRoute = new ListAppender<>();
        voiceRoute.start();
        companionLogger.addAppender(voiceRoute);
        if (server != null) server.respawnCompanion();
        Thread.sleep(2000);
    }

    @AfterEach
    void detach() {
        if (companionLogger != null && voiceRoute != null) {
            companionLogger.detachAppender(voiceRoute);
        }
    }

    @Test
    void taskTellsAreAuthoredByTheNineBDrive() throws Exception {
        var outcomes = new ArrayList<String>();
        int correct = 0;
        for (var tell : TASK_TELLS) {
            var model = routeFor(tell);
            boolean ok = model != null && model.startsWith("9B");
            if (ok) correct++;
            outcomes.add(String.format("  [TASK]   %-52s → %s %s",
                "'" + preview(tell) + "'",
                model == null ? "(no [voice-route] line)" : model,
                ok ? "✓" : "✗ expected 9B"));
        }
        outcomes.forEach(System.out::println);
        assertTrue(correct >= 2,
            "Expected ≥2/3 TASK tells authored by the 9B drive; got " + correct + "/3\n"
            + String.join("\n", outcomes));
    }

    @Test
    void socialTellsAreAuthoredByTheFourBVoice() throws Exception {
        var outcomes = new ArrayList<String>();
        int correct = 0;
        for (var tell : SOCIAL_TELLS) {
            var model = routeFor(tell);
            boolean ok = model != null && model.contains("4B-VOICE");
            if (ok) correct++;
            outcomes.add(String.format("  [SOCIAL] %-52s → %s %s",
                "'" + preview(tell) + "'",
                model == null ? "(no [voice-route] line)" : model,
                ok ? "✓" : "✗ expected 4B-VOICE"));
        }
        outcomes.forEach(System.out::println);
        assertTrue(correct >= 2,
            "Expected ≥2/3 SOCIAL tells authored by the 4B voice; got " + correct + "/3\n"
            + String.join("\n", outcomes));
    }

    // ─── infrastructure ────────────────────────────────────────────────────

    /**
     * Send one tell, let the reactive turn run, and return the {@code authored
     * by …} field from this turn's {@code [voice-route]} log line (matched on the
     * logged trigger preview, with the latest line as fallback). {@code null} if
     * no voice-route line was emitted.
     */
    private String routeFor(String tell) throws Exception {
        try (var ws = connect()) {
            voiceRoute.list.clear();   // drop any connect-time greeting noise
            ws.sendSay("nexus", "tell " + COMPANION.toLowerCase() + " " + tell);
            collect(ws, COLLECT_WINDOW);
        }
        var key = preview(tell);
        String latest = null;
        // ListAppender.list isn't thread-safe; the turn may still be logging.
        // Snapshot with a CME-retry so the read is safe.
        List<ILoggingEvent> events = null;
        for (int t = 0; t < 20 && events == null; t++) {
            try { events = new ArrayList<>(voiceRoute.list); }
            catch (java.util.ConcurrentModificationException cme) { Thread.sleep(50); }
        }
        if (events == null) events = List.of();
        for (var ev : events) {
            var msg = ev.getFormattedMessage();
            if (!msg.contains("[voice-route]")) continue;
            var authored = authoredBy(msg);
            if (authored == null) continue;
            latest = authored;                 // remember the most recent overall
            if (msg.contains(key)) return authored;  // exact turn match wins
        }
        return latest;
    }

    /** Extract the {@code authored by X} token (up to the next " |"). */
    private static String authoredBy(String logLine) {
        int i = logLine.indexOf("authored by ");
        if (i < 0) return null;
        var rest = logLine.substring(i + "authored by ".length());
        int bar = rest.indexOf(" |");
        return (bar >= 0 ? rest.substring(0, bar) : rest).strip();
    }

    /** First 40 chars of the tell — a substring of the log's {@code trigger='…'}. */
    private static String preview(String tell) {
        return tell.length() <= 40 ? tell : tell.substring(0, 40);
    }

    private TestWebSocketClient connect() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(CONNECT_TIMEOUT);
        return ws;
    }

    /** Drain the reactive turn so it doesn't bleed into the next probe. */
    private void collect(TestWebSocketClient ws, Duration duration) {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        boolean got = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                var msg = ws.waitForMessage(m -> {
                    var type = m.path("type").asText("");
                    return "prose".equals(type) || "emote".equals(type);
                }, Duration.ofSeconds(10));
                if (msg == null) continue;
                var text = msg.path("text").asText("");
                if (text.isBlank()) continue;
                got = true;
                // settle: 6s quiet after first substantive content
                long quietEnd = System.currentTimeMillis() + 6_000;
                while (System.currentTimeMillis() < quietEnd
                        && System.currentTimeMillis() < deadline) {
                    try {
                        var more = ws.waitForMessage(m -> {
                            var type = m.path("type").asText("");
                            return "prose".equals(type) || "emote".equals(type);
                        }, Duration.ofSeconds(2));
                        if (more != null && !more.path("text").asText("").isBlank()) {
                            quietEnd = System.currentTimeMillis() + 6_000;
                        }
                    } catch (ConditionTimeoutException ignore) {
                        // keep polling until the quiet window expires
                    }
                }
                break;
            } catch (ConditionTimeoutException e) {
                // no message yet — keep waiting until the deadline
            }
        }
        // The [voice-route] line fires at turn assembly regardless; `got` is only
        // a drain signal, so we don't assert on it here.
        if (!got) System.out.println("[voice-route-e2e] (no prose collected within window)");
    }
}
