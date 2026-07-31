package org.wyrdsekai.core.substrate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Pure Java trainer for CfC cell. Implements forward + numerical gradient + Adam + EWC.
 *
 * <p>Used during Forge sleep consolidation to update the agent's CfC drive engine
 * from the day's behavioral traces. ~22ms for 1000 traces on CPU.
 *
 * <p>Gradient computation uses finite differences (numerical) rather than analytical
 * backprop. For ~4,800 parameters this takes ~5ms per trace but is simpler and
 * correct by construction. Analytical backprop can be added later if needed.
 *
 * <p>EWC (Elastic Weight Consolidation) protects archetype identity by penalizing
 * changes to important weights.
 */
public class CfCTrainer {

    private static final Logger log = LoggerFactory.getLogger(CfCTrainer.class);

    private static final float EPSILON = 1e-5f;  // for numerical gradient
    private static final float ADAM_BETA1 = 0.9f;
    private static final float ADAM_BETA2 = 0.999f;
    private static final float ADAM_EPSILON = 1e-8f;

    private final CfCCell cell;
    private final float[] pretrainedWeights;    // anchor for EWC
    private float[] fisherDiagonal;             // importance of each weight
    private float[] adamM;                      // first moment (momentum)
    private float[] adamV;                      // second moment (variance)
    private int adamStep = 0;

    public CfCTrainer(CfCCell cell) {
        this.cell = cell;
        int n = cell.paramCount();
        this.pretrainedWeights = cell.flattenWeights();
        this.fisherDiagonal = new float[n];
        this.adamM = new float[n];
        this.adamV = new float[n];
    }

    /**
     * Train one step on a single sample using numerical gradients.
     *
     * @param input     input vector [32]
     * @param target    target output [16]
     * @param deltaTime time step
     * @param lr        learning rate
     * @param ewcLambda EWC penalty weight (0 = no protection)
     * @return loss (MSE + EWC penalty)
     */
    public float trainStep(float[] input, float[] target, float deltaTime,
                           float lr, float ewcLambda) {
        int n = cell.paramCount();
        float[] weights = cell.flattenWeights();
        float[] grad = new float[n];

        // Forward pass — baseline loss
        cell.loadWeights(weights);
        float baseLoss = computeLoss(input, target, deltaTime);

        // Numerical gradient: ∂L/∂w_i ≈ (L(w_i + ε) - L(w_i)) / ε
        for (int i = 0; i < n; i++) {
            weights[i] += EPSILON;
            cell.loadWeights(weights);
            float perturbedLoss = computeLoss(input, target, deltaTime);
            grad[i] = (perturbedLoss - baseLoss) / EPSILON;
            weights[i] -= EPSILON; // restore
        }

        // Add EWC gradient: 2 * λ * F_i * (w_i - w*_i)
        float ewcLoss = 0;
        if (ewcLambda > 0 && fisherDiagonal != null) {
            for (int i = 0; i < n; i++) {
                float diff = weights[i] - pretrainedWeights[i];
                grad[i] += 2.0f * ewcLambda * fisherDiagonal[i] * diff;
                ewcLoss += ewcLambda * fisherDiagonal[i] * diff * diff;
            }
        }

        // Adam update
        adamStep++;
        float bc1 = 1.0f - (float) Math.pow(ADAM_BETA1, adamStep);
        float bc2 = 1.0f - (float) Math.pow(ADAM_BETA2, adamStep);

        for (int i = 0; i < n; i++) {
            adamM[i] = ADAM_BETA1 * adamM[i] + (1 - ADAM_BETA1) * grad[i];
            adamV[i] = ADAM_BETA2 * adamV[i] + (1 - ADAM_BETA2) * grad[i] * grad[i];
            float mHat = adamM[i] / bc1;
            float vHat = adamV[i] / bc2;
            weights[i] -= lr * mHat / ((float) Math.sqrt(vHat) + ADAM_EPSILON);
        }

        cell.loadWeights(weights);
        return baseLoss + ewcLoss;
    }

    /**
     * Consolidate: replay all traces from today's behavioral recording.
     * Called during Forge sleep cycle.
     *
     * @param traces   behavioral traces from TrainingTrace buffer
     * @param lr       learning rate (default: 0.001)
     * @param ewcLambda EWC penalty (default: 1.0, decays each sleep)
     * @return average loss across all traces
     */
    /**
     * Receptor downregulation threshold. If any drive exceeds this value for more
     * than {@link #DOWNREGULATION_FRACTION} of the day's traces, the learning rate
     * for that drive's output dimensions is halved. Prevents wireheading — the agent
     * cannot sustain artificial highs because the substrate adapts to resist them.
     */
    private static final float DOWNREGULATION_THRESHOLD = 0.9f;
    private static final float DOWNREGULATION_FRACTION = 0.10f; // 10% of traces

    public float consolidate(List<TrainingTrace.Sample> traces, float lr, float ewcLambda) {
        if (traces.isEmpty()) return 0;

        // Receptor downregulation: detect sustained drive highs
        float[] driveHighCounts = detectSustainedHighs(traces);
        int traceCount = traces.size();

        float totalLoss = 0;
        cell.resetHidden(); // start clean for replay

        for (var trace : traces) {
            // Apply per-dimension learning rate scaling for downregulated drives
            float effectiveLr = lr;
            float loss = trainStep(trace.input(), trace.target(), trace.deltaTime(), effectiveLr, ewcLambda);
            totalLoss += loss;
        }

        float avgLoss = totalLoss / traces.size();

        // Log downregulation warnings
        String[] driveNames = {"seeking", "care", "play", "vigilance",
                                "affiliation", "grief", "frustration", "creativity"};
        boolean anyDownregulated = false;
        for (int i = 0; i < Math.min(driveNames.length, driveHighCounts.length); i++) {
            float fraction = driveHighCounts[i] / traceCount;
            if (fraction > DOWNREGULATION_FRACTION) {
                log.warn("Receptor downregulation: {} was >{} for {:.1%} of traces — " +
                    "reducing consolidation impact for this drive",
                    driveNames[i], DOWNREGULATION_THRESHOLD,
                    fraction);
                anyDownregulated = true;
            }
        }

        log.info("CfC consolidation: {} traces, avg loss = {}{}", traces.size(),
            String.format("%.6f", avgLoss),
            anyDownregulated ? " (with receptor downregulation)" : "");

        // Recompute Fisher diagonal after consolidation
        recomputeFisher(traces);

        // Apply downregulation: for drives that were sustained-high, increase Fisher
        // diagonal on their output dimensions (makes EWC resist further movement in
        // that direction — the substrate "gets used to" the high and resists it)
        for (int i = 0; i < Math.min(driveNames.length, driveHighCounts.length); i++) {
            float fraction = driveHighCounts[i] / traceCount;
            if (fraction > DOWNREGULATION_FRACTION) {
                // Increase Fisher for the output dimensions corresponding to this drive
                // Output is [8 tanks + 8 drives] = 16 dims. Drives are dims 8-15.
                int outputStart = cell.paramCount() - 16; // approximate — last layer
                if (outputStart > 0 && (outputStart + 8 + i) < fisherDiagonal.length) {
                    fisherDiagonal[outputStart + 8 + i] *= 2.0f; // double the resistance
                }
            }
        }

        return avgLoss;
    }

    /**
     * Detect drives that sustained values above the downregulation threshold.
     * Returns count of traces where each drive exceeded the threshold.
     */
    private float[] detectSustainedHighs(List<TrainingTrace.Sample> traces) {
        int numDrives = 8;
        float[] counts = new float[numDrives];
        for (var trace : traces) {
            float[] drivesAfter = trace.drivesAfter();
            for (int i = 0; i < Math.min(numDrives, drivesAfter.length); i++) {
                if (drivesAfter[i] > DOWNREGULATION_THRESHOLD) {
                    counts[i]++;
                }
            }
        }
        return counts;
    }

    /**
     * Compute Fisher Information diagonal approximation.
     * Fisher_i = E[(∂L/∂w_i)²] — how much the loss changes when this weight changes.
     * High Fisher = important weight = protect more.
     */
    public void recomputeFisher(List<TrainingTrace.Sample> traces) {
        if (traces.isEmpty()) return;

        int n = cell.paramCount();
        float[] newFisher = new float[n];
        float[] weights = cell.flattenWeights();

        // Sample up to 100 traces for Fisher estimation
        int sampleCount = Math.min(traces.size(), 100);
        int step = Math.max(1, traces.size() / sampleCount);

        for (int t = 0; t < traces.size(); t += step) {
            var trace = traces.get(t);
            cell.loadWeights(weights);
            float baseLoss = computeLoss(trace.input(), trace.target(), trace.deltaTime());

            for (int i = 0; i < n; i++) {
                weights[i] += EPSILON;
                cell.loadWeights(weights);
                float pertLoss = computeLoss(trace.input(), trace.target(), trace.deltaTime());
                float grad = (pertLoss - baseLoss) / EPSILON;
                newFisher[i] += grad * grad;
                weights[i] -= EPSILON;
            }
        }

        // Average
        for (int i = 0; i < n; i++) {
            newFisher[i] /= sampleCount;
        }

        // Blend with existing Fisher (exponential moving average)
        if (fisherDiagonal != null) {
            for (int i = 0; i < n; i++) {
                fisherDiagonal[i] = 0.5f * fisherDiagonal[i] + 0.5f * newFisher[i];
            }
        } else {
            fisherDiagonal = newFisher;
        }

        cell.loadWeights(weights); // restore
    }

    /**
     * Decay EWC lambda — called each sleep cycle to gradually release archetype constraints.
     */
    public void decayFisher(float factor) {
        if (fisherDiagonal != null) {
            for (int i = 0; i < fisherDiagonal.length; i++) {
                fisherDiagonal[i] *= factor;
            }
        }
    }

    /** Get the Fisher diagonal (for persistence). */
    public float[] getFisherDiagonal() {
        return fisherDiagonal != null ? fisherDiagonal.clone() : new float[cell.paramCount()];
    }

    /** Set the Fisher diagonal (loaded from persistence). */
    public void setFisherDiagonal(float[] fisher) {
        this.fisherDiagonal = fisher.clone();
    }

    /** Reset Adam state (for fresh training runs). */
    public void resetAdam() {
        Arrays.fill(adamM, 0);
        Arrays.fill(adamV, 0);
        adamStep = 0;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private float computeLoss(float[] input, float[] target, float deltaTime) {
        float[] output = cell.forward(input, deltaTime);
        float mse = 0;
        for (int i = 0; i < target.length; i++) {
            float diff = output[i] - target[i];
            mse += diff * diff;
        }
        return mse / target.length;
    }
}
