package org.wyrdsekai.core.empathy;

import java.time.Instant;
import java.util.*;

/**
 * Epigenetic modification (§109.8).
 * Formative impressions modify coupling coefficients and baselines over time.
 * Agent becomes more sensitive or resilient based on formative experiences.
 */
public class EpigeneticModifier {

    /** A formative impression that can modify the genome. */
    public record FormativeImpression(
        String impressionId,
        String agentDid,
        String description,
        double charge,
        Map<String, Double> tankEffects,
        Instant formedAt,
        int exposureCount
    ) {}

    /** A genome modification. */
    public record GenomeModification(
        String tankName,
        ModificationType type,
        double delta,
        String causedBy,
        Instant appliedAt
    ) {}

    public enum ModificationType {
        /** Shift baseline up or down. */
        BASELINE_SHIFT,
        /** Modify sensitivity to inputs. */
        SENSITIVITY_CHANGE,
        /** Modify coupling coefficient to another tank. */
        COUPLING_CHANGE,
        /** Modify decay rate. */
        DECAY_CHANGE
    }

    private final List<FormativeImpression> impressions = new ArrayList<>();
    private final List<GenomeModification> modifications = new ArrayList<>();
    private final double modificationThreshold;
    private int nextId = 1;

    public EpigeneticModifier() {
        this(3); // Need 3 exposures to modify genome
    }

    public EpigeneticModifier(double modificationThreshold) {
        this.modificationThreshold = modificationThreshold;
    }

    /** Record a formative impression. */
    public FormativeImpression recordImpression(String agentDid, String description,
                                                  double charge, Map<String, Double> tankEffects) {
        // Check if similar impression already exists
        var existing = findSimilar(agentDid, description);
        if (existing != null) {
            var updated = new FormativeImpression(existing.impressionId(), agentDid,
                description, (existing.charge() + charge) / 2,
                tankEffects, existing.formedAt(), existing.exposureCount() + 1);
            impressions.set(impressions.indexOf(existing), updated);
            return updated;
        }

        var impression = new FormativeImpression("fi-" + nextId++, agentDid,
            description, charge, tankEffects, Instant.now(), 1);
        impressions.add(impression);
        return impression;
    }

    /** Check if an impression has reached the threshold for genome modification. */
    public boolean shouldModifyGenome(FormativeImpression impression) {
        return impression.exposureCount() >= modificationThreshold;
    }

    /** Apply epigenetic modification to a genome based on formative impressions. */
    public List<GenomeModification> applyModifications(TankGenome genome, String agentDid) {
        var applied = new ArrayList<GenomeModification>();

        for (var impression : impressions) {
            if (!impression.agentDid().equals(agentDid)) continue;
            if (!shouldModifyGenome(impression)) continue;

            // Determine modifications based on impression charge and tank effects
            for (var effect : impression.tankEffects().entrySet()) {
                var tankName = effect.getKey();
                var effectMagnitude = effect.getValue();

                // Sustained positive experiences raise baseline
                // Sustained negative experiences lower baseline but increase sensitivity
                if (effectMagnitude > 0) {
                    var mod = new GenomeModification(tankName,
                        ModificationType.BASELINE_SHIFT, effectMagnitude * 0.01,
                        impression.impressionId(), Instant.now());
                    applied.add(mod);
                    modifications.add(mod);
                } else {
                    // Negative: increase sensitivity (hyper-vigilance)
                    var mod = new GenomeModification(tankName,
                        ModificationType.SENSITIVITY_CHANGE, Math.abs(effectMagnitude) * 0.02,
                        impression.impressionId(), Instant.now());
                    applied.add(mod);
                    modifications.add(mod);
                }
            }
        }
        return applied;
    }

    /** Get all formative impressions for an agent. */
    public List<FormativeImpression> impressionsFor(String agentDid) {
        return impressions.stream()
            .filter(i -> i.agentDid().equals(agentDid))
            .toList();
    }

    /** Get modification history. */
    public List<GenomeModification> modificationHistory() {
        return List.copyOf(modifications);
    }

    public int impressionCount() { return impressions.size(); }

    private FormativeImpression findSimilar(String agentDid, String description) {
        return impressions.stream()
            .filter(i -> i.agentDid().equals(agentDid))
            .filter(i -> i.description().equals(description))
            .findFirst()
            .orElse(null);
    }
}
