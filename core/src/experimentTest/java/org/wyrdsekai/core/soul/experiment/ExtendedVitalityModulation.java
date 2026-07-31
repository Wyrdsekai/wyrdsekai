package org.wyrdsekai.core.soul.experiment;

/**
 * Experiment 6 Part E: Extended vitality modulation with LoRA alpha scaling.
 *
 * Maps 8 vitality tanks to the full generation parameter surface:
 *
 * | Parameter        | Tank Input                  | Effect                                  |
 * |------------------|-----------------------------|------------------------------------------|
 * | loraAlpha        | composite                   | Personality strength: 0.3→1.0            |
 * | temperature      | confidence, errorPressure   | Uncertain=creative, stressed=conservative |
 * | maxTokens        | energy                      | Low energy = shorter                     |
 * | repeatPenalty    | focus                       | Distracted = more repetitive             |
 * | presencePenalty  | focus, momentum             | Distracted = topic drift                 |
 * | frequencyPenalty | energy                      | Tired = simpler vocabulary               |
 * | topK             | confidence                  | Confident = narrower sampling            |
 * | minP             | confidence                  | Confident = higher minimum probability   |
 *
 * LoRA alpha formula:
 *   soulStrength = 0.30*confidence + 0.25*energy + 0.20*focus
 *                + 0.15*(1-errorPressure) + 0.10*momentum
 *   loraAlpha = 0.3 + 0.7 * soulStrength    // range [0.3, 1.0]
 *
 * When exhausted/stressed → personality fades (lower LoRA influence).
 * When confident/energetic → personality shines (full LoRA strength).
 */
public final class ExtendedVitalityModulation {

    /** Tank indices matching VitalityState ordering. */
    static final int CTX_BUDGET = 0, CONFIDENCE = 1, ENERGY = 2, ALIGNMENT = 3,
                     ERR_PRESSURE = 4, MOMENTUM = 5, RAPPORT = 6, FOCUS = 7;

    private final double substrateFactor;

    /**
     * @param substrateFactor Amplification for larger models (1.0 for small models,
     *                        higher for larger ones where parameter ranges need widening).
     *                        Default: clamp(log2(modelParamsBillions) + 1.0, 0.5, 6.0)
     */
    public ExtendedVitalityModulation(double substrateFactor) {
        this.substrateFactor = substrateFactor;
    }

    /** Create with substrate factor derived from model size. */
    public static ExtendedVitalityModulation forModelSize(double paramsBillions) {
        double factor = Math.max(0.5, Math.min(6.0,
            Math.log(paramsBillions) / Math.log(2) + 1.0));
        return new ExtendedVitalityModulation(factor);
    }

    /**
     * Compute LoRA alpha from vitality state.
     *
     * @param tanks [8] vitality values in [0,1]
     * @return LoRA alpha in [0.3, 1.0]
     */
    public double computeLoraAlpha(double[] tanks) {
        double soulStrength =
            0.30 * tanks[CONFIDENCE] +
            0.25 * tanks[ENERGY] +
            0.20 * tanks[FOCUS] +
            0.15 * (1.0 - tanks[ERR_PRESSURE]) +
            0.10 * tanks[MOMENTUM];

        return 0.3 + 0.7 * clamp(soulStrength, 0.0, 1.0);
    }

    /**
     * Compute full extended generation parameters from vitality state.
     *
     * @param tanks [8] vitality values in [0,1]
     * @return Extended generation parameters
     */
    public InferenceHelper.GenerationParams compute(double[] tanks) {
        if (tanks.length != 8) throw new IllegalArgumentException("Expected 8 tanks, got " + tanks.length);

        double conf = tanks[CONFIDENCE];
        double energy = tanks[ENERGY];
        double errP = tanks[ERR_PRESSURE];
        double focus = tanks[FOCUS];
        double momentum = tanks[MOMENTUM];

        // Temperature: uncertain/creative when low confidence, conservative when stressed
        // Base: 0.7, range: [0.3, 1.2] scaled by substrate factor
        double tempBase = 0.7;
        double tempDelta = (0.5 - conf) * 0.3 + errP * 0.2;
        double temperature = clamp(tempBase + tempDelta * substrateFactor, 0.1, 2.0);

        // MaxTokens: lower energy = shorter responses
        // Base: 512, range: [64, 512]
        int maxTokens = (int) clamp(64 + 448 * energy, 64, 512);

        // RepeatPenalty: distracted (low focus) = more repetitive = lower penalty needed? No —
        // higher penalty PREVENTS repetition. Distracted should ALLOW some repetition.
        // Base: 1.1, range: [1.0, 1.4]
        double repeatPenalty = clamp(1.0 + 0.4 * focus * substrateFactor * 0.3, 1.0, 1.5);

        // PresencePenalty: distracted = topic drift (higher presence penalty encourages new topics)
        // Base: 0.0, range: [-0.2, 0.6]
        double presencePenalty = clamp(0.3 * (1.0 - focus) + 0.2 * momentum - 0.1, -0.5, 1.0);

        // FrequencyPenalty: tired = simpler vocabulary (lower frequency penalty)
        // Base: 0.0, range: [0.0, 0.4]
        double frequencyPenalty = clamp(0.4 * (1.0 - energy) * substrateFactor * 0.3, 0.0, 1.0);

        // TopK: confident = narrower sampling (more deterministic)
        // 0 = disabled, high confidence → topK = 20, low confidence → topK = 0 (disabled)
        int topK = conf > 0.6 ? (int) (10 + 30 * (conf - 0.6) / 0.4) : 0;

        // MinP: confident = higher minimum probability (less random)
        // 0.0 = disabled, range: [0.0, 0.1]
        double minP = conf > 0.5 ? 0.05 + 0.05 * (conf - 0.5) / 0.5 : 0.0;

        // TopP: not modulated (keep at 1.0 to avoid interaction with topK)
        double topP = 1.0;

        return new InferenceHelper.GenerationParams(
            maxTokens, temperature, repeatPenalty, presencePenalty,
            frequencyPenalty, topK, minP, topP);
    }

    /**
     * Compute full modulation result including LoRA alpha and generation params.
     */
    public ModulationResult computeFull(double[] tanks) {
        return new ModulationResult(computeLoraAlpha(tanks), compute(tanks));
    }

    /** Full modulation output. */
    public record ModulationResult(
        double loraAlpha,
        InferenceHelper.GenerationParams params
    ) {
        public String summary() {
            return String.format(
                "loraAlpha=%.2f temp=%.2f maxTok=%d repPen=%.2f presPen=%.2f freqPen=%.2f topK=%d minP=%.3f",
                loraAlpha, params.temperature(), params.maxTokens(),
                params.repeatPenalty(), params.presencePenalty(),
                params.frequencyPenalty(), params.topK(), params.minP());
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
