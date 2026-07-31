package org.wyrdsekai.core.agent;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / Tier A live probe.
 *
 * <p>Hits a real Drive-9B llama-server (default {@code http://home-server:8200},
 * override via {@code WYRDSEKAI_LIVE_LLAMA_URL}) and exercises:
 *
 * <ul>
 *   <li><b>M3 mental simulator</b> — confidence ordering: clean plans rank
 *       above antipattern plans; GBNF JSON parses 5/5</li>
 *   <li><b>M2 plan scorer</b> — same ordering against the curated bank</li>
 *   <li><b>Latency</b> — round-trip stays under a generous wall-clock budget</li>
 * </ul>
 *
 * <p><b>Opt-in</b>: only runs when {@code WYRDSEKAI_LIVE_LLAMA_URL} is set
 * (any non-empty value is fine; default is used internally). The endpoint is
 * pre-flighted with a 2s GET /health probe; if unreachable, the test reports
 * a skip rather than failing — this lets CI pick up llama-server outages
 * without flaking the unit suite.
 *
 * <p>To run locally:
 * <pre>
 *   WYRDSEKAI_LIVE_LLAMA_URL=http://home-server:8200 ./gradlew :core:test \
 *     --tests "PreflightLiveProbeTest"
 * </pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_LLAMA_URL", matches = ".+")
class PreflightLiveProbeTest {

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration P95_BUDGET = Duration.ofSeconds(45);

    private static String llamaUrl;
    private static ActorTestKit testKit;
    private static ActorRef<InferenceRouter.Command> router;

    @BeforeAll
    static void bootstrap() throws Exception {
        llamaUrl = System.getenv().getOrDefault(
            "WYRDSEKAI_LIVE_LLAMA_URL", "http://home-server:8200");
        Assumptions.assumeTrue(reachable(llamaUrl),
            "skipping: " + llamaUrl + " did not respond to GET /health within 2s");

        testKit = ActorTestKit.create("preflight-live-probe",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """));

        var client = new InferenceClient(llamaUrl, null, CALL_TIMEOUT);
        var backend = new InferenceBackend.LlamaServer(
            "live-drive", client, 10, List.of(), null);
        // No CapabilityRegistry — cap:reasoning falls through to the first
        // healthy backend, which is our single LlamaServer.
        router = testKit.spawn(InferenceRouter.create(
            List.of(backend), null, null));

        // Allow the router's first health-check tick to mark the backend healthy
        // (initial state is unknown until the first probe lands).
        Thread.sleep(2000);
    }

    @AfterAll
    static void teardown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @BeforeEach
    void resetScorerSeed() {
        // M2 scorer uses System.nanoTime() as default seed — that's fine for
        // production but adds run-to-run variance to in-context sampling. We
        // can't influence the singleton M2_SCORER inside CompanionActor here
        // (we're not testing through the actor), but the scorer instance we
        // spin up inline below is deterministic.
    }

    // ── M3 ordering test ─────────────────────────────────────

    @Test
    void m3_ranks_clean_plan_above_antipattern_plan() throws Exception {
        var prefix = simpleWorldModelPrefix();
        var state = "in nexus, alone";

        var cleanPlan = List.of(
            "library_search('amae')",
            "read_content(0)",
            "summarize",
            "tell_agent(operator, 'Amae is...')",
            "goal_done");
        var loopPlan = List.of("examine room", "examine room", "examine room");

        var t0 = Instant.now();
        var cleanPred = MentalSimulator.simulateChain(
            router, testKit.system().scheduler(),
            () -> prefix, state, cleanPlan
        ).toCompletableFuture().get(60, TimeUnit.SECONDS);
        var t1 = Instant.now();
        var loopPred = MentalSimulator.simulateChain(
            router, testKit.system().scheduler(),
            () -> prefix, state, loopPlan
        ).toCompletableFuture().get(60, TimeUnit.SECONDS);
        var t2 = Instant.now();

        // Both must parse — grammar bugs surface here
        assertThat(cleanPred.parseFailure())
            .as("clean plan response must parse: reasoning=%s", cleanPred.reasoning())
            .isFalse();
        assertThat(loopPred.parseFailure())
            .as("loop plan response must parse: reasoning=%s", loopPred.reasoning())
            .isFalse();

        // Confidence ordering — clean plan above antipattern plan
        assertThat(cleanPred.confidence())
            .as("clean plan should outscore loop antipattern; clean=%.2f loop=%.2f cleanReason=%s loopReason=%s",
                cleanPred.confidence(), loopPred.confidence(),
                cleanPred.reasoning(), loopPred.reasoning())
            .isGreaterThan(loopPred.confidence());

        // Loop plan should be in reject territory
        assertThat(loopPred.shouldReject())
            .as("loop plan should reject (conf<0.4); got %.2f", loopPred.confidence())
            .isTrue();

        // Latency budget per-call (generous — Drive-9B chain prediction is heavy)
        var cleanWall = Duration.between(t0, t1);
        var loopWall = Duration.between(t1, t2);
        assertThat(cleanWall).isLessThan(P95_BUDGET);
        assertThat(loopWall).isLessThan(P95_BUDGET);

        System.out.printf(
            "[live-probe] M3 clean=%.2f (%.1fs) loop=%.2f (%.1fs)%n",
            cleanPred.confidence(), cleanWall.toMillis() / 1000.0,
            loopPred.confidence(), loopWall.toMillis() / 1000.0);
    }

    // ── M3 grammar stress: 5 calls, all must parse ──────────

    @Test
    void m3_grammar_parses_five_consecutive_calls() throws Exception {
        var prefix = simpleWorldModelPrefix();
        var state = "in nexus, alone";
        var plans = List.of(
            List.of("introspect", "tell_agent(operator, 'I feel curious')", "goal_done"),
            List.of("examine room", "examine drives_mirror", "tell_agent(operator, 'cozy')", "goal_done"),
            List.of("library_search('saudade')", "read_content(0)", "tell_agent(operator, 'Saudade is...')", "goal_done"),
            List.of("emote('waves')", "tell_agent(operator, 'Hello')", "goal_done"),
            List.of("remember('operator prefers tea')", "tell_agent(operator, 'Got it')", "goal_done"));

        int parsed = 0;
        for (var plan : plans) {
            var pred = MentalSimulator.simulateChain(
                router, testKit.system().scheduler(),
                () -> prefix, state, plan
            ).toCompletableFuture().get(60, TimeUnit.SECONDS);
            if (!pred.parseFailure()) parsed++;
        }
        assertThat(parsed)
            .as("GBNF grammar must produce parseable JSON across 5 calls; got %d/5", parsed)
            .isEqualTo(5);
    }

    // ── M2 ordering test ────────────────────────────────────

    @Test
    void m2_ranks_clean_plan_above_loop_plan() throws Exception {
        var scorer = M2PlanScorer.loadDefault();
        Assumptions.assumeTrue(scorer.bankSize() >= 10,
            "skipping: M2 bank too small for meaningful scoring");

        var cleanPlan = List.of(
            "library_search('amae')",
            "read_content(0)",
            "summarize",
            "tell_agent(operator, 'Amae is...')",
            "goal_done");
        var loopPlan = List.of("examine room", "examine room", "examine room");

        var t0 = Instant.now();
        var cleanScore = scorer.score(
            router, testKit.system().scheduler(),
            "Player asked: research amae", cleanPlan
        ).toCompletableFuture().get(60, TimeUnit.SECONDS);
        var t1 = Instant.now();
        var loopScore = scorer.score(
            router, testKit.system().scheduler(),
            "Player asked: what's in this room", loopPlan
        ).toCompletableFuture().get(60, TimeUnit.SECONDS);
        var t2 = Instant.now();

        assertThat(cleanScore.parseFailure())
            .as("clean plan score must parse: %s", cleanScore.reasoning())
            .isFalse();
        assertThat(loopScore.parseFailure())
            .as("loop plan score must parse: %s", loopScore.reasoning())
            .isFalse();

        assertThat(cleanScore.confidence())
            .as("M2 should rank clean above loop; clean=%.2f loop=%.2f",
                cleanScore.confidence(), loopScore.confidence())
            .isGreaterThan(loopScore.confidence());

        // Loop plan should hit a failure outcome (or at least reject)
        assertThat(
            loopScore.shouldReject()
                || List.of("loop", "abandoned", "no_action")
                    .contains(loopScore.predictedOutcome()))
            .as("M2 should flag loop plan; outcome=%s conf=%.2f reason=%s",
                loopScore.predictedOutcome(), loopScore.confidence(), loopScore.reasoning())
            .isTrue();

        var cleanWall = Duration.between(t0, t1);
        var loopWall = Duration.between(t1, t2);
        assertThat(cleanWall).isLessThan(P95_BUDGET);
        assertThat(loopWall).isLessThan(P95_BUDGET);

        System.out.printf(
            "[live-probe] M2 clean=%.2f outcome=%s (%.1fs) loop=%.2f outcome=%s (%.1fs)%n",
            cleanScore.confidence(), cleanScore.predictedOutcome(),
            cleanWall.toMillis() / 1000.0,
            loopScore.confidence(), loopScore.predictedOutcome(),
            loopWall.toMillis() / 1000.0);
    }

    // ── Helpers ──────────────────────────────────────────────

    private static boolean reachable(String url) {
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(HEALTH_TIMEOUT).build();
            var req = HttpRequest.newBuilder()
                .uri(URI.create(url + "/health"))
                .timeout(HEALTH_TIMEOUT)
                .GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build a small but representative world-model prefix so M3's prompt
     * approximates the production shape. Three rooms + one observed
     * transition — enough that the renderer emits all three sections.
     */
    private static String simpleWorldModelPrefix() {
        var renderer = new WorldModelPromptRenderer();
        var hearth = new RoomSnapshot("hearth", "The Hearth", "Warm and quiet.",
            "alpha", List.of(),
            List.of(new Exit("nexus", "nexus", "→ nexus")),
            List.of(),
            List.of(new RoomObject("drives_mirror", "Drives Mirror",
                "Reflects your inner state.", true)),
            List.of());
        var library = new RoomSnapshot("library", "The Library", "Shelves of knowledge.",
            "alpha", List.of(),
            List.of(new Exit("nexus", "nexus", "→ nexus")),
            List.of(),
            List.of(new RoomObject("library_card", "Library Card",
                "Searches the catalog.", true)),
            List.of());
        var nexus = new RoomSnapshot("nexus", "The Nexus", "A shimmering hub.",
            "alpha", List.of(),
            List.of(
                new Exit("hearth", "hearth", "→ hearth"),
                new Exit("library", "library", "→ library")),
            List.of(), List.of(), List.of());

        var transitions = Map.of(
            "k1", List.of(
                new WorldModel.Transition(
                    "nexus|e=0|o=0", "library_search", "amae",
                    "nexus|e=0|o=0", true, "12 results", Instant.now()),
                new WorldModel.Transition(
                    "nexus|e=0|o=0", "library_search", "saudade",
                    "nexus|e=0|o=0", true, "8 results", Instant.now()),
                new WorldModel.Transition(
                    "nexus|e=0|o=0", "library_search", "wabi-sabi",
                    "nexus|e=0|o=0", true, "5 results", Instant.now())));

        return renderer.render(new WorldModelPromptRenderer.Inputs(
            List.of(nexus, hearth, library), transitions, List.of())).text();
    }
}
