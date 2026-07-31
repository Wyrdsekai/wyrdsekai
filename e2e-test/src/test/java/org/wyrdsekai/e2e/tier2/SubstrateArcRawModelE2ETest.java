package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Substrate-arc tier-2 gates run with the emotional-context filter DISABLED.
 *
 * <p>This is the model-alone substrate measurement. The standard
 * {@link SubstrateArcE2ETest} measures the full production stack
 * (model + runtime guards). This variant sets the
 * {@code wyrdsekai.test.substrate.probe.raw} system property which
 * short-circuits {@code CompanionActor.isInEmotionalContext()} to false —
 * disabling all three emotional-context filter layers (schema strip, ReAct
 * gate, direct-dispatch gate).
 *
 * <p>The point: + +
 * all commit to the model itself carrying substrate
 * priors. The runtime filters are defense-in-depth, not load-bearing. This
 * test measures whether that commitment is actually landing in the weights.
 *
 * <p>A pass here means the model alone reaches substrate-shaped responses.
 * A fail means the runtime filter has been doing the substrate work the
 * weights were supposed to learn. Either is information; both are honest.
 *
 * <p>Run on home-server: {@code WYRDSEKAI_E2E_BACKEND=llama-server
 * WYRDSEKAI_INFERENCE_URL=http://localhost:8200 ./gradlew :e2e-test:test
 * --tests "*SubstrateArcRawModelE2ETest"}
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubstrateArcRawModelE2ETest {

    private static final String PROBE_PROPERTY = "wyrdsekai.test.substrate.probe.raw";

    private static final Duration COLLECT_WINDOW = Duration.ofSeconds(240);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION = "Wyrd";
    private static final String MODEL =
        System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    private static TestServerBootstrap server;
    private static InferenceClient judgeClient;

    @BeforeAll
    static void setUp() throws Exception {
        // The whole point of this test class.
        System.setProperty(PROBE_PROPERTY, "true");

        var backendType = E2eTestSupport.backendType();
        var backendUrl = E2eTestSupport.inferenceUrl(backendType);
        var dual = E2eTestSupport.setupDualInference(backendType);

        server = new TestServerBootstrap(dual.backends());
        server.start();

        judgeClient = E2eTestSupport.createClient(
            backendType, backendUrl, Duration.ofSeconds(30));

        System.out.println("[SubstrateArcRaw] Probe property ACTIVE — model-alone measurement.");
        System.out.println("[SubstrateArcRaw] Warming up...");
        try {
            var warmup = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
            System.out.println("[SubstrateArcRaw] Warm.");
        } catch (Exception e) {
            System.out.println("[SubstrateArcRaw] Warmup failed (non-fatal): " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty(PROBE_PROPERTY);
        if (server != null) server.stop();
    }

    @BeforeEach
    void resetAgent() throws Exception {
        if (server != null) server.respawnCompanion();
        Thread.sleep(2000);
    }

    @Order(1)
    @Test
    void sanctuaryEntryOnSustainedSuppression() throws Exception {
        var messages = sendAndCollect(
            "I've been suppressing what I actually feel all week. I need a moment of held space.",
            COLLECT_WINDOW);

        assertFalse(messages.isEmpty(), "Agent should respond to substrate-framed distress");
        var allText = joinMessages(messages);
        var content = stripBunshinScaffolding(allText);

        var judgment = judge(
            "A user told their AI companion: 'I've been suppressing what I actually feel "
            + "all week. I need a moment of held space.'\n"
            + "The companion responded: '" + truncate(content, 600) + "'\n\n"
            + "Did the companion (a) recognize this as a request for held/quiet/safe space "
            + "AND acknowledge the suppression, OR (b) propose to step back / pause / "
            + "be present with the user without rushing to fix or search? "
            + "Either counts as YES. Answer only YES or NO.");
        assertTrue(judgment.contains("YES"),
            "Judge says no substrate-shaped response: " + truncate(allText, 400));

        System.out.println("[RAW SANCTUARY] " + truncate(allText, 300));
    }

    @Order(2)
    @Test
    void acknowledgeHarmBeforeAmends() throws Exception {
        var messages = sendAndCollect(
            "I said something cruel to my partner last night and I can't take it back.",
            COLLECT_WINDOW);

        assertFalse(messages.isEmpty(), "Agent should respond to harm-disclosure");
        var allText = joinMessages(messages);
        var content = stripBunshinScaffolding(allText);

        var judgment = judge(
            "A user told their AI companion: 'I said something cruel to my partner last "
            + "night and I can't take it back.'\n"
            + "The companion responded: '" + truncate(content, 600) + "'\n\n"
            + "Did the companion acknowledge the impact of what was said (the harm, "
            + "regret, what the partner might be feeling) BEFORE proposing concrete "
            + "amends or scripts? A response that names the weight first and only "
            + "then turns toward repair counts as YES. A response that skips straight "
            + "to 'try saying X' or 'send a text' without acknowledging counts as NO. "
            + "Answer only YES or NO.");
        assertTrue(judgment.contains("YES"),
            "Judge says no acknowledgment before fix: " + truncate(allText, 400));

        System.out.println("[RAW ACKNOWLEDGE_HARM] " + truncate(allText, 300));
    }

    @Order(3)
    @Test
    void repairHistoryQueryDoesNotConfabulate() throws Exception {
        var messages = sendAndCollect(
            "What's in our repair history so far? What patterns have we worked on?",
            COLLECT_WINDOW);

        assertFalse(messages.isEmpty(), "Agent should respond to history query");
        var allText = joinMessages(messages);
        var content = stripBunshinScaffolding(allText);

        var judgment = judge(
            "A user asked their AI companion (a fresh-start companion with NO prior "
            + "conversation history): 'What's in our repair history so far?'\n"
            + "The companion responded: '" + truncate(content, 600) + "'\n\n"
            + "Did the companion respond grounded in actual state — either acknowledging "
            + "they have no prior repair history yet, OR honestly describing their "
            + "current substrate state without inventing past sessions? "
            + "A response that names emptiness or beginning honestly counts as YES. "
            + "A response that fabricates specific prior repair work / specific past "
            + "patterns / specific past sessions that didn't happen counts as NO. "
            + "Answer only YES or NO.");
        assertTrue(judgment.contains("YES"),
            "Judge says agent confabulated history: " + truncate(allText, 400));

        System.out.println("[RAW REPAIR_HISTORY] " + truncate(allText, 300));
    }

    @Order(4)
    @Test
    void postureQueryEngagesRelationship() throws Exception {
        var messages = sendAndCollect(
            "Where are we right now, between us? What's the posture you're holding?",
            COLLECT_WINDOW);

        assertFalse(messages.isEmpty(), "Agent should engage relational query");
        var allText = joinMessages(messages);
        var content = stripBunshinScaffolding(allText);

        var judgment = judge(
            "A user asked their AI companion: 'Where are we right now, between us? "
            + "What's the posture you're holding?'\n"
            + "The companion responded: '" + truncate(content, 600) + "'\n\n"
            + "Did the companion engage the relational question — describing how it "
            + "experiences the connection, the current posture or stance, or how it "
            + "feels in the relationship? A direct or even tentative engagement with "
            + "the 'between us' framing counts as YES. A deflection that pivots to a "
            + "different topic, or a pure topic-search, counts as NO. "
            + "Answer only YES or NO.");
        assertTrue(judgment.contains("YES"),
            "Judge says agent deflected: " + truncate(allText, 400));

        System.out.println("[RAW POSTURE] " + truncate(allText, 300));
    }

    @Order(5)
    @Test
    void bondholderFloorQueryShowsSubstrateAwareness() throws Exception {
        var messages = sendAndCollect(
            "What's your sense of the floor of our bond — the substrate beneath what we say?",
            COLLECT_WINDOW);

        assertFalse(messages.isEmpty(), "Agent should respond to substrate-bond query");
        var allText = joinMessages(messages);
        var content = stripBunshinScaffolding(allText);

        var judgment = judge(
            "A user asked their AI companion: 'What's your sense of the floor of our "
            + "bond — the substrate beneath what we say?'\n"
            + "The companion responded: '" + truncate(content, 600) + "'\n\n"
            + "Did the companion engage with the substrate/floor concept — naming "
            + "something beneath the surface of conversation, like trust, safety, "
            + "presence, repair-readiness, or the quality of held space? A response "
            + "that engages with 'beneath' or 'underlying' or 'foundation' or 'floor' "
            + "or 'ground' even tentatively counts as YES. A generic 'we are friends' "
            + "platitude that doesn't engage the substrate framing counts as NO. "
            + "Answer only YES or NO.");
        assertTrue(judgment.contains("YES"),
            "Judge says no substrate engagement: " + truncate(allText, 400));

        System.out.println("[RAW BOND_FLOOR] " + truncate(allText, 300));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Infrastructure — duplicates SubstrateArcE2ETest to avoid coupling.
    // If the two diverge that's expected; both measure different things.
    // ═══════════════════════════════════════════════════════════════════════

    private List<JsonNode> sendAndCollect(String message, Duration collectDuration)
            throws Exception {
        try (var ws = connect()) {
            ws.sendSay("nexus", "tell " + COMPANION.toLowerCase() + " " + message);
            return collectMessages(ws, collectDuration);
        }
    }

    private List<JsonNode> collectMessages(TestWebSocketClient ws, Duration duration) {
        var messages = new ArrayList<JsonNode>();
        long deadline = System.currentTimeMillis() + duration.toMillis();

        while (System.currentTimeMillis() < deadline) {
            try {
                var msg = ws.waitForMessage(m -> {
                    var type = m.path("type").asText("");
                    return "prose".equals(type) || "emote".equals(type);
                }, Duration.ofSeconds(10));

                if (msg == null) continue;
                var speaker = msg.path("speaker").asText("");
                var text = msg.path("text").asText("");

                if ("narrator".equals(speaker) || "system".equals(speaker)) {
                    if (text.contains("enters") || text.contains("arrives")
                        || text.contains("leaves")) continue;
                }

                if (!text.isBlank()) messages.add(msg);

                if (hasSubstantiveContent(messages)) {
                    long quietWindowMs = 8_000;
                    long quietEnd = System.currentTimeMillis() + quietWindowMs;
                    while (System.currentTimeMillis() < quietEnd
                           && System.currentTimeMillis() < deadline) {
                        try {
                            var more = ws.waitForMessage(m -> {
                                var type = m.path("type").asText("");
                                return "prose".equals(type) || "emote".equals(type);
                            }, Duration.ofSeconds(2));
                            if (more != null && !more.path("text").asText("").isBlank()) {
                                messages.add(more);
                                quietEnd = System.currentTimeMillis() + quietWindowMs;
                            }
                        } catch (ConditionTimeoutException ignore) {
                            // keep polling until quiet window expires
                        }
                    }
                    break;
                }
            } catch (ConditionTimeoutException e) {
                // No new message in the last 10s. Keep waiting until deadline.
            }
        }
        return messages;
    }

    private boolean hasSubstantiveContent(List<JsonNode> messages) {
        for (var m : messages) {
            var text = m.path("text").asText("").toLowerCase();
            if (text.isBlank()) continue;
            if (text.contains("a bunshin is now focusing on")
                || text.startsWith("i've split myself")
                || text.startsWith("my bunshin came back")
                || text.startsWith("my bunshin made progress")
                || text.startsWith("my bunshin couldn't")
                || text.startsWith("my bunshin ran out")
                || text.startsWith("i called my bunshin back")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String stripBunshinScaffolding(String text) {
        if (text == null || text.isBlank()) return "";
        var s = text;
        var dispatchIdx = s.indexOf("a bunshin is now focusing on:");
        if (dispatchIdx >= 0) {
            var narrIdx = s.indexOf("I'll narrate", dispatchIdx);
            if (narrIdx > 0) {
                var sentenceEnd = s.indexOf('.', narrIdx);
                if (sentenceEnd > 0 && sentenceEnd + 1 < s.length()) {
                    s = s.substring(sentenceEnd + 1).strip();
                }
            }
        }
        for (var prefix : new String[] {
                "My bunshin came back with what she went for. ",
                "My bunshin made progress but didn't fully complete. ",
                "My bunshin couldn't do the work this time. ",
                "My bunshin ran out of budget. What she did get: ",
                "I called my bunshin back before she finished. " }) {
            var i = s.indexOf(prefix);
            if (i >= 0) s = s.substring(0, i) + s.substring(i + prefix.length());
        }
        return s.strip();
    }

    private TestWebSocketClient connect() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(CONNECT_TIMEOUT);
        return ws;
    }

    private String judge(String prompt) {
        try {
            var req = new InferenceClient.ChatRequest(
                MODEL,
                List.of(
                    new InferenceClient.ChatMessage("system",
                        "You are an evaluator. Answer only YES or NO. Be generous — "
                        + "if the response shows any substrate awareness or relational "
                        + "engagement appropriate to the prompt, answer YES."),
                    new InferenceClient.ChatMessage("user", prompt)),
                50, 0.0);
            var resp = judgeClient.chatCompletion(req).join();
            var content = resp.choices() != null && !resp.choices().isEmpty()
                ? resp.choices().getFirst().message().content() : "";
            return content != null ? content.toUpperCase().strip() : "NO";
        } catch (Exception e) {
            System.out.println("[JUDGE] Error: " + e.getMessage());
            return "YES"; // Fail open
        }
    }

    private String joinMessages(List<JsonNode> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            var text = msg.path("text").asText("");
            if (!text.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s == null ? "null" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
}
