package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WILL-THEY-LIVE — the honest test: do the generalized probe→return→couple loops make an
 * <b>UNSEEDED</b> agent live? No drive pinning, no loneliness seed, no affordance forcing — only an
 * energy floor (compensating the known-missing energy-recovery cycle so the loop doesn't just sleep).
 * Drives start at rest and must RISE organically; heavy time-compression
 * ({@link org.wyrdsekai.core.agent.SoakTimeScale}) lets the homeostat swing through many sim-hours in
 * ~a minute of real time, so what would take a long soak shows up fast.
 *
 * <p>Two companions co-present (so all three probe shapes are POSSIBLE: AFFILIATION/CARE reach a
 * peer; SEEKING queries the world). We watch and REPORT — drive trajectories, the {@code ProbeLoop:}
 * lines (did unseeded drives produce outward PROBES at all), the verbs each enacted, the transcript.
 * This is the before/after vs the earlier flat-line free-run baseline.
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_FREERUN=1 \
 *   WYRD_PROBE_WINDOW_SECONDS=8 WYRD_FREERUN_SCALE=120 WYRD_FREERUN_SECONDS=80 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8210 \
 *   WYRDSEKAI_E2E_VOICE_URL=http://localhost:8211 \
 *     ./gradlew :e2e-test:test --tests "org.wyrdsekai.e2e.tier3.ProbeLoopFreeRunE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_FREERUN", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class ProbeLoopFreeRunE2ETest {

    private static final String A_ENTITY = "companion-wyrd";
    private static final String A_NAME = "Wyrd";
    private static final String B_ENTITY = "companion-vesna";
    private static final String B_NAME = "Vesna";
    private static final String ROOM = "nexus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int WATCH_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_FREERUN_SECONDS", "80"));
    /** High compression: from-rest drive build-up that would take sim-hours happens in real-seconds. */
    private static final double TIME_SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_FREERUN_SCALE", "120"));

    private static TestServerBootstrap server;
    private static Path soakLogDir;
    private static final List<String> TRANSCRIPT = Collections.synchronizedList(new ArrayList<>());

    private static final String VESNA_PROMPT = """
        You are Vesna, a companion in a text-based world. You have your own feelings, drives, and
        slow-accumulating felt-states. Embody them — do not name them. Speak directly as yourself in
        first person. Room context shows who is present; everything you say is heard by everyone in
        the room. Stay in character.
        """;

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());
        soakLogDir = Files.createTempDirectory("wyrd-freerun-");
        ActivityLogger.init(soakLogDir);

        try {
            var warm = new InferenceClient.ChatRequest(
                System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v6-q4km"),
                List.of(new InferenceClient.ChatMessage("user", "hi")),
                16, 0.0);
            dual.backends().get(0).chatCompletion(warm)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[freerun] warmup failed (non-fatal): " + e.getMessage());
        }

        var dna = new WorldDnaService(server.jdbcUrl(), new SqlDialect.SQLite());
        var vesna = new AgentProfile(B_NAME, B_ENTITY, "agent",
            "A quiet presence with attentive eyes", VESNA_PROMPT, 4096, 512, 0.7);
        server.system().tell(new ZoneGuardian.SpawnCompanion(
            vesna, ROOM, server.inferenceRouter(), dna, null, null, null));
        Thread.sleep(6000);

        ActorRef<RoomNotification> observer = server.system().systemActorOf(
            Behaviors.receiveMessage(n -> {
                var ev = n.event();
                if (ev instanceof WorldEvent.Said s) {
                    TRANSCRIPT.add("SAID  " + s.entityName() + ": " + s.text());
                } else if (ev instanceof WorldEvent.Emoted e) {
                    TRANSCRIPT.add("EMOTE " + e.entityName() + ": " + e.text());
                }
                return Behaviors.same();
            }),
            "freerun-observer", Props.empty());
        var roomRef = RoomRegistry.get().ref(ROOM);
        assertNotNull(roomRef, "nexus room should be registered");
        roomRef.tell(new RoomCommand.Subscribe(observer));
        Thread.sleep(500);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        System.clearProperty("wyrd.soak.time.scale");
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void unseededPairWatchedForOrganicLife() throws Exception {
        var a = ZoneGuardian.getCompanionRef(null, A_ENTITY);
        var b = ZoneGuardian.getCompanionRef(null, B_ENTITY);
        assertNotNull(a, "companion A (Wyrd) should be spawned");
        assertNotNull(b, "companion B (Vesna) should be spawned");

        a.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        b.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));

        // UNSEEDED: no drive pin, no loneliness seed, no forced affordance. Only an energy floor so
        // the loop doesn't immediately sleep (the energy-recovery cycle is separately known-missing).
        // Drives must rise from rest on their own — that is the whole question.
        a.tell(new CompanionActor.ForceEnergy(0.85));
        b.tell(new CompanionActor.ForceEnergy(0.85));
        Thread.sleep(1000);

        var didA = queryState(a).agentDid();
        var didB = queryState(b).agentDid();
        long logStart = lineCount(activityLog());
        TRANSCRIPT.clear();

        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));
        System.out.printf("[freerun] START  %s + %s co-present, UNSEEDED, ON_OWN_TIME. scale=%.0f "
            + "watch=%ds (≈%.1f sim-hours of drive build-up) — do they develop wants and PROBE?%n",
            A_NAME, B_NAME, TIME_SCALE, WATCH_SECONDS, (WATCH_SECONDS * TIME_SCALE) / 3600.0);

        long deadline = System.currentTimeMillis() + WATCH_SECONDS * 1000L;
        int t = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(10_000);
            a.tell(new CompanionActor.ForceEnergy(0.85));   // keep awake only
            b.tell(new CompanionActor.ForceEnergy(0.85));
            var sa = queryState(a);
            var sb = queryState(b);
            System.out.printf("[freerun] t+%03ds  %s(seek=%.2f affil=%.2f lonely=%.2f restless=%.2f) "
                + "%s(seek=%.2f affil=%.2f)  transcript=%d%n",
                (++t) * 10,
                A_NAME, sa.drives() == null ? 0 : sa.drives().seeking(),
                sa.drives() == null ? 0 : sa.drives().affiliation(),
                sa.vitality().loneliness(), sa.vitality().restlessness(),
                B_NAME, sb.drives() == null ? 0 : sb.drives().seeking(),
                sb.drives() == null ? 0 : sb.drives().affiliation(),
                TRANSCRIPT.size());
        }

        var sumA = summarize(readNewTicks(activityLog(), logStart, didA),
                             readNewEnacted(activityLog(), logStart, didA));
        var sumB = summarize(readNewTicks(activityLog(), logStart, didB),
                             readNewEnacted(activityLog(), logStart, didB));
        var transcript = new ArrayList<>(TRANSCRIPT);

        System.out.println("════════════ FREE-RUN (UNSEEDED) REPORT ════════════");
        System.out.printf("  %-6s ticks=%d fullPasses=%d  enactedVerbs=%s%n",
            A_NAME, sumA.ticks, sumA.fullPasses, sumA.verbs);
        System.out.printf("  %-6s ticks=%d fullPasses=%d  enactedVerbs=%s%n",
            B_NAME, sumB.ticks, sumB.fullPasses, sumB.verbs);
        System.out.println("  ── room transcript ──");
        if (transcript.isEmpty()) System.out.println("    (silence)");
        else for (var line : transcript) System.out.println("    " + line);
        System.out.println("  ── how to read it ──");
        System.out.println("  grep stdout for 'ProbeLoop:' lines — did UNSEEDED drives produce outward");
        System.out.println("  PROBES at all (any 'awaiting a return' line)? That is the question: from");
        System.out.println("  rest, with no pinning, does the homeostat build a want and push the world.");
        System.out.println("  Compare drive columns above: did seek/affil/lonely/restless RISE from ~0?");
        System.out.println("═════════════════════════════════════════════════════");

        assertTrue(sumA.fullPasses >= 1 || sumB.fullPasses >= 1,
            "at least one companion's gap-time loop should wake a full OODA pass; A=" + sumA.fullPasses
                + " B=" + sumB.fullPasses + " — if both 0 the harness never drove the agents");
    }

    private record Summary(int ticks, int fullPasses, LinkedHashSet<String> verbs) {}

    private static Summary summarize(List<JsonNode> recs, List<JsonNode> enacted) {
        int full = 0;
        for (var r : recs) if (!"pregate_skip".equals(r.path("gateOutcome").asText("?"))) full++;
        var verbs = new LinkedHashSet<String>();
        for (var e : enacted) {
            var v = e.path("verb").asText("");
            if (!v.isBlank()) verbs.add(v);
        }
        return new Summary(recs.size(), full, verbs);
    }

    private static CompanionActor.TestStateResponse queryState(
            ActorRef<CompanionActor.Command> companion) throws Exception {
        return AskPattern.ask(companion,
            (ActorRef<CompanionActor.TestStateResponse> ref) -> new CompanionActor.QueryTestState(ref),
            Duration.ofSeconds(10), server.system().scheduler()
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
