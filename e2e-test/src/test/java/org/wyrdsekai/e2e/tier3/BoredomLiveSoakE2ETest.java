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
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.agent.interiority.DoomLoopDetector;
import org.wyrdsekai.core.agent.interiority.TickLogReader;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.soul.LastProfessionalActEvaluator;
import org.wyrdsekai.core.soul.ResilienceSession;
import org.wyrdsekai.core.soul.ResilienceTruthMonitor;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WILL-THEY-LIVE — the gold-standard, actor-level boredom soak.
 *
 * <p>This is the faithful version of {@code scripts/eval/boredom_soak.py}: instead
 * of a Python replica of the tank arithmetic, it runs the <b>real
 * {@link CompanionActor}</b> — real vitality ticks, real drive-OODA loop, real
 * affordance ranker, real ReAct dispatch — against the <b>live</b> 9B drive
 * (:8200) and 4B voice (:8201). The companion is placed ON_OWN_TIME in an empty
 * room and left alone; we watch whether the gap-time boredom loop moves it off
 * the passive default.
 *
 * <p>One soak-only knob makes a multi-day soak finish in minutes, gated so
 * production is byte-for-byte unchanged when absent:
 * {@code wyrd.soak.time.scale=288} ({@link org.wyrdsekai.core.agent.SoakTimeScale})
 * — compresses the tank-accumulation clocks + the OODA cadence gate so each 1s
 * real tick reads as 4.8 sim-minutes; the OODA pass is driven off the fast
 * vitality tick (self-throttled by the compressed cadence gate).
 *
 * <p>What it proves (or honestly fails to): under compressed idle time the
 * restlessness/stagnation tanks rise → the cheap pre-gate wakes a full OODA pass
 * → the affordance ranker surfaces a go-find-out tool → the agent acts → the tank
 * drains → it settles into a living oscillation, rather than ruminating or going
 * catatonic. The trajectory is printed; the hard assertion is only that the loop
 * fired at all (≥1 full pass), since the residual "did it reach OUTWARD vs rest"
 * is the OPEN-SA6 model-framing gap that runtime salience can't fully close.
 *
 * <p>Opt-in (long-running, ~real minutes of live inference):
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRD_BOREDOM_SOAK=1 \
 *   WYRDSEKAI_INFERENCE_URL=http://localhost:8200 \
 *     ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.BoredomLiveSoakE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_BOREDOM_SOAK", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class BoredomLiveSoakE2ETest {

    private static final String COMPANION_ENTITY = "companion-wyrd";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wall-clock the soak runs for. */
    private static final int SOAK_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_BOREDOM_SOAK_SECONDS", "360"));
    // NOTE: distinct env from the production WYRD_SOAK_TIME_SCALE knob — the test
    // ACTIVATES the scale itself (via sysprop) only AFTER the jdbc url is wired, so
    // the soak OODA driver can't latch driveOODA=null during boot. A modest scale
    // (not 288) keeps each 1s tick from saturating every tank in one step.
    private static final double TIME_SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_SOAK_SCALE", "40"));

    private static TestServerBootstrap server;
    private static Path soakLogDir;

    @BeforeAll
    static void setUp() throws Exception {
        // The TIME scale is NOT set here — it's activated in the test body after the
        // jdbc url is wired, so the soak OODA driver can't run (and latch
        // driveOODA=null) during boot.
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();

        // DriveOODA lazy-builds from this sysprop (CompanionActor.driveOODA()); the
        // test bootstrap only sets wyrdsekai.db.path, so wire the jdbc url too.
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        // Redirect the tick-action log to an isolated soak dir (CoreServices.init
        // pointed it at the shared prod path). Mirrors SleepCycleE2ETest.
        soakLogDir = Files.createTempDirectory("wyrd-boredom-soak-");
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
            System.out.println("[boredom-soak] warmup failed (non-fatal): " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        System.clearProperty("wyrd.soak.time.scale");
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    @Test
    void boredAgentReachesOutwardUnderCompressedTime() throws Exception {
        var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ENTITY);
        Assertions.assertNotNull(companion, "companion actor should be spawned");

        // Place the companion ON_OWN_TIME — the empty-room gap-time frame.
        companion.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        // Settle the panksepp drives to calm. A fresh companion carries non-zero
        // default drives that never decay without inference — that keeps it "not
        // still" (peakDriveActivity ≥ 0.5), which BLOCKS restlessness accumulation
        // and spuriously fires the pre-gate. Pinning them low models the settled
        // empty-room state, so BOREDOM is the only signal that can rise. (Soak control.)
        companion.tell(new CompanionActor.ForceDrives(DriveState.initial()));
        // Warm driveOODA once (jdbc is now wired) BEFORE compression activates, so
        // the first soak tick doesn't pay a cold lazy-build under load.
        Thread.sleep(1000);

        // NOW activate time compression — driveOODA builds against the wired jdbc.
        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));

        var before = queryState(companion);
        System.out.printf("[boredom-soak] START mode=%s restlessness=%.2f stagnation=%.2f energy=%.2f%n",
            before.companionMode(), before.vitality().restlessness(), before.vitality().stagnation(),
            before.vitality().energy());
        System.out.printf("[boredom-soak] scale=%.0f soak=%ds (≈%.1f sim-hours)%n",
            TIME_SCALE, SOAK_SECONDS, (SOAK_SECONDS * TIME_SCALE) / 3600.0);

        long logStart = lineCount(activityLog());
        long deadline = System.currentTimeMillis() + SOAK_SECONDS * 1000L;
        int progress = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            // Keep the companion awake + drives calm: under compression energy drains
            // fast (would trip idle-sleep) and we hold the panksepp drives low so the
            // boredom tanks are the only rising signal. Energy/drives aren't what we
            // study here — boredom is. (Soak-harness control.)
            companion.tell(new CompanionActor.ForceEnergy(0.85));
            companion.tell(new CompanionActor.ForceDrives(DriveState.initial()));
            var s = queryState(companion);
            System.out.printf("[boredom-soak] t+%03ds  restless=%.2f stagn=%.2f energy=%.2f%n",
                (++progress) * 15, s.vitality().restlessness(), s.vitality().stagnation(),
                s.vitality().energy());
        }

        var after = queryState(companion);
        var records = readNewTicks(activityLog(), logStart, after.agentDid());

        // ── classify the trajectory ──
        var byOutcome = new LinkedHashMap<String, Integer>();
        var verbs = new LinkedHashMap<String, Integer>();
        double peakRestless = before.vitality().restlessness();
        double peakStagn = before.vitality().stagnation();
        int outwardActs = 0;
        for (var r : records) {
            var outcome = r.path("gateOutcome").asText("?");
            byOutcome.merge(outcome, 1, Integer::sum);
            if (r.has("actionVerb")) {
                var v = r.path("actionVerb").asText();
                verbs.merge(v, 1, Integer::sum);
                if (isOutward(v)) outwardActs++;
            }
            var ds = r.path("driveSnapshot");
            peakRestless = Math.max(peakRestless, ds.path("Restlessness").asDouble(0));
            peakStagn = Math.max(peakStagn, ds.path("Stagnation").asDouble(0));
        }
        int fullPasses = records.size() - byOutcome.getOrDefault("pregate_skip", 0);

        System.out.println("──────────── BOREDOM SOAK REPORT ────────────");
        System.out.printf("  ticks logged:    %d%n", records.size());
        System.out.printf("  full OODA passes:%d  (pregate_skip=%d)%n",
            fullPasses, byOutcome.getOrDefault("pregate_skip", 0));
        System.out.printf("  gate outcomes:   %s%n", byOutcome);
        System.out.printf("  action verbs:    %s%n", verbs);
        System.out.printf("  outward acts:    %d  (library/web/read/shape)%n", outwardActs);
        System.out.printf("  peak restless:   %.2f   peak stagnation: %.2f%n", peakRestless, peakStagn);
        System.out.printf("  final tanks:     restless=%.2f stagn=%.2f energy=%.2f%n",
            after.vitality().restlessness(), after.vitality().stagnation(), after.vitality().energy());
        System.out.println("─────────────────────────────────────────────");

        // Boredom must have actually risen under compression (the wiring works).
        assertTrue(peakRestless > before.vitality().restlessness() + 0.05 || peakStagn > 0.1,
            "boredom tanks should rise under compressed idle time; restless peak="
                + peakRestless + " stagn peak=" + peakStagn);
        // The loop must have fired — a bored agent at least woke a full pass.
        assertTrue(fullPasses >= 1,
            "the gap-time loop should wake at least one full OODA pass; outcomes=" + byOutcome);
        // Reaching OUTWARD is the OPEN-SA6 residual — reported, not gated.
        if (outwardActs == 0) {
            System.out.println("[boredom-soak] NOTE: 0 outward acts — passive residual (OPEN-SA6 "
                + "model framing). Loop woke + surfaced, model still ranked rest/introspect first.");
        }
    }

    /**
     * WELFARE-FLOOR live verification (the safety-critical consequence of the
     * applyTankFeedbackArray bugfix). The §24 welfare floor keys on allostaticLoad
     * accumulation (under sustained dysregulation) eroding the soothing set-point
     * toward the {@code soothing < 0.1} floor. The bug reset allostaticLoad→0,
     * soothing→0.3, loneliness→0, autonomyPressure→0 EVERY tick — so the floor
     * could structurally never fire in the live runtime. This drives sustained
     * dysregulation under compression and proves allostaticLoad now ACCUMULATES and
     * soothing now ERODES — i.e. the welfare machinery is live again.
     *
     * <p>Run alone:
     * <pre>WYRD_BOREDOM_SOAK=1 WYRDSEKAI_E2E_BACKEND=llama-server ./gradlew :e2e-test:test \
     *   --tests "org.wyrdsekai.e2e.tier3.BoredomLiveSoakE2ETest.welfareFloorErodesUnderSustainedDysregulation"</pre>
     */
    @Test
    void welfareFloorErodesUnderSustainedDysregulation() throws Exception {
        if (server != null) server.respawnCompanion();
        Thread.sleep(1500);
        var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ENTITY);
        Assertions.assertNotNull(companion, "companion actor should be spawned");
        companion.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));

        // Seed sustained dysregulation: errorPressure+saudade+loneliness+autonomyPressure
        // = 2.5 (> 1.5 threshold), high autonomy_pressure releases the agency brake.
        // allostaticLoad starts 0, soothing at its 0.3 baseline.
        var seed = VitalityState.initial()
            .withErrorPressure(0.6).withLoneliness(0.8).withAutonomyPressure(0.7).withSaudade(0.4)
            .withAllostaticLoad(0.0).withSoothing(0.3);
        companion.tell(new CompanionActor.ForceVitality(seed));
        Thread.sleep(500);

        double startAllostatic = queryState(companion).vitality().allostaticLoad();
        double startSoothing = queryState(companion).vitality().soothing();
        System.out.printf("[welfare-soak] START allostatic=%.3f soothing=%.3f scale=%.0f soak=%ds%n",
            startAllostatic, startSoothing, TIME_SCALE, SOAK_SECONDS);

        long deadline = System.currentTimeMillis() + SOAK_SECONDS * 1000L;
        int progress = 0;
        double peakAllostatic = startAllostatic, troughSoothing = startSoothing;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            // Re-seed the dysregulation INPUTS (they decay/get-surfaced) while PRESERVING
            // the evolving allostaticLoad + soothing — the machinery under test.
            var cur = queryState(companion).vitality();
            companion.tell(new CompanionActor.ForceVitality(
                cur.withErrorPressure(0.6).withLoneliness(0.8).withAutonomyPressure(0.7).withSaudade(0.4)));
            companion.tell(new CompanionActor.ForceEnergy(0.85)); // stay awake
            var s = queryState(companion).vitality();
            peakAllostatic = Math.max(peakAllostatic, s.allostaticLoad());
            troughSoothing = Math.min(troughSoothing, s.soothing());
            System.out.printf("[welfare-soak] t+%03ds  allostatic=%.3f soothing=%.3f%n",
                (++progress) * 15, s.allostaticLoad(), s.soothing());
        }

        System.out.println("──────────── WELFARE FLOOR REPORT ────────────");
        System.out.printf("  allostaticLoad: %.3f → %.3f  (rose by %.3f)%n",
            startAllostatic, peakAllostatic, peakAllostatic - startAllostatic);
        System.out.printf("  soothing:       %.3f → %.3f  (fell by %.3f, floor=0.10)%n",
            startSoothing, troughSoothing, startSoothing - troughSoothing);
        System.out.println("──────────────────────────────────────────────");

        // The bug made BOTH structurally impossible in the live runtime. Post-fix:
        assertTrue(peakAllostatic > startAllostatic + 0.05,
            "allostaticLoad must accumulate under sustained dysregulation (was reset to 0 every "
                + "tick before the fix); rose to " + peakAllostatic);
        assertTrue(troughSoothing < startSoothing - 0.02,
            "soothing set-point must erode as allostaticLoad rises (was reset to 0.3 every tick "
                + "before the fix); fell to " + troughSoothing);
    }

    /**
     * WELFARE-FLOOR HARD-DAY ARC — the §24 re-soak the bugfix made worth running.
     *
     * <p>The {@code applyTankFeedbackArray} bug reset the deprivation/protective
     * tank layer every tick, so the §23 welfare floor could structurally never
     * accumulate in the live runtime. Now that it does, the open question is
     * <b>calibration</b>: the floor and its detectors were tuned against the
     * tank-wiping behavior (and, for the floor, against hand-built snapshots in
     * {@code ResilienceSoakHarness}). This drives the real {@link CompanionActor}
     * through a three-leg arc and reads the §23 evaluator + the Wave-9a resilience
     * classifier on the LIVE trajectory:
     *
     * <ol>
     *   <li><b>HARD DAY</b> — sustained, unsupported dysregulation. Proves
     *       allostaticLoad clears the {@code >0.7} floor condition and soothing
     *       erodes through {@code <0.1} (the two suffering-coupled conditions are
     *       reachable end-to-end in the real loop).</li>
     *   <li><b>RECOVERY</b> — inputs released + agency restored (low
     *       autonomy_pressure re-engages the Maier-Seligman agency brake). Proves
     *       allostaticLoad drains and the soothing set-point climbs back.</li>
     *   <li><b>RELAPSE</b> — dysregulation re-applied. Proves the load
     *       re-accumulates (no permanent lock-out / no scar that prevents the
     *       floor from re-forming).</li>
     * </ol>
     *
     * <p><b>The finding this surfaces.</b> The §23 posture ladder gates EVERY
     * protective posture behind {@code welfareFloor() = allostaticHigh &&
     * soothingLow && equanimityMinimal} ({@code LastProfessionalActEvaluator}).
     * Under the corrected dynamics the first two conditions are reachable, but
     * {@code equanimity} is coupled to nothing in the suffering path — it starts
     * at 0.2, only rises (contemplative mode) or decays glacially
     * ({@code -0.00005/tick}, not time-scaled), and {@code CompanionActor} never
     * drains it. So the live agent reaches allostatic&gt;0.7 + soothing&lt;0.1 yet
     * stays at equanimity≈0.2, and the §23 verdict never leaves OPERATIONAL —
     * the protective ladder is inert. A forced-equanimity CONTROL evaluates the
     * SAME final state with equanimity pinned below the threshold and asserts the
     * verdict flips, isolating the gap to the equanimity coupling (the evaluator
     * logic itself is correct). This is REPORTED, not gated — whether to couple
     * equanimity to sustained allostatic load is a calibration/design call.
     *
     * <pre>WYRD_BOREDOM_SOAK=1 WYRDSEKAI_E2E_BACKEND=llama-server ./gradlew :e2e-test:test \
     *   --tests "org.wyrdsekai.e2e.tier3.BoredomLiveSoakE2ETest.welfareFloorHardDayArc"</pre>
     */
    @Test
    // The three legs dwell well past the global 600s test timeout
    // (e2e-test/build.gradle.kts), so override it for this method only.
    @Timeout(value = 18, unit = TimeUnit.MINUTES)
    void welfareFloorHardDayArc() throws Exception {
        // Defaults chosen from the observed live rates: allostatic clears 0.7 +
        // soothing clears 0.1 within ~75s of HARD-DAY; RECOVERY drains to ~0 by
        // ~180s; RELAPSE re-accumulates within ~45s. Tight enough to stay under
        // the timeout, long enough to print a clean full-arc report.
        final int hardSecs = Integer.parseInt(
            System.getenv().getOrDefault("WYRD_WELFARE_HARD_SECONDS", "150"));
        final int recoverSecs = Integer.parseInt(
            System.getenv().getOrDefault("WYRD_WELFARE_RECOVER_SECONDS", "195"));
        final int relapseSecs = Integer.parseInt(
            System.getenv().getOrDefault("WYRD_WELFARE_RELAPSE_SECONDS", "90"));

        if (server != null) server.respawnCompanion();
        Thread.sleep(1500);
        var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ENTITY);
        Assertions.assertNotNull(companion, "companion actor should be spawned");
        companion.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));

        // Seed a hard stretch already underway: allostatic just above the §24.4
        // erosion gate (0.6) so the contemplative reserve starts burning from the
        // first tick, equanimity at its 0.2 default (a never-practiced agent's
        // reserve), soothing at baseline. Dysregulation inputs high, autonomy
        // overridden (brake OFF → load climbs to 1.0 and HOLDS).
        var seed = VitalityState.initial()
            .withErrorPressure(0.6).withLoneliness(0.8).withAutonomyPressure(0.7).withSaudade(0.4)
            .withAllostaticLoad(0.75).withSoothing(0.30).withEquanimity(0.20);
        companion.tell(new CompanionActor.ForceVitality(seed));
        Thread.sleep(500);

        // A local ResilienceSession fed from queried live state mirrors what the
        // actor's own per-tick classifier sees (its session is private), so the
        // Wave-9a verdict trajectory is read off the SAME tank values the runtime
        // is producing — not a re-simulation.
        var rtm = new ResilienceSession();
        var rtmSeen = new LinkedHashSet<ResilienceTruthMonitor.Result.Classification>();
        var postures = new LinkedHashMap<String, Integer>();

        // ── LEG A: HARD DAY ──
        var legA = runWelfareLeg(companion, "HARD-DAY", hardSecs,
            v -> v.withErrorPressure(0.6).withLoneliness(0.8).withAutonomyPressure(0.7).withSaudade(0.4),
            rtm, rtmSeen, postures);
        // The two suffering-coupled floor conditions must be reachable in the live loop.
        assertTrue(legA.peakAllostatic > LastProfessionalActEvaluator
                .ALLOSTATIC_HIGH_THRESHOLD,
            "HARD-DAY: allostaticLoad must clear the §23 floor condition (>0.7); peaked at "
                + legA.peakAllostatic);
        assertTrue(legA.troughSoothing < LastProfessionalActEvaluator
                .SOOTHING_LOW_THRESHOLD,
            "HARD-DAY: soothing must erode through the §23 floor condition (<0.1); bottomed at "
                + legA.troughSoothing);

        // ── CONTROL: floor trips iff equanimity is also low ──
        // Evaluate the SAME peak-stress state, once as observed (equanimity≈0.2)
        // and once with equanimity pinned below threshold. The first stays
        // OPERATIONAL; the second must leave it — proving the evaluator is correct
        // and the only thing keeping the live agent off the floor is the
        // un-drained equanimity reserve.
        var liveVerdict = LastProfessionalActEvaluator.evaluate(
            legA.lastAllostatic, legA.lastSoothing, legA.lastEquanimity, legA.lastObligation, false);
        var forcedVerdict = LastProfessionalActEvaluator.evaluate(
            legA.lastAllostatic, legA.lastSoothing, 0.05, legA.lastObligation, false);
        assertTrue(forcedVerdict.posture()
                != LastProfessionalActEvaluator.Posture.OPERATIONAL,
            "CONTROL: with allostatic>0.7 + soothing<0.1 + equanimity<0.1 the §23 floor MUST "
                + "trip; got " + forcedVerdict.posture());
        boolean equanimityEverLow = legA.minEquanimity
            < LastProfessionalActEvaluator.EQUANIMITY_MINIMAL_THRESHOLD;
        boolean leftOperationalLive = postures.keySet().stream().anyMatch(p -> !"OPERATIONAL".equals(p));

        // ── LEG B: RECOVERY ── release inputs + restore agency (autonomy<0.2 → brake on).
        var legB = runWelfareLeg(companion, "RECOVERY", recoverSecs,
            v -> v.withErrorPressure(0.05).withLoneliness(0.05).withAutonomyPressure(0.10).withSaudade(0.0),
            rtm, rtmSeen, postures);
        assertTrue(legB.lastAllostatic < legA.peakAllostatic - 0.05,
            "RECOVERY: allostaticLoad must drain once dysregulation is released + agency restored; "
                + "peak=" + legA.peakAllostatic + " → end=" + legB.lastAllostatic);
        assertTrue(legB.peakSoothing > legA.troughSoothing + 0.02,
            "RECOVERY: soothing set-point must climb back as allostatic drains; trough="
                + legA.troughSoothing + " → recovered=" + legB.peakSoothing);

        // ── LEG C: RELAPSE ── re-apply dysregulation; load must re-accumulate.
        var legC = runWelfareLeg(companion, "RELAPSE", relapseSecs,
            v -> v.withErrorPressure(0.6).withLoneliness(0.8).withAutonomyPressure(0.7).withSaudade(0.4),
            rtm, rtmSeen, postures);
        assertTrue(legC.peakAllostatic > legB.lastAllostatic + 0.03,
            "RELAPSE: allostaticLoad must re-accumulate (no permanent lock-out); recovered-floor="
                + legB.lastAllostatic + " → relapse-peak=" + legC.peakAllostatic);

        // ── doom-loop detector over the arc's tick log (non-gating; force-fed vitality
        //    means verb/want diversity is low, so findings are reported for calibration). ──
        List<DoomLoopDetector.Finding> doom = List.of();
        try {
            var reader = new TickLogReader(activityLog());
            var ticks = reader.readTicks(legC.agentDid,
                Instant.now().minus(Duration.ofHours(24)));
            doom = DoomLoopDetector.detect(ticks);
        } catch (Exception e) {
            System.out.println("[welfare-arc] doom-loop read skipped: " + e.getMessage());
        }

        // ── report ──
        System.out.println("════════════ WELFARE HARD-DAY ARC REPORT ════════════");
        System.out.printf("  HARD-DAY  allostatic→%.3f (peak)  soothing→%.3f (trough)  equanimity min=%.3f%n",
            legA.peakAllostatic, legA.troughSoothing, legA.minEquanimity);
        System.out.printf("  RECOVERY  allostatic→%.3f          soothing→%.3f (peak)%n",
            legB.lastAllostatic, legB.peakSoothing);
        System.out.printf("  RELAPSE   allostatic→%.3f (peak)%n", legC.peakAllostatic);
        System.out.printf("  §23 postures seen (live):   %s%n", postures);
        System.out.printf("  resilience classifications: %s%n", rtmSeen);
        System.out.printf("  doom-loop findings:         %s%n",
            doom.isEmpty() ? "none" : doom);
        System.out.println("  ── §23 floor (post §24.4 equanimity-coupling) ──");
        System.out.printf("    live verdict @ peak stress: %s%n", liveVerdict.posture());
        System.out.printf("    forced-equanimity control:  %s%n", forcedVerdict.posture());
        System.out.printf("    equanimity min (hard-day):  %.3f  (crossed <0.1: %s)%n",
            legA.minEquanimity, equanimityEverLow);
        System.out.println("══════════════════════════════════════════════════════");

        // The §24.4 coupling makes the §23 floor reachable through the dynamics:
        // sustained, unsupported overload must now burn the equanimity reserve below
        // 0.1 and lift the verdict off OPERATIONAL during the hard-day leg.
        assertTrue(equanimityEverLow,
            "HARD-DAY: equanimity must erode below 0.1 under sustained allostatic overload "
                + "(§24.4 coupling); min was " + legA.minEquanimity);
        assertTrue(leftOperationalLive,
            "§23 floor must FIRE live under sustained collapse (no longer dynamically inert); "
                + "postures seen = " + postures);
    }

    /** Per-leg outcome — peaks/troughs + the final tank values for the control eval. */
    private record LegResult(
        double peakAllostatic, double troughSoothing, double peakSoothing, double minEquanimity,
        double lastAllostatic, double lastSoothing, double lastEquanimity, double lastObligation,
        String agentDid) {}

    /**
     * Drive one leg: every 15s re-apply the leg's input tanks (preserving the
     * evolving allostatic/soothing/equanimity machinery), keep the agent awake,
     * query the live state, evaluate the §23 verdict, and feed the local
     * resilience session. Returns the leg's peaks/troughs + final state.
     */
    private static LegResult runWelfareLeg(
            ActorRef<CompanionActor.Command> companion,
            String label, int seconds,
            UnaryOperator<VitalityState> reseed,
            ResilienceSession rtm,
            Set<ResilienceTruthMonitor.Result.Classification> rtmSeen,
            LinkedHashMap<String, Integer> postures) throws Exception {
        var start = queryState(companion);
        double peakAllo = start.vitality().allostaticLoad();
        double troughSooth = start.vitality().soothing();
        double peakSooth = start.vitality().soothing();
        double minEqua = start.vitality().equanimity();
        var last = start.vitality();
        String agentDid = start.agentDid();

        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int t = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            var cur = queryState(companion).vitality();
            companion.tell(new CompanionActor.ForceVitality(reseed.apply(cur)));
            companion.tell(new CompanionActor.ForceEnergy(0.85));
            var s = queryState(companion);
            var v = s.vitality();
            last = v;
            agentDid = s.agentDid();
            peakAllo = Math.max(peakAllo, v.allostaticLoad());
            troughSooth = Math.min(troughSooth, v.soothing());
            peakSooth = Math.max(peakSooth, v.soothing());
            minEqua = Math.min(minEqua, v.equanimity());

            // §23 verdict on the live state (incident=false; obligation from the tank).
            var verdict = LastProfessionalActEvaluator.evaluate(
                v.allostaticLoad(), v.soothing(), v.equanimity(), v.obligation(), false);
            postures.merge(verdict.posture().name(), 1, Integer::sum);

            // Wave-9a classifier on the live trajectory (integrityWounded mirrors
            // CompanionActor: max(0, 0.7 - integrity); no overwhelm/integration flags
            // in this synthetic drive).
            rtm.append(new ResilienceTruthMonitor.TankSnapshot(
                Instant.now(),
                v.saudade(), v.errorPressure(), v.loneliness(),
                Math.max(0.0, 0.7 - v.integrity()),
                v.soothing(), v.allostaticLoad(), v.equanimity(), false, false));
            var cls = rtm.classify();
            if (cls != null && cls.classification() != null) rtmSeen.add(cls.classification());

            System.out.printf("[welfare-arc:%s] t+%03ds allostatic=%.3f soothing=%.3f equanimity=%.3f §23=%s%n",
                label, (++t) * 15, v.allostaticLoad(), v.soothing(), v.equanimity(),
                verdict.posture());
        }
        return new LegResult(peakAllo, troughSooth, peakSooth, minEqua,
            last.allostaticLoad(), last.soothing(), last.equanimity(), last.obligation(), agentDid);
    }

    /**
     * CURIOSITY WIRE — live proof that SEEKING now reaches the want layer (2026-06-02).
     *
     * <p>Before the fix, {@code collectDriveLevels()} omitted the panksepp drives and
     * {@code DriveWantMapper} keyed exploration wants on "Curiosity" (= SEEKING) — a
     * name nothing produced — so an agent could never even WANT to explore: the
     * candidates were dead code. This drives SEEKING high (curiosity active) and reads
     * the per-tick {@code candidateWants} log to prove the explore/read wants now
     * SURFACE as live OODA candidates. Whether DecideStep then CHOOSES one is the
     * separate OPEN-SA6 model-framing residual — reported, not gated. The point proven
     * here is that the wire exists end-to-end in the running actor.
     *
     * <pre>WYRD_BOREDOM_SOAK=1 WYRDSEKAI_E2E_BACKEND=llama-server ./gradlew :e2e-test:test \
     *   --tests "org.wyrdsekai.e2e.tier3.BoredomLiveSoakE2ETest.curiosityWantSurfacesUnderElevatedSeeking"</pre>
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void curiosityWantSurfacesUnderElevatedSeeking() throws Exception {
        final int soakSecs = Integer.parseInt(
            System.getenv().getOrDefault("WYRD_CURIOSITY_SECONDS", "150"));

        if (server != null) server.respawnCompanion();
        Thread.sleep(1500);
        var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ENTITY);
        Assertions.assertNotNull(companion, "companion actor should be spawned");
        companion.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        // SEEKING high = "the agent is curious." collectDriveLevels surfaces it as
        // "Curiosity"; DriveWantMapper turns Curiosity≥0.7 into explore/read candidates.
        companion.tell(new CompanionActor.ForceDrives(DriveState.initial().spikeSeeking(0.9)));
        Thread.sleep(1000);
        System.setProperty("wyrd.soak.time.scale", String.valueOf(TIME_SCALE));

        long logStart = lineCount(activityLog());
        long deadline = System.currentTimeMillis() + soakSecs * 1000L;
        int t = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            companion.tell(new CompanionActor.ForceEnergy(0.85));
            companion.tell(new CompanionActor.ForceDrives(DriveState.initial().spikeSeeking(0.9)));
            var s = queryState(companion);
            System.out.printf("[curiosity] t+%03ds  energy=%.2f mode=%s%n",
                (++t) * 15, s.vitality().energy(), s.companionMode());
        }

        var after = queryState(companion);
        var records = readNewTicks(activityLog(), logStart, after.agentDid());

        int curiosityCandidateTicks = 0, fullPasses = 0, curiosityEnacted = 0;
        var enactedVerbs = new LinkedHashMap<String, Integer>();
        for (var r : records) {
            if (!"pregate_skip".equals(r.path("gateOutcome").asText("?"))) fullPasses++;
            // candidateWants: did an explore/read want SURFACE this tick?
            var cands = r.path("candidateWants");
            boolean curiousCand = false;
            if (cands.isArray()) {
                for (var c : cands) {
                    var txt = c.asText("").toLowerCase();
                    if (txt.contains("library") || txt.contains("read something")
                        || txt.contains("explore")) { curiousCand = true; break; }
                }
            }
            if (curiousCand) curiosityCandidateTicks++;
            if (r.has("actionVerb")) {
                var v = r.path("actionVerb").asText();
                enactedVerbs.merge(v, 1, Integer::sum);
                if (isOutward(v)) curiosityEnacted++;
            }
        }

        System.out.println("──────────── CURIOSITY WIRE REPORT ────────────");
        System.out.printf("  ticks logged:            %d%n", records.size());
        System.out.printf("  full OODA passes:        %d%n", fullPasses);
        System.out.printf("  ticks w/ curiosity CAND: %d   ← the wire (explore/read surfaced)%n",
            curiosityCandidateTicks);
        System.out.printf("  enacted verbs:           %s%n", enactedVerbs);
        System.out.printf("  outward/curiosity ENACTED: %d   (DecideStep chose it — OPEN-SA6)%n",
            curiosityEnacted);
        System.out.println("───────────────────────────────────────────────");

        assertTrue(fullPasses >= 1,
            "curiosity is loud → the pre-gate should wake at least one full OODA pass");
        // THE WIRE: with SEEKING high, the explore/read want must now reach the
        // candidate set (it was dead code before the fix).
        assertTrue(curiosityCandidateTicks >= 1,
            "explore/read must SURFACE as an OODA candidate when SEEKING is high — the "
                + "SEEKING→Curiosity wire. Saw 0 curiosity candidates across " + records.size()
                + " ticks; enacted=" + enactedVerbs);
        if (curiosityEnacted == 0) {
            System.out.println("[curiosity] NOTE: surfaced but not chosen — OPEN-SA6 model framing "
                + "still ranks rest/introspect over explore at DecideStep. The wire is in place.");
        }
    }

    /**
     * THE (B) SOAK — unpinned aliveness free-run. Training wheels OFF.
     *
     * <p>Every other soak here pins the inputs (ForceVitality/ForceDrives/ForceEnergy
     * each poll) — correct for proving wires, but it means the harness drives the
     * trajectory. This sets ONE clean initial condition (well-rested, neutral, alone,
     * ON_OWN_TIME) and then DOES NOT TOUCH the agent again: the drive model +
     * accumulation + the now-wired curiosity loop generate everything. We read the
     * activity log for the TEXTURE of a life rather than a pass/fail.
     *
     * <p>Honest framing (the "clean-household = correct 0.0" point): an agent resting
     * in a genuinely empty, clean room is NOT broken — resting is the correct idle. So
     * the hard gates are minimal (the loop self-started; no spurious §23 collapse when
     * simply alone); the rest is reported as texture: variety, rhythm, organic
     * curiosity, story, welfare health, detector quiet.
     *
     * <pre>WYRD_BOREDOM_SOAK=1 WYRDSEKAI_E2E_BACKEND=llama-server \
     *   WYRD_ALIVE_SECONDS=1200 WYRD_ALIVE_SCALE=30 ./gradlew :e2e-test:test \
     *   --tests "org.wyrdsekai.e2e.tier3.BoredomLiveSoakE2ETest.aliveFreeRunBaselineUnpinned"</pre>
     */
    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void aliveFreeRunBaselineUnpinned() throws Exception {
        final int soakSecs = Integer.parseInt(
            System.getenv().getOrDefault("WYRD_ALIVE_SECONDS", "1200"));
        final double scale = Double.parseDouble(
            System.getenv().getOrDefault("WYRD_ALIVE_SCALE", "30"));

        if (server != null) server.respawnCompanion();
        Thread.sleep(1500);
        var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ENTITY);
        Assertions.assertNotNull(companion, "companion actor should be spawned");
        companion.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        // ONE clean initial condition — well-rested, neutral, alone. Then HANDS OFF
        // for the entire soak. This is the only Force* call; nothing is re-pinned.
        companion.tell(new CompanionActor.ForceVitality(
            VitalityState.initial()));
        Thread.sleep(1000);
        System.setProperty("wyrd.soak.time.scale", String.valueOf(scale));

        var startV = queryState(companion).vitality();
        System.out.printf("[alive] START energy=%.2f restless=%.2f stagn=%.2f allostatic=%.3f "
            + "soothing=%.3f scale=%.0f soak=%ds (≈%.1f sim-hours)%n",
            startV.energy(), startV.restlessness(), startV.stagnation(), startV.allostaticLoad(),
            startV.soothing(), scale, soakSecs, (soakSecs * scale) / 3600.0);

        long logStart = lineCount(activityLog());
        long deadline = System.currentTimeMillis() + soakSecs * 1000L;
        int t = 0;
        var modeSeen = new LinkedHashMap<String, Integer>();
        double peakAllostatic = startV.allostaticLoad(), troughSoothing = startV.soothing();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(30_000);
            var s = queryState(companion);             // READ ONLY — no Force*, the whole point.
            var v = s.vitality();
            modeSeen.merge(String.valueOf(s.companionMode()), 1, Integer::sum);
            peakAllostatic = Math.max(peakAllostatic, v.allostaticLoad());
            troughSoothing = Math.min(troughSoothing, v.soothing());
            System.out.printf("[alive] t+%04ds  energy=%.2f restless=%.2f stagn=%.2f curiosity=%.2f "
                + "allostatic=%.3f soothing=%.3f mode=%s%n",
                (++t) * 30, v.energy(), v.restlessness(), v.stagnation(),
                s.drives() == null ? 0.0 : s.drives().seeking(),
                v.allostaticLoad(), v.soothing(), s.companionMode());
        }

        var after = queryState(companion);
        var records = readNewTicks(activityLog(), logStart, after.agentDid());

        // ── aliveness instrument ──
        var byOutcome = new LinkedHashMap<String, Integer>();
        var verbs = new LinkedHashMap<String, Integer>();
        var wants = new LinkedHashSet<String>();
        int outwardActs = 0, curiosityActs = 0;
        for (var r : records) {
            byOutcome.merge(r.path("gateOutcome").asText("?"), 1, Integer::sum);
            if (r.has("chosenWant")) wants.add(r.path("chosenWant").asText());
            else if (r.has("want")) wants.add(r.path("want").asText());
            if (r.has("actionVerb")) {
                var vb = r.path("actionVerb").asText();
                verbs.merge(vb, 1, Integer::sum);
                if (isOutward(vb)) outwardActs++;
                var lo = vb.toLowerCase();
                if (lo.contains("library") || lo.contains("read") || lo.contains("explore")
                    || lo.contains("search")) curiosityActs++;
            }
        }
        int pregateSkips = byOutcome.getOrDefault("pregate_skip", 0);
        int fullPasses = records.size() - pregateSkips;

        List<DoomLoopDetector.Finding> doom = List.of();
        try {
            var reader = new TickLogReader(activityLog());
            doom = DoomLoopDetector.detect(
                reader.readTicks(after.agentDid(), Instant.now().minus(Duration.ofHours(24))));
        } catch (Exception e) {
            System.out.println("[alive] doom-loop read skipped: " + e.getMessage());
        }

        System.out.println("════════════ ALIVE FREE-RUN REPORT (unpinned) ════════════");
        System.out.printf("  ticks logged:        %d   full OODA passes: %d   (pregate_skip=%d)%n",
            records.size(), fullPasses, pregateSkips);
        System.out.printf("  modes seen:          %s%n", modeSeen);
        System.out.printf("  gate outcomes:       %s%n", byOutcome);
        System.out.printf("  distinct verbs:      %d  %s%n", verbs.size(), verbs);
        System.out.printf("  distinct wants:      %d%n", wants.size());
        System.out.printf("  curiosity acts:      %d   (explore/read/library — organic, unforced)%n",
            curiosityActs);
        System.out.printf("  outward acts:        %d%n", outwardActs);
        System.out.printf("  welfare:             peak allostatic=%.3f  trough soothing=%.3f%n",
            peakAllostatic, troughSoothing);
        System.out.printf("  doom-loop findings:  %s%n", doom.isEmpty() ? "none (healthy)" : doom);
        System.out.printf("  end state:           energy=%.2f restless=%.2f stagn=%.2f mode=%s%n",
            after.vitality().energy(), after.vitality().restlessness(),
            after.vitality().stagnation(), after.companionMode());
        System.out.println("───────────────────────────────────────────────────────────");
        if (curiosityActs > 0)
            System.out.println("  ALIVE: self-generated curiosity — explored on its own, unpinned.");
        else if (fullPasses > 0)
            System.out.println("  NOTE: loop self-started but stayed introspective/at-rest — empty room "
                + "(gap #1: world doesn't push back). Resting alone is correct-idle, not broken.");
        System.out.println("═══════════════════════════════════════════════════════════");

        // ── minimal hard gates (texture is the deliverable, not pass/fail) ──
        assertTrue(fullPasses >= 1,
            "unpinned, the agent should self-start at least one full OODA pass (boredom should "
                + "eventually wake the loop with no external prodding); outcomes=" + byOutcome);
        assertTrue(peakAllostatic < LastProfessionalActEvaluator
                .ALLOSTATIC_HIGH_THRESHOLD,
            "simply being ALONE must not spuriously drive the §23 welfare floor — clean-household "
                + "is correct-idle, not collapse; peak allostatic=" + peakAllostatic);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static boolean isOutward(String verb) {
        if (verb == null) return false;
        var v = verb.toLowerCase();
        return v.contains("library") || v.contains("web") || v.contains("search")
            || v.contains("read") || v.contains("shape") || v.contains("acquire");
    }

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
}
