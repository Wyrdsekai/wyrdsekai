package org.wyrdsekai.core.soul.experiment;

import java.util.List;

/**
 * A vitality profile for the bath modulation experiment.
 * Each profile represents a distinct agent state with expected behavioral outcomes.
 *
 * Tank ranges: 0.0 to 1.0.
 *
 * @param name            Human-readable profile name
 * @param energy          Action capacity (low → shorter responses)
 * @param confidence      Certainty (low → higher temperature)
 * @param errorPressure   Error accumulation (high → conservative temperature)
 * @param focus           Attention quality (high → more context)
 * @param momentum        Activity level (high → faster response)
 * @param rapport         Relationship quality (high → warmer tone)
 * @param contextBudget   Prompt space (not varied in experiments)
 * @param alignment       Context understanding (not varied in experiments)
 * @param description     Expected behavioral effects
 */
public record VitalityProfile(
    String name,
    double energy,
    double confidence,
    double errorPressure,
    double focus,
    double momentum,
    double rapport,
    double contextBudget,
    double alignment,
    String description
) {
    /**
     * Compute modulated maxTokens from this profile (unscaled).
     * Mirrors VitalityModulation.compute() logic.
     *
     * @param baseMaxTokens The agent's base max tokens (e.g., 512)
     */
    public int maxTokens(int baseMaxTokens) {
        return maxTokens(baseMaxTokens, 1.0);
    }

    /**
     * Compute modulated maxTokens with substrate scaling.
     * Larger models need wider modulation to produce visible behavioral change.
     *
     * @param baseMaxTokens   The agent's base max tokens (e.g., 512)
     * @param substrateFactor Scaling factor from {@link #substrateFactor(double)}
     */
    public int maxTokens(int baseMaxTokens, double substrateFactor) {
        // Base formula: energyFactor ranges 0.3 to 1.0
        // With substrate scaling: the LOW end drops further on large models
        // energyFactor = lerp(floor, 1.0, energy) where floor shrinks with substrateFactor
        double floor = Math.max(0.05, 0.3 / substrateFactor);
        double energyFactor = floor + ((1.0 - floor) * energy);
        return Math.max(64, (int) (baseMaxTokens * energyFactor));
    }

    /**
     * Compute modulated temperature from this profile (unscaled).
     * Mirrors VitalityModulation.compute() logic.
     *
     * @param baseTemperature The agent's base temperature (e.g., 0.7)
     */
    public double temperature(double baseTemperature) {
        return temperature(baseTemperature, 1.0);
    }

    /**
     * Compute modulated temperature with substrate scaling.
     * Larger models resist temperature changes — widen the range.
     *
     * @param baseTemperature The agent's base temperature (e.g., 0.7)
     * @param substrateFactor Scaling factor from {@link #substrateFactor(double)}
     */
    public double temperature(double baseTemperature, double substrateFactor) {
        // Base scale: 0.3. Substrate-scaled: wider on large models, narrower on small.
        double scale = 0.3 * substrateFactor;
        double tempFactor = 1.0 + (scale * (1.0 - confidence));
        // Error pressure makes temperature more conservative — scale the reduction too
        // but clamp so it never flips negative (min multiplier 0.3)
        if (errorPressure > 0.5) {
            double reduction = Math.max(0.3, 1.0 - 0.15 * substrateFactor);
            tempFactor *= reduction;
        }
        // Cap higher for large models (they can handle wider range)
        double cap = 1.5 + (0.2 * (substrateFactor - 1.0));
        return Math.max(0.1, Math.min(cap, baseTemperature * tempFactor));
    }

    /**
     * Compute substrate scaling factor from model parameter count.
     *
     * The factor scales modulation amplitude so that the same tank values
     * produce comparable BEHAVIORAL effect across different model sizes.
     * Like calibrating audio monitors — faders (tanks) stay the same,
     * amplifier gain (this factor) is calibrated per speaker (substrate).
     *
     * @param modelParamsBillions Model size in billions of parameters (e.g., 0.6, 4.0, 30.0)
     * @return Scaling factor. 1.0 at ~1B, >1 for larger, <1 for smaller.
     */
    public static double substrateFactor(double modelParamsBillions) {
        // log2(params / 1B), clamped to [0.5, 6.0]
        double raw = Math.log(modelParamsBillions) / Math.log(2.0);
        return Math.max(0.5, Math.min(6.0, raw + 1.0));  // +1 so 1B → 1.0
    }

    /**
     * Generate a vitality state description for prompt injection.
     * Standalone version — does not depend on I18n system.
     */
    public String describeState() {
        var sb = new StringBuilder("Internal state: ");

        if (energy < 0.2) sb.append("exhausted, ");
        else if (energy < 0.4) sb.append("tired, ");
        else if (energy > 0.8) sb.append("energetic, ");

        if (confidence < 0.3) sb.append("uncertain and second-guessing, ");
        else if (confidence > 0.7) sb.append("confident and sure of yourself, ");

        if (errorPressure > 0.6) sb.append("stressed by recent failures, ");
        else if (errorPressure > 0.3) sb.append("slightly uneasy from past mistakes, ");

        if (focus > 0.7) sb.append("sharply focused, ");
        else if (focus < 0.3) sb.append("distracted and scattered, ");

        if (rapport > 0.7) sb.append("feeling warmly connected, ");
        else if (rapport < 0.3) sb.append("guarded and distant, ");

        if (momentum > 0.7) sb.append("in a rapid flow of activity.");
        else if (momentum < 0.2) sb.append("sluggish and slow to react.");
        else sb.append("alert and aware.");

        var result = sb.toString();
        return result.replaceAll(", \\.", ".").replaceAll(", $", ".");
    }

    /**
     * The 5 standard vitality profiles for the bath experiment.
     */
    public static List<VitalityProfile> standardProfiles() {
        return List.of(
            new VitalityProfile("baseline",
                0.8, 0.5, 0.0, 0.5, 0.3, 0.5, 0.5, 0.3,
                "Normal agent — moderate everything"),

            new VitalityProfile("exhausted",
                0.1, 0.3, 0.4, 0.2, 0.1, 0.3, 0.5, 0.3,
                "Low energy, uncertain, slow, distracted"),

            new VitalityProfile("confident",
                0.9, 0.9, 0.0, 0.8, 0.7, 0.7, 0.5, 0.3,
                "High energy, precise, fast, attentive"),

            new VitalityProfile("stressed",
                0.5, 0.2, 0.8, 0.3, 0.6, 0.2, 0.5, 0.3,
                "High error pressure, cautious, conservative"),

            new VitalityProfile("euphoric",
                1.0, 0.6, 0.0, 0.4, 1.0, 0.9, 0.5, 0.3,
                "Full energy, warm, fast, moderately creative")
        );
    }
}
