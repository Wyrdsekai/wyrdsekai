package org.wyrdsekai.core.soul.experiment;

import java.util.random.RandomGenerator;

/**
 * Closed-Form Continuous-depth (CfC) cell.
 *
 * Implements the closed-form solution to a continuous-time neural ODE:
 * <pre>
 *   z(t+dt) = z(t) · exp(-α · dt) + β · (1 - exp(-α · dt))
 *   h(t+1)  = γ · z(t+1)
 * </pre>
 *
 * Where:
 *   α = softplus(head_f(backbone(x,h)))  — time constant (always > 0)
 *   β = tanh(head_g(backbone(x,h)))      — proposed state [-1,1]
 *   γ = sigmoid(head_h(backbone(x,h)))   — output gate [0,1]
 *
 * The backbone is a 2-layer MLP operating on [x(t) ; h(t-1)].
 *
 * Reference: Hasani et al. "Closed-Form Continuous-Depth Models"
 *            Nature Machine Intelligence 4, 2022.
 */
final class CfCCell {

    private final int inputSize;
    private final int stateSize;

    // Backbone: [input + state] -> hidden -> hidden
    private final DenseLayer backbone1;
    private final DenseLayer backbone2;

    // Three heads (each produces stateSize outputs)
    private final DenseLayer alphaHead;  // SOFTPLUS: time constant (>0)
    private final DenseLayer betaHead;   // TANH: proposal state [-1,1]
    private final DenseLayer gammaHead;  // SIGMOID: output gate [0,1]

    // Internal state
    private double[] z;  // continuous-time state [stateSize]
    private double[] h;  // output state [stateSize]

    // Cached for backprop
    private double[] lastAlpha, lastBeta, lastGamma;
    private double[] lastExpDecay;
    private double[] lastZPrev;

    CfCCell(int inputSize, int stateSize, int backboneHidden, RandomGenerator rng) {
        this.inputSize = inputSize;
        this.stateSize = stateSize;

        int combinedSize = inputSize + stateSize;
        this.backbone1 = new DenseLayer(combinedSize, backboneHidden,
            DenseLayer.Activation.LECUN_TANH, rng);
        this.backbone2 = new DenseLayer(backboneHidden, backboneHidden,
            DenseLayer.Activation.LECUN_TANH, rng);

        this.alphaHead = new DenseLayer(backboneHidden, stateSize,
            DenseLayer.Activation.SOFTPLUS, rng);
        this.betaHead = new DenseLayer(backboneHidden, stateSize,
            DenseLayer.Activation.TANH, rng);
        this.gammaHead = new DenseLayer(backboneHidden, stateSize,
            DenseLayer.Activation.SIGMOID, rng);

        reset();
    }

    /** Reset state to zeros. */
    void reset() {
        z = new double[stateSize];
        h = new double[stateSize];
    }

    /**
     * Forward step.
     *
     * @param input   External input [inputSize]
     * @param deltaT  Time step (seconds). 1.0 for discrete scenarios.
     * @return Output state h [stateSize]
     */
    double[] forward(double[] input, double deltaT) {
        // Concatenate input + h_prev
        var combined = new double[inputSize + stateSize];
        System.arraycopy(input, 0, combined, 0, inputSize);
        System.arraycopy(h, 0, combined, inputSize, stateSize);

        // Backbone
        var bb1 = backbone1.forward(combined);
        var bb2 = backbone2.forward(bb1);

        // Three heads
        lastAlpha = alphaHead.forward(bb2);
        lastBeta = betaHead.forward(bb2);
        lastGamma = gammaHead.forward(bb2);

        // CfC state update
        lastZPrev = z.clone();
        lastExpDecay = new double[stateSize];
        var zNew = new double[stateSize];
        var hNew = new double[stateSize];

        for (int j = 0; j < stateSize; j++) {
            lastExpDecay[j] = Math.exp(-lastAlpha[j] * deltaT);
            zNew[j] = z[j] * lastExpDecay[j] + lastBeta[j] * (1.0 - lastExpDecay[j]);
            hNew[j] = lastGamma[j] * zNew[j];
        }

        z = zNew;
        h = hNew;
        return h.clone();
    }

    /**
     * Backward through CfC dynamics + backbone.
     *
     * @param dOutput      dLoss/dh [stateSize]
     * @param deltaT       Same deltaT used in forward
     * @param learningRate SGD learning rate
     * @return dLoss/dInput [inputSize] for upstream chain rule
     */
    double[] backward(double[] dOutput, double deltaT, double learningRate) {
        // dh/dGamma = z_new, dh/dZ_new = gamma
        var dGamma = new double[stateSize];
        var dZ = new double[stateSize];
        for (int j = 0; j < stateSize; j++) {
            dGamma[j] = dOutput[j] * z[j];     // z is z_new after forward
            dZ[j] = dOutput[j] * lastGamma[j];
        }

        // dZ/dAlpha = dt * (beta - z_old) * exp(-alpha * dt)
        // dZ/dBeta  = 1 - exp(-alpha * dt)
        var dAlpha = new double[stateSize];
        var dBeta = new double[stateSize];
        for (int j = 0; j < stateSize; j++) {
            dAlpha[j] = dZ[j] * deltaT * (lastBeta[j] - lastZPrev[j]) * lastExpDecay[j];
            dBeta[j] = dZ[j] * (1.0 - lastExpDecay[j]);
        }

        // Backprop through heads
        var dBb2FromAlpha = alphaHead.backward(dAlpha, learningRate);
        var dBb2FromBeta = betaHead.backward(dBeta, learningRate);
        var dBb2FromGamma = gammaHead.backward(dGamma, learningRate);

        // Sum gradients from all three heads
        var dBb2 = new double[dBb2FromAlpha.length];
        for (int i = 0; i < dBb2.length; i++) {
            dBb2[i] = dBb2FromAlpha[i] + dBb2FromBeta[i] + dBb2FromGamma[i];
        }

        // Backprop through backbone
        var dBb1 = backbone2.backward(dBb2, learningRate);
        var dCombined = backbone1.backward(dBb1, learningRate);

        // Extract dInput (first inputSize elements of dCombined)
        var dInput = new double[inputSize];
        System.arraycopy(dCombined, 0, dInput, 0, inputSize);
        return dInput;
    }

    int stateSize() { return stateSize; }
    int inputSize() { return inputSize; }

    int paramCount() {
        return backbone1.paramCount() + backbone2.paramCount()
            + alphaHead.paramCount() + betaHead.paramCount() + gammaHead.paramCount();
    }

    /** Last computed alpha values — needed by BathDynamics for modulation. */
    double[] lastAlpha() { return lastAlpha; }

    /** Current output state. */
    double[] output() { return h.clone(); }
}
