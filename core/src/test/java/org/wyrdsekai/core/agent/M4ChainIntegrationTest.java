package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.oracle.OraclePrediction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §M4 — component-level integration test.
 *
 * <p>Exercises the full A→B→C→D lifecycle using the real components
 * (PredictionScheduler, PredictionPlanSynthesizer, PredictionOutcomeTracker,
 * PredictionOutcomeLedger, BetaBinomialCalibrator) but without an actor
 * system. The CompanionActor wires these as JVM-static singletons rooted
 * at WyrdConfig.dataDir(); this test validates the *contract* between
 * the components by reproducing the same lifecycle against a {@link TempDir}.
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>Happy path — schedule, poll, synthesize (no scoring), track,
 *       resolve via topic match, ledger gains FOLLOWED_UP, calibrator
 *       picks up posterior on next refresh.</li>
 *   <li>Reaper path — schedule, fire, no resolution within window,
 *       reaper writes IGNORED.</li>
 * </ol>
 *
 * <p>Companion-actor wiring (the actual production hooks) is exercised
 * separately by the live Tier 3 multi-zone E2E suite.
 */
@Tag("integration")
class M4ChainIntegrationTest {

    @Test
    void full_chain_schedule_fire_resolve_calibrate(@TempDir Path tmp) throws Exception {
        // ── Wire components rooted at the temp data dir ─────────────
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger);
        var scheduler = new PredictionScheduler(tmp);
        var calibrator = new BetaBinomialCalibrator(ledger.ledgerFile());

        // ── A/B: schedule a near-instant prediction ─────────────────
        var prediction = new OraclePrediction(
            "p-temporal-1",
            "User asks about gardening in 1 minute",
            "temporal", 0.7, "k", "evidence", true);
        var entry = scheduler.schedule(prediction, "agent-X");
        assertThat(entry).isPresent();
        assertThat(scheduler.scheduledCount()).isEqualTo(1);

        // ── B: poll past fire time → due fire surfaces ──────────────
        var pollAt = Instant.now().plus(Duration.ofMinutes(2));
        var dueFires = scheduler.pollDueFires("agent-X", pollAt);
        assertThat(dueFires).hasSize(1);
        assertThat(dueFires.get(0).predictionId()).isEqualTo("p-temporal-1");

        // ── A: synthesize plan (no router/scorer → uncalibrated path,
        //   matches the production no-inference fallback) ─────────────
        var initiative = PredictionPlanSynthesizer.synthesize(
            prediction, null, null, null, null, calibrator)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(initiative).isNotNull();
        assertThat(initiative.actionJson()).contains("\"predictionId\":\"p-temporal-1\"");

        // ── C: tracker begins post-fire window ──────────────────────
        var firedAt = Instant.now();
        tracker.track(prediction.id(), "agent-X", prediction.category(),
            prediction.text(), firedAt);
        assertThat(tracker.openCount()).isEqualTo(1);

        // ── C resolution: user mentions topic within window ─────────
        var resolved = tracker.resolve("agent-X",
            "I should water my gardening plants", firedAt.plusSeconds(45));
        assertThat(resolved).isNotNull();
        assertThat(resolved.predictionId()).isEqualTo("p-temporal-1");
        assertThat(tracker.openCount()).isZero();

        // ── Ledger gained a FOLLOWED_UP entry ───────────────────────
        var ledgerLines = Files.readAllLines(ledger.ledgerFile());
        assertThat(ledgerLines).hasSize(1);
        assertThat(ledgerLines.get(0)).contains("FOLLOWED_UP");
        assertThat(ledgerLines.get(0)).contains("token=gardening");

        // ── D: calibrator refresh picks up the new posterior ────────
        calibrator.refresh();
        var posterior = calibrator.posteriorFor("temporal");
        assertThat(posterior.alpha()).isEqualTo(2);  // 1 prior + 1 followed
        assertThat(posterior.beta()).isEqualTo(1);   // 1 prior + 0 against
        assertThat(posterior.observations()).isEqualTo(1);

        // Single observation only — threshold ramps in proportionally
        var threshold = calibrator.thresholdFor("temporal");
        assertThat(threshold).isLessThan(BetaBinomialCalibrator.DEFAULT_THRESHOLD);
        assertThat(threshold).isGreaterThan(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD - BetaBinomialCalibrator.MAX_SHIFT);
    }

    @Test
    void reaper_path_writes_ignored_when_window_expires(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        // 1-second window so the test runs fast
        var tracker = new PredictionOutcomeTracker(ledger, Duration.ofSeconds(1));
        var calibrator = new BetaBinomialCalibrator(ledger.ledgerFile());

        var prediction = new OraclePrediction(
            "p-anomaly-1",
            "Something seems off about the system",
            "anomaly", 0.6, "k", "evidence", true);

        var firedAt = Instant.now();
        tracker.track(prediction.id(), "agent-Y", prediction.category(),
            prediction.text(), firedAt);
        assertThat(tracker.openCount()).isEqualTo(1);

        // Sweep 5 seconds past — well past the 1-second window
        var reaped = tracker.reapExpired("agent-Y",
            firedAt.plus(Duration.ofSeconds(5)));
        assertThat(reaped).hasSize(1);
        assertThat(tracker.openCount()).isZero();

        // Ledger gained an IGNORED entry
        var ledgerLines = Files.readAllLines(ledger.ledgerFile());
        assertThat(ledgerLines).hasSize(1);
        assertThat(ledgerLines.get(0)).contains("IGNORED");
        assertThat(ledgerLines.get(0)).contains("expired");

        // Calibrator picks it up — anomaly category now has β=2
        calibrator.refresh();
        var posterior = calibrator.posteriorFor("anomaly");
        assertThat(posterior.alpha()).isEqualTo(1);
        assertThat(posterior.beta()).isEqualTo(2);
    }

    @Test
    void spontaneous_mention_pre_fire_cancels_both_tracker_and_scheduler(
            @TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var tracker = new PredictionOutcomeTracker(ledger);
        var scheduler = new PredictionScheduler(tmp);

        // Schedule a not-yet-fired prediction
        var prediction = new OraclePrediction(
            "p-pre-1", "Coffee preferences come up around 9am",
            "topic", 0.5, "k", "e", true);
        scheduler.schedule(prediction, "agent-Z");
        assertThat(scheduler.scheduledCount()).isEqualTo(1);

        // Simulate user spontaneously mentioning the topic.
        // Production logic in CompanionActor.WorldEvent.Said handler:
        //   for (fire : scheduler.snapshot())
        //       if (matchedTopicToken(tokenize(fire.text()), userMessageLower))
        //           scheduler.cancel(fire.predictionId());
        //           tracker.cancel(fire.predictionId());
        var userMessage = "want to talk about coffee".toLowerCase();
        for (var fire : scheduler.snapshot()) {
            var tokens = PredictionOutcomeTracker.tokenize(fire.text());
            var matched = PredictionOutcomeTracker.matchedTopicToken(tokens, userMessage);
            if (matched != null) {
                scheduler.cancel(fire.predictionId());
                tracker.cancel(fire.predictionId());
            }
        }

        // Both dropped, no ledger entry written
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(tracker.openCount()).isZero();
        assertThat(Files.exists(ledger.ledgerFile())).isFalse();
    }
}
