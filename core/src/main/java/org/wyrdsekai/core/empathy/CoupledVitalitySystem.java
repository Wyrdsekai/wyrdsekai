package org.wyrdsekai.core.empathy;

import java.util.*;

/**
 * Coupled vitality system (§109.7).
 * Tanks influence each other via coupling matrix from genome.
 * Same input produces different behavior depending on genome.
 * Replaces independent tank updates.
 */
public class CoupledVitalitySystem {

    private final TankGenome genome;
    private final Map<String, Double> tankValues = new LinkedHashMap<>();

    public CoupledVitalitySystem(TankGenome genome) {
        this.genome = genome;
        // Initialize tanks to baseline values
        for (var tankName : genome.tankNames()) {
            genome.gene(tankName).ifPresent(gene ->
                tankValues.put(tankName, gene.baseline()));
        }
    }

    /** Apply a perturbation to a single tank, propagating through coupling. */
    public Map<String, Double> perturb(String tankName, double delta) {
        var gene = genome.gene(tankName);
        if (gene.isEmpty()) return Map.of();

        // Apply sensitivity scaling
        double scaledDelta = delta * gene.get().sensitivity();

        // Apply direct change
        double current = tankValues.getOrDefault(tankName, 0.0);
        double newValue = gene.get().clamp(current + scaledDelta);
        tankValues.put(tankName, newValue);

        // Track all changes
        var changes = new LinkedHashMap<String, Double>();
        changes.put(tankName, newValue - current);

        // Propagate through coupling
        for (var entry : gene.get().couplingCoefficients().entrySet()) {
            var targetTank = entry.getKey();
            var coefficient = entry.getValue();
            var targetGene = genome.gene(targetTank);
            if (targetGene.isEmpty()) continue;

            double coupledDelta = scaledDelta * coefficient;
            double targetCurrent = tankValues.getOrDefault(targetTank, 0.0);
            double targetNew = targetGene.get().clamp(targetCurrent + coupledDelta);
            tankValues.put(targetTank, targetNew);
            changes.put(targetTank, targetNew - targetCurrent);
        }

        return changes;
    }

    /** Apply decay toward baselines for all tanks. */
    public void decay() {
        for (var tankName : genome.tankNames()) {
            genome.gene(tankName).ifPresent(gene -> {
                double current = tankValues.getOrDefault(tankName, gene.baseline());
                double decayed = gene.decay(current);
                tankValues.put(tankName, decayed);
            });
        }
    }

    /** Apply batch perturbations (e.g., from MirrorResonance). */
    public Map<String, Double> perturbBatch(Map<String, Double> perturbations) {
        var allChanges = new LinkedHashMap<String, Double>();
        for (var entry : perturbations.entrySet()) {
            var changes = perturb(entry.getKey(), entry.getValue());
            for (var change : changes.entrySet()) {
                allChanges.merge(change.getKey(), change.getValue(), Double::sum);
            }
        }
        return allChanges;
    }

    /** Get current tank value. */
    public double value(String tankName) {
        return tankValues.getOrDefault(tankName, 0.0);
    }

    /** Get all current values. */
    public Map<String, Double> allValues() {
        return Map.copyOf(tankValues);
    }

    /** Set a tank value directly (for initialization or reset). */
    public void set(String tankName, double value) {
        genome.gene(tankName).ifPresent(gene ->
            tankValues.put(tankName, gene.clamp(value)));
    }

    /** Count tanks below a threshold. */
    public long tanksBelow(double threshold) {
        return tankValues.values().stream()
            .filter(v -> v < threshold)
            .count();
    }

    /** Overall vitality (average of all tanks). */
    public double overallVitality() {
        if (tankValues.isEmpty()) return 0.0;
        return tankValues.values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }

    public TankGenome genome() { return genome; }
}
