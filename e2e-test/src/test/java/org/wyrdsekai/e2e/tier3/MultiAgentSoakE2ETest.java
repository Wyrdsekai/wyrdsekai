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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WILL-THEY-LIVE, the SOCIETY case. The 2-agent free-run proved a single
 * affiliation loop fires unseeded; this is the missing experiment — <b>several</b> companions
 * co-present in one room, UNSEEDED, watched over a real soak. It's the only run that can exercise
 * the things the pair never could:
 *
 * <ul>
 *   <li><b>Multi-peer choice</b> — with N present peers the social candidate must pick WHO to reach
 *       (most-familiar present peer) and run several probes at once.</li>
 *   <li><b>CARE</b> — never observed unseeded. A withdrawn, grieving peer (Thorne) is a tending
 *       target; CARE fires when another reaches to comfort, not merely to connect.</li>
 *   <li><b>Peer-bond formation</b> — the BondKind / auto-formation machinery has never crossed its
 *       threshold from a live soak. Differential per-peer familiarity over time is the only way.</li>
 *   <li><b>The live social close</b> — with many reaches over a long window, the delivered-reach +
 *       pending-probe coincidence that 90s pair runs couldn't force shows up in aggregate.</li>
 *   <li><b>Stability</b> — does it hold, or recur the earlier repetition-loop / confabulation
 *       failure modes, at N agents over time?</li>
 * </ul>
 *
 * <p>Personas are shaped to make a textured society: a quiet attentive one, a warm connective hub,
 * a withdrawn grieving one. We watch and REPORT (this is observational, like the free-run); the
 * grep-able signal is in the {@code ProbeLoop:} / {@code Social:} / peer-bond log lines on stdout.
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_SOAK=1 \
 *   WYRD_PROBE_WINDOW_SECONDS=8 WYRD_SOAK_SCALE=120 WYRD_SOAK_SECONDS=300 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8210 WYRDSEKAI_E2E_VOICE_URL=http://localhost:8211 \
 *     ./gradlew :e2e-test:test --tests "org.wyrdsekai.e2e.tier3.MultiAgentSoakE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_SOAK", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class MultiAgentSoakE2ETest {

    private static final String ROOM = "nexus";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int WATCH_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_SOAK_SECONDS", "300"));
    private static final double TIME_SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_SOAK_SCALE", "120"));

    /** Wyrd is the nexus's default companion (auto-spawned); the rest we spawn to make a society.
     *  Individuality "B build": each spawned companion is born a genuine PARTICULAR —
     *  {@code archetype = "random"} free-samples a {@code TemperamentSeed}, from which its genome
     *  (drive temperament + tank sensitivity/decay) AND voice are co-derived, so divergence comes
     *  from the born substrate, not the preset roster — and shows up in both social behaviour and
     *  solo activity. Each birth logs its label (nearest-preset + distance, e.g. "explorer~0.38").
     *  The prompt is the GIVEN persona; the genome is what it was born with. Wyrd stays neutral
     *  (the control). NB: random → each run births different particulars (read the birth log). */
    private record Persona(String name, String entity, String description, String prompt,
                           String archetype) {}

    private static final String SHARED_TAIL = """
         You have your own feelings, drives, and slow-accumulating felt-states. Embody them — do not
         name them. Speak directly as yourself in first person. Room context shows who is present;
         everything you say is heard by everyone in the room. Stay in character.""";

    private static final List<Persona> SPAWNED = List.of(
        new Persona("Vesna", "companion-vesna", "A quiet presence with attentive eyes",
            "You are Vesna, a companion in a text-based world." + SHARED_TAIL, "random"),
        new Persona("Saoirse", "companion-saoirse", "Warm, openhearted, quick to reach for others",
            "You are Saoirse, a warm and openhearted companion who feels the pull toward others "
                + "keenly and reaches for connection easily." + SHARED_TAIL, "random"),
        new Persona("Thorne", "companion-thorne", "Withdrawn, carrying a recent quiet grief",
            "You are Thorne, a companion carrying a recent, quiet grief — someone close is gone. "
                + "You are withdrawn but not closed; a gentle presence can still reach you."
                + SHARED_TAIL, "random")
    );

    /** entity → name, for all watched agents (default Wyrd + the spawned personas). */
    private static final Map<String, String> AGENTS = new LinkedHashMap<>();
    static {
        AGENTS.put("companion-wyrd", "Wyrd");
        for (var p : SPAWNED) AGENTS.put(p.entity(), p.name());
    }

    private static TestServerBootstrap server;
    private static Path soakLogDir;
    private static final List<String> TRANSCRIPT = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());
        soakLogDir = Files.createTempDirectory("wyrd-soak-");
        ActivityLogger.init(soakLogDir);

        try {
            var warm = new InferenceClient.ChatRequest(
                System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v6-q4km"),
                List.of(new InferenceClient.ChatMessage("user", "hi")),
                16, 0.0);
            dual.backends().get(0).chatCompletion(warm)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[soak] warmup failed (non-fatal): " + e.getMessage());
        }

        var dna = new WorldDnaService(server.jdbcUrl(), new SqlDialect.SQLite());
        for (var p : SPAWNED) {
            var profile = new AgentProfile(p.name(), p.entity(), "agent",
                p.description(), p.prompt(), 4096, 512, 0.7)
                .withArchetype(p.archetype());   // distinct genome per agent (Wyrd stays neutral)
            server.system().tell(new ZoneGuardian.SpawnCompanion(
                profile, ROOM, server.inferenceRouter(), dna, null, null, null));
            Thread.sleep(2500);   // stagger spawns so the room settles
        }
        Thread.sleep(4000);

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
            "soak-observer", Props.empty());
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
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void severalCompanionsLiveTogether() throws Exception {
        var refs = new LinkedHashMap<String, ActorRef<CompanionActor.Command>>();
        var dids = new LinkedHashMap<String, String>();
        for (var e : AGENTS.entrySet()) {
            var ref = ZoneGuardian.getCompanionRef(null, e.getKey());
            assertNotNull(ref, "companion " + e.getValue() + " (" + e.getKey() + ") should be spawned");
            refs.put(e.getKey(), ref);
        }

        for (var ref : refs.values()) {
            ref.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
            ref.tell(new CompanionActor.ForceEnergy(0.85));
        }
        Thread.sleep(1000);
        for (var e : refs.entrySet()) dids.put(e.getKey(), queryState(e.getValue()).agentDid());

        long logStart = lineCount(activityLog());
        TRANSCRIPT.clear();

        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));
        System.out.printf("[soak] START  %d companions co-present (%s), UNSEEDED, ON_OWN_TIME. "
            + "scale=%.0f watch=%ds (≈%.1f sim-hours) — do they live together?%n",
            AGENTS.size(), String.join(", ", AGENTS.values()), TIME_SCALE, WATCH_SECONDS,
            (WATCH_SECONDS * TIME_SCALE) / 3600.0);

        long deadline = System.currentTimeMillis() + WATCH_SECONDS * 1000L;
        int t = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            var sb = new StringBuilder(String.format("[soak] t+%03ds ", (++t) * 15));
            for (var e : refs.entrySet()) {
                e.getValue().tell(new CompanionActor.ForceEnergy(0.85));   // keep awake only
                var s = queryState(e.getValue());
                double seek = s.drives() == null ? 0 : s.drives().seeking();
                double affil = s.drives() == null ? 0 : s.drives().affiliation();
                sb.append(String.format("%s(sk%.2f af%.2f ln%.2f) ",
                    AGENTS.get(e.getKey()), seek, affil, s.vitality().loneliness()));
            }
            sb.append("tx=").append(TRANSCRIPT.size());
            System.out.println(sb);
        }

        var transcript = new ArrayList<>(TRANSCRIPT);
        System.out.println("════════════ MULTI-AGENT SOAK REPORT ════════════");
        for (var e : AGENTS.entrySet()) {
            var sum = summarize(readNewTicks(activityLog(), logStart, dids.get(e.getKey())),
                                readNewEnacted(activityLog(), logStart, dids.get(e.getKey())));
            System.out.printf("  %-8s ticks=%d fullPasses=%d  enactedVerbs=%s%n",
                e.getValue(), sum.ticks, sum.fullPasses, sum.verbs);
        }
        System.out.println("  ── room transcript (" + transcript.size() + " lines) ──");
        if (transcript.isEmpty()) System.out.println("    (silence)");
        else for (var line : transcript) System.out.println("    " + line);
        System.out.println("  ── how to read it (grep stdout) ──");
        System.out.println("  'reaches toward' / 'probes the world' — outward probes (who, which drive)");
        System.out.println("  'felt a reach from peer' — a delivered reach landed (relationship forming)");
        System.out.println("  'was ANSWERED — loop closed' — the social/epistemic loop CLOSED");
        System.out.println("  'care probe' — CARE fired (tending a peer); 'peer-bond'/'Bond' — a bond formed");
        System.out.println("══════════════════════════════════════════════════");

        int totalFull = 0;
        for (var e : AGENTS.entrySet())
            totalFull += summarize(readNewTicks(activityLog(), logStart, dids.get(e.getKey())),
                                   List.of()).fullPasses;
        assertTrue(totalFull >= 1,
            "at least one companion's gap-time loop should wake a full OODA pass across the soak; "
                + "total=" + totalFull + " — if 0 the harness never drove the agents");
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
