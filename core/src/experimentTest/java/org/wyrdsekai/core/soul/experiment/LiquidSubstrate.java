package org.wyrdsekai.core.soul.experiment;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Complete CfC + Bath substrate.
 *
 * Architecture:
 *   InputProjection (fixed random) → N CfC cells → BathDynamics gain → OutputProjection (fixed random)
 *
 * This is NOT an LLM replacement. It processes scenario embeddings conditioned on
 * bath (vitality) state and produces predicted response embeddings.
 *
 * Production use cases: fast behavioral pre-screening, consistency verification,
 * client-side personality preservation when LLM is unavailable.
 */
final class LiquidSubstrate {

    private final SubstrateConfig config;
    private final DenseLayer inputProjection;   // embeddingDim → hiddenDim (frozen)
    private final CfCCell[] cells;
    private final BathDynamics bath;
    private final DenseLayer outputProjection;  // hiddenDim → outputDim (frozen)

    LiquidSubstrate(SubstrateConfig config) {
        this.config = config;
        var rng = RandomGeneratorFactory.of("L64X128MixRandom");

        // Fixed random projections (Johnson-Lindenstrauss)
        this.inputProjection = new DenseLayer(config.inputDim(), config.hiddenDim(),
            DenseLayer.Activation.LECUN_TANH, true, rng.create(config.seed()));
        this.outputProjection = new DenseLayer(config.hiddenDim(), config.outputDim(),
            DenseLayer.Activation.IDENTITY, true, rng.create(config.seed() + 1));

        // CfC cells
        this.cells = new CfCCell[config.numCells()];
        for (int i = 0; i < config.numCells(); i++) {
            // Each cell's input: its partition of projected + adjacency inputs
            int adjCount = config.adjacency()[i].length;
            int cellInputSize = config.cellStateSize() + adjCount * config.cellStateSize();
            cells[i] = new CfCCell(cellInputSize, config.cellStateSize(),
                config.backboneHidden(), rng.create(config.seed() + 10 + i));
        }

        // Bath dynamics across all neurons
        this.bath = new BathDynamics(config.hiddenDim(), rng.create(config.seed() + 100));
    }

    /**
     * Forward pass: scenario embedding → predicted response embedding.
     *
     * @param inputEmbedding Scenario embedding [inputDim] (from all-minilm)
     * @param tankValues     Vitality state [8] in [0,1]
     * @param deltaT         Time step (1.0 for discrete)
     * @return Predicted response embedding [outputDim], L2-normalized
     */
    double[] process(double[] inputEmbedding, double[] tankValues, double deltaT) {
        // 1. Project input
        var projected = inputProjection.forward(inputEmbedding);

        // 2. Compute bath modulation
        var modulation = bath.compute(tankValues);

        // 3. Process through CfC cells
        var cellOutputs = new double[config.numCells()][];
        for (int i = 0; i < config.numCells(); i++) {
            // Gather cell input: own partition + adjacency outputs
            var cellInput = gatherCellInput(i, projected, cellOutputs);
            cellOutputs[i] = cells[i].forward(cellInput, deltaT);

            // Apply bath time modulation: scale the effective time constant
            // (modulates via the cell's internal alpha, applied as post-hoc gain on output)
            int offset = i * config.cellStateSize();
            for (int j = 0; j < config.cellStateSize(); j++) {
                cellOutputs[i][j] *= modulation.gainFactors()[offset + j];
            }
        }

        // 4. Concatenate cell outputs
        var hidden = new double[config.hiddenDim()];
        for (int i = 0; i < config.numCells(); i++) {
            System.arraycopy(cellOutputs[i], 0, hidden,
                i * config.cellStateSize(), config.cellStateSize());
        }

        // 5. Output projection
        var output = outputProjection.forward(hidden);

        // 6. L2 normalize
        return l2Normalize(output);
    }

    /**
     * Train one step: forward + loss + backward.
     *
     * @return Loss value (1 - cosine similarity)
     */
    double trainStep(double[] input, double[] target, double[] tankValues,
                     double deltaT, double learningRate) {
        var predicted = process(input, tankValues, deltaT);

        // Loss: 1 - cosine similarity
        double cosSim = BehavioralMetrics.cosineSimilarity(predicted, target);
        double loss = 1.0 - cosSim;

        // Gradient of cosine similarity w.r.t. predicted
        // d(cosSim)/d(predicted) = (target/||target|| - cosSim * predicted/||predicted||) / ||predicted||
        double predNorm = norm(predicted);
        double targetNorm = norm(target);
        if (predNorm < 1e-10 || targetNorm < 1e-10) return loss;

        var dPredicted = new double[predicted.length];
        for (int i = 0; i < predicted.length; i++) {
            dPredicted[i] = -(target[i] / targetNorm - cosSim * predicted[i] / predNorm) / predNorm;
        }

        // Backprop through output projection (frozen, passes through gradients)
        var dHidden = outputProjection.backward(dPredicted, learningRate);

        // Backprop through gain modulation + cells
        var modulation = bath.compute(tankValues);  // recompute (or cache)
        var dGainFactors = new double[config.hiddenDim()];
        var dCellOutputs = new double[config.numCells()][];

        // Split dHidden into per-cell gradients
        for (int i = 0; i < config.numCells(); i++) {
            dCellOutputs[i] = new double[config.cellStateSize()];
            int offset = i * config.cellStateSize();
            for (int j = 0; j < config.cellStateSize(); j++) {
                // hidden[j] = cellOutput[j] * gainFactor[j]
                // dHidden/dCellOutput = gainFactor
                // dHidden/dGainFactor = cellOutput (but cellOutput was already scaled... use pre-gain)
                dCellOutputs[i][j] = dHidden[offset + j] * modulation.gainFactors()[offset + j];
                dGainFactors[offset + j] = dHidden[offset + j]; // simplified: actual cellOutput needed
            }
        }

        // Bath backward
        var dTimeFactors = new double[config.hiddenDim()]; // not directly used in this simplified model
        bath.backward(dTimeFactors, dGainFactors, learningRate);

        // Cell backward (in reverse order for adjacency dependencies)
        for (int i = config.numCells() - 1; i >= 0; i--) {
            cells[i].backward(dCellOutputs[i], deltaT, learningRate);
        }

        // Input projection backward (frozen, no weight update, just for completeness)
        // Not needed since inputProjection is frozen

        return loss;
    }

    /** Reset all cell states. */
    void reset() {
        for (var cell : cells) cell.reset();
    }

    int paramCount() {
        int total = bath.paramCount();
        for (var cell : cells) total += cell.paramCount();
        return total; // projections are frozen, don't count
    }

    // --- Internal ---

    private double[] gatherCellInput(int cellIdx, double[] projected, double[][] cellOutputs) {
        int partitionSize = config.cellStateSize();
        int[] adj = config.adjacency()[cellIdx];
        int inputSize = partitionSize + adj.length * partitionSize;
        var input = new double[inputSize];

        // Own partition of projected input
        System.arraycopy(projected, cellIdx * partitionSize, input, 0, partitionSize);

        // Adjacency inputs (from other cells' outputs, or zeros if not yet computed)
        for (int a = 0; a < adj.length; a++) {
            int srcCell = adj[a];
            int destOffset = partitionSize + a * partitionSize;
            if (cellOutputs[srcCell] != null) {
                System.arraycopy(cellOutputs[srcCell], 0, input, destOffset, partitionSize);
            }
            // else zeros (default)
        }

        return input;
    }

    private static double[] l2Normalize(double[] v) {
        double n = norm(v);
        if (n < 1e-10) return v;
        var result = new double[v.length];
        for (int i = 0; i < v.length; i++) result[i] = v[i] / n;
        return result;
    }

    private static double norm(double[] v) {
        double sum = 0;
        for (double x : v) sum += x * x;
        return Math.sqrt(sum);
    }
}
