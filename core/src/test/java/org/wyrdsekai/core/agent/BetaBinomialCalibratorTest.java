package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BetaBinomialCalibratorTest {

    @Test
    void empty_ledger_returns_default_threshold(@TempDir Path tmp) {
        var calib = new BetaBinomialCalibrator(tmp.resolve("nonexistent.jsonl"));
        assertThat(calib.thresholdFor("temporal"))
            .isEqualTo(BetaBinomialCalibrator.DEFAULT_THRESHOLD);
    }

    @Test
    void unknown_category_returns_default_threshold(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // Add data for "topic" only
        ledger.append(new PredictionOutcomeLedger.Entry(
            "p", "ag", "topic", PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        assertThat(calib.thresholdFor("anomaly"))
            .isEqualTo(BetaBinomialCalibrator.DEFAULT_THRESHOLD);
    }

    @Test
    void high_followed_up_rate_lowers_threshold(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // 8 followed-up, 0 dismissed/ignored → mean ≈ 0.9 → max loosen
        for (int i = 0; i < 8; i++) {
            ledger.append(new PredictionOutcomeLedger.Entry(
                "p" + i, "ag", "temporal",
                PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));
        }

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        var threshold = calib.thresholdFor("temporal");
        // Should be near DEFAULT - MAX_SHIFT (0.4 - 0.15 = 0.25)
        assertThat(threshold).isCloseTo(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD - BetaBinomialCalibrator.MAX_SHIFT,
            within(0.03));
        assertThat(threshold).isLessThan(BetaBinomialCalibrator.DEFAULT_THRESHOLD);
    }

    @Test
    void low_followed_up_rate_raises_threshold(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // 1 followed-up, 8 ignored → mean ≈ 0.18 → max tighten
        ledger.append(new PredictionOutcomeLedger.Entry(
            "f", "ag", "anomaly", PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));
        for (int i = 0; i < 8; i++) {
            ledger.append(new PredictionOutcomeLedger.Entry(
                "i" + i, "ag", "anomaly",
                PredictionOutcomeLedger.Kind.IGNORED, t, t, "expired"));
        }

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        var threshold = calib.thresholdFor("anomaly");
        assertThat(threshold).isCloseTo(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD + BetaBinomialCalibrator.MAX_SHIFT,
            within(0.03));
        assertThat(threshold).isGreaterThan(BetaBinomialCalibrator.DEFAULT_THRESHOLD);
    }

    @Test
    void single_outcome_only_partially_shifts_threshold(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // 1 outcome only — sample-size ramp (n=1 / MIN_OBSERVATIONS=5 = 0.2 weight)
        ledger.append(new PredictionOutcomeLedger.Entry(
            "p", "ag", "topic", PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        var threshold = calib.thresholdFor("topic");
        // Posterior mean (2/3 ≈ 0.667) → max shift would be -0.15, but ramp = 1/5
        // → final shift around -0.03 → threshold ≈ 0.37
        assertThat(threshold).isLessThan(BetaBinomialCalibrator.DEFAULT_THRESHOLD);
        assertThat(threshold).isGreaterThan(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD - BetaBinomialCalibrator.MAX_SHIFT);
    }

    @Test
    void mid_rate_falls_between_extremes(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // 4 followed-up, 4 ignored → mean ≈ 0.5 → middle of band
        for (int i = 0; i < 4; i++) {
            ledger.append(new PredictionOutcomeLedger.Entry(
                "f" + i, "ag", "forecast",
                PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));
            ledger.append(new PredictionOutcomeLedger.Entry(
                "i" + i, "ag", "forecast",
                PredictionOutcomeLedger.Kind.IGNORED, t, t, "expired"));
        }

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        var threshold = calib.thresholdFor("forecast");
        // Mean ≈ 0.5 → between 0.6 and 0.2 → some loosen but not max
        assertThat(threshold).isCloseTo(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD - 0.075, within(0.03));
    }

    @Test
    void posterior_counts_alpha_beta_correctly(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // 3 followed-up, 2 dismissed, 1 ignored → α=4, β=4
        for (int i = 0; i < 3; i++)
            ledger.append(new PredictionOutcomeLedger.Entry(
                "f" + i, "ag", "topic",
                PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));
        for (int i = 0; i < 2; i++)
            ledger.append(new PredictionOutcomeLedger.Entry(
                "d" + i, "ag", "topic",
                PredictionOutcomeLedger.Kind.DISMISSED, t, t, "x"));
        ledger.append(new PredictionOutcomeLedger.Entry(
            "i", "ag", "topic", PredictionOutcomeLedger.Kind.IGNORED, t, t, "expired"));

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        var post = calib.posteriorFor("topic");
        assertThat(post.alpha()).isEqualTo(4); // 1 prior + 3 followed
        assertThat(post.beta()).isEqualTo(4);  // 1 prior + 2 dismissed + 1 ignored
        assertThat(post.mean()).isCloseTo(0.5, within(0.001));
        assertThat(post.observations()).isEqualTo(6);
    }

    @Test
    void refresh_picks_up_new_entries(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        ledger.append(new PredictionOutcomeLedger.Entry(
            "p1", "ag", "topic", PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        assertThat(calib.posteriorFor("topic").alpha()).isEqualTo(2);

        // Append more
        for (int i = 0; i < 5; i++)
            ledger.append(new PredictionOutcomeLedger.Entry(
                "p" + (i + 2), "ag", "topic",
                PredictionOutcomeLedger.Kind.IGNORED, t, t, "expired"));
        calib.refresh();
        assertThat(calib.posteriorFor("topic").alpha()).isEqualTo(2);
        assertThat(calib.posteriorFor("topic").beta()).isEqualTo(6);
    }

    @Test
    void refresh_is_noop_when_file_unchanged(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        ledger.append(new PredictionOutcomeLedger.Entry(
            "p", "ag", "topic", PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));

        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        var snapshot = calib.snapshot();
        calib.refresh(); // file size unchanged
        assertThat(calib.snapshot()).isEqualTo(snapshot);
    }

    @Test
    void threshold_clamps_within_band(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // 100 followed-up, 0 against → mean would push way below band, clamp must hold
        for (int i = 0; i < 100; i++)
            ledger.append(new PredictionOutcomeLedger.Entry(
                "p" + i, "ag", "topic",
                PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));
        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        var threshold = calib.thresholdFor("topic");
        assertThat(threshold).isGreaterThanOrEqualTo(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD - BetaBinomialCalibrator.MAX_SHIFT - 0.001);
        assertThat(threshold).isLessThanOrEqualTo(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD + 0.001);
    }

    @Test
    void malformed_lines_are_skipped(@TempDir Path tmp) throws Exception {
        var path = tmp.resolve("m4").resolve("outcomes.jsonl");
        Files.createDirectories(path.getParent());
        // Mix of valid + garbage
        Files.writeString(path,
            "not-json\n"
                + "{\"category\":\"topic\",\"kind\":\"FOLLOWED_UP\"}\n"
                + "{\"missing\":\"fields\"}\n"
                + "{\"category\":\"topic\",\"kind\":\"FOLLOWED_UP\"}\n");

        var calib = new BetaBinomialCalibrator(path);
        // Two valid FOLLOWED_UP lines → α=3, β=1
        assertThat(calib.posteriorFor("topic").alpha()).isEqualTo(3);
        assertThat(calib.posteriorFor("topic").beta()).isEqualTo(1);
    }

    @Test
    void per_category_isolation(@TempDir Path tmp) throws Exception {
        var ledger = new PredictionOutcomeLedger(tmp);
        var t = Instant.parse("2026-05-08T10:00:00Z");
        // High follow-up for "temporal", high ignore for "anomaly"
        for (int i = 0; i < 6; i++) {
            ledger.append(new PredictionOutcomeLedger.Entry(
                "t" + i, "ag", "temporal",
                PredictionOutcomeLedger.Kind.FOLLOWED_UP, t, t, "x"));
            ledger.append(new PredictionOutcomeLedger.Entry(
                "a" + i, "ag", "anomaly",
                PredictionOutcomeLedger.Kind.IGNORED, t, t, "expired"));
        }
        var calib = new BetaBinomialCalibrator(ledger.ledgerFile());
        assertThat(calib.thresholdFor("temporal")).isLessThan(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD);
        assertThat(calib.thresholdFor("anomaly")).isGreaterThan(
            BetaBinomialCalibrator.DEFAULT_THRESHOLD);
    }
}
