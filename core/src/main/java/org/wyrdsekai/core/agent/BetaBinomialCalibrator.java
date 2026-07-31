package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * §M4-D — per-category Beta-Binomial threshold
 * calibrator. Reads the §M4-C outcome ledger and maintains posteriors over
 * the FOLLOWED_UP rate for each prediction category. The posterior mean
 * shifts the {@link M2PlanScorer} reject threshold so categories that
 * historically pay off get tighter (i.e. lower) thresholds, while
 * categories that consistently bounce off get raised thresholds.
 *
 * <p>Bayesian primitives, not LoRA fine-tuning — works from interaction #1
 * via the uniform prior. With no data, every category returns the default
 * threshold; with data, the posterior tightens onto the empirical rate.
 *
 * <p>Statistics:
 * <ul>
 *   <li>α = 1 (uniform prior) + FOLLOWED_UP count</li>
 *   <li>β = 1 (uniform prior) + DISMISSED count + IGNORED count</li>
 *   <li>posterior mean = α / (α + β)</li>
 * </ul>
 *
 * <p>Threshold mapping (additive shift around default 0.4):
 * <ul>
 *   <li>rate ≥ 0.6 → threshold = default - {@link #MAX_SHIFT} (looser; fire more)</li>
 *   <li>rate ≤ 0.2 → threshold = default + {@link #MAX_SHIFT} (tighter; fire less)</li>
 *   <li>between → linear interpolation</li>
 * </ul>
 *
 * <p>Effective sample size gate: while α + β &lt; {@link #MIN_OBSERVATIONS},
 * we lean on the prior and shift only proportionally. This avoids wild
 * threshold swings from a single outcome.
 */
public final class BetaBinomialCalibrator {

    private static final Logger log = LoggerFactory.getLogger(BetaBinomialCalibrator.class);

    public static final double DEFAULT_THRESHOLD = 0.4;
    /** Maximum +/- shift around DEFAULT_THRESHOLD. Keeps the gate sane. */
    public static final double MAX_SHIFT = 0.15;
    /** α + β below this → ramp the shift in linearly toward MAX_SHIFT. */
    public static final int MIN_OBSERVATIONS = 5;

    /** Per-category Beta posterior. */
    public record Posterior(int alpha, int beta) {
        /** α + β − 2 (the prior contributes 2 of these). */
        public int observations() { return alpha + beta - 2; }
        public double mean() { return (double) alpha / (alpha + beta); }
    }

    private final Path ledgerFile;
    private final Map<String, Posterior> posteriors = new HashMap<>();
    private long lastLoadedBytes = -1;

    public BetaBinomialCalibrator(Path ledgerFile) {
        this.ledgerFile = ledgerFile;
        refresh();
    }

    /** Re-scan the ledger and rebuild posteriors. Called on construction + sleep cycle. */
    public synchronized void refresh() {
        if (ledgerFile == null || !Files.exists(ledgerFile)) {
            posteriors.clear();
            return;
        }
        long size;
        try {
            size = Files.size(ledgerFile);
        } catch (IOException e) {
            log.warn("Calibrator size check failed: {}", e.getMessage());
            return;
        }
        if (size == lastLoadedBytes && !posteriors.isEmpty()) return; // no change
        lastLoadedBytes = size;

        var counts = new HashMap<String, int[]>(); // [followedUp, dismissed, ignored]
        try {
            for (var line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                var entry = parse(line);
                if (entry == null) continue;
                var c = counts.computeIfAbsent(entry.category, k -> new int[3]);
                switch (entry.kind) {
                    case "FOLLOWED_UP" -> c[0]++;
                    case "DISMISSED" -> c[1]++;
                    case "IGNORED" -> c[2]++;
                    default -> { /* unknown — skip */ }
                }
            }
        } catch (IOException e) {
            log.warn("Calibrator failed to read {}: {}", ledgerFile, e.getMessage());
            return;
        }

        var fresh = new HashMap<String, Posterior>();
        for (var e : counts.entrySet()) {
            var c = e.getValue();
            // α = 1 (uniform prior) + FOLLOWED_UP
            // β = 1 (uniform prior) + DISMISSED + IGNORED
            fresh.put(e.getKey(), new Posterior(1 + c[0], 1 + c[1] + c[2]));
        }
        posteriors.clear();
        posteriors.putAll(fresh);
        log.info("Calibrator loaded {} categories from {} bytes", fresh.size(), size);
    }

    /** Posterior for a category — defaults to uninformed Beta(1,1) if unknown. */
    public synchronized Posterior posteriorFor(String category) {
        return posteriors.getOrDefault(category, new Posterior(1, 1));
    }

    /**
     * Calibrated reject threshold for a prediction category. Falls back to
     * {@link #DEFAULT_THRESHOLD} when no observations exist.
     *
     * <p>High follow-up rate → loosen (lower threshold = fire more).
     * Low follow-up rate → tighten (higher threshold = fire less).
     */
    public double thresholdFor(String category) {
        var p = posteriorFor(category);
        var n = p.observations();
        if (n <= 0) return DEFAULT_THRESHOLD;

        double mean = p.mean();
        // Map mean ∈ [0.2, 0.6] → shift ∈ [+MAX_SHIFT, −MAX_SHIFT] linearly.
        double shift;
        if (mean >= 0.6) shift = -MAX_SHIFT;
        else if (mean <= 0.2) shift = +MAX_SHIFT;
        else shift = MAX_SHIFT - 2 * MAX_SHIFT * ((mean - 0.2) / 0.4);
        // Ramp by effective sample size up to MIN_OBSERVATIONS.
        double weight = Math.min(1.0, (double) n / MIN_OBSERVATIONS);
        return clamp(DEFAULT_THRESHOLD + weight * shift,
            DEFAULT_THRESHOLD - MAX_SHIFT,
            DEFAULT_THRESHOLD + MAX_SHIFT);
    }

    public synchronized Map<String, Posterior> snapshot() {
        return Map.copyOf(posteriors);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Minimal record used during ledger scan. */
    private record Row(String category, String kind) {}

    /** Pull just `category` and `kind` from a JSONL line. Tolerant of field order. */
    private static Row parse(String line) {
        try {
            var node = Json.mapper().readTree(line);
            var category = node.path("category").asText("");
            var kind = node.path("kind").asText("");
            if (category.isEmpty() || kind.isEmpty()) return null;
            return new Row(category, kind);
        } catch (Exception e) {
            return null;
        }
    }
}
