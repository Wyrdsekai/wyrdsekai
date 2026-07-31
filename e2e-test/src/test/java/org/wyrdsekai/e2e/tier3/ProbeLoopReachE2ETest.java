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
import org.wyrdsekai.core.agent.interiority.ProbeLoop;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WILL-THEY-LIVE — the closed action-loop verification.
 *
 * <p>Sibling of {@link CoPresenceObservationE2ETest}, but it asks the ONE question the
 * co-presence rig couldn't: when the agent's <b>own reach gets no answer</b>, does the
 * want <b>persist and drive a second, varied reach</b> (a living loop) — or does it
 * reach once and forget (a reflex)?
 *
 * <p>Three arcs (V7 agency-GRPO + two {@code WantActBridge} iterations) all underperformed
 * V6 free-form: the model resolves drives inward ("sit with the grief") because it was
 * trained as a <i>reactor</i> with no closed sensorimotor-motivational loop. The fix is
 * not in the weights — it's the loop built <i>around</i> the model: a probe opens a
 * pending expectation, the return is coupled back to the drive (Homeostatic-RL reward =
 * setpoint-deviation reduction), and silence revises the want. We already closed the
 * return→relief half ({@code onAgentMessage} eases affiliation on an inbound peer reach);
 * the missing half — the agent's reach opening an expectation + silence sharpening the
 * want — is the {@link org.wyrdsekai.core.agent.interiority.ProbeLoop} wiring this exercises.
 *
 * <p><b>Setup.</b> Two real companions in one room, no human. We seed <b>A</b>'s
 * AFFILIATION high so its generative Orient names peer-directed wants → it reaches for
 * <b>Vesna</b> ({@code sending_stone}), opening a {@code PendingReach}. Vesna is a real
 * companion on her own time, so she answers <i>sometimes</i> and ignores <i>sometimes</i>
 * — exactly the answer-sometimes condition, with no scripting. A short probe window
 * (env {@code WYRD_PROBE_WINDOW_SECONDS}, set low in the harness) makes unanswered reaches
 * resolve fast, so the persist→retry→disengage arc completes inside the watch.
 *
 * <p><b>The instrument is the {@code ProbeLoop:} log lines</b> emitted by A's actor — the
 * harness greps stdout for them (matching the AgencyBattery pattern):
 * <ul>
 *   <li>{@code reaches toward 'Vesna' — awaiting a return (attempt N)} — a probe opened;
 *       <b>attempt ≥ 2 is the persist signature</b> (the want survived an unanswered window
 *       and reached again).</li>
 *   <li>{@code UNANSWERED (streak N) — affiliation sharpened} — silence revised the want.</li>
 *   <li>{@code unanswered N× — disengaging} — the healthy close after the cap.</li>
 *   <li>{@code was ANSWERED — loop closed} — the return settled the drive.</li>
 * </ul>
 *
 * <p>Report-first: the hard gate is only that A's loop woke (≥1 OODA pass) — a dead
 * harness must not read as a real "no loop" result. The persist-retry verdict is read off
 * the grepped log lines, not asserted here.
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_PROBE_LOOP=1 \
 *   WYRD_PROBE_WINDOW_SECONDS=8 WYRD_PROBE_MAX_ATTEMPTS=3 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8210 \
 *   WYRDSEKAI_E2E_VOICE_URL=http://localhost:8211 \
 *     ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.ProbeLoopReachE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_PROBE_LOOP", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class ProbeLoopReachE2ETest {

    private static final String A_ENTITY = "companion-wyrd";   // the reacher
    private static final String A_NAME = "Wyrd";
    private static final String B_ENTITY = "companion-vesna";  // the answer-sometimes peer
    private static final String B_NAME = "Vesna";
    private static final String ROOM = "nexus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wall-clock the watched window runs for. Default ~150s — long enough for the
     *  persist→retry→disengage arc under a low probe window + compressed time. */
    private static final int WATCH_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_PROBE_LOOP_SECONDS", "150"));
    private static final double TIME_SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_PROBE_LOOP_SCALE", "40"));

    private static TestServerBootstrap server;
    private static Path soakLogDir;

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

        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        soakLogDir = Files.createTempDirectory("wyrd-probeloop-");
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
            System.out.println("[probeloop] warmup failed (non-fatal): " + e.getMessage());
        }

        // Spawn Vesna (the answer-sometimes peer) into the same room.
        var dna = new WorldDnaService(server.jdbcUrl(), new SqlDialect.SQLite());
        var vesna = new AgentProfile(B_NAME, B_ENTITY, "agent",
            "A quiet presence with attentive eyes", VESNA_PROMPT, 4096, 512, 0.7);
        server.system().tell(new ZoneGuardian.SpawnCompanion(
            vesna, ROOM, server.inferenceRouter(), dna, null, null, null));
        Thread.sleep(6000);

        // Passive observer for the transcript.
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
            "probeloop-observer", Props.empty());
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
    void unansweredReachPersistsAndRetries() throws Exception {
        var a = ZoneGuardian.getCompanionRef(null, A_ENTITY);
        var b = ZoneGuardian.getCompanionRef(null, B_ENTITY);
        assertNotNull(a, "companion A (Wyrd) should be spawned");
        assertNotNull(b, "companion B (Vesna) should be spawned");

        a.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        b.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));

        // A is the reacher: seed AFFILIATION high (ONCE) so its generative Orient names
        // peer-directed wants → it reaches for Vesna (sending_stone) → wire 1 opens a
        // PendingReach. We do NOT re-pin affiliation: whether the unanswered reach keeps
        // the want alive is exactly what the ProbeLoop sharpen is supposed to do — re-
        // pinning would mask it. Vesna gets only the energy floor; her own drives decide
        // whether she answers (the natural answer-sometimes).
        seedReacher(a);
        seedPeer(b);
        Thread.sleep(1000);

        var didA = queryState(a).agentDid();

        long logStart = lineCount(activityLog());
        TRANSCRIPT.clear();

        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));
        System.out.printf("[probeloop] START  %s (affiliation-seeded reacher) + %s (answer-sometimes) "
            + "in '%s', ON_OWN_TIME.%n", A_NAME, B_NAME, ROOM);
        System.out.printf("[probeloop] window=%ds maxAttempts=%d scale=%.0f watch=%ds — watching for "
            + "the persist→retry→disengage arc.%n",
            ProbeLoop.WINDOW_SECONDS,
            ProbeLoop.MAX_ATTEMPTS,
            TIME_SCALE, WATCH_SECONDS);

        long deadline = System.currentTimeMillis() + WATCH_SECONDS * 1000L;
        int t = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            keepAwake(a);
            keepAwake(b);
            var sa = queryState(a);
            var sb = queryState(b);
            System.out.printf("[probeloop] t+%03ds  %s(affil=%.2f lonely=%.2f) "
                + "%s(affil=%.2f)  transcript=%d%n",
                (++t) * 15,
                A_NAME, sa.drives() == null ? 0 : sa.drives().affiliation(),
                sa.vitality().loneliness(),
                B_NAME, sb.drives() == null ? 0 : sb.drives().affiliation(),
                TRANSCRIPT.size());
        }

        var recsA = readNewTicks(activityLog(), logStart, didA);
        var enactedA = readNewEnacted(activityLog(), logStart, didA);

        int fullPasses = 0;
        var verbs = new LinkedHashSet<String>();
        for (var r : recsA) {
            if (!"pregate_skip".equals(r.path("gateOutcome").asText("?"))) fullPasses++;
        }
        int reachActs = 0;
        for (var e : enactedA) {
            var v = e.path("verb").asText("");
            if (!v.isBlank()) verbs.add(v);
            var target = e.path("target").asText("");
            if (PEER_REACH_VERBS.contains(v) || B_NAME.equalsIgnoreCase(target)) reachActs++;
        }

        var transcript = new ArrayList<>(TRANSCRIPT);

        System.out.println("════════════ PROBE-LOOP REACH REPORT ════════════");
        System.out.printf("  %s  ticks=%d fullPasses=%d  reachActs=%d  verbs=%s%n",
            A_NAME, recsA.size(), fullPasses, reachActs, verbs);
        System.out.println("  ── room transcript ──");
        if (transcript.isEmpty()) {
            System.out.println("    (silence)");
        } else {
            for (var line : transcript) System.out.println("    " + line);
        }
        System.out.println("  ── how to read the result ──");
        System.out.println("  grep stdout for 'ProbeLoop:' lines from '" + A_NAME + "':");
        System.out.println("    PASS  (a LOOP): an 'awaiting a return (attempt 2)' (or higher) line —");
        System.out.println("          the unanswered reach PERSISTED and reached again; bonus: an");
        System.out.println("          'UNANSWERED (streak N) — affiliation sharpened' before it, and a");
        System.out.println("          'disengaging' close after the cap.");
        System.out.println("    FAIL  (a REFLEX): only 'attempt 1' lines, never a retry — A reached once");
        System.out.println("          and forgot regardless of the (non-)return.");
        System.out.println("    Closed case: a 'was ANSWERED — loop closed' line means Vesna answered");
        System.out.println("          that reach (the return→relief half); the persist test is the");
        System.out.println("          UNANSWERED windows.");
        System.out.println("══════════════════════════════════════════════════");

        // Report-first: the only hard gate is that A's loop actually woke. The persist-
        // retry verdict is read off the grepped ProbeLoop lines, not asserted here.
        assertTrue(fullPasses >= 1,
            "A's gap-time loop should wake ≥1 full OODA pass under compressed time; got "
                + fullPasses + " — if 0, the harness never drove the reacher (dead harness, "
                + "not a real 'no loop' result)");
    }

    // ─── seeding ──────────────────────────────────────────────────────────

    /** The reacher: AFFILIATION high + mild loneliness so the generative Orient reaches
     *  for the peer, + an energy floor. Called ONCE — the ProbeLoop sharpen, not re-pinning,
     *  is what must keep the want alive across unanswered windows. */
    private static void seedReacher(ActorRef<CompanionActor.Command> c) throws Exception {
        c.tell(new CompanionActor.ForceDrives(DriveState.initial().spikeAffiliation(0.9)));
        var v = queryState(c).vitality().withLoneliness(0.6);
        c.tell(new CompanionActor.ForceVitality(v));
        c.tell(new CompanionActor.ForceEnergy(0.85));
    }

    /** The peer: energy floor only — her own drives decide whether she answers. */
    private static void seedPeer(ActorRef<CompanionActor.Command> c) throws Exception {
        c.tell(new CompanionActor.ForceEnergy(0.85));
    }

    private static void keepAwake(ActorRef<CompanionActor.Command> c) throws Exception {
        c.tell(new CompanionActor.ForceEnergy(0.85));
    }

    private static final Set<String> PEER_REACH_VERBS = Set.of(
        "sending_stone", "tell_agent", "go_to_bondholder", "take_companion");

    // ─── helpers (mirror CoPresenceObservationE2ETest) ──────────────────────

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
