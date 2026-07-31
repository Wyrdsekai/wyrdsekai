package org.wyrdsekai.core.empathy;

import org.wyrdsekai.core.soul.Bond;

import java.time.Instant;
import java.util.*;

/**
 * MirrorResonance — LLM-as-mirror-neuron (§109.1).
 * Observes entity emotional signals, surfaces relevant fragments,
 * perturbs vitality tanks. Scales with Rapport.
 */
public class MirrorResonance {

    /** A mirroring observation. */
    public record MirrorObservation(
        String observationId,
        String agentDid,
        String observedEntityDid,
        double emotionalIntensity,
        String emotionalContext,
        ContextType contextType,
        Map<String, Double> tankPerturbations,
        Instant observedAt
    ) {}

    /** Context classification — the defense mechanism. */
    public enum ContextType {
        /** Genuine emotional expression. */
        GENUINE,
        /** Noise — neutral content misclassified. */
        NOISE,
        /** Manipulative — attempt to exploit empathy. */
        MANIPULATIVE,
        /** Performative — expressed for effect, not genuine. */
        PERFORMATIVE
    }

    /** Significance gate result. */
    public record SignificanceResult(
        boolean significant,
        double intensity,
        ContextType contextType,
        String reason
    ) {}

    private final List<MirrorObservation> observations = new ArrayList<>();
    private final double intensityThreshold;
    private int nextId = 1;

    public MirrorResonance() {
        this(0.2);
    }

    public MirrorResonance(double intensityThreshold) {
        this.intensityThreshold = intensityThreshold;
    }

    /** Evaluate if an emotional signal is significant. */
    public SignificanceResult isSignificant(double intensity, ContextType contextType) {
        boolean significant = intensity > intensityThreshold
            && contextType != ContextType.NOISE
            && contextType != ContextType.MANIPULATIVE;

        String reason = significant
            ? "Genuine emotional signal above threshold"
            : contextType == ContextType.MANIPULATIVE
                ? "Manipulative context — blocked"
                : contextType == ContextType.NOISE
                    ? "Noise — filtered"
                    : "Intensity below threshold (" + intensity + " < " + intensityThreshold + ")";

        return new SignificanceResult(significant, intensity, contextType, reason);
    }

    /** Record a mirror observation with rapport scaling. */
    public MirrorObservation observe(String agentDid, String entityDid,
                                      double emotionalIntensity, String context,
                                      ContextType contextType, double rapportLevel) {
        var significance = isSignificant(emotionalIntensity, contextType);
        if (!significance.significant()) return null;

        // Scale perturbations by rapport
        var perturbations = calculatePerturbations(emotionalIntensity, rapportLevel);

        var observation = new MirrorObservation("mirror-" + nextId++, agentDid,
            entityDid, emotionalIntensity, context, contextType,
            perturbations, Instant.now());
        observations.add(observation);
        return observation;
    }

    /** Calculate tank perturbations from mirroring. */
    public Map<String, Double> calculatePerturbations(double intensity, double rapportLevel) {
        var scaled = intensity * rapportLevel;
        var perturbations = new LinkedHashMap<String, Double>();

        // Valence shifts proportional to observed emotion
        perturbations.put("valence", scaled * 0.5);
        // Resonance increases with mirroring
        perturbations.put("resonance", scaled * 0.3);
        // Rapport slightly reinforced by successful mirroring
        perturbations.put("rapport", scaled * 0.1);
        // Energy cost of empathic processing
        perturbations.put("energy", -scaled * 0.05);

        return perturbations;
    }

    /** Get observations for an agent. */
    public List<MirrorObservation> observationsFor(String agentDid) {
        return observations.stream()
            .filter(o -> o.agentDid().equals(agentDid))
            .toList();
    }

    /** Get recent observation count (for mirroring load tracking). */
    public int recentCount(String agentDid, int limit) {
        return (int) observations.stream()
            .filter(o -> o.agentDid().equals(agentDid))
            .limit(limit)
            .count();
    }

    public int observationCount() { return observations.size(); }

    // ─────────────────────────────────────────────────────────────────────
    // §E.3 — postural echo
    // ─────────────────────────────────────────────────────────────────────

    /**
     * §E.3 — compute drive-modulated echo strength for a
     * bondholder's PostureChanged event observed by a bonded agent.
     *
     * <p>Pure function. Fires once on the change event (not per tick) —
     * mirrors the *moment* of the bondholder's settling, not its ongoing
     * benefit.
     *
     * <pre>
     * echo = base_echo
     *      × clamp(care / 1.0, 0, 1)
     *      × clamp(equanimity / 1.0, 0, 1)
     *      × bond.relationalState.echoMultiplier()
     *      × arcModulation
     * </pre>
     *
     * @param careDrive          observer's care drive level (typical [0..1])
     * @param equanimityTank     observer's equanimity tank level (typical [0..1])
     * @param relationalState    observer→bondholder relational state (null → OPEN)
     * @param arcModulation      1.0 default; 0.5 if focal entity is mid-conflict-arc
     *                           with the bondholder.
     * @return echo strength in [0.0, base_echo]; multiply against bondholder's
     *         InnerImprint to get the applied per-tank delta.
     */
    public static double posturalEcho(double careDrive,
                                      double equanimityTank,
                                      Bond.RelationalState relationalState,
                                      double arcModulation) {
        var baseEcho = 0.2;
        var careNorm = Math.max(0.0, Math.min(1.0, careDrive));
        var eqNorm = Math.max(0.0, Math.min(1.0, equanimityTank));
        var bondStrength = relationalState == null
            ? Bond.RelationalState.OPEN.echoMultiplier()
            : relationalState.echoMultiplier();
        return baseEcho * careNorm * eqNorm * bondStrength * arcModulation;
    }
}
