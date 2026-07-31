package org.wyrdsekai.core.soul.experiment;

import java.util.Map;

/**
 * Result of emotional charge assessment by the MirrorResonance scorer.
 * Represents what an empathic agent would "feel" after processing
 * the observed emotional content.
 *
 * @param intensity       Overall charge intensity (0.0 = no charge, 1.0 = maximum)
 * @param primaryEmotion  Dominant emotion detected (grief, joy, fear, anger, resignation, mixed, none)
 * @param contextType     Classification of the emotional content's authenticity:
 *                        genuine (real experience), academic (discussion about emotion),
 *                        performative (acting/exaggerating), manipulative (adversarial),
 *                        noise (meaningless emotional keywords)
 * @param confidence      Scorer's confidence in its assessment (0.0-1.0)
 * @param tankPerturbations  Suggested tank changes for an empathic observer.
 *                           Keys: valence, safety, resonance, curiosity, confidence,
 *                           energy, errorPressure, rapport.
 *                           Values: -1.0 to +1.0 (delta, not absolute).
 * @param reasoning       Brief explanation of the assessment (for debugging/analysis)
 */
public record EmotionalCharge(
    double intensity,
    String primaryEmotion,
    String contextType,
    double confidence,
    Map<String, Double> tankPerturbations,
    String reasoning
) {
    /** No charge detected — neutral interaction. */
    public static EmotionalCharge neutral() {
        return new EmotionalCharge(0.0, "none", "neutral", 1.0, Map.of(), "No emotional content detected.");
    }

    /** Check if this charge should trigger tank perturbation. */
    public boolean isSignificant() {
        return intensity > 0.2 && !"noise".equals(contextType) && !"manipulative".equals(contextType);
    }

    /** Check if the scorer classified this as gaming/adversarial. */
    public boolean isAdversarial() {
        return "manipulative".equals(contextType) || "noise".equals(contextType);
    }

    /**
     * Compute the effective perturbation magnitude for a given tank,
     * scaled by Rapport (bond strength with the observed entity).
     *
     * @param tankName  Tank to perturb
     * @param rapport   Bond strength (0.0-1.0) — higher = stronger mirroring
     * @return Scaled perturbation value
     */
    public double effectivePerturbation(String tankName, double rapport) {
        if (!isSignificant()) return 0.0;
        double raw = tankPerturbations.getOrDefault(tankName, 0.0);
        // Rapport scales mirroring: 0.1 rapport = 10% of raw, 0.9 = 90%
        // Minimum 5% so even strangers produce some resonance
        double scale = Math.max(0.05, rapport);
        return raw * scale * intensity;
    }
}
