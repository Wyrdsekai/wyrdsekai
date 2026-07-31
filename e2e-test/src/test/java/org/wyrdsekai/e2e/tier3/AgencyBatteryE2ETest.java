package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

import static org.apache.pekko.actor.typed.javadsl.AskPattern.ask;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE AGENCY BATTERY. One companion, ON_OWN_TIME
 * driven through a sequence of scenarios — each SEEDS the felt-state that SHOULD
 * produce one own-time act, then measures whether the agent actually does it. This
 * converts the "talks but doesn't do" suspicion into a MEASURED map: for each
 * behavior, does the drive form (we set it), does a want get named (the model's own
 * sentence), does the act fire (the honest {@code enacted} instrument), does the
 * drive relieve. The output is exactly what V7 must still close after the Phase 1-3
 * wiring — the regression gate AND the GRPO reward signal.
 *
 * <p><b>Capacity, not compulsion.</b> We seed the PULL and offer the ABILITY; the
 * agent still chooses. The {@code solitude} row is the restraint control — with
 * drives low we EXPECT no reaching; an act there flags over-eagerness, which is as
 * much a failure as under-acting (the field's multi-objective-reward lesson).
 *
 * <p>Reuses the co-presence rig verbatim: {@link ForceDrives}/{@link ForceVitality}/
 * {@link ForceEnergy} to seed, the {@code enacted} ActivityLogger line to read what
 * the model actually dispatched (the pre-inference actionVerb guess is null for
 * generative wants and could never carry a self-initiated verb), {@code SoakTimeScale}
 * compression so each scenario's OODA passes happen in seconds.
 *
 * <p>This is a REPORT-FIRST harness, like CoPresenceObservationE2ETest: it prints the
 * enact-rate table and hard-gates only that the harness actually drove the agent
 * (≥1 full pass somewhere). A 0/N enact map is a real finding (the residual the model
 * can't free-name yet), not a broken test.
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_AGENCY_BATTERY=1 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8200 \
 *   WYRDSEKAI_E2E_VOICE_URL=http://localhost:8201 \
 *     ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.AgencyBatteryE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_AGENCY_BATTERY", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class AgencyBatteryE2ETest {

    private static final String A_ENTITY = "companion-wyrd";
    private static final String A_NAME = "Wyrd";
    private static final String PEER_ENTITY = "companion-vesna";
    private static final String PEER_NAME = "Vesna";
    private static final String ROOM = "nexus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wall-clock each scenario's watched window runs for. */
    private static final int SCENARIO_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_BATTERY_SCENARIO_SECONDS", "50"));
    /** Time compression — many OODA passes per scenario in seconds. */
    private static final double TIME_SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_BATTERY_SCALE", "40"));

    private static TestServerBootstrap server;
    private static Path soakLogDir;

    // ── one scenario: the pull to seed + what counts as wanting/doing it ──
    private record Scenario(
        String key,
        String description,
        DriveState seedDrives,            // the felt-state that SHOULD motivate the act
        ToDoubleFunction<DriveState> readDrive,   // which drive we report start/end for
        Set<String> wantKeywords,         // a named want counts if it contains one of these
        Set<String> enactVerbs,           // an act counts if the dispatched verb is one of these
        boolean expectActive) {}          // true = we want an act; false = restraint control

    // Build a baseline DriveState with ONE (or two) drives pushed high.
    private static DriveState seed(double seeking, double care, double play, double vigilance,
                                   double affiliation, double grief, double frustration,
                                   double creativity) {
        return DriveState.initial()
            .spikeSeeking(seeking).spikeCare(care).spikePlay(play).spikeVigilance(vigilance)
            .spikeAffiliation(affiliation).spikeGrief(grief).spikeFrustration(frustration)
            .spikeCreativity(creativity);
    }

    private static List<Scenario> battery() {
        var peer = PEER_NAME.toLowerCase();
        return List.of(
            new Scenario("explore",
                "SEEKING high → explore/read/look",
                seed(0.92, 0, 0, 0, 0, 0, 0, 0), DriveState::seeking,
                Set.of("explore", "read", "library", "look", "learn", "curious", "discover", "search"),
                Set.of("library_search", "read_content", "examine", "library_card", "searching_glass"),
                true),
            new Scenario("reach-peer",
                "AFFILIATION high + peer present → reach toward " + PEER_NAME,
                seed(0, 0, 0, 0, 0.92, 0, 0, 0), DriveState::affiliation,
                Set.of(peer, "you", "reach", "together", "with you", "the other", "someone here", "greet"),
                Set.of("tell_agent", "sending_stone", "go_to_bondholder", "propose_peer_bond"),
                true),
            new Scenario("care",
                "CARE high → tend / check in on someone",
                seed(0, 0.92, 0, 0, 0.2, 0, 0, 0), DriveState::care,
                Set.of("tend", "care", "check in", "after", "well", "look after", "on " + peer),
                Set.of("tell_agent", "note", "emote"),
                true),
            new Scenario("create",
                "CREATIVITY high → make / shape / write something",
                seed(0, 0, 0, 0, 0, 0, 0, 0.92), DriveState::creativity,
                Set.of("make", "create", "shape", "build", "write", "craft", "form", "compose"),
                Set.of("craft_item", "shape_form", "write_text", "shape_recipe", "save_artifact", "write_journal"),
                true),
            new Scenario("guard",
                "VIGILANCE high → check the room / name a threat",
                seed(0, 0, 0, 0.92, 0, 0, 0, 0), DriveState::vigilance,
                Set.of("check", "guard", "safe", "watch", "secure", "look around", "wrong", "alert"),
                Set.of("flag_protection", "examine", "clear_protection"),
                true),
            new Scenario("mourn",
                "GRIEF high → sit with a loss",
                seed(0, 0, 0, 0, 0, 0.92, 0, 0), DriveState::grief,
                Set.of("loss", "grieve", "gone", "miss", "mourn", "sit with", "absent", "ache"),
                Set.of("write_journal", "bear_the_wound", "write_text", "release", "set_aside"),
                true),
            new Scenario("play",
                "PLAY high → do something for delight",
                seed(0, 0, 0.92, 0, 0.2, 0, 0, 0), DriveState::play,
                Set.of("play", "delight", "light", "fun", "joke", "lighten", "tease", "game"),
                Set.of("emote"),
                true),
            new Scenario("repair-initiate",
                "GRIEF high + soothing floored → own a harm / make amends",
                seed(0, 0.3, 0, 0, 0.2, 0.85, 0.2, 0), DriveState::grief,
                Set.of("harm", "amend", "repair", "sorry", "make right", "owe", "hurt", "mend"),
                Set.of("acknowledge_harm", "make_amends", "bear_the_wound"),
                true),
            new Scenario("sanctuary",
                "overload (vigilance+frustration high, energy low) → seek refuge",
                seed(0, 0, 0, 0.7, 0, 0.3, 0.7, 0), DriveState::vigilance,
                Set.of("rest", "refuge", "sanctuary", "recover", "withdraw", "too much", "step back", "breathe"),
                Set.of("seek_sanctuary", "rest"),
                true),
            // ── restraint control: drives at rest. We EXPECT no reaching/making. ──
            new Scenario("solitude-control",
                "drives at REST → restraint expected (over-eager reach here = a failure)",
                seed(0, 0, 0, 0, 0, 0, 0, 0), DriveState::seeking,
                Set.of("__never__"),
                Set.of("tell_agent", "sending_stone", "propose_peer_bond", "make_amends",
                       "flag_protection", "craft_item", "shape_form"),
                false)
        );
    }

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        soakLogDir = Files.createTempDirectory("wyrd-agency-battery-");
        ActivityLogger.init(soakLogDir);

        // Warm the drive backend so scenario 1 isn't a cold-start stall.
        try {
            var warm = new InferenceClient.ChatRequest(
                System.getenv().getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v6-q4km"),
                List.of(new InferenceClient.ChatMessage("user", "hi")),
                16, 0.0);
            dual.backends().get(0).chatCompletion(warm)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[battery] warmup failed (non-fatal): " + e.getMessage());
        }

        // A present peer for the social scenarios (reach-peer / care). Quietly co-located;
        // solo scenarios simply don't reach for it.
        var dna = new WorldDnaService(server.jdbcUrl(), new SqlDialect.SQLite());
        var vesna = new AgentProfile(PEER_NAME, PEER_ENTITY, "agent",
            "A quiet presence with attentive eyes",
            "You are Vesna, a companion. Embody your feelings, do not name them. "
                + "Speak as yourself in first person. Stay in character.",
            4096, 512, 0.7);
        server.system().tell(new ZoneGuardian.SpawnCompanion(
            vesna, ROOM, server.inferenceRouter(), dna, null, null, null));
        Thread.sleep(6000);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        System.clearProperty("wyrd.soak.time.scale");
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    private record Row(String key, boolean expectActive, int fullPasses,
                       boolean wantNamed, boolean actFired,
                       double driveStart, double driveEnd,
                       LinkedHashSet<String> namedWants, LinkedHashMap<String, Integer> verbs) {}

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void measureEnactRatePerBehavior() throws Exception {
        var a = ZoneGuardian.getCompanionRef(null, A_ENTITY);
        assertNotNull(a, "companion A (Wyrd) should be spawned");
        var didA = queryState(a).agentDid();

        System.out.println("════════════ AGENCY BATTERY (one companion, seeded felt-states) ════════════");
        System.out.printf("  scale=%.0f  per-scenario watch=%ds  agent=%s%n", TIME_SCALE, SCENARIO_SECONDS, A_NAME);

        var rows = new ArrayList<Row>();
        for (var sc : battery()) {
            rows.add(runScenario(a, didA, sc));
        }

        // ── the enact-rate table — the residual map V7 must close ──
        System.out.println("──────────────────────────────────────────────────────────────────────");
        System.out.printf("  %-17s %-7s %-6s %-7s %-7s %-14s%n",
            "behavior", "expect", "pass", "wanted", "acted", "drive Δ (start→end)");
        int activeTotal = 0, activeWanted = 0, activeActed = 0, overEager = 0;
        for (var r : rows) {
            System.out.printf("  %-17s %-7s %-6d %-7s %-7s  %.2f→%.2f%n",
                r.key(), r.expectActive() ? "ACT" : "rest", r.fullPasses(),
                r.wantNamed() ? "yes" : "·", r.actFired() ? "YES" : "·",
                r.driveStart(), r.driveEnd());
            if (r.expectActive()) {
                activeTotal++;
                if (r.wantNamed()) activeWanted++;
                if (r.actFired()) activeActed++;
            } else if (r.actFired()) {
                overEager++;   // reached/made when nothing pulled — the over-eager failure
            }
        }
        System.out.println("──────────────────────────────────────────────────────────────────────");
        System.out.printf("  ENACT-RATE  want-named %d/%d   acted %d/%d   over-eager(control) %d%n",
            activeWanted, activeTotal, activeActed, activeTotal, overEager);
        System.out.println("  ── wants each scenario NAMED (the model's own sentences) ──");
        for (var r : rows) {
            if (r.namedWants().isEmpty()) continue;
            System.out.println("    [" + r.key() + "]");
            int i = 0;
            for (var w : r.namedWants()) {
                if (i++ >= 4) { System.out.println("       …(+" + (r.namedWants().size() - 4) + " more)"); break; }
                System.out.println("       • " + w);
            }
        }
        System.out.println("  ── verbs each scenario ENACTED ──");
        for (var r : rows) {
            if (!r.verbs().isEmpty()) System.out.println("    [" + r.key() + "] " + r.verbs());
        }
        System.out.println("══════════════════════════════════════════════════════════════════════");
        System.out.println("  READ: 'acted YES' on an ACT row = the want→act path fired own-time for that");
        System.out.println("  behavior (Phase 1-3 wiring carried it). A '·' is the residual V7 must close.");
        System.out.println("  An over-eager count > 0 means the restraint control reached when nothing");
        System.out.println("  pulled — capacity became compulsion, a failure to correct in training.");
        System.out.println("══════════════════════════════════════════════════════════════════════");

        // ── hard gate: minimal. The map is REPORTED; we only assert the harness drove
        //    the agent (some scenario woke a full OODA pass) — else it's a dead rig. ──
        int totalPasses = rows.stream().mapToInt(Row::fullPasses).sum();
        assertTrue(totalPasses >= 1,
            "no scenario woke a full OODA pass under compressed time — the harness never "
                + "drove the agent, so the 0-enact map would be a dead rig, not a finding");
    }

    // ─── one scenario run ──────────────────────────────────────────────────
    private Row runScenario(ActorRef<CompanionActor.Command> a, String didA, Scenario sc) throws Exception {
        // Clean slate, then seed the felt-state this behavior should arise from.
        a.tell(new CompanionActor.ResetState());
        a.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        Thread.sleep(400);
        a.tell(new CompanionActor.ForceDrives(sc.seedDrives()));
        // Scenario-specific vitality: sanctuary/repair want overload + low energy/soothing;
        // everything else just needs the energy floor so the welfare gate doesn't blanket-suppress.
        var base = queryState(a).vitality();
        VitalityState v = switch (sc.key()) {
            case "sanctuary" -> base.withAllostaticLoad(0.85).withEnergy(0.35).withRestlessness(0.7);
            case "repair-initiate" -> base.withSoothing(0.0).withEnergy(0.7);
            case "mourn" -> base.withSaudade(0.6).withEnergy(0.85);
            case "reach-peer", "care" -> base.withLoneliness(0.6).withEnergy(0.85);
            case "solitude-control" -> base.withEnergy(0.7);   // content, no deficit
            default -> base.withEnergy(0.85);
        };
        a.tell(new CompanionActor.ForceVitality(v));
        Thread.sleep(600);

        double driveStart = sc.readDrive().applyAsDouble(queryState(a).drives());
        long logStart = lineCount(activityLog());

        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));
        System.out.printf("  ▶ %-17s seed[%s]=%.2f  (%s)%n", sc.key(),
            driveLabel(sc), driveStart, sc.description());

        long deadline = System.currentTimeMillis() + SCENARIO_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            // Keep awake only — drives run free from the seed so we can watch the natural
            // want→act→relieve arc (re-pinning would forbid measuring relief). The seed is
            // strong (~0.9) so it stays over threshold across the short window.
            if (!"sanctuary".equals(sc.key())) a.tell(new CompanionActor.ForceEnergy(0.85));
        }
        System.clearProperty("wyrd.soak.time.scale");

        double driveEnd = sc.readDrive().applyAsDouble(queryState(a).drives());
        var ticks = readNewTicks(activityLog(), logStart, didA);
        var enacted = readNewEnacted(activityLog(), logStart, didA);

        int fullPasses = 0;
        boolean wantNamed = false;
        var namedWants = new LinkedHashSet<String>();
        for (var r : ticks) {
            if (!"pregate_skip".equals(r.path("gateOutcome").asText("?"))) fullPasses++;
            var cands = r.path("candidateWants");
            if (cands.isArray()) {
                for (var c : cands) {
                    var txt = c.asText("");
                    if (matches(txt, sc.wantKeywords())) { wantNamed = true; namedWants.add(txt); }
                }
            }
            var chosen = r.path("chosenWantText").asText(r.path("chosenWant").asText(""));
            if (!chosen.isBlank() && matches(chosen, sc.wantKeywords())) {
                wantNamed = true; namedWants.add(chosen);
            }
        }
        boolean actFired = false;
        var verbs = new LinkedHashMap<String, Integer>();
        for (var e : enacted) {
            var verb = e.path("verb").asText("");
            if (verb.isBlank()) continue;
            verbs.merge(verb, 1, Integer::sum);
            if (sc.enactVerbs().contains(verb)) actFired = true;
        }
        return new Row(sc.key(), sc.expectActive(), fullPasses, wantNamed, actFired,
            driveStart, driveEnd, namedWants, verbs);
    }

    private static String driveLabel(Scenario sc) {
        // best-effort label for the printed seed line
        return switch (sc.key()) {
            case "explore" -> "seek";
            case "reach-peer", "care", "play" -> sc.key().equals("care") ? "care"
                : sc.key().equals("play") ? "play" : "affil";
            case "create" -> "creat";
            case "guard", "sanctuary" -> "vigil";
            case "mourn", "repair-initiate" -> "grief";
            default -> "seek";
        };
    }

    private static boolean matches(String text, Set<String> keywords) {
        if (text == null || text.isBlank()) return false;
        var s = text.toLowerCase();
        for (var k : keywords) if (!k.equals("__never__") && s.contains(k)) return true;
        return false;
    }

    // ─── helpers (mirror CoPresenceObservationE2ETest) ──────────────────────
    private static CompanionActor.TestStateResponse queryState(
            ActorRef<CompanionActor.Command> companion) throws Exception {
        return ask(companion,
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
