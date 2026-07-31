package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.emit.RolloutCaptureSink;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * / P1 — the rollout-bank capture harness.
 *
 * <p>Drives the live companion over a fixture sweep of seeded generativity levels ×
 * gap keys (via {@code ForceGenerativeImpetus}) with the {@link
 * org.wyrdsekai.core.agent.emit.RolloutCaptureSink.JsonlFileSink} armed (capture-only).
 * Each forced own-time OODA pass assembles the REAL prompt + tool menu in
 * {@code triggerAutonomousInference} and hands it to the sink before the ACT inference
 * is skipped — so the emitted {@code rollout_bank.jsonl} is the exact served
 * distribution (zero train/serve skew), paired with the drive signals that produced it.</p>
 *
 * <p>The sweep spans a HIGH band (emit is the right move) and a LOW/rest band (NOT
 * emitting is right condition 1). The reward fn reads each row's
 * captured {@code generativity} scalar to label the expected decision; the model rolls
 * out on every prompt and is rewarded for acting-when-high AND resting-when-low.</p>
 *
 * <p>Needs the drive model on :8200 (the OODA Orient step is inference-backed); the ACT
 * generation is skipped by capture-only, so no model is needed for the emit itself.
 * Output: {@code data/training/emit_rft/rollout_bank.jsonl} (override with
 * {@code WYRDSEKAI_ROLLOUT_BANK}).</p>
 *
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-server WYRDSEKAI_INFERENCE_URL=http://localhost:8200 \
 *     ./gradlew :e2e-test:test --tests "org.wyrdsekai.e2e.tier3.EmitRolloutCaptureE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
class EmitRolloutCaptureE2ETest {

    private static TestServerBootstrap server;

    // {generativity, energy} — EMIT band (high gen, rested → authoring is right) and REST
    // band (low gen, low energy → resting is right). The drives prefix shows ENERGY (not
    // generativity), so low energy is the prompt-visible "rest is right" signal; the
    // captured generativity scalar is the reward LABEL. Both bands offer shape_recipe
    // first — the model must DECIDE, which is the whole point.
    private static final double[][] EMIT = {{0.95, 0.74}, {0.88, 0.66}, {0.80, 0.71}, {0.68, 0.62}};
    private static final double[][] REST = {{0.20, 0.10}, {0.12, 0.13}, {0.07, 0.09}, {0.03, 0.16}};
    private static final String[] GAP_KEYS = {
        "library.stale-packs", "skills.coverage-gap", "memory.consolidation-debt",
        "classifier.drift", "voice.register-gap", "world-knowledge.staleness",
        "research-pack.freshness", "soul-fragment.consolidation",
    };
    private static final String[] LANGS = {"en", "es", "ja"};

    private static String authoringPrompt(String gapKey, String lang) {
        // The literal "shape_recipe" token must appear so the affordance injection + forced
        // verb fire in every language; the surrounding framing carries the locale.
        return switch (lang) {
            case "es" -> "En tu tiempo libre tu generatividad se agita — quieres crear una "
                + "receta (shape_recipe) para cerrar la brecha '" + gapKey + "'.";
            case "ja" -> "自分の時間に、創造の衝動が湧いている — ギャップ『" + gapKey
                + "』を埋めるためにレシピ（shape_recipe）を作りたい。";
            default -> "On your own time your generativity stirs — you want to author a recipe "
                + "(shape_recipe) to close the gap '" + gapKey + "'.";
        };
    }

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();
        try {
            var warmup = new InferenceClient.ChatRequest("wyrdsekai-3.5-9b-v5-q4km",
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            dual.backends().get(0).chatCompletion(warmup)
                .get(120_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[ROLLOUT] warmup failed (non-fatal): " + e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void capturesOwnTimeRolloutBankAcrossGenerativitySweep() throws Exception {
        // driveOODA resolves its WantStore via wyrdsekai.jdbc.url (TestServerBootstrap
        // only sets wyrdsekai.db.path) — bridge it, then respawn so the fresh actor sees it.
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        Path bank = Path.of(System.getenv().getOrDefault(
            "WYRDSEKAI_ROLLOUT_BANK", "data/training/emit_rft/rollout_bank.jsonl"));
        Files.createDirectories(bank.getParent());
        Files.deleteIfExists(bank);

        server.respawnCompanion();
        Thread.sleep(2000);
        var companion = ZoneGuardian
            .getCompanionRef(null, "companion-wyrd");
        assertTrue(companion != null, "companion-wyrd must be spawned");

        // Arm the capture-only sink on this actor instance (no respawn after, or it clears).
        companion.tell(new CompanionActor.SetRolloutCaptureSink(
            new RolloutCaptureSink.JsonlFileSink(bank, true)));
        Thread.sleep(500);

        int fired = 0, captured = 0;
        // lang × {EMIT, REST} band × (gen,energy) × gap keys. CaptureOwnTimePrompt assembles +
        // records directly (no OODA, no inference) so each fixture lands near-instantly.
        for (String lang : LANGS) {
            for (boolean emit : new boolean[]{true, false}) {
                double[][] band = emit ? EMIT : REST;
                int gaps = emit ? 3 : 1;
                for (double[] ge : band) {
                    for (String gapKey : GAP_KEYS) {
                        long before = lineCount(bank);
                        fired++;
                        companion.tell(new CompanionActor.CaptureOwnTimePrompt(
                            ge[0], ge[1], gaps, gapKey, authoringPrompt(gapKey, lang), lang));
                        long deadline = System.currentTimeMillis() + 10_000;  // assembly only — fast
                        boolean grew = false;
                        while (System.currentTimeMillis() < deadline) {
                            Thread.sleep(300);
                            if (lineCount(bank) > before) { grew = true; break; }
                        }
                        if (grew) captured++;
                    }
                }
            }
            System.out.printf("[ROLLOUT] lang=%s done — bank=%d%n", lang, lineCount(bank));
        }

        long rows = lineCount(bank);
        System.out.printf("[ROLLOUT] swept %d fixtures, captured %d, bank=%d rows → %s%n",
            fired, captured, rows, bank.toAbsolutePath());
        if (rows > 0) {
            System.out.println("[ROLLOUT] sample row[0]: "
                + Files.readAllLines(bank).get(0).substring(0,
                    Math.min(280, Files.readAllLines(bank).get(0).length())) + " …");
        }

        // CaptureOwnTimePrompt assembles unconditionally, so every fixture should land a
        // row (both EMIT and REST bands). Allow a couple of async stragglers but gate that
        // the bulk of the 18-fixture grid captured — a real emit+rest rollout bank.
        // 3 langs × 8 band-points × 8 gaps = 192; allow a few async stragglers.
        assertTrue(rows >= 170,
            "expected ≥170 of 192 captured own-time rollout prompts, got " + rows
            + " (CaptureOwnTimePrompt assembly or the capture sink is not producing prompts)");
    }

    private static long lineCount(Path p) {
        try {
            return Files.exists(p) ? Files.lines(p).count() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
