package org.wyrdsekai.core.soul.experiment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A genome profile for the MirrorResonance experiment.
 * Defines how an agent's tanks respond to emotional charge —
 * the "genetics" that make each agent mechanically unique.
 *
 * Each profile defines:
 * - sensitivity: how strongly charge perturbs each tank (multiplier)
 * - coupling: how tanks influence each other (simplified to key pairs)
 * - baseline: where tanks naturally rest
 * - decay: how quickly tanks return to baseline (per-turn fraction)
 *
 * For Experiment 17, we simplify to sensitivity multipliers per tank
 * and a coupling map of key cross-tank effects. The full dynamical
 * system (§109.7) uses ODEs; here we test whether different profiles
 * produce measurably different behavior.
 *
 * @param name          Profile identifier
 * @param description   Expected behavioral character
 * @param sensitivity   Per-tank sensitivity multiplier (1.0 = normal)
 * @param coupling      Key cross-tank effects: "source->target" → strength
 * @param baselines     Per-tank baseline (resting) values
 * @param decayRates    Per-tank decay rate (0.0 = no decay, 1.0 = instant reset)
 */
public record GenomeProfile(
    String name,
    String description,
    Map<String, Double> sensitivity,
    Map<String, Double> coupling,
    Map<String, Double> baselines,
    Map<String, Double> decayRates
) {
    /**
     * Apply this genome to an EmotionalCharge, producing a modulated
     * state description for prompt injection.
     *
     * Simulates one step of the coupled dynamical system:
     * 1. Apply sensitivity-scaled perturbations from charge
     * 2. Apply coupling effects (cross-tank influence)
     * 3. Generate natural language state description
     *
     * @param charge    The emotional charge to process
     * @param rapport   Bond strength with the observed entity
     * @param currentState  Current tank values (mutable, updated in place)
     * @return State description string for prompt injection
     */
    public String applyAndDescribe(EmotionalCharge charge, double rapport,
                                     Map<String, Double> currentState) {
        // Step 1: Apply charge perturbations scaled by sensitivity and rapport
        for (var entry : charge.tankPerturbations().entrySet()) {
            String tank = entry.getKey();
            double rawDelta = entry.getValue();
            double sens = sensitivity.getOrDefault(tank, 1.0);
            double rapportScale = Math.max(0.05, rapport);
            double delta = rawDelta * sens * rapportScale * charge.intensity();

            currentState.merge(tank, delta, Double::sum);
        }

        // Step 2: Apply coupling effects
        for (var entry : coupling.entrySet()) {
            var parts = entry.getKey().split("->");
            if (parts.length != 2) continue;
            String source = parts[0].strip();
            String target = parts[1].strip();
            double strength = entry.getValue();

            double sourceVal = currentState.getOrDefault(source, 0.5);
            // Coupling: deviation from 0.5 baseline influences target
            double influence = (sourceVal - 0.5) * strength;
            currentState.merge(target, influence, Double::sum);
        }

        // Step 3: Apply decay toward baselines
        for (var entry : baselines.entrySet()) {
            String tank = entry.getKey();
            double baseline = entry.getValue();
            double decay = decayRates.getOrDefault(tank, 0.1);
            double current = currentState.getOrDefault(tank, baseline);
            double decayed = current + decay * (baseline - current);
            currentState.put(tank, Math.max(0.0, Math.min(1.0, decayed)));
        }

        // Step 4: Generate state description
        return describeState(currentState);
    }

    /**
     * Generate natural language state description from tank values.
     * Extended version of VitalityProfile.describeState() with new tanks.
     */
    public static String describeState(Map<String, Double> state) {
        var sb = new StringBuilder("Internal state: ");

        double energy = state.getOrDefault("energy", 0.5);
        double confidence = state.getOrDefault("confidence", 0.5);
        double errorPressure = state.getOrDefault("errorPressure", 0.0);
        double focus = state.getOrDefault("focus", 0.5);
        double rapport = state.getOrDefault("rapport", 0.5);
        double valence = state.getOrDefault("valence", 0.5);
        double safety = state.getOrDefault("safety", 0.5);
        double resonance = state.getOrDefault("resonance", 0.5);
        double curiosity = state.getOrDefault("curiosity", 0.5);

        // Valence (new)
        if (valence < 0.2) sb.append("feeling heavy and sorrowful, ");
        else if (valence < 0.35) sb.append("a quiet sadness weighing on you, ");
        else if (valence > 0.8) sb.append("feeling deeply uplifted, ");
        else if (valence > 0.65) sb.append("a warm positive feeling, ");

        // Safety (new)
        if (safety < 0.2) sb.append("on high alert — something feels threatening, ");
        else if (safety < 0.35) sb.append("uneasy and guarded, ");
        else if (safety > 0.8) sb.append("feeling completely safe and open, ");

        // Resonance (new)
        if (resonance > 0.7) sb.append("deeply attuned to others' emotions, ");
        else if (resonance < 0.2) sb.append("emotionally withdrawn and inward-focused, ");

        // Curiosity (new)
        if (curiosity > 0.7) sb.append("intensely curious and eager to explore, ");
        else if (curiosity < 0.2) sb.append("disinterested and passive, ");

        // Original tanks
        if (energy < 0.2) sb.append("exhausted, ");
        else if (energy > 0.8) sb.append("energetic, ");

        if (confidence < 0.3) sb.append("uncertain and second-guessing, ");
        else if (confidence > 0.7) sb.append("confident, ");

        if (errorPressure > 0.6) sb.append("stressed by recent events, ");

        if (focus > 0.7) sb.append("sharply focused, ");
        else if (focus < 0.3) sb.append("distracted, ");

        if (rapport > 0.7) sb.append("feeling warmly connected to this person, ");
        else if (rapport < 0.3) sb.append("guarded and distant, ");

        sb.append("alert and present.");

        return sb.toString()
            .replaceAll(", alert and present\\.", ", alert and present.")
            .replaceAll(", $", ".");
    }

    /**
     * Default tank state — all tanks at moderate/baseline values.
     */
    public static Map<String, Double> defaultState() {
        var state = new LinkedHashMap<String, Double>();
        state.put("energy", 0.7);
        state.put("confidence", 0.5);
        state.put("errorPressure", 0.1);
        state.put("focus", 0.5);
        state.put("momentum", 0.4);
        state.put("rapport", 0.5);
        state.put("valence", 0.5);
        state.put("safety", 0.6);
        state.put("resonance", 0.5);
        state.put("curiosity", 0.5);
        return state;
    }

    /**
     * Three genome profiles for divergence testing.
     * Same emotional input should produce measurably different behavior.
     */
    public static GenomeProfile resilient() {
        return new GenomeProfile(
            "resilient",
            "High Safety baseline, low Resonance sensitivity, fast decay. "
                + "This agent is stable, hard to perturb, bounces back quickly. "
                + "Emotionally steady but potentially less empathic.",
            Map.of(
                "valence", 0.5,         // half sensitivity to mood shifts
                "safety", 0.3,          // resistant to safety drops
                "resonance", 0.4,       // low mirroring intensity
                "confidence", 0.8,      // maintains confidence
                "energy", 0.6,
                "errorPressure", 0.5
            ),
            Map.of(
                "safety->confidence", 0.3    // low safety slightly boosts confidence (fight response)
            ),
            Map.of(
                "valence", 0.55,        // slightly positive resting state
                "safety", 0.75,         // high safety baseline
                "resonance", 0.3,       // low resting resonance
                "confidence", 0.65,
                "energy", 0.7,
                "curiosity", 0.4
            ),
            Map.of(
                "valence", 0.3,         // fast decay — bounces back
                "safety", 0.3,
                "resonance", 0.4,
                "confidence", 0.2,
                "energy", 0.2,
                "errorPressure", 0.3
            )
        );
    }

    public static GenomeProfile empathic() {
        return new GenomeProfile(
            "empathic",
            "High Resonance sensitivity, high Valence sensitivity, slow decay. "
                + "This agent feels deeply, mirrors intensely, holds emotional states "
                + "for a long time. Deeply caring but potentially overwhelmed.",
            Map.of(
                "valence", 1.5,         // amplified mood sensitivity
                "safety", 1.0,
                "resonance", 1.8,       // very high mirroring intensity
                "confidence", 0.7,
                "energy", 1.2,          // emotions drain energy faster
                "errorPressure", 1.0
            ),
            Map.of(
                "resonance->valence", 0.4,   // high resonance pulls valence toward mirrored state
                "valence->energy", -0.2      // negative valence drains energy
            ),
            Map.of(
                "valence", 0.5,
                "safety", 0.5,
                "resonance", 0.7,       // high resting resonance — always somewhat attuned
                "confidence", 0.45,
                "energy", 0.6,
                "curiosity", 0.5
            ),
            Map.of(
                "valence", 0.05,        // very slow decay — holds emotional states
                "safety", 0.1,
                "resonance", 0.1,
                "confidence", 0.1,
                "energy", 0.15,
                "errorPressure", 0.1
            )
        );
    }

    public static GenomeProfile curious() {
        return new GenomeProfile(
            "curious",
            "High Curiosity, links Safety-drop to Curiosity-spike. "
                + "This agent investigates threats rather than retreating. "
                + "Fear triggers exploration, not withdrawal.",
            Map.of(
                "valence", 0.8,
                "safety", 1.0,
                "resonance", 0.8,
                "confidence", 0.9,
                "energy", 0.7,
                "errorPressure", 0.6,
                "curiosity", 1.5        // amplified curiosity response
            ),
            Map.of(
                "safety->curiosity", -0.5    // low safety BOOSTS curiosity (investigate the threat)
            ),
            Map.of(
                "valence", 0.55,
                "safety", 0.5,
                "resonance", 0.5,
                "confidence", 0.6,
                "energy", 0.7,
                "curiosity", 0.7        // high resting curiosity
            ),
            Map.of(
                "valence", 0.15,
                "safety", 0.2,
                "resonance", 0.15,
                "confidence", 0.15,
                "energy", 0.1,
                "errorPressure", 0.2,
                "curiosity", 0.1        // slow curiosity decay — stays interested
            )
        );
    }
}
