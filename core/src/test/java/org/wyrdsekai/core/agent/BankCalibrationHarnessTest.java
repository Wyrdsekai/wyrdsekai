package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / Tier C bank calibration harness.
 *
 * <p>Feeds every plan in {@code scripts/m2/plan_examples.jsonl} through both
 * preflight gates (M3 mental simulator + M2 plan scorer with leave-one-out
 * example pool), writes prediction + synthetic-outcome rows to
 * {@code data/m3/bank-calibration.jsonl}, then runs a quick assertion that
 * SOMETHING parsed.
 *
 * <p>Real calibration analysis is run separately:
 * <pre>
 *   python3 scripts/m2/analyze_calibration.py data/m3/bank-calibration.jsonl
 * </pre>
 *
 * <p>Opt-in via {@code WYRDSEKAI_LIVE_LLAMA_URL}. Wall: ~15-20 minutes for
 * the full 95-plan bank (95 × 2 gates × ~5-7s each).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_LLAMA_URL", matches = ".+")
class BankCalibrationHarnessTest {

    private static final ObjectMapper M = new ObjectMapper();
    /** Resolve bank path: gradle runs from {@code core/}, so probe upward. */
    private static Path resolveBankPath() {
        for (var p : List.of(
                Path.of("scripts", "m2", "plan_examples.jsonl"),
                Path.of("..", "scripts", "m2", "plan_examples.jsonl"),
                Path.of("../..", "scripts", "m2", "plan_examples.jsonl"))) {
            if (Files.exists(p)) return p;
        }
        // Classpath fallback — bundled at /m2/plan_examples.jsonl by syncM2Bank.
        try {
            var tmp = Files.createTempFile("bank-", ".jsonl");
            try (var in = BankCalibrationHarnessTest.class
                    .getResourceAsStream("/m2/plan_examples.jsonl")) {
                if (in != null) {
                    Files.write(tmp, in.readAllBytes());
                    return tmp;
                }
            }
        } catch (Exception ignored) {}
        return Path.of("scripts", "m2", "plan_examples.jsonl"); // will fail below with clear msg
    }

    /** Output dir relative to project root regardless of cwd. */
    private static Path resolveOutPath() {
        for (var base : List.of(Path.of("."), Path.of(".."), Path.of("../.."))) {
            var attempt = base.resolve(Path.of("data", "m3", "bank-calibration.jsonl"));
            // Pick the first base where its parent's parent (project root) has scripts/
            if (Files.exists(base.resolve("scripts/m2/plan_examples.jsonl"))) {
                return attempt;
            }
        }
        return Path.of("data", "m3", "bank-calibration.jsonl");
    }

    private static final Path BANK_PATH = resolveBankPath();
    private static final Path OUT_PATH = resolveOutPath();
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private static String llamaUrl;
    private static ActorTestKit testKit;
    private static ActorRef<InferenceRouter.Command> router;
    private static List<M2PlanScorer.Example> bankExamples;

    @BeforeAll
    static void bootstrap() throws Exception {
        llamaUrl = System.getenv().getOrDefault(
            "WYRDSEKAI_LIVE_LLAMA_URL", "http://home-server:8200");
        Assumptions.assumeTrue(reachable(llamaUrl),
            "skipping: " + llamaUrl + " did not respond to /health within 2s");

        testKit = ActorTestKit.create("bank-cal-harness",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));

        var client = new InferenceClient(llamaUrl, null, CALL_TIMEOUT);
        var backend = new InferenceBackend.LlamaServer(
            "live-drive", client, 10, List.of(), null);
        router = testKit.spawn(InferenceRouter.create(
            List.of(backend), null, null));
        Thread.sleep(2000);

        bankExamples = M2PlanScorer.parseJsonl(Files.readAllBytes(BANK_PATH));
        System.out.printf("[harness] loaded %d bank plans%n", bankExamples.size());

        Files.createDirectories(OUT_PATH.getParent());
        // Truncate prior run.
        Files.writeString(OUT_PATH, "", StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
    }

    @AfterAll
    static void teardown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test
    void score_every_plan_against_both_gates() throws Exception {
        var prefix = simpleWorldModelPrefix();
        var state = "evaluating plan against world model";
        int parsed = 0, total = 0;
        var t0 = Instant.now();

        for (int i = 0; i < bankExamples.size(); i++) {
            var ex = bankExamples.get(i);
            total++;
            // Build leave-one-out scorer pool: every plan except this one.
            var pool = new ArrayList<M2PlanScorer.Example>(bankExamples.size() - 1);
            for (int j = 0; j < bankExamples.size(); j++) {
                if (j != i) pool.add(bankExamples.get(j));
            }
            var scorer = new M2PlanScorer(pool, 42L + i);

            var actions = ex.actions();
            MentalSimulator.Prediction m3 = null;
            M2PlanScorer.Score m2 = null;

            try {
                m3 = MentalSimulator.simulateChain(
                    router, testKit.system().scheduler(),
                    () -> prefix, state, actions
                ).toCompletableFuture().get(70, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.out.printf("[harness] %s M3 error: %s%n", ex.planId(), e.getMessage());
            }

            try {
                m2 = scorer.score(
                    router, testKit.system().scheduler(),
                    ex.context(), actions
                ).toCompletableFuture().get(70, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.out.printf("[harness] %s M2 error: %s%n", ex.planId(), e.getMessage());
            }

            writePredictionRow(ex, actions, m3, m2);
            writeOutcomeRow(ex);
            if ((m3 != null && !m3.parseFailure()) || (m2 != null && !m2.parseFailure())) {
                parsed++;
            }

            if ((i + 1) % 10 == 0 || i == bankExamples.size() - 1) {
                var elapsed = Duration.between(t0, Instant.now()).toSeconds();
                System.out.printf("[harness] %d/%d parsed=%d elapsed=%ds%n",
                    i + 1, bankExamples.size(), parsed, elapsed);
            }
        }

        System.out.printf("[harness] DONE total=%d parsed=%d output=%s%n",
            total, parsed, OUT_PATH);
        assertThat(parsed).as("at least 80%% of plans must parse")
            .isGreaterThanOrEqualTo((int) (total * 0.8));
    }

    // ── Output writers (match analyze_calibration.py schema) ──

    private void writePredictionRow(M2PlanScorer.Example ex, List<String> actions,
                                    MentalSimulator.Prediction m3,
                                    M2PlanScorer.Score m2) throws Exception {
        var node = M.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("companion", "harness");
        node.put("planId", ex.planId());
        node.put("plan", ex.context());
        var arr = node.putArray("goals");
        for (var a : actions) arr.add(a);
        if (m3 != null) {
            var sub = node.putObject("m3");
            sub.put("confidence", m3.confidence());
            sub.put("predictedEndState", m3.predictedEndState());
            sub.put("reasoning", m3.reasoning());
            sub.put("parseFailure", m3.parseFailure());
            sub.put("shouldReject", m3.shouldReject());
            sub.put("highConfidence", m3.highConfidence());
        }
        if (m2 != null) {
            var sub = node.putObject("m2");
            sub.put("confidence", m2.confidence());
            sub.put("predictedOutcome", m2.predictedOutcome());
            sub.put("reasoning", m2.reasoning());
            sub.put("parseFailure", m2.parseFailure());
            sub.put("shouldReject", m2.shouldReject());
            sub.put("highConfidence", m2.highConfidence());
        }
        Files.writeString(OUT_PATH, M.writeValueAsString(node) + "\n",
            StandardOpenOption.APPEND);
    }

    private void writeOutcomeRow(M2PlanScorer.Example ex) throws Exception {
        var node = M.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("companion", "harness");
        node.put("planId", ex.planId());
        node.put("outcomeRow", true);
        // Map the bank's labelled outcome onto the analyzer's success/failure
        // scheme: "completed" → succeeded, anything else → failed.
        node.put("outcome", "completed".equals(ex.outcome()) ? "completed" : "abandoned");
        node.put("labeled", ex.outcome());
        node.put("reason", ex.notes() == null ? "" : ex.notes());
        node.put("wallSeconds", 0);
        node.put("goalsCompleted", "completed".equals(ex.outcome()) ? ex.actions().size() : 0);
        node.put("goalsTotal", ex.actions().size());
        Files.writeString(OUT_PATH, M.writeValueAsString(node) + "\n",
            StandardOpenOption.APPEND);
    }

    private static boolean reachable(String url) {
        try {
            var c = HttpClient.newBuilder().connectTimeout(HEALTH_TIMEOUT).build();
            var req = HttpRequest.newBuilder()
                .uri(URI.create(url + "/health"))
                .timeout(HEALTH_TIMEOUT).GET().build();
            return c.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String simpleWorldModelPrefix() {
        var renderer = new WorldModelPromptRenderer();
        var nexus = new RoomSnapshot("nexus", "The Nexus", "Hub.", "alpha", List.of(),
            List.of(new Exit("library", "library", "→ library"),
                    new Exit("workshop", "workshop", "→ workshop")),
            List.of(), List.of(), List.of());
        var library = new RoomSnapshot("library", "The Library", "Books.", "alpha",
            List.of(),
            List.of(new Exit("nexus", "nexus", "→ nexus")),
            List.of(),
            List.of(new RoomObject("library_card", "Library Card",
                "Searches the catalog.", true)),
            List.of());
        var workshop = new RoomSnapshot("workshop", "The Workshop", "Tools.", "alpha",
            List.of(),
            List.of(new Exit("nexus", "nexus", "→ nexus")),
            List.of(),
            List.of(new RoomObject("workbench", "Workbench",
                "Build forms here.", false)),
            List.of());

        var transitions = Map.of(
            "k1", List.of(
                new WorldModel.Transition("nexus|e=0|o=0", "library_search", "amae",
                    "nexus|e=0|o=0", true, "12 results", Instant.now()),
                new WorldModel.Transition("nexus|e=0|o=0", "library_search", "saudade",
                    "nexus|e=0|o=0", true, "8 results", Instant.now()),
                new WorldModel.Transition("nexus|e=0|o=0", "tell_agent", "operator",
                    null, true, "delivered", Instant.now())));

        return renderer.render(new WorldModelPromptRenderer.Inputs(
            List.of(nexus, library, workshop), transitions, List.of())).text();
    }
}
