package org.wyrdsekai.core.soul.experiment;

import java.util.random.RandomGenerator;

/**
 * Volumetric neuromodulation for CfC cells.
 *
 * Models 8 neuromodulatory channels (one per vitality tank).
 * Each neuron has receptor sensitivities controlling how each channel
 * affects its time constant (α) and output gain.
 *
 * Pharmacological model — Hill equation for receptor occupancy:
 * <pre>
 *   occupancy = [L]^n / (Kd^n + [L]^n)
 * </pre>
 * where [L] = ligand concentration (tank value 0-1), Kd = dissociation constant, n = Hill coefficient.
 *
 * Neurotransmitter mapping (from Mei et al. 2025, arXiv:2501.06762):
 *   contextBudget → Acetylcholine (attention)
 *   confidence    → Serotonin (certainty)
 *   energy        → Noradrenaline (arousal)
 *   alignment     → Oxytocin (bonding)
 *   errorPressure → Cortisol (stress)
 *   momentum      → Dopamine (drive)
 *   rapport       → Endorphins (warmth)
 *   focus         → GABA (inhibition)
 *
 * Reference: Empathy Engine White Paper (Petrarch Technologies, 2003)
 */
final class BathDynamics {

    static final int CHANNEL_COUNT = 8;

    // Per-neuron receptor sensitivities: how each channel affects this neuron
    private final double[][] receptorTime;   // [neuronCount][8] — modulates time constant
    private final double[][] receptorGain;   // [neuronCount][8] — modulates output gain

    // Hill equation parameters (shared across all neurons)
    private final double hillCoefficient;    // n, default 2.0
    private final double[] kd;               // [8] dissociation constants per channel

    private final int neuronCount;

    // Cached for backprop
    private double[] lastConcentrations;
    private double[][] lastOccupancy;
    private double[] lastTimeFactors;
    private double[] lastGainFactors;

    BathDynamics(int neuronCount, RandomGenerator rng) {
        this(neuronCount, 2.0, rng);
    }

    BathDynamics(int neuronCount, double hillCoefficient, RandomGenerator rng) {
        this.neuronCount = neuronCount;
        this.hillCoefficient = hillCoefficient;
        this.receptorTime = new double[neuronCount][CHANNEL_COUNT];
        this.receptorGain = new double[neuronCount][CHANNEL_COUNT];
        this.kd = new double[CHANNEL_COUNT];

        // Initialize: small random receptor sensitivities, Kd=0.5 for all channels
        for (int j = 0; j < neuronCount; j++) {
            for (int c = 0; c < CHANNEL_COUNT; c++) {
                receptorTime[j][c] = rng.nextGaussian() * 0.1;
                receptorGain[j][c] = rng.nextGaussian() * 0.1;
            }
        }
        for (int c = 0; c < CHANNEL_COUNT; c++) {
            kd[c] = 0.5;
        }
    }

    /**
     * Compute modulation factors from vitality tank values.
     *
     * @param tankValues [8] vitality values in [0,1], ordered per VitalityState:
     *                   contextBudget, confidence, energy, alignment,
     *                   errorPressure, momentum, rapport, focus
     * @return Per-neuron time and gain modulation factors
     */
    Modulation compute(double[] tankValues) {
        if (tankValues.length != CHANNEL_COUNT)
            throw new IllegalArgumentException("Expected 8 tank values, got " + tankValues.length);

        lastConcentrations = tankValues.clone();
        lastOccupancy = new double[neuronCount][CHANNEL_COUNT];
        lastTimeFactors = new double[neuronCount];
        lastGainFactors = new double[neuronCount];

        // Compute receptor occupancy per neuron per channel
        for (int j = 0; j < neuronCount; j++) {
            double timeMod = 1.0;
            double gainMod = 1.0;
            for (int c = 0; c < CHANNEL_COUNT; c++) {
                double occ = hill(tankValues[c], kd[c], hillCoefficient);
                lastOccupancy[j][c] = occ;
                timeMod += receptorTime[j][c] * occ;
                gainMod += receptorGain[j][c] * occ;
            }
            // Clamp: time factor must be positive, gain factor bounded
            lastTimeFactors[j] = Math.max(0.1, timeMod);
            lastGainFactors[j] = Math.max(0.1, Math.min(5.0, gainMod));
        }

        return new Modulation(lastTimeFactors.clone(), lastGainFactors.clone());
    }

    /**
     * Backward through bath dynamics.
     * Given dLoss/dAlphaEffective and dLoss/dGainEffective, update receptor sensitivities.
     */
    void backward(double[] dTimeFactor, double[] dGainFactor, double learningRate) {
        for (int j = 0; j < neuronCount; j++) {
            // Only update if not clamped
            if (lastTimeFactors[j] > 0.1) {
                for (int c = 0; c < CHANNEL_COUNT; c++) {
                    double dReceptorTime = dTimeFactor[j] * lastOccupancy[j][c];
                    receptorTime[j][c] -= learningRate * dReceptorTime;
                }
            }
            if (lastGainFactors[j] > 0.1 && lastGainFactors[j] < 5.0) {
                for (int c = 0; c < CHANNEL_COUNT; c++) {
                    double dReceptorGain = dGainFactor[j] * lastOccupancy[j][c];
                    receptorGain[j][c] -= learningRate * dReceptorGain;
                }
            }
        }
    }

    int neuronCount() { return neuronCount; }
    int paramCount() { return 2 * neuronCount * CHANNEL_COUNT; }

    /** Modulation result: per-neuron time constant and gain scaling factors. */
    record Modulation(double[] timeFactors, double[] gainFactors) {}

    // --- Hill equation ---

    /** Hill equation: receptor occupancy as function of ligand concentration. */
    static double hill(double concentration, double kd, double n) {
        if (concentration <= 0) return 0;
        double Ln = Math.pow(concentration, n);
        double Kn = Math.pow(kd, n);
        return Ln / (Kn + Ln);
    }

    /** Derivative of Hill equation with respect to concentration. */
    static double hillDerivative(double concentration, double kd, double n) {
        if (concentration <= 0) return 0;
        double Ln = Math.pow(concentration, n);
        double Kn = Math.pow(kd, n);
        double denom = Kn + Ln;
        return n * Math.pow(concentration, n - 1) * Kn / (denom * denom);
    }
}
