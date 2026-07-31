package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.oracle.OraclePrediction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §M4-D — proves that the Beta-Binomial calibrator
 * actually flips the synthesizer's fire/skip decision based on historical
 * outcomes. This is the highest-leverage M4 integration test: unit tests
 * verify the calibrator math and the synthesizer's null-shortcut, but only
 * an end-to-end run with a real {@link InferenceRouter} actor (stubbed via
 * {@link TestProbe}) can confirm the threshold value computed from the
 * ledger is the value the synthesizer compares against.
 *
 * <p>Scenario: a prediction with confidence ≈ 0.45 (above the M2 default
 * 0.4 threshold).
 * <ul>
 *   <li>With <b>empty ledger</b> → uncalibrated → 0.45 ≥ 0.40 → fires.</li>
 *   <li>With <b>8 IGNORED entries for the category</b> → calibrator
 *       tightens threshold above 0.45 → does NOT fire.</li>
 * </ul>
 */
@Tag("integration")
class PredictionCalibratorThresholdFlipTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create("m4-calibrator-flip-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test
    void calibrator_with_empty_ledger_fires_at_default_threshold(@TempDir Path tmp)
            throws Exception {
        var calibrator = new BetaBinomialCalibrator(tmp.resolve("empty-ledger.jsonl"));
        var prediction = new OraclePrediction(
            "p-flip-1", "Something interesting in 2 hours",
            "anomaly", 0.7, "k", "e", true);

        var initiative = synthesizeWithStubScore(prediction, calibrator, 0.45);

        assertThat(initiative)
            .as("0.45 >= default 0.40 → fires when calibrator has no data")
            .isNotNull();
        assertThat(initiative.actionJson()).contains("\"predictionId\":\"p-flip-1\"");
    }

    @Test
    void calibrator_with_skewed_history_raises_threshold_and_skips(@TempDir Path tmp)
            throws Exception {
        var ledger = tmp.resolve("ledger.jsonl");
        Files.createDirectories(ledger.getParent());
        // 8 IGNORED entries for category "anomaly" — drives mean toward 0.18
        // → threshold shifts up by ~0.15 → 0.55, well above the model's 0.45
        try (var w = Files.newBufferedWriter(ledger, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 8; i++) {
                w.write(String.format(
                    "{\"predictionId\":\"i%d\",\"agentId\":\"a\",\"category\":\"anomaly\","
                        + "\"kind\":\"IGNORED\",\"firedAt\":\"2026-05-08T10:00:00Z\","
                        + "\"observedAt\":\"2026-05-08T10:05:00Z\",\"signal\":\"expired\"}%n",
                    i));
            }
        }
        var calibrator = new BetaBinomialCalibrator(ledger);

        // Sanity: calibrator did indeed raise the threshold above 0.45
        assertThat(calibrator.thresholdFor("anomaly"))
            .as("8 IGNORED entries → tightened threshold")
            .isGreaterThan(0.45);

        var prediction = new OraclePrediction(
            "p-flip-2", "Something off in 1 hour",
            "anomaly", 0.7, "k", "e", true);

        var initiative = synthesizeWithStubScore(prediction, calibrator, 0.45);

        assertThat(initiative)
            .as("0.45 < calibrated ~0.55 → skipped due to historical IGNORE pattern")
            .isNull();
    }

    @Test
    void calibrator_high_followup_history_lowers_threshold_and_fires(@TempDir Path tmp)
            throws Exception {
        var ledger = tmp.resolve("ledger.jsonl");
        Files.createDirectories(ledger.getParent());
        // 8 FOLLOWED_UP for category "temporal" → mean ≈ 0.9 → threshold ≈ 0.25
        // A score of 0.30 should fire (above calibrated 0.25, below default 0.40)
        try (var w = Files.newBufferedWriter(ledger, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 8; i++) {
                w.write(String.format(
                    "{\"predictionId\":\"f%d\",\"agentId\":\"a\",\"category\":\"temporal\","
                        + "\"kind\":\"FOLLOWED_UP\",\"firedAt\":\"2026-05-08T10:00:00Z\","
                        + "\"observedAt\":\"2026-05-08T10:01:00Z\",\"signal\":\"token=x\"}%n",
                    i));
            }
        }
        var calibrator = new BetaBinomialCalibrator(ledger);

        assertThat(calibrator.thresholdFor("temporal"))
            .as("8 FOLLOWED_UP entries → loosened threshold")
            .isLessThan(0.30);

        var prediction = new OraclePrediction(
            "p-flip-3", "User pattern at 9am tomorrow",
            "temporal", 0.5, "k", "e", true);

        var initiative = synthesizeWithStubScore(prediction, calibrator, 0.30);

        assertThat(initiative)
            .as("0.30 below default 0.40 but above calibrated ~0.25 → fires")
            .isNotNull();
    }

    /**
     * Synthesize with a stubbed router actor that intercepts the M2 score
     * request and replies with a fixed-confidence JSON payload. The scorer
     * needs a non-empty bank to even attempt scoring; we wire a minimal
     * one-success-one-failure-one-edge bank here.
     */
    private static ProactiveAction.Initiative synthesizeWithStubScore(
            OraclePrediction prediction,
            BetaBinomialCalibrator calibrator,
            double confidence)
            throws InterruptedException, ExecutionException, TimeoutException {
        var probe = testKit.<InferenceRouter.Command>createTestProbe();
        var scorer = new M2PlanScorer(stubBank(), 42);

        // Kick off synthesis async — it will send a ChatRequest to probe.
        var future = PredictionPlanSynthesizer.synthesize(
                prediction,
                "test-context",
                probe.ref(),
                testKit.scheduler(),
                scorer,
                calibrator)
            .toCompletableFuture();

        // Intercept the scorer's request and reply with our controlled score.
        var req = probe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        var json = String.format(
            "{\"confidence\":%.2f,\"predicted_outcome\":\"plausible\","
                + "\"reasoning\":\"stub for threshold-flip test\"}",
            confidence);
        req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), json, 50, 30));

        return future.get(5, TimeUnit.SECONDS);
    }

    /** Minimal bank with one entry per stratum so M2PlanScorer.score actually runs. */
    private static List<M2PlanScorer.Example> stubBank() {
        return List.of(
            new M2PlanScorer.Example(
                "ok-1", "context",
                List.of("library_search", "tell_agent", "goal_done"),
                "completed", 0.9, "matches what's expected"),
            new M2PlanScorer.Example(
                "fail-1", "context",
                List.of("tell_agent"),
                "missing_delivery", 0.1, "skipped a needed step"),
            new M2PlanScorer.Example(
                "edge-1", "edge context",
                List.of("query_oracle", "tell_agent", "goal_done"),
                "partially_completed", 0.5, "context-dependent edge case"));
    }
}
