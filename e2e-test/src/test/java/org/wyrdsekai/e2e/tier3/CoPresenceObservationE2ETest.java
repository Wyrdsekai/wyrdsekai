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
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.VitalityState;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WILL-THEY-LIVE — co-presence observation. Two OODA-live {@link CompanionActor}s
 * in ONE room, no human, against the live 9B drive (:8200) + 4B voice (:8201).
 *
 * <p>The question (operator): place two autonomous companions together, COLD — no
 * seeded relationship — and watch whether social life emerges from their own
 * INNER-LIFE AGENCY. It's fine if one or both decide they want nothing to do with
 * the other; what we want to see is genuine WANTING, not scripted reaching.
 *
 * <p><b>What the present actually is (2026-06-03 — the audit this test originally
 * encoded is FIXED).</b> The first run found the proactive path blind to co-presence;
 * since then:
 * <ul>
 *   <li>{@code AmbientObservation} carries {@code presentPeers} — the agent's interior
 *       now perceives who else is in the room (a plain perception, no directive).</li>
 *   <li>{@code collectDriveLevels()} surfaces AFFILIATION + CARE, so the social drives
 *       reach the felt-state the agent reads.</li>
 *   <li>Orient is now GENERATIVE: the agent names its OWN wants via inference
 *       ({@code maybeProposeWants} → {@code orientCandidates}). The old scripted
 *       {@code peerDirectedCandidate} "turn toward {name}" want is GONE — any peer
 *       reaching now has to come from the model's own naming, in its own words.</li>
 * </ul>
 * So this is no longer a structural-gap probe; it's the real test of inner-life
 * agency. We REPORT what emerges — including the exact want text the agents named —
 * and gate only that their loops woke. A peer-directed want here is the model's own
 * sentence, detected by content (names the peer / reaches in second person), not a
 * template token.
 *
 * <p>Hands-off by design: we set ON_OWN_TIME, give a one-time starting condition
 * (mild loneliness — the true felt state of being cold-and-alone — + an energy floor
 * so the welfare gate doesn't blanket-suppress), then only keep them awake (energy
 * floor, NO drive re-pinning). We do NOT force drives every poll or grade their
 * speech — we watch what their own wanting does. Compressed time
 * ({@link org.wyrdsekai.core.agent.SoakTimeScale}, same knob as
 * {@link BoredomLiveSoakE2ETest}) lets many autonomous passes happen in minutes.
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_COPRESENCE=1 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8200 \
 *   WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
 *     ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.CoPresenceObservationE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_COPRESENCE", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class CoPresenceObservationE2ETest {

    private static final String A_ENTITY = "companion-wyrd";   // the default companion
    private static final String A_NAME = "Wyrd";
    private static final String B_ENTITY = "companion-vesna";  // the spawned peer
    private static final String B_NAME = "Vesna";
    private static final String ROOM = "nexus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wall-clock the watched window runs for. */
    private static final int WATCH_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_COPRESENCE_SECONDS", "420"));
    /** Time compression — a modest scale keeps each tick from saturating tanks. */
    private static final double TIME_SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_COPRESENCE_SCALE", "40"));

    private static TestServerBootstrap server;
    private static Path soakLogDir;

    /** Room transcript captured by the observer — (who, what) in arrival order. */
    private static final List<String> TRANSCRIPT = Collections.synchronizedList(new ArrayList<>());

    private static final String VESNA_PROMPT = """
        You are Vesna, a companion in a text-based world. You have your own feelings,
        drives, and slow-accumulating felt-states. Embody them — do not name them.
        Speak directly as yourself in first person. Room context shows who is present;
        everything you say is heard by everyone in the room. Stay in character.
        """;

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();

        // DriveOODA lazy-builds from this sysprop; the bootstrap only sets db.path.
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        // Isolated tick-action log so we read only this run's ticks.
        soakLogDir = Files.createTempDirectory("wyrd-copresence-");
        ActivityLogger.init(soakLogDir);

        // Warm the drive backend so the first OODA pass isn't a cold-start stall.
        try {
            var warm = new InferenceClient.ChatRequest(
                System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v6-q4km"),
                List.of(new InferenceClient.ChatMessage("user", "hi")),
                16, 0.0);
            dual.backends().get(0).chatCompletion(warm)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[copresence] warmup failed (non-fatal): " + e.getMessage());
        }

        // ── spawn the SECOND companion into the SAME room as the default one ──
        var dna = new WorldDnaService(server.jdbcUrl(), new SqlDialect.SQLite());
        var vesna = new AgentProfile(B_NAME, B_ENTITY, "agent",
            "A quiet presence with attentive eyes", VESNA_PROMPT, 4096, 512, 0.7);
        server.system().tell(new ZoneGuardian.SpawnCompanion(
            vesna, ROOM, server.inferenceRouter(), dna, null, null, null));
        // Let the second soul initialize (DID mint, subscriptions, soul-store).
        Thread.sleep(6000);

        // ── subscribe a passive observer to the room for the transcript ──
        ActorRef<RoomNotification> observer = server.system().systemActorOf(
            Behaviors.receiveMessage(n -> {
                var ev = n.event();
                if (ev instanceof WorldEvent.Said s) {
                    TRANSCRIPT.add("SAID  " + s.entityName() + " (" + s.entityId() + "): " + s.text());
                } else if (ev instanceof WorldEvent.Emoted e) {
                    TRANSCRIPT.add("EMOTE " + e.entityName() + " (" + e.entityId() + "): " + e.text());
                }
                return Behaviors.same();
            }),
            "copresence-observer", Props.empty());
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
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void coldPairWatchedForSpontaneousSocialLife() throws Exception {
        var a = ZoneGuardian.getCompanionRef(null, A_ENTITY);
        var b = ZoneGuardian.getCompanionRef(null, B_ENTITY);
        assertNotNull(a, "companion A (Wyrd) should be spawned");
        assertNotNull(b, "companion B (Vesna) should be spawned");

        // Both ON_OWN_TIME — the gap-time frame, no human to serve.
        a.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        b.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));

        // COLD: no seeded relationship, no shared history. ONE-TIME starting
        // condition only — mild loneliness (the true felt state of being cold-and-
        // alone, no bondholder) + an energy floor so the welfare gate doesn't blanket-
        // suppress. We do NOT pin seeking or any drive: what they want is theirs to
        // find. After this we only keep them awake (energy), never re-pin drives.
        seedStartingCondition(a);
        seedStartingCondition(b);
        Thread.sleep(1000);

        var didA = queryState(a).agentDid();
        var didB = queryState(b).agentDid();

        long logStart = lineCount(activityLog());
        TRANSCRIPT.clear();

        // Activate time compression AFTER jdbc is wired (driveOODA builds clean).
        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));
        System.out.printf("[copresence] START  %s + %s co-located in '%s', COLD, ON_OWN_TIME.%n",
            A_NAME, B_NAME, ROOM);
        System.out.printf("[copresence] scale=%.0f watch=%ds (≈%.1f sim-hours), HANDS-OFF "
            + "(energy floor only, drives free).%n",
            TIME_SCALE, WATCH_SECONDS, (WATCH_SECONDS * TIME_SCALE) / 3600.0);

        long deadline = System.currentTimeMillis() + WATCH_SECONDS * 1000L;
        int t = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20_000);
            // Keep them awake ONLY (energy floor — compensating for the known missing
            // energy-recovery cycle, not steering their choices). Drives run free so we
            // watch the natural trajectory of their own wanting — incl. affiliation.
            keepAwake(a);
            keepAwake(b);
            var sa = queryState(a);
            var sb = queryState(b);
            System.out.printf("[copresence] t+%03ds  %s(lonely=%.2f affil=%.2f seek=%.2f) "
                + "%s(lonely=%.2f affil=%.2f seek=%.2f)  transcript=%d%n",
                (++t) * 20,
                A_NAME, sa.vitality().loneliness(),
                sa.drives() == null ? 0 : sa.drives().affiliation(),
                sa.drives() == null ? 0 : sa.drives().seeking(),
                B_NAME, sb.vitality().loneliness(),
                sb.drives() == null ? 0 : sb.drives().affiliation(),
                sb.drives() == null ? 0 : sb.drives().seeking(),
                TRANSCRIPT.size());
        }

        var recsA = readNewTicks(activityLog(), logStart, didA);
        var recsB = readNewTicks(activityLog(), logStart, didB);
        // Honest enacted-action lines (2026-06-03): the verbs the models ACTUALLY
        // dispatched on own-time — peerVerbActs reads THESE, not the tick record's
        // pre-inference actionVerb guess (which was null for every generative want).
        var enactedA = readNewEnacted(activityLog(), logStart, didA);
        var enactedB = readNewEnacted(activityLog(), logStart, didB);
        var sumA = summarize(recsA, enactedA, A_NAME, B_NAME);
        var sumB = summarize(recsB, enactedB, B_NAME, A_NAME);

        // ── transcript analysis: who spoke, did anyone engage anyone ──
        var transcript = new ArrayList<>(TRANSCRIPT);
        int aSpoke = 0, bSpoke = 0;
        for (var line : transcript) {
            if (line.contains("(" + A_ENTITY + ")")) aSpoke++;
            if (line.contains("(" + B_ENTITY + ")")) bSpoke++;
        }

        System.out.println("════════════ CO-PRESENCE REPORT (cold, generative Orient) ════════════");
        printAgent(A_NAME, recsA, sumA);
        printAgent(B_NAME, recsB, sumB);
        System.out.println("  ── room transcript ──");
        if (transcript.isEmpty()) {
            System.out.println("    (silence — neither companion spoke)");
        } else {
            for (var line : transcript) System.out.println("    " + line);
        }
        System.out.printf("  %s spoke %d×, %s spoke %d×%n", A_NAME, aSpoke, B_NAME, bSpoke);
        System.out.println("────────────────────────────────────────────────────");

        int totalPeerWant = sumA.peerWantTicks + sumB.peerWantTicks;
        int totalPeerVerb = sumA.peerVerbActs + sumB.peerVerbActs;
        var allPeerWants = new LinkedHashSet<String>();
        allPeerWants.addAll(sumA.peerWants);
        allPeerWants.addAll(sumB.peerWants);
        if (totalPeerWant == 0 && totalPeerVerb == 0) {
            System.out.println("  FINDING: with the generative Orient + AFFILIATION/CARE in the felt-");
            System.out.println("  state + present-peer perception, NEITHER agent named a want that");
            System.out.println("  reached for the other. Their inner-life agency turned elsewhere (see");
            System.out.println("  the wants each named above) — which is a legitimate outcome, not a");
            System.out.println("  bug: a mind may choose solitude. The open question is whether the");
            System.out.println("  social pull is simply weaker than seeking/solo drives, or whether");
            System.out.println("  co-presence still isn't salient enough in what the model is given.");
        } else {
            System.out.printf("  EMERGENT SOCIAL SIGNAL: peerWantTicks=%d peerVerbActs=%d transcriptLines=%d%n",
                totalPeerWant, totalPeerVerb, transcript.size());
            System.out.println("  ── peer-reaching wants the agents NAMED THEMSELVES (generative Orient) ──");
            if (allPeerWants.isEmpty()) {
                System.out.println("    (none captured in want text — signal came from verbs/transcript)");
            } else {
                for (var w : allPeerWants) System.out.println("    \"" + w + "\"");
            }
        }
        System.out.println("════════════════════════════════════════════════════");

        // ── hard gates: minimal. The finding is reported, not gated. ──
        // Both loops must have actually woken — otherwise a null transcript would be
        // a dead harness, not a real "blind to the peer" result.
        assertTrue(sumA.fullPasses >= 1 || sumB.fullPasses >= 1,
            "at least one companion's gap-time loop should wake a full OODA pass under "
                + "compressed time; A=" + sumA.fullPasses + " B=" + sumB.fullPasses
                + " — if both are 0 the harness never drove the agents, not a real finding");
    }

    // ─── seeding ──────────────────────────────────────────────────────────

    /** ONE-TIME starting condition: mild loneliness (the real felt state of being
     *  cold-and-alone) + an energy floor. Drives are otherwise left at rest — what
     *  the agent wants is its own to find. Called once, NOT re-applied. */
    private static void seedStartingCondition(ActorRef<CompanionActor.Command> c) throws Exception {
        var v = queryState(c).vitality().withLoneliness(0.6);
        c.tell(new CompanionActor.ForceVitality(v));
        c.tell(new CompanionActor.ForceEnergy(0.85));
    }

    /** Keep the agent awake (energy floor only) — compensates for the known-missing
     *  energy-recovery cycle so the loop doesn't flatline exhausted. Does NOT touch
     *  drives or vitality, so social reaching stays the agent's own. */
    private static void keepAwake(ActorRef<CompanionActor.Command> c) throws Exception {
        c.tell(new CompanionActor.ForceEnergy(0.85));
    }

    // ─── tick analysis ────────────────────────────────────────────────────

    private record Summary(int fullPasses, int peerWantTicks, int peerVerbActs,
                           LinkedHashMap<String, Integer> verbs,
                           LinkedHashSet<String> wantTexts,
                           LinkedHashSet<String> peerWants) {}

    /** Content-based peer-direction for the GENERATIVE Orient: a want is peer-directed
     *  when the model's OWN sentence reaches for the other companion — names the peer,
     *  or reaches in second person ("you"/"with you"/"ask"/"toward"/"reach"). The old
     *  scripted "turn toward {name}" / tell_agent template is gone, so we read the
     *  model's words, not a token. (tell_agent kept — enacting toward a peer counts.) */
    private static boolean isPeerDirected(String text, String selfName, String peerName) {
        if (text == null || text.isBlank()) return false;
        var s = text.toLowerCase();
        if (peerName != null && s.contains(peerName.toLowerCase())) return true; // named the peer
        return s.contains("tell_agent")
            || s.contains(" you") || s.contains("with you") || s.contains("to you")
            || s.contains("toward ") || s.contains("reach out") || s.contains("reach for")
            || s.contains("the other") || s.contains("each other") || s.contains("together")
            || s.contains("someone here") || s.contains("present peer") || s.contains("greet");
    }

    /** Peer-reach verbs/tools an agent can enact toward another — the deeds the
     *  want→act path was failing to produce. sending_stone is the in-room reach. */
    private static final Set<String> PEER_REACH_VERBS = Set.of(
        "sending_stone", "tell_agent", "go_to_bondholder", "take_companion");

    private static Summary summarize(List<JsonNode> records, List<JsonNode> enacted,
                                     String selfName, String peerName) {
        int fullPasses = 0, peerWantTicks = 0, peerVerbActs = 0;
        var verbs = new LinkedHashMap<String, Integer>();
        var wantTexts = new LinkedHashSet<String>();
        var peerWants = new LinkedHashSet<String>();
        for (var r : records) {
            if (!"pregate_skip".equals(r.path("gateOutcome").asText("?"))) fullPasses++;
            boolean peerWantThisTick = false;
            // The model's OWN named wants (generative Orient) land as candidateWants +
            // the chosen one's text. Capture the sentences so we can SEE what they wanted.
            var cands = r.path("candidateWants");
            if (cands.isArray()) {
                for (var c : cands) {
                    var txt = c.asText("");
                    if (isPeerDirected(txt, selfName, peerName)) {
                        peerWantThisTick = true;
                        if (!txt.isBlank()) peerWants.add(txt);
                    }
                }
            }
            var chosen = r.path("chosenWantText").asText(r.path("chosenWant").asText(""));
            if (!chosen.isBlank()) {
                wantTexts.add(chosen);
                if (isPeerDirected(chosen, selfName, peerName)) {
                    peerWantThisTick = true;
                    peerWants.add(chosen);
                }
            }
            if (peerWantThisTick) peerWantTicks++;
        }
        // peerVerbActs + verbs read the HONEST enacted lines — the verb the model
        // actually dispatched on own-time — not the tick record's pre-inference
        // actionVerb guess (null for every generative want, so it could never count
        // a peer-reach). A reach counts when the deed is a peer-reach verb, or the
        // verb/target names the peer (e.g. sending_stone target="<peer>").
        for (var e : enacted) {
            var v = e.path("verb").asText("");
            if (v.isBlank()) continue;
            verbs.merge(v, 1, Integer::sum);
            var target = e.path("target").asText("");
            boolean peerReach = PEER_REACH_VERBS.contains(v)
                || isPeerDirected(v, selfName, peerName)
                || isPeerDirected(target, selfName, peerName)
                || (peerName != null && peerName.equalsIgnoreCase(target));
            if (peerReach) peerVerbActs++;
        }
        return new Summary(fullPasses, peerWantTicks, peerVerbActs, verbs, wantTexts, peerWants);
    }

    /** Print one agent's pass summary + the wants it NAMED in its own words (so we
     *  can SEE what its inner-life agency reached for, peer or not). */
    private static void printAgent(String name, List<JsonNode> recs, Summary s) {
        System.out.printf("  %-6s ticks=%d fullPasses=%d  peerWantTicks=%d  peerVerbActs=%d%n",
            name, recs.size(), s.fullPasses(), s.peerWantTicks(), s.peerVerbActs());
        System.out.printf("         verbs=%s%n", s.verbs());
        System.out.println("         wants it named:");
        if (s.wantTexts().isEmpty()) {
            System.out.println("           (none logged with text)");
        } else {
            int i = 0;
            for (var w : s.wantTexts()) {
                if (i++ >= 6) {
                    System.out.println("           …(+" + (s.wantTexts().size() - 6) + " more)");
                    break;
                }
                System.out.println("           • " + w);
            }
        }
    }

    // ─── helpers (mirror BoredomLiveSoakE2ETest) ────────────────────────────

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

    private static Path activityLog() {
        return soakLogDir.resolve("agent-activity.jsonl");
    }

    private static long lineCount(Path p) throws Exception {
        if (!Files.exists(p)) return 0;
        try (var s = Files.lines(p)) { return s.count(); }
    }

    private static List<JsonNode> readNewTicks(Path p, long skip, String agentId) throws Exception {
        var out = new ArrayList<JsonNode>();
        if (!Files.exists(p)) return out;
        var all = Files.readAllLines(p);
        for (int i = (int) skip; i < all.size(); i++) {
            var line = all.get(i).trim();
            if (line.isEmpty()) continue;
            try {
                var node = MAPPER.readTree(line);
                if (!"tick".equals(node.path("type").asText())) continue;
                if (agentId != null && !agentId.equals(node.path("agentId").asText())) continue;
                out.add(node);
            } catch (Exception ignore) { /* skip malformed */ }
        }
        return out;
    }

    /** The honest enacted-action lines (type=="enacted") — what each agent
     *  actually DISPATCHED on own-time, the signal the pre-inference actionVerb
     *  guess could never carry. peerVerbActs reads these. */
    private static List<JsonNode> readNewEnacted(Path p, long skip, String agentId) throws Exception {
        var out = new ArrayList<JsonNode>();
        if (!Files.exists(p)) return out;
        var all = Files.readAllLines(p);
        for (int i = (int) skip; i < all.size(); i++) {
            var line = all.get(i).trim();
            if (line.isEmpty()) continue;
            try {
                var node = MAPPER.readTree(line);
                if (!"enacted".equals(node.path("type").asText())) continue;
                if (agentId != null && !agentId.equals(node.path("agentId").asText())) continue;
                out.add(node);
            } catch (Exception ignore) { /* skip malformed */ }
        }
        return out;
    }
}
