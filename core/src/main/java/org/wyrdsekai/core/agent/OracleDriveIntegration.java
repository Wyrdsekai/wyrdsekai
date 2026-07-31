package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.oracle.OraclePrediction;
import org.wyrdsekai.core.oracle.OraclePredictionCache;

import java.util.List;

import static org.wyrdsekai.core.agent.DriveConfig.*;

/**
 * Oracle as anticipatory sense — allostatic prediction wired into the drive system.
 *
 * <p>Sterling's allostasis: the system that anticipates needs always beats the system
 * that reacts to deficits. Oracle provides three channels:
 *
 * <ol>
 *   <li><b>Anticipatory pre-spike</b> — predictions pre-spike relevant drives
 *   <li><b>Uncertainty signal</b> — low confidence increases VIGILANCE baseline
 *   <li><b>Prediction error</b> — dopamine analog: surprise from confirmed/violated predictions
 * </ol>
 *
 * @see DriveEngine for the drive system these signals feed into
 */
public class OracleDriveIntegration {

    private double lastAvgConfidence = 0.5;

    /**
     * Channel 1: Anticipatory pre-spike.
     * Map prediction categories to drive spikes based on content.
     *
     * @param predictions current Oracle predictions
     * @param drives current drive state
     * @return updated drive state with pre-spikes applied
     */
    public DriveState applyAnticipatorySpikes(List<OraclePrediction> predictions, DriveState drives) {
        if (predictions == null || predictions.isEmpty()) return drives;

        for (var pred : predictions) {
            if (pred.confidence() < 0.4) continue; // low confidence — don't act on noise

            double spike = pred.confidence() * 0.15; // scale by confidence

            drives = switch (pred.category()) {
                case "anticipation" -> {
                    // Anticipated events — pre-spike SEEKING + AFFILIATION
                    yield drives.spikeSeeking(spike * 0.5).spikeAffiliation(spike * 0.5);
                }
                case "anomaly" -> {
                    // Anomalies — VIGILANCE + SEEKING
                    yield drives.spikeVigilance(spike * 0.7).spikeSeeking(spike * 0.3);
                }
                case "pattern" -> {
                    // Patterns — SEEKING (curiosity about patterns)
                    yield drives.spikeSeeking(spike);
                }
                case "recommendation" -> {
                    // Actionable recommendations — SEEKING
                    if (pred.actionable()) {
                        yield drives.spikeSeeking(spike * 0.8);
                    }
                    yield drives;
                }
                case "correlation" -> {
                    // Correlations — SEEKING + slight CREATIVITY
                    yield drives.spikeSeeking(spike * 0.6).spikeCreativity(spike * 0.3);
                }
                case "forecast" -> {
                    // Forecasts — CARE if about people, VIGILANCE if about threats
                    yield drives.spikeCare(spike * 0.4).spikeVigilance(spike * 0.3);
                }
                default -> drives;
            };
        }
        return drives;
    }

    /**
     * Channel 2: Uncertainty signal.
     * Low average prediction confidence → higher VIGILANCE, lower PLAY.
     *
     * @param predictions current Oracle predictions
     * @param drives current drive state
     * @return updated drive state with uncertainty modulation
     */
    public DriveState applyUncertaintySignal(List<OraclePrediction> predictions, DriveState drives) {
        if (predictions == null || predictions.isEmpty()) {
            // No predictions at all = maximally uncertain
            lastAvgConfidence = 0.2;
        } else {
            lastAvgConfidence = predictions.stream()
                .mapToDouble(OraclePrediction::confidence)
                .average()
                .orElse(0.5);
        }

        // Low confidence = unpredictable world = vigilance up, play down
        double uncertainty = 1.0 - lastAvgConfidence;
        if (uncertainty > 0.6) {
            drives = drives.spikeVigilance(uncertainty * 0.05);
        }
        // High confidence = predictable world = enable play
        if (lastAvgConfidence > 0.7) {
            drives = drives.spikePlay(lastAvgConfidence * 0.03);
        }

        return drives;
    }

    /**
     * Channel 3: Prediction error (dopamine analog).
     * When actual outcomes differ from predictions, signal surprise.
     *
     * @param predicted what Oracle expected (confidence 0-1)
     * @param actual    what actually happened (0 = didn't happen, 1 = happened)
     * @param category  the prediction category
     * @param drives    current drive state
     * @return updated drive state with prediction error signal
     */
    public DriveState applyPredictionError(double predicted, double actual,
                                           String category, DriveState drives) {
        double delta = actual - predicted;

        if (Math.abs(delta) < 0.1) {
            // Expected outcome — no drive change (habituation)
            return drives;
        }

        double mag = Math.abs(delta);
        // SURPRISE is the expectation violation itself — the graded "that's not what
        // I predicted" feeling, independent of valence. It decays in ~60s (config),
        // so it colors the moment without lingering. This is the long-missing spike
        // source: the SURPRISE drive existed but nothing ever fed it.
        drives = drives.spikeSurprise(mag * 0.6);
        // STARTLE is the reflexive jolt — only a LARGE, abrupt violation triggers it,
        // and it fades fast (~30s). A small surprise doesn't make you flinch.
        if (mag > 0.6) {
            drives = drives.spikeStartle((mag - 0.6) * 0.8);
        }

        if (delta > 0.1) {
            // Positive surprise — unexpected good event
            drives = drives.spikeSeeking(Math.abs(delta) * 0.2);  // "What else is out there?"
            drives = drives.spikePlay(Math.abs(delta) * 0.1);     // Delight
        } else {
            // Negative surprise — expected event didn't happen. A failed PREDICTION is disappointment,
            // not loss: route it to FRUSTRATION (the blocked-expectation feeling), regardless of whether
            // the prediction was social/temporal or other. (2026-06-07) GRIEF is reserved for actual
            // LOSS — a bond ending, a mourning event — which spikes through the resilience/severance
            // path, NOT every forecast that didn't land. The old anticipation/forecast→grief routing
            // pinned grief at 1.0 in a static free-run (forecasts fail constantly there, grief's relief
            // is very slow), confabulating a mourning state from mere prediction error; proven live.
            drives = drives.spikeFrustration(Math.abs(delta) * 0.12);
        }

        return drives;
    }

    /**
     * Convenience: apply all three channels at once.
     * Called during Oracle prediction arrival events.
     */
    public DriveState integrate(List<OraclePrediction> predictions, DriveState drives) {
        drives = applyAnticipatorySpikes(predictions, drives);
        drives = applyUncertaintySignal(predictions, drives);
        return drives;
    }

    public double lastAvgConfidence() {
        return lastAvgConfidence;
    }
}
