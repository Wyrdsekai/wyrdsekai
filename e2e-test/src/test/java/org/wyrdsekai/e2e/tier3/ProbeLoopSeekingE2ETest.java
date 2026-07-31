package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.interiority.ProbeLoop;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WILL-THEY-LIVE — the closed action-loop on the EPISTEMIC drive (, generalized).
 *
 * <p>Sibling of {@link ProbeLoopReachE2ETest}, which proves the loop on the appetitive/social side
 * (AFFILIATION reach → peer answers). This proves the SAME loop on a genuinely DIFFERENT probe
 * shape: SEEKING. A world-query ({@code library_search}/{@code examine}) is an <b>epistemic probe</b>
 * — it pushes the world a question and awaits an <b>answer</b> (a novel result). With an EMPTY
 * library every query comes back with nothing new = SILENCE, so the question stays open: SEEKING
 * sharpens and the next Orient asks AGAIN (persist-retry), disengaging after the cap.
 *
 * <p>One companion, no human, no peer needed. We seed SEEKING high so its generative Orient names a
 * curiosity want → it queries the world → the result-arrival site ({@code applyProductionFeedback})
 * is where the epistemic probe closes (novel → ANSWERED; empty → UNANSWERED → sharpen + persist).
 *
 * <p>Instrument = the {@code ProbeLoop:} log lines for the SEEKING drive:
 * <ul>
 *   <li>{@code probes the world for '<query>' — awaiting a return (attempt N)} — a query opened;
 *       <b>attempt ≥ 2 is the persist signature</b> (an unanswered question re-asked).</li>
 *   <li>{@code seeking probe ... UNANSWERED (streak N) — seeking sharpened} — silence revised the want.</li>
 *   <li>{@code unanswered N× — disengaging} — the question is set down after the cap.</li>
 *   <li>{@code was ANSWERED — loop closed} — a novel result settled the curiosity.</li>
 * </ul>
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_PROBE_SEEKING=1 \
 *   WYRD_PROBE_WINDOW_SECONDS=8 WYRD_PROBE_MAX_ATTEMPTS=3 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8210 \
 *   WYRDSEKAI_E2E_VOICE_URL=http://localhost:8211 \
 *     ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.ProbeLoopSeekingE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_PROBE_SEEKING", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class ProbeLoopSeekingE2ETest {

    private static final String A_ENTITY = "companion-wyrd";
    private static final String A_NAME = "Wyrd";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int WATCH_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_PROBE_SEEKING_SECONDS", "150"));
    private static final double TIME_SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_PROBE_SEEKING_SCALE", "40"));

    private static TestServerBootstrap server;
    private static Path soakLogDir;

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();

        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        soakLogDir = Files.createTempDirectory("wyrd-probeseeking-");
        ActivityLogger.init(soakLogDir);

        try {
            var warm = new InferenceClient.ChatRequest(
                System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v6-q4km"),
                List.of(new InferenceClient.ChatMessage("user", "hi")),
                16, 0.0);
            dual.backends().get(0).chatCompletion(warm)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[probeseeking] warmup failed (non-fatal): " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        System.clearProperty("wyrd.soak.time.scale");
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void unansweredQueryPersistsAndReasks() throws Exception {
        var a = ZoneGuardian.getCompanionRef(null, A_ENTITY);
        assertNotNull(a, "companion A (Wyrd) should be spawned");

        a.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));

        // Seed SEEKING high (ONCE) so the generative Orient names a curiosity want → the agent queries
        // the world (library_search/examine). The library is EMPTY (fresh test DB), so every query
        // comes back with nothing novel — the epistemic probe is UNANSWERED and must PERSIST (re-ask),
        // not fire once and forget. We do NOT re-pin seeking: the ProbeLoop sharpen is what keeps the
        // question alive across unanswered windows — re-pinning would mask exactly that.
        a.tell(new CompanionActor.ForceDrives(DriveState.initial().spikeSeeking(0.9)));
        a.tell(new CompanionActor.ForceEnergy(0.85));
        Thread.sleep(1000);

        var didA = queryState(a).agentDid();
        long logStart = lineCount(activityLog());

        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));
        System.out.printf("[probeseeking] START  %s (seeking-seeded, empty library), ON_OWN_TIME. "
            + "window=%ds maxAttempts=%d scale=%.0f watch=%ds — watching the epistemic persist arc.%n",
            A_NAME,
            ProbeLoop.WINDOW_SECONDS,
            ProbeLoop.MAX_ATTEMPTS,
            TIME_SCALE, WATCH_SECONDS);

        long deadline = System.currentTimeMillis() + WATCH_SECONDS * 1000L;
        int t = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            a.tell(new CompanionActor.ForceEnergy(0.85));   // keep awake only
            var sa = queryState(a);
            System.out.printf("[probeseeking] t+%03ds  %s(seeking=%.2f frustration=%.2f)%n",
                (++t) * 15, A_NAME,
                sa.drives() == null ? 0 : sa.drives().seeking(),
                sa.drives() == null ? 0 : sa.drives().frustration());
        }

        var recsA = readNewTicks(activityLog(), logStart, didA);
        var enactedA = readNewEnacted(activityLog(), logStart, didA);
        int fullPasses = 0;
        for (var r : recsA) {
            if (!"pregate_skip".equals(r.path("gateOutcome").asText("?"))) fullPasses++;
        }
        var verbs = new LinkedHashSet<String>();
        int queryActs = 0;
        for (var e : enactedA) {
            var v = e.path("verb").asText("");
            if (!v.isBlank()) verbs.add(v);
            if (SEEKING_QUERY_VERBS.contains(v)) queryActs++;
        }

        System.out.println("════════════ PROBE-LOOP SEEKING REPORT ════════════");
        System.out.printf("  %s  ticks=%d fullPasses=%d  queryActs=%d  verbs=%s%n",
            A_NAME, recsA.size(), fullPasses, queryActs, verbs);
        System.out.println("  ── how to read the result ──");
        System.out.println("  grep stdout for 'ProbeLoop:' lines from '" + A_NAME + "':");
        System.out.println("    PASS (a LOOP): a 'probes the world for ... (attempt 2)' (or higher) —");
        System.out.println("         the unanswered question PERSISTED and was re-asked; bonus: a");
        System.out.println("         'seeking probe ... UNANSWERED (streak N) — seeking sharpened' before");
        System.out.println("         it, and a 'disengaging' close after the cap.");
        System.out.println("    FAIL (a REFLEX): only 'attempt 1' query lines, never a re-ask.");
        System.out.println("    Closed case: 'was ANSWERED — loop closed' = a novel result settled it");
        System.out.println("         (rare with an empty library; the persist test is the empty returns).");
        System.out.println("════════════════════════════════════════════════════");

        assertTrue(fullPasses >= 1,
            "A's gap-time loop should wake ≥1 full OODA pass under compressed time; got "
                + fullPasses + " — if 0, the harness never drove the seeker (dead harness, not a "
                + "real 'no loop' result)");
    }

    private static final Set<String> SEEKING_QUERY_VERBS = Set.of(
        "library_search", "examine", "web_search", "read_content");

    private static CompanionActor.TestStateResponse queryState(
            ActorRef<CompanionActor.Command> companion) throws Exception {
        return AskPattern.ask(
            companion,
            (ActorRef<CompanionActor.TestStateResponse> ref)
                -> new CompanionActor.QueryTestState(ref),
            Duration.ofSeconds(10),
            server.system().scheduler()
        ).toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    private static Path activityLog() { return soakLogDir.resolve("agent-activity.jsonl"); }

    private static long lineCount(Path p) throws Exception {
        if (!Files.exists(p)) return 0;
        try (var s = Files.lines(p)) { return s.count(); }
    }

    private static List<JsonNode> readNewTicks(Path p, long skip, String agentId) throws Exception {
        return readNewByType(p, skip, agentId, "tick");
    }
    private static List<JsonNode> readNewEnacted(Path p, long skip, String agentId) throws Exception {
        return readNewByType(p, skip, agentId, "enacted");
    }
    private static List<JsonNode> readNewByType(Path p, long skip, String agentId, String type)
            throws Exception {
        var out = new ArrayList<JsonNode>();
        if (!Files.exists(p)) return out;
        var all = Files.readAllLines(p);
        for (int i = (int) skip; i < all.size(); i++) {
            var line = all.get(i).trim();
            if (line.isEmpty()) continue;
            try {
                var node = MAPPER.readTree(line);
                if (!type.equals(node.path("type").asText())) continue;
                if (agentId != null && !agentId.equals(node.path("agentId").asText())) continue;
                out.add(node);
            } catch (Exception ignore) { /* skip malformed */ }
        }
        return out;
    }
}
