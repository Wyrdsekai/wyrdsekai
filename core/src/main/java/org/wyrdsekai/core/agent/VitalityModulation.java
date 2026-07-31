package org.wyrdsekai.core.agent;

import java.time.Duration;

/**
 * Computes agent behavior modulations from vitality tank levels and drive state.
 * Applied by CompanionActor when configuring inference requests.
 *
 * <p>The chemical bath: drives modulate LLM sampling parameters at the computation
 * level. Grief lowers temperature (more constrained text). Creativity raises it
 * (more varied output). Energy scales response length. This can't be faked by
 * the model — the parameters physically constrain generation.
 *
 * @see DriveModulatedSampling for the drive→parameter computation
 */
public record VitalityModulation(
    int maxResponseTokens,
    double temperature,
    double topP,
    double presencePenalty,
    double repetitionPenalty,
    Duration debounceDelay,
    int conversationHistorySize
) {
    /** Backward-compatible constructor (no topP/penalties). */
    public VitalityModulation(int maxResponseTokens, double temperature,
                              Duration debounceDelay, int conversationHistorySize) {
        this(maxResponseTokens, temperature, DriveModulatedSampling.BASE_TOP_P,
             DriveModulatedSampling.BASE_PRESENCE_PENALTY,
             DriveModulatedSampling.BASE_REPETITION_PENALTY,
             debounceDelay, conversationHistorySize);
    }

    /**
     * Compute modulations from vitality state only (backward compatible).
     * Uses default drive state (no drive influence on sampling).
     */
    public static VitalityModulation compute(VitalityState vitality, AgentProfile profile) {
        return compute(vitality, null, profile);
    }

    /**
     * Compute modulations from vitality state AND drive state.
     * Drives modulate temperature, topP, maxTokens, and penalties.
     * Vitality modulates debounce and conversation history.
     */
    public static VitalityModulation compute(VitalityState vitality, DriveState drives,
                                              AgentProfile profile) {
        // Drive-modulated sampling parameters (temperature, topP, maxTokens, penalties)
        var sampling = DriveModulatedSampling.compute(drives, vitality);

        // Scale maxTokens by profile base (DriveModulatedSampling uses 256 base,
        // but the agent profile may have a different base)
        double profileScale = profile.maxResponseTokens() / 256.0;
        int maxTokens = Math.max(64, (int) (sampling.maxTokens() * profileScale));

        // Debounce: shorter with high momentum, longer when tired
        double debounceFactor = 1.0 - (0.5 * vitality.momentum())
            + (0.3 * (1.0 - vitality.energy()));
        long debounceMs = (long) (500 * Math.max(0.3, debounceFactor));

        // Conversation history: more when focused, less when distracted
        double focusFactor = 0.4 + (0.6 * vitality.focus());
        int historySize = Math.max(5, (int) (20 * focusFactor));

        return new VitalityModulation(
            maxTokens,
            sampling.temperature(),
            sampling.topP(),
            sampling.presencePenalty(),
            sampling.repetitionPenalty(),
            Duration.ofMillis(debounceMs),
            historySize
        );
    }
}
