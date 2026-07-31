package org.wyrdsekai.core.soul.experiment;

import java.util.random.RandomGenerator;

/**
 * Simple dense (fully-connected) layer with analytical backpropagation.
 * output = activation(W * input + bias)
 *
 * Pure Java, no ML framework. Weights stored as flat double[] in row-major order.
 */
final class DenseLayer {

    enum Activation {
        IDENTITY, RELU, SIGMOID, TANH, SOFTPLUS, LECUN_TANH
    }

    private final int inputSize;
    private final int outputSize;
    private final Activation activation;
    private final boolean frozen;  // if true, backward() skips weight updates

    // Parameters
    final double[] weights;   // [outputSize * inputSize], row-major
    final double[] biases;    // [outputSize]

    // Cached forward pass state for backprop
    private double[] lastInput;
    private double[] lastPreAct;
    private double[] lastOutput;

    DenseLayer(int inputSize, int outputSize, Activation activation,
               boolean frozen, RandomGenerator rng) {
        this.inputSize = inputSize;
        this.outputSize = outputSize;
        this.activation = activation;
        this.frozen = frozen;
        this.weights = new double[outputSize * inputSize];
        this.biases = new double[outputSize];
        initWeights(rng);
    }

    DenseLayer(int inputSize, int outputSize, Activation activation, RandomGenerator rng) {
        this(inputSize, outputSize, activation, false, rng);
    }

    /** Forward pass: output = activation(W * input + bias). */
    double[] forward(double[] input) {
        if (input.length != inputSize)
            throw new IllegalArgumentException("Expected " + inputSize + " inputs, got " + input.length);

        lastInput = input;
        lastPreAct = new double[outputSize];
        lastOutput = new double[outputSize];

        for (int j = 0; j < outputSize; j++) {
            double sum = biases[j];
            int rowStart = j * inputSize;
            for (int i = 0; i < inputSize; i++) {
                sum += weights[rowStart + i] * input[i];
            }
            lastPreAct[j] = sum;
            lastOutput[j] = activate(sum, activation);
        }
        return lastOutput.clone();
    }

    /**
     * Backward pass: given dLoss/dOutput, update weights and return dLoss/dInput.
     */
    double[] backward(double[] dOutput, double learningRate) {
        if (dOutput.length != outputSize)
            throw new IllegalArgumentException("Expected " + outputSize + " gradients, got " + dOutput.length);

        var dPreAct = new double[outputSize];
        for (int j = 0; j < outputSize; j++) {
            dPreAct[j] = dOutput[j] * activateDerivative(lastPreAct[j], activation);
        }

        // dLoss/dInput for upstream chain rule
        var dInput = new double[inputSize];
        for (int i = 0; i < inputSize; i++) {
            double sum = 0;
            for (int j = 0; j < outputSize; j++) {
                sum += weights[j * inputSize + i] * dPreAct[j];
            }
            dInput[i] = sum;
        }

        // Update weights and biases (SGD)
        if (!frozen) {
            for (int j = 0; j < outputSize; j++) {
                int rowStart = j * inputSize;
                for (int i = 0; i < inputSize; i++) {
                    weights[rowStart + i] -= learningRate * dPreAct[j] * lastInput[i];
                }
                biases[j] -= learningRate * dPreAct[j];
            }
        }

        return dInput;
    }

    int inputSize() { return inputSize; }
    int outputSize() { return outputSize; }
    int paramCount() { return frozen ? 0 : weights.length + biases.length; }

    // --- Activation functions ---

    static double activate(double x, Activation act) {
        return switch (act) {
            case IDENTITY -> x;
            case RELU -> Math.max(0, x);
            case SIGMOID -> 1.0 / (1.0 + Math.exp(-x));
            case TANH -> Math.tanh(x);
            case SOFTPLUS -> Math.log(1.0 + Math.exp(x));
            case LECUN_TANH -> 1.7159 * Math.tanh(2.0 * x / 3.0);
        };
    }

    static double activateDerivative(double x, Activation act) {
        return switch (act) {
            case IDENTITY -> 1.0;
            case RELU -> x > 0 ? 1.0 : 0.0;
            case SIGMOID -> {
                double s = 1.0 / (1.0 + Math.exp(-x));
                yield s * (1.0 - s);
            }
            case TANH -> {
                double t = Math.tanh(x);
                yield 1.0 - t * t;
            }
            case SOFTPLUS -> 1.0 / (1.0 + Math.exp(-x)); // = sigmoid(x)
            case LECUN_TANH -> {
                double t = Math.tanh(2.0 * x / 3.0);
                yield 1.7159 * (2.0 / 3.0) * (1.0 - t * t);
            }
        };
    }

    // Xavier initialization
    private void initWeights(RandomGenerator rng) {
        double bound = Math.sqrt(6.0 / (inputSize + outputSize));
        for (int i = 0; i < weights.length; i++) {
            weights[i] = rng.nextDouble(-bound, bound);
        }
        // biases start at zero
    }
}
