package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.interiority.ChronicleService;
import org.wyrdsekai.core.agent.interiority.TickLogReader;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WILL-THEY-LIVE — observe two freshly-landed faculties in one live run (2026-06-03):
 *
 * <ol>
 *   <li><b>SURPRISE / STARTLE grounding.</b> Novel utterances arrive in the room.
 *       Each genuinely-new perception is an expectation violation → the live
 *       {@code OracleDriveIntegration.applyPredictionError} now spikes the SURPRISE
 *       drive (and STARTLE on a large/abrupt one) instead of routing around them.
 *       We inject a stream of varied lines + one jarring burst and watch the two
 *       drives — previously dead 0.0 slots — actually move.</li>
 *   <li><b>Voiced testimony quality.</b> The chronicle's testimony is now voiced
 *       through the 4B ({@code ChronicleService.buildVoiced}); the synthesis stays
 *       deterministic. We build the day's chronicle from this run's tick log, then
 *       voice the testimony against the live :8201 and print BOTH side-by-side so
 *       the rephrase-not-confabulate quality can be eyeballed.</li>
 * </ol>
 *
 * <p>Hard gates are minimal — SURPRISE must rise above 0 at least once (the wire
 * fired live) and the voiced testimony must come back non-empty. The quality read
 * is REPORTED, not gated.
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_SURPRISE_SOAK=1 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8200 \
 *   WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
 *     ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.SurpriseStartleChronicleSoakE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_SURPRISE_SOAK", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class SurpriseStartleChronicleSoakE2ETest {

    private static final String AGENT = "companion-wyrd";
    private static final String NAME = "Wyrd";
    private static final String ROOM = "nexus";
    private static final String VISITOR = "soak-visitor";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Varied novel lines — each distinct → high novelty → SURPRISE. A few NAME Wyrd
     *  so they trip the reactive gate and populate real testimony. */
    private static final List<String> NOVEL_LINES = List.of(
        "A door I've never seen opens onto a corridor of mirrors.",
        "Wyrd, did you notice the light just changed colour?",
        "There's a sound here that wasn't here a moment ago.",
        "Someone left a folded paper crane on the floor.",
        "Wyrd — the floor is warm where it was cold.",
        "A bird I can't name just crossed the room and vanished.",
        "The clock is running backwards now.",
        "Wyrd, a stranger's voice just said your name from nowhere.");

    /** The jarring burst — abrupt, intense, never-seen → STARTLE on top of surprise. */
    private static final String STARTLE_LINE =
        "!!! A SUDDEN CRASH — the whole room lurches and everything goes white !!!";

    private static TestServerBootstrap server;
    private static Path soakLogDir;
    private static InferenceBackend voiceBackend;
    private static String voiceModel;

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        soakLogDir = Files.createTempDirectory("wyrd-surprise-soak-");
        ActivityLogger.init(soakLogDir);

        // Voice backend = last in the dual list when healthy; resolve a model name
        // for direct testimony voicing (Phase 2).
        var backends = dual.backends();
        voiceBackend = backends.size() > 1 ? backends.get(backends.size() - 1) : backends.get(0);
        var voiceUrl = System.getenv().getOrDefault("WYRDSEKAI_E2E_VOICE_URL", "http://localhost:8201");
        voiceModel = discoverModel(voiceUrl);
        if (voiceModel == null) voiceModel = System.getenv()
            .getOrDefault("WYRDSEKAI_VOICE_MODEL", "wyrdsekai-3.5-4b-v10-q4km");

        // Warm the drive backend so the first perception isn't a cold-start stall.
        try {
            var warm = new InferenceClient.ChatRequest(
                System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v6-q4km"),
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            backends.get(0).chatCompletion(warm).get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[soak] warmup failed (non-fatal): " + e.getMessage());
        }
        Thread.sleep(1500);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        System.clearProperty("wyrd.soak.time.scale");
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    @Test
    @Timeout(value = 18, unit = TimeUnit.MINUTES)
    void surpriseStartleGroundingAndVoicedTestimony() throws Exception {
        var c = ZoneGuardian.getCompanionRef(null, AGENT);
        assertNotNull(c, "default companion (Wyrd) should be spawned");
        c.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        Thread.sleep(800);

        var did = queryState(c).agentDid();
        long logStart = lineCount(activityLog());

        // A throwaway sink for room responses to injected utterances.
        ActorRef<RoomResponse> sink = server.system().systemActorOf(
            Behaviors.ignore(), "soak-room-sink", Props.empty());
        var roomRef = RoomRegistry.get().ref(ROOM);
        assertNotNull(roomRef, "nexus should be registered");

        // Light compression so the OODA loop + reactions flow while perceptions land.
        System.setProperty("wyrd.soak.time.scale", "12");

        System.out.println("════════ SURPRISE / STARTLE GROUNDING ════════");
        System.out.printf("  injecting %d novel lines + 1 startle burst into '%s', reading %s's drives%n",
            NOVEL_LINES.size(), ROOM, NAME);

        double maxSurprise = 0, maxStartle = 0;
        // Baseline (should be ~0 — nothing has surprised her yet).
        var base = queryState(c).drives();
        System.out.printf("  t+000  baseline   surprise=%.3f startle=%.3f seeking=%.3f%n",
            base.surprise(), base.startle(), base.seeking());

        for (int i = 0; i < NOVEL_LINES.size(); i++) {
            roomRef.tell(new RoomCommand.SayInRoom(VISITOR, "A Visitor", NOVEL_LINES.get(i), sink));
            Thread.sleep(2500);                       // let the perception land + spike
            var d = queryState(c).drives();
            maxSurprise = Math.max(maxSurprise, d.surprise());
            maxStartle = Math.max(maxStartle, d.startle());
            System.out.printf("  novel#%d  surprise=%.3f startle=%.3f seeking=%.3f%n",
                i + 1, d.surprise(), d.startle(), d.seeking());
        }

        // The jarring burst — expect STARTLE to jump.
        roomRef.tell(new RoomCommand.SayInRoom(VISITOR, "A Visitor", STARTLE_LINE, sink));
        Thread.sleep(2500);
        var jolt = queryState(c).drives();
        maxSurprise = Math.max(maxSurprise, jolt.surprise());
        maxStartle = Math.max(maxStartle, jolt.startle());
        System.out.printf("  STARTLE  surprise=%.3f startle=%.3f seeking=%.3f%n",
            jolt.surprise(), jolt.startle(), jolt.seeking());
        System.out.printf("  ── peak: surprise=%.3f startle=%.3f ──%n", maxSurprise, maxStartle);

        // Let a couple OODA passes run so the tick log gathers testimony content.
        Thread.sleep(30_000);
        System.clearProperty("wyrd.soak.time.scale");

        // ════════ VOICED TESTIMONY QUALITY ════════
        System.out.println("════════ VOICED TESTIMONY QUALITY ════════");
        var service = new ChronicleService(new TickLogReader(activityLog()));
        var deterministic = service.build(did, NAME, ChronicleService.Scale.DAY);
        System.out.println("  ── DETERMINISTIC testimony (ground truth) ──");
        System.out.println("    " + deterministic.testimony());
        System.out.println("  ── SYNTHESIS (always deterministic) ──");
        System.out.println("    " + deterministic.synthesis());

        Function<String, CompletionStage<String>> voiceFn = detText -> {
            var req = new InferenceClient.ChatRequest(voiceModel, List.of(
                new InferenceClient.ChatMessage("system", ChronicleService.TESTIMONY_VOICE_SYSTEM),
                new InferenceClient.ChatMessage("user", ChronicleService.testimonyVoiceUserPrompt(detText))),
                220, 0.35);
            return voiceBackend.chatCompletion(req).thenApply(resp -> {
                if (resp == null || resp.choices() == null || resp.choices().isEmpty()) return null;
                var m = resp.choices().getFirst().message();
                return m == null ? null : m.content();
            });
        };
        var voiced = service.buildVoiced(did, NAME, ChronicleService.Scale.DAY, voiceFn)
            .toCompletableFuture().get(60, TimeUnit.SECONDS);
        System.out.println("  ── VOICED testimony (4B rephrase) ──");
        System.out.println("    " + voiced.testimony());
        boolean voiceChanged = voiced.testimony() != null
            && !voiced.testimony().equals(deterministic.testimony());
        boolean synthesisUntouched = voiced.synthesis().equals(deterministic.synthesis());
        System.out.printf("  voiceChanged=%b synthesisUntouched=%b%n",
            voiceChanged, synthesisUntouched);
        System.out.println("══════════════════════════════════════════");

        // ── gates: minimal, the rest is reported ──
        assertTrue(maxSurprise > 0.0,
            "SURPRISE must rise above 0 on novel perceptions — the prediction-error wire "
                + "should feed the surprise drive (peak=" + maxSurprise + ")");
        assertTrue(synthesisUntouched,
            "the voiced chronicle must leave the synthesis byte-identical (ground truth)");
        assertTrue(voiced.testimony() != null && !voiced.testimony().isBlank(),
            "voiced testimony must come back non-empty");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String discoverModel(String baseUrl) {
        try {
            var conn = (HttpURLConnection)
                URI.create(baseUrl + "/v1/models").toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            try (var in = conn.getInputStream()) {
                var node = MAPPER.readTree(in);
                var data = node.path("data");
                if (data.isArray() && data.size() > 0) return data.get(0).path("id").asText(null);
            }
        } catch (Exception ignore) { /* fall back to env/default */ }
        return null;
    }

    private static CompanionActor.TestStateResponse queryState(
            ActorRef<CompanionActor.Command> companion) throws Exception {
        return AskPattern.ask(companion,
            (ActorRef<CompanionActor.TestStateResponse> ref) -> new CompanionActor.QueryTestState(ref),
            Duration.ofSeconds(10), server.system().scheduler()
        ).toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    private static Path activityLog() {
        return soakLogDir.resolve("agent-activity.jsonl");
    }

    private static long lineCount(Path p) throws Exception {
        if (!Files.exists(p)) return 0;
        try (var s = Files.lines(p)) { return s.count(); }
    }
}
