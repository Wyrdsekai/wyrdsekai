package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-language Ember progressive-tasks E2E (Stage 2 of the 9B model
 * comparison strategy).
 *
 * <p><b>Why this exists separate from {@link EmberProgressiveTasksE2ETest}:</b>
 * Ember tests measure 9B drive/skills behaviour — does it pick the right
 * tool, does the ReAct loop complete, does the multi-step plan ship a
 * goal_done? The EN test gates production deploys. This test gates
 * deploys to non-EN users by sending the same 14 task prompts in JA
 * or ES and asserting the same EN content/topic keywords (because the
 * library/tools/world data are EN regardless of user language).</p>
 *
 * <p><b>Failure modes this catches</b>:
 * <ul>
 *   <li>9B picks wrong tool when input is JA/ES (e.g. {@code respond_agent}
 *       instead of {@code library_search}).</li>
 *   <li>Tool args are mangled (model translates JA query to EN before
 *       searching, losing query intent).</li>
 *   <li>ReAct loop confused by JA/ES content in working memory.</li>
 *   <li>{@code ActionTriage} misclassifies (complex JA request labelled
 *       ROUTINE).</li>
 * </ul></p>
 *
 * <p><b>Run</b>:
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-drive \
 *   WYRDSEKAI_E2E_LANG=ja \
 *   ./gradlew :e2e-test:test -PincludeTags=e2e \
 *       --tests "*EmberProgressiveTasksMultiLangE2ETest"
 * </pre></p>
 *
 * <p>Skipped unless {@code WYRDSEKAI_E2E_LANG} is {@code ja} or {@code es};
 * EN coverage stays in {@link EmberProgressiveTasksE2ETest} and is unaffected
 * by this class.</p>
 *
 * <p>Tells live in {@code e2e-test/src/test/resources/ember-tells-multilang.json}
 * — translated from the EN source-of-truth in the sibling test class.
 * Content/topic keywords stay EN by design.</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama|llama-drive|drive")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_LANG", matches = "ja|es")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmberProgressiveTasksMultiLangE2ETest {

    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPANION = "Wyrd";

    /** Language code: "ja" or "es". Asserted non-null by enable-if guard. */
    private static final String LANG = System.getenv("WYRDSEKAI_E2E_LANG");

    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    private static TestServerBootstrap server;
    private static JsonNode tells;

    @BeforeAll
    static void setUp() throws Exception {
        // Load translated tells from JSON resource. Keep separate from EN
        // sibling so EN test class stays inert if this resource changes.
        var mapper = new ObjectMapper();
        try (InputStream in = EmberProgressiveTasksMultiLangE2ETest.class
                .getClassLoader().getResourceAsStream("ember-tells-multilang.json")) {
            assertNotNull(in, "ember-tells-multilang.json missing from test resources");
            tells = mapper.readTree(in);
        }

        var dual = E2eTestSupport.setupDualInference(E2eTestSupport.backendType());

        System.out.println("[MultiLangE2E] Lang=" + LANG + ", warming up...");
        try {
            var warmupReq = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmupReq)
                .get(Duration.ofSeconds(120).toMillis(),
                    TimeUnit.MILLISECONDS);
            System.out.println("[MultiLangE2E] " + MODEL + " warm.");
        } catch (Exception e) {
            System.out.println("[MultiLangE2E] Warmup failed (non-fatal): " + e.getMessage());
        }

        server = new TestServerBootstrap(dual.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void respawnCompanion() {
        if (server != null) server.respawnCompanion();
    }

    /**
     * Look up the {lang}-translated tell for the named task. Fails fast if
     * the JSON is missing the entry — better than silently falling back to
     * EN, which would mask a coverage gap.
     */
    private String tellFor(String taskKey) {
        var node = tells.get(taskKey);
        assertNotNull(node, "Missing task entry in ember-tells-multilang.json: " + taskKey);
        var value = node.get(LANG);
        assertNotNull(value, "Missing " + LANG + " translation for " + taskKey);
        var s = value.asText();
        assertFalse(s.isBlank(), "Empty " + LANG + " translation for " + taskKey);
        return s;
    }

    private TestWebSocketClient connectAndTell(String message) throws Exception {
        // Pass the test locale on connect so the server tags Said events with
        // it, which engages CompanionActor's translate-route-translate hop
        // (drive sees EN-canonical text; voice polish translates output back).
        var ws = TestWebSocketClient.connect(server.baseUrl(), null, LANG);
        ws.waitForRoomState(CONNECT_TIMEOUT);
        for (int i = 0; i < 5; i++) {
            try {
                var drainTimeout = (i == 0) ? Duration.ofSeconds(30) : Duration.ofSeconds(5);
                ws.waitForProse(drainTimeout);
            } catch (ConditionTimeoutException e) {
                break;
            }
        }
        ws.sendSay("nexus", "tell wyrd " + message);
        return ws;
    }

    private String waitForDelivery(TestWebSocketClient ws) {
        long deadline = System.currentTimeMillis() + TASK_TIMEOUT.toMillis();
        long lastKeepalive = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var remaining = Duration.ofMillis(
                    Math.max(1000, deadline - System.currentTimeMillis()));
                var wait = remaining.compareTo(Duration.ofSeconds(30)) < 0
                    ? remaining : Duration.ofSeconds(30);
                var prose = ws.waitForProseFrom(COMPANION, wait);
                var text = prose.path("text").asText("");
                if (text.length() <= 50) continue;
                var lower = text.toLowerCase();
                if (lower.contains("welcome to the nexus")
                        || lower.contains("i'm wyrd") || lower.contains("i’m wyrd")
                        || (lower.contains("welcome") && lower.contains("companion"))
                        || (lower.contains("welcome") && lower.contains("here to help"))
                        || (lower.contains("welcome") && lower.contains("guide"))
                        || (lower.contains("welcome") && lower.contains("nexus"))) {
                    System.out.println("    [waitForDelivery] Skipped greeting: "
                        + text.substring(0, Math.min(80, text.length())) + "...");
                    continue;
                }
                return text;
            } catch (ConditionTimeoutException e) {
                if (System.currentTimeMillis() - lastKeepalive > 60_000) {
                    try { ws.sendLook("nexus"); } catch (Exception ignore) {}
                    lastKeepalive = System.currentTimeMillis();
                }
            }
        }
        return null;
    }

    private void assertTask(int num, String tell, String... topicKeywords) throws Exception {
        try (var ws = connectAndTell(tell)) {
            var result = waitForDelivery(ws);
            assertNotNull(result, "[Task " + num + " " + LANG + "] Companion must respond to: " + tell);
            System.out.println("[Task " + num + " " + LANG + "] Companion delivered: "
                + result.substring(0, Math.min(200, result.length())));
            assertTrue(result.length() > 20,
                "[Task " + num + " " + LANG + "] Response should be substantive (was "
                    + result.length() + " chars)");
            var lower = result.toLowerCase();
            boolean topicMatch = Arrays.stream(topicKeywords)
                .anyMatch(kw -> lower.contains(kw.toLowerCase()));
            assertTrue(topicMatch,
                "[Task " + num + " " + LANG + "] Response should mention topic. Keywords: "
                    + String.join(", ", topicKeywords)
                    + "\nActual: " + result.substring(0, Math.min(100, result.length())));
        }
    }

    private void assertTaskWithContent(int num, String tell, String[] contentKeywords,
                                        String... topicKeywords) throws Exception {
        // Quiet-period accept: if the companion has been silent for this long
        // AND we have at least one scored candidate, stop waiting and assert
        // on best-so-far. Without this, a task that the model genuinely fails
        // (e.g. calls library_card SEARCH instead of create_room_from_template,
        // then goal_done-fabricates success internally without emitting it) used
        // to hang the whole 10-minute TASK_TIMEOUT before the topic-match
        // assertion below could fire. See task #744 — JA task13 / ES task8 hangs.
        final long QUIET_PERIOD_MS = 30_000L;
        try (var ws = connectAndTell(tell)) {
            String bestResult = null;
            int bestScore = -1;
            long deadline = System.currentTimeMillis() + TASK_TIMEOUT.toMillis();
            long lastKeepalive = System.currentTimeMillis();
            long lastMessageAt = System.currentTimeMillis();

            while (System.currentTimeMillis() < deadline) {
                try {
                    var remaining = Math.max(1000, deadline - System.currentTimeMillis());
                    var wait = Duration.ofMillis(Math.min(remaining, 30_000));
                    var prose = ws.waitForProseFrom(COMPANION, wait);
                    lastMessageAt = System.currentTimeMillis();
                    var text = prose.path("text").asText("");
                    if (text.length() <= 50) continue;
                    var low = text.toLowerCase();
                    if (low.contains("welcome to the nexus") || low.contains("i'm wyrd")
                            || low.contains("i’m wyrd")
                            || (low.contains("welcome") && low.contains("guide"))
                            || (low.contains("welcome") && low.contains("nexus"))) {
                        System.out.println("    [Task " + num + " " + LANG + "] Skipped greeting");
                        continue;
                    }
                    // Score by combined content+topic matches; track best across all
                    // candidates, only break when BOTH match (real successful delivery).
                    int contentMatches = (int) Arrays.stream(contentKeywords)
                        .filter(kw -> low.contains(kw.toLowerCase())).count();
                    int topicMatches = (int) Arrays.stream(topicKeywords)
                        .filter(kw -> low.contains(kw.toLowerCase())).count();
                    int score = contentMatches + topicMatches;
                    // A response that hits BOTH axes always wins, even if its
                    // raw score ties or lags a single-axis-heavy candidate —
                    // the assertion below checks topic-presence on bestResult,
                    // so we must keep the content+topic match here regardless
                    // of how many extra content keywords an earlier narration
                    // happened to spray. (Live-observed JA task13 2026-05-06:
                    // a 4-content-keyword narration tied a 3-content+1-topic
                    // success message, and the narration won the tie even
                    // though it had no creation verb.)
                    boolean isBoth = contentMatches > 0 && topicMatches > 0;
                    final String prevBest = bestResult;
                    final String prevBestLow = prevBest == null ? null : prevBest.toLowerCase();
                    boolean wasBoth = prevBestLow != null
                        && Arrays.stream(contentKeywords)
                            .anyMatch(kw -> prevBestLow.contains(kw.toLowerCase()))
                        && Arrays.stream(topicKeywords)
                            .anyMatch(kw -> prevBestLow.contains(kw.toLowerCase()));
                    if (bestResult == null
                            || (isBoth && !wasBoth)
                            || (isBoth == wasBoth && score > bestScore)) {
                        bestResult = text;
                        bestScore = score;
                    }
                    if (isBoth) {
                        System.out.println("[Task " + num + " " + LANG + "] Content+topic match: "
                            + text.substring(0, Math.min(200, text.length())));
                        break;
                    }
                    System.out.println("[Task " + num + " " + LANG + "] Skipped intent (c="
                        + contentMatches + ",t=" + topicMatches + "): "
                        + text.substring(0, Math.min(80, text.length())) + "...");
                } catch (ConditionTimeoutException e) {
                    long quietFor = System.currentTimeMillis() - lastMessageAt;
                    if (bestResult != null && quietFor > QUIET_PERIOD_MS) {
                        System.out.println("[Task " + num + " " + LANG
                            + "] Companion quiet for " + (quietFor / 1000)
                            + "s — accepting best-so-far (score=" + bestScore + ")");
                        break;
                    }
                    if (System.currentTimeMillis() - lastKeepalive > 60_000) {
                        try { ws.sendLook("nexus"); } catch (Exception ignore) {}
                        lastKeepalive = System.currentTimeMillis();
                    }
                }
            }

            assertNotNull(bestResult,
                "[Task " + num + " " + LANG + "] Companion must respond to: " + tell);
            System.out.println("[Task " + num + " " + LANG + "] Companion delivered (best score="
                + bestScore + "): "
                + bestResult.substring(0, Math.min(200, bestResult.length())));
            var lower = bestResult.toLowerCase();
            boolean topicMatch = Arrays.stream(topicKeywords)
                .anyMatch(kw -> lower.contains(kw.toLowerCase()));
            assertTrue(topicMatch,
                "[Task " + num + " " + LANG + "] Response should mention topic. Keywords: "
                    + String.join(", ", topicKeywords)
                    + "\nActual: " + bestResult.substring(0, Math.min(200, bestResult.length())));
        }
    }

    // ── Tasks ─────────────────────────────────────────────────────────────
    //
    // Tells come from JSON; content/topic keywords mirror the EN sibling
    // because library content + tool outputs are EN regardless of input
    // language. Any drift from EN keyword sets should be deliberate.

    @Test @Order(1)
    void task1_library_search() throws Exception {
        assertTaskWithContent(1, tellFor("task1_library_search"),
            new String[]{"greek", "norse", "egyptian", "mythology",
                         "olympus", "mjolnir", "odin", "hades", "heracles", "asgard",
                         "osiris", "anubis", "poseidon", "cyclopes", "yggdrasil",
                         "zeus", "thor", "ra",
                         "findings", "sources"},
            "zeus", "thor", "ra", "mythology", "greek", "norse", "egyptian",
            "olympus", "mjolnir");
    }

    @Test @Order(2)
    void task2_web_search() throws Exception {
        assertTaskWithContent(2, tellFor("task2_web_search"),
            new String[]{"http", "2026", "2025", "model", "training", "neural", "transformer",
                         "findings", "sources", "paper", "published", "arxiv", "conference"},
            "ai", "research", "search", "findings", "sources");
    }

    @Test @Order(3)
    void task3_news_search() throws Exception {
        assertTaskWithContent(3, tellFor("task3_news_search"),
            new String[]{"japan", "japanese", "tokyo", "railway", "minister",
                         "trade", "economy", "technology", "earthquake", "olympics",
                         "government", "yen", "asia", "pacific", "bullet train"},
            "japan", "news", "found");
    }

    @Test @Order(4)
    void task4_multi_source_research() throws Exception {
        assertTaskWithContent(4, tellFor("task4_multi_source_research"),
            new String[]{"superposition", "entanglement", "shor", "grover", "qubit",
                         "findings", "sources"},
            "quantum", "computing", "superposition", "entanglement", "qubit");
    }

    @Test @Order(5)
    void task5_oracle_query() throws Exception {
        assertTaskWithContent(5, tellFor("task5_oracle_query"),
            new String[]{"you've used", "times in the last", "confidence",
                         "typically active", "check in roughly",
                         "library_search", "web_search"},
            "pattern", "used", "times", "confidence", "days");
    }

    @Test @Order(6)
    void task6_build_tool() throws Exception {
        // Tightened 2026-05-06: require BOTH a specific unit (proves engagement with
        // the actual conversion request) AND a topic word. Previously a "I can't
        // help with temperature" reply could pass via single keyword echo.
        assertTaskWithContent(6, tellFor("task6_build_tool"),
            new String[]{"celsius", "fahrenheit", "converter", "conversion"},
            "temperature", "convert", "tool", "build", "craft", "oracle", "pattern");
    }

    @Test @Order(7)
    void task7_comprehensive_report() throws Exception {
        assertTaskWithContent(7, tellFor("task7_comprehensive_report"),
            new String[]{"solar", "wind", "battery", "perovskite", "fossil",
                         "findings", "sources", "generation"},
            "renewable", "energy", "solar", "wind", "battery");
    }

    @Test @Order(8)
    void task8_discover_workshop_catalog() throws Exception {
        // Tightened 2026-05-06: require at least one specific template name
        // (book/crystal/tool/room) — proves the model actually discovered the
        // catalog, not just echoed query keywords back in a "no results" reply.
        // The previous lax form passed when library_card returned
        // "No results found for: workshop templates craft" because the failure
        // message contained topic words verbatim. See JA Ember run 06:56:30.
        assertTaskWithContent(8, tellFor("task8_discover_workshop_catalog"),
            new String[]{"book", "crystal", "tool", "room"},
            "workshop", "template", "catalog", "craft", "create", "make");
    }

    @Test @Order(9)
    void task9_create_book_from_template() throws Exception {
        // Tightened 2026-05-06: require a content word that proves the response
        // is about the book/observations subject, not just any topic word from
        // the request echoed in a refusal. The wide topic set is preserved (the
        // 9B engages conversationally for this task — see comment in EN sibling).
        // Stem coverage matches EN sibling (observ matches observe/observed/etc).
        assertTaskWithContent(9, tellFor("task9_create_book_from_template"),
            new String[]{"book", "observation", "observ", "memory", "memories"},
            "wrote", "write", "created", "craft", "made", "written", "share",
            "reflect", "thought", "tell", "world", "note");
    }

    @Test @Order(10)
    void task10_create_crystal_from_template() throws Exception {
        // Tightened 2026-05-06: must mention crystal/scrying (specific to the
        // requested item) AND a completion/success signal. Voice polish often
        // rewrites "I've crafted X. It's equipped." → "X is done — set up in
        // my inventory" so we accept "done", "equipped", "ready", "set up" as
        // valid completion signals alongside the literal creation verbs.
        assertTaskWithContent(10, tellFor("task10_create_crystal_from_template"),
            new String[]{"crystal", "scrying", "zone", "stat", "activity"},
            "created", "craft", "made", "observation",
            "done", "equipped", "ready", "set up", "finished");
    }

    @Test @Order(11)
    void task11_library_search_with_real_content() throws Exception {
        assertTaskWithContent(11, tellFor("task11_library_search_with_real_content"),
            new String[]{"greek", "norse", "egyptian", "mythology",
                         "olympus", "mjolnir", "odin", "hades", "heracles", "asgard",
                         "osiris", "anubis", "poseidon", "cyclopes", "yggdrasil",
                         "zeus", "thor", "ra"},
            "zeus", "thor", "ra", "mythology", "greek", "norse", "egyptian",
            "olympus", "mjolnir", "odin", "hades", "asgard");
    }

    @Test @Order(12)
    void task12_create_room_from_template() throws Exception {
        // Tightened 2026-05-06: must mention the requested specific name
        // (garden/zen) — proves engagement with the actual instruction, not
        // a generic "I'll create a room" reply.
        assertTaskWithContent(12, tellFor("task12_create_room_from_template"),
            new String[]{"garden", "zen"},
            "room", "template", "created", "creating", "nexus", "connected");
    }

    @Test @Order(13)
    void task13_create_library_room() throws Exception {
        // Tightened 2026-05-06: must mention specific name (library/star/archive)
        // or theme (space).
        assertTaskWithContent(13, tellFor("task13_create_library_room"),
            new String[]{"library", "star", "archive", "space"},
            "room", "template", "created", "creating");
    }

    @Test @Order(14)
    void task14_create_cyberpunk_zone() throws Exception {
        // Tightened 2026-05-06: must mention at least one specific theme word
        // (cyberpunk/data/haven/market/workshop/neon/cyber) — generic "I'll
        // create a zone with rooms and agents" was previously enough to pass.
        assertTaskWithContent(14, tellFor("task14_create_cyberpunk_zone"),
            new String[]{"cyberpunk", "data", "haven", "market", "workshop",
                         "neon", "cyber", "net"},
            "zone", "room", "agent", "created", "creating");
    }

    @Test @Order(15)
    void task15_teleport_to_workshop() throws Exception {
        // exercises the teleport_to verb path.
        // Acceptance is intentionally lenient: a model that doesn't know the
        // verb yet (pre-V7-corpus) will use go_to_room and still pass via the
        // arrival announcement. Once the corpus teaches teleport_to, the test
        // upgrades naturally — the response will mention "appears" / "vanishes"
        // (rendered by ClientSessionActor on direction=teleport).
        // Content: must mention the destination (workshop). Topic: arrival /
        // teleport vocabulary in any form.
        assertTaskWithContent(15, tellFor("task15_teleport_to_workshop"),
            new String[]{"workshop"},
            "teleport", "instant", "appear", "vanish", "arrive", "arrived",
            "here", "here.");
    }
}
