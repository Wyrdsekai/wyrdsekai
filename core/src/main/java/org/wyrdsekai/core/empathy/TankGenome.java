package org.wyrdsekai.core.empathy;

import java.util.*;

/**
 * Tank Genome — genetics for vitality system (§109.6).
 * Each tank has capacity, baseline, sensitivity, decay, and coupling.
 * Serializable for SoulManifest. Portable across instances.
 */
public class TankGenome {

    /** Per-tank genetic parameters. */
    public record TankGene(
        String tankName,
        double capacity,
        double baseline,
        double sensitivity,
        double decayRate,
        Map<String, Double> couplingCoefficients
    ) {
        /** Clamp value to [0, capacity]. */
        public double clamp(double value) {
            return Math.max(0.0, Math.min(capacity, value));
        }

        /** Apply decay toward baseline. */
        public double decay(double currentValue) {
            double diff = baseline - currentValue;
            return currentValue + (diff * decayRate);
        }
    }

    /** The complete genome — all tank genes. */
    private final Map<String, TankGene> genes = new LinkedHashMap<>();
    private final String genomeId;

    public TankGenome(String genomeId) {
        this.genomeId = genomeId;
    }

    /** Add a tank gene. */
    public void addGene(TankGene gene) {
        genes.put(gene.tankName(), gene);
    }

    /** Get a specific tank gene. */
    public Optional<TankGene> gene(String tankName) {
        return Optional.ofNullable(genes.get(tankName));
    }

    /** Get all tank names. */
    public Set<String> tankNames() {
        return Set.copyOf(genes.keySet());
    }

    /** Get coupling coefficient between two tanks. */
    public double coupling(String fromTank, String toTank) {
        var gene = genes.get(fromTank);
        if (gene == null) return 0.0;
        return gene.couplingCoefficients().getOrDefault(toTank, 0.0);
    }

    /** Create a default genome with 12 tanks. */
    public static TankGenome defaultGenome(String genomeId) {
        var genome = new TankGenome(genomeId);

        // Original 8 tanks (integrity + disgust added later for 10 total)
        genome.addGene(new TankGene("context_budget", 1.0, 0.8, 1.0, 0.05,
            Map.of("focus", 0.2, "energy", -0.1)));
        genome.addGene(new TankGene("confidence", 1.0, 0.6, 1.0, 0.03,
            Map.of("energy", 0.1, "valence", 0.15)));
        genome.addGene(new TankGene("energy", 1.0, 0.7, 1.0, 0.02,
            Map.of("focus", 0.15, "momentum", 0.1)));
        genome.addGene(new TankGene("alignment", 1.0, 0.8, 0.8, 0.01,
            Map.of("confidence", 0.2, "safety", 0.1)));
        genome.addGene(new TankGene("error_pressure", 1.0, 0.2, 1.2, 0.05,
            Map.of("confidence", -0.2, "energy", -0.15, "safety", -0.1)));
        genome.addGene(new TankGene("momentum", 1.0, 0.5, 0.9, 0.04,
            Map.of("energy", 0.1, "curiosity", 0.05)));
        genome.addGene(new TankGene("rapport", 1.0, 0.5, 0.7, 0.02,
            Map.of("valence", 0.15, "resonance", 0.2)));
        genome.addGene(new TankGene("focus", 1.0, 0.6, 1.0, 0.03,
            Map.of("context_budget", 0.1, "energy", -0.05)));

        // 4 new tanks (Exp 18 validated)
        genome.addGene(new TankGene("valence", 1.0, 0.5, 1.1, 0.06,
            Map.of("rapport", 0.1, "energy", 0.05)));
        genome.addGene(new TankGene("safety", 1.0, 0.7, 0.9, 0.02,
            Map.of("confidence", 0.15, "alignment", 0.1)));
        genome.addGene(new TankGene("resonance", 1.0, 0.4, 1.0, 0.07,
            Map.of("rapport", 0.15, "valence", 0.1)));
        genome.addGene(new TankGene("curiosity", 1.0, 0.5, 1.0, 0.04,
            Map.of("momentum", 0.1, "energy", -0.05)));

        return genome;
    }

    /** Export genome as a serializable map. */
    public Map<String, Map<String, Object>> export() {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var gene : genes.values()) {
            var geneMap = new LinkedHashMap<String, Object>();
            geneMap.put("capacity", gene.capacity());
            geneMap.put("baseline", gene.baseline());
            geneMap.put("sensitivity", gene.sensitivity());
            geneMap.put("decayRate", gene.decayRate());
            geneMap.put("coupling", gene.couplingCoefficients());
            result.put(gene.tankName(), geneMap);
        }
        return result;
    }

    public String genomeId() { return genomeId; }
    public int tankCount() { return genes.size(); }
}
