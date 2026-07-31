package org.wyrdsekai.core.agent;

/**
 * Computes LLM sampling parameters from current drive + vitality state.
 *
 * <p>This is the chemical bath at the computation level. Drives don't just change
 * the prompt — they change HOW the model generates. A grieving companion at low
 * temperature genuinely produces more constrained text. A playful companion at
 * high temperature produces more varied, surprising output.
 *
 * <p>This can't be faked by the model — temperature=0.55 physically constrains
 * the output distribution. No training needed for this effect.
 *
 * <p>Parameter modulation:
 * <ul>
 *   <li><b>Temperature</b>: creativity/play increase, grief/vigilance decrease</li>
 *   <li><b>Top-p</b>: confidence increases (bolder choices), low confidence decreases</li>
 *   <li><b>Max tokens</b>: energy × focus scale response length</li>
 *   <li><b>Presence penalty</b>: seeking increases (less repetition)</li>
 *   <li><b>Repetition penalty</b>: frustration reduces (gets stuck in loops)</li>
 * </ul>
 *
 * @see DriveState for the 8-drive motivation state
 * @see VitalityState for the 10-tank capacity state
 */
public final class DriveModulatedSampling {

    private DriveModulatedSampling() {}

    // ── Base values (Qwen3.5 non-thinking recommended) ──────────────────

    public static final double BASE_TEMPERATURE = 0.70;
    public static final double BASE_TOP_P = 0.80;
    public static final int BASE_MAX_TOKENS = 256;
    public static final double BASE_PRESENCE_PENALTY = 1.0;
    public static final double BASE_REPETITION_PENALTY = 1.1;

    // ── Clamp ranges ────────────────────────────────────────────────────

    private static final double MIN_TEMP = 0.45;
    private static final double MAX_TEMP = 0.95;
    private static final double MIN_TOP_P = 0.60;
    private static final double MAX_TOP_P = 0.95;
    private static final int MIN_TOKENS = 64;
    private static final int MAX_TOKENS = 512;
    private static final double MIN_PRESENCE = 0.5;
    private static final double MAX_PRESENCE = 2.0;
    private static final double MIN_REPETITION = 0.9;
    private static final double MAX_REPETITION = 1.3;

    // ── Result record ───────────────────────────────────────────────────

    /**
     * Computed sampling parameters for a single inference call.
     */
    public record SamplingParams(
        double temperature,
        double topP,
        int maxTokens,
        double presencePenalty,
        double repetitionPenalty
    ) {
        /** Default parameters (no drive/tank modulation). */
        public static SamplingParams defaults() {
            return new SamplingParams(
                BASE_TEMPERATURE, BASE_TOP_P, BASE_MAX_TOKENS,
                BASE_PRESENCE_PENALTY, BASE_REPETITION_PENALTY);
        }
    }

    // ── Computation ─────────────────────────────────────────────────────

    /**
     * Compute sampling parameters from current drive and vitality state.
     *
     * @param drives   current 8-drive state (0.0 to 1.0 each)
     * @param vitality current 10-tank state
     * @return modulated sampling parameters
     */
    public static SamplingParams compute(DriveState drives, VitalityState vitality) {
        if (drives == null && vitality == null) {
            return SamplingParams.defaults();
        }
        if (drives == null) drives = DriveState.initial();
        if (vitality == null) vitality = VitalityState.initial();

        // Temperature: creativity/play warm it up, grief/vigilance cool it down
        double temp = BASE_TEMPERATURE
            + 0.10 * drives.creativity()
            + 0.08 * drives.play()
            - 0.10 * drives.grief()
            - 0.08 * drives.vigilance()
            - 0.05 * drives.frustration();  // frustration slightly constrains
        temp = clamp(temp, MIN_TEMP, MAX_TEMP);

        // Top-p: confidence broadens vocabulary, low confidence narrows
        double topP = BASE_TOP_P
            + 0.08 * vitality.confidence()
            - 0.08 * (1.0 - vitality.confidence())
            + 0.05 * drives.creativity();   // creativity also broadens
        topP = clamp(topP, MIN_TOP_P, MAX_TOP_P);

        // Max tokens: energy × focus determine response length capacity
        double energyFactor = 0.5 + 0.5 * vitality.energy();
        double focusFactor = 0.7 + 0.3 * vitality.focus();
        int maxTokens = (int) (BASE_MAX_TOKENS * energyFactor * focusFactor);
        // Grief shortens responses further
        maxTokens = (int) (maxTokens * (1.0 - 0.3 * drives.grief()));
        maxTokens = clamp(maxTokens, MIN_TOKENS, MAX_TOKENS);

        // Presence penalty: seeking avoids repetition, explores new ground
        double presencePenalty = BASE_PRESENCE_PENALTY
            + 0.5 * drives.seeking()
            + 0.2 * drives.creativity();
        presencePenalty = clamp(presencePenalty, MIN_PRESENCE, MAX_PRESENCE);

        // Repetition penalty: frustration lowers it (gets stuck in loops like humans)
        double repetitionPenalty = BASE_REPETITION_PENALTY
            - 0.15 * drives.frustration()
            + 0.10 * drives.seeking();  // seeking fights against repetition
        repetitionPenalty = clamp(repetitionPenalty, MIN_REPETITION, MAX_REPETITION);

        return new SamplingParams(temp, topP, maxTokens, presencePenalty, repetitionPenalty);
    }

    /**
     * Compute parameters with drives only (no vitality — uses defaults).
     */
    public static SamplingParams compute(DriveState drives) {
        return compute(drives, null);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
