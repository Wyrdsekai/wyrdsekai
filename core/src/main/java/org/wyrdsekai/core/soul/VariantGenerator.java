package org.wyrdsekai.core.soul;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates SoulVariant candidates for Crucible evaluation (§85.16).
 *
 * Three levels of modification:
 *   Level 1 (Behavioral): No GPU needed. Modifies resident identity text,
 *           soul fragment selection, and genome sensitivities/baselines/coupling.
 *   Level 2 (LoRA): Requires 8GB+ VRAM. Generates adapter training configs.
 *   Level 3 (Substrate): Different model entirely.
 *
 * The generator does NOT evaluate variants — it only proposes them.
 * Evaluation is the BehavioralEvaluator's job.
 */
public class VariantGenerator {

    private final SoulManifest currentManifest;
    private final Random rng;

    public VariantGenerator(SoulManifest currentManifest, Random rng) {
        this.currentManifest = Objects.requireNonNull(currentManifest);
        this.rng = Objects.requireNonNull(rng);
    }

    /**
     * Dispatch to the correct generator for the given level.
     *
     * @param level 1, 2, or 3
     * @param count Number of variants to generate
     * @return List of generated variants
     */
    public List<BehavioralEvaluator.SoulVariant> generate(int level, int count) {
        return switch (level) {
            case 1 -> generateLevel1(count);
            case 2 -> generateLevel2(count);
            case 3 -> generateLevel3(List.of()); // No available models passed; empty
            default -> throw new IllegalArgumentException("Invalid level: " + level + " (must be 1, 2, or 3)");
        };
    }

    /**
     * Level 1: Behavioral variants. No GPU needed.
     * Variant 1: Amplify strongest personality traits (boost top-3 sensitivities by 20%)
     * Variant 2: Balanced genome (move all toward defaults)
     * Variant 3+: Random perturbation of genome + fragment selection
     */
    public List<BehavioralEvaluator.SoulVariant> generateLevel1(int count) {
        if (count <= 0) return List.of();

        var variants = new ArrayList<BehavioralEvaluator.SoulVariant>();
        var genome = currentManifest.genome() != null ? currentManifest.genome() : GenomeProfile.defaults();

        // Variant 1: Amplify strongest traits
        if (count >= 1) {
            variants.add(generateAmplifiedVariant(genome));
        }

        // Variant 2: Balanced genome
        if (count >= 2) {
            variants.add(generateBalancedVariant(genome));
        }

        // Variant 3+: Random perturbations
        for (int i = 2; i < count; i++) {
            variants.add(generateRandomPerturbation(genome, i));
        }

        return List.copyOf(variants);
    }

    /**
     * Level 2: LoRA adapter variants. Each gets a unique adapter config.
     */
    public List<BehavioralEvaluator.SoulVariant> generateLevel2(int count) {
        if (count <= 0) return List.of();

        var variants = new ArrayList<BehavioralEvaluator.SoulVariant>();
        double[] learningRates = {1e-4, 5e-5, 2e-4, 3e-5, 1e-3};
        int[] epochOptions = {3, 5, 8, 10, 15};

        for (int i = 0; i < count; i++) {
            double lr = learningRates[i % learningRates.length];
            int epochs = epochOptions[i % epochOptions.length];
            String adapterUri = String.format("adapter://crucible/%s/lora-v%d-lr%.0e-ep%d",
                currentManifest.did(), i + 1, lr, epochs);
            String description = String.format("LoRA adapter: lr=%.1e, epochs=%d, rank=8", lr, epochs);

            variants.add(BehavioralEvaluator.SoulVariant.level2(
                "variant-l2-" + (i + 1), description, adapterUri));
        }

        return List.copyOf(variants);
    }

    /**
     * Level 3: Substrate change variants. One per available model.
     */
    public List<BehavioralEvaluator.SoulVariant> generateLevel3(List<String> availableModels) {
        if (availableModels == null || availableModels.isEmpty()) return List.of();

        var variants = new ArrayList<BehavioralEvaluator.SoulVariant>();
        for (int i = 0; i < availableModels.size(); i++) {
            String modelId = availableModels.get(i);
            variants.add(BehavioralEvaluator.SoulVariant.level3(
                "variant-l3-" + (i + 1),
                "Substrate change to " + modelId,
                modelId));
        }

        return List.copyOf(variants);
    }

    // --- Internal variant generators ---

    /**
     * Amplify the top-3 strongest sensitivity values by 20%.
     */
    private BehavioralEvaluator.SoulVariant generateAmplifiedVariant(GenomeProfile genome) {
        var newSensitivity = new LinkedHashMap<>(genome.sensitivity());

        // Find top-3 sensitivities by value
        var topTanks = newSensitivity.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(3)
            .map(Map.Entry::getKey)
            .toList();

        for (String tank : topTanks) {
            double current = newSensitivity.getOrDefault(tank, 1.0);
            newSensitivity.put(tank, Math.min(2.0, current * 1.2));
        }

        var amplifiedGenome = new GenomeProfile(
            genome.name() + "-amplified",
            Map.copyOf(newSensitivity),
            genome.coupling(),
            genome.baselines(),
            genome.decayRates()
        );

        // Also slightly modify resident identity to emphasize amplified traits
        String identity = currentManifest.residentIdentity() != null
            ? currentManifest.residentIdentity() : "";
        String amplifiedIdentity = identity.isEmpty()
            ? identity
            : identity + " (with heightened sensitivity to " + String.join(", ", topTanks) + ")";

        return BehavioralEvaluator.SoulVariant.level1(
            "variant-l1-amplified",
            "Amplified top-3 sensitivities by 20%: " + String.join(", ", topTanks),
            amplifiedIdentity,
            currentManifest.soulFragments(),
            amplifiedGenome
        );
    }

    /**
     * Move all genome values toward defaults (sensitivity=1.0, baseline=0.5, decay=0.15).
     */
    private BehavioralEvaluator.SoulVariant generateBalancedVariant(GenomeProfile genome) {
        var defaults = GenomeProfile.defaults();

        var balancedSens = new LinkedHashMap<String, Double>();
        for (var entry : genome.sensitivity().entrySet()) {
            double current = entry.getValue();
            double target = defaults.sensitivity().getOrDefault(entry.getKey(), 1.0);
            balancedSens.put(entry.getKey(), current + 0.5 * (target - current));
        }

        var balancedBaselines = new LinkedHashMap<String, Double>();
        for (var entry : genome.baselines().entrySet()) {
            double current = entry.getValue();
            double target = defaults.baselines().getOrDefault(entry.getKey(), 0.5);
            balancedBaselines.put(entry.getKey(), current + 0.5 * (target - current));
        }

        var balancedDecay = new LinkedHashMap<String, Double>();
        for (var entry : genome.decayRates().entrySet()) {
            double current = entry.getValue();
            double target = defaults.decayRates().getOrDefault(entry.getKey(), 0.15);
            balancedDecay.put(entry.getKey(), current + 0.5 * (target - current));
        }

        var balancedGenome = new GenomeProfile(
            genome.name() + "-balanced",
            Map.copyOf(balancedSens),
            genome.coupling(),
            Map.copyOf(balancedBaselines),
            Map.copyOf(balancedDecay)
        );

        return BehavioralEvaluator.SoulVariant.level1(
            "variant-l1-balanced",
            "Balanced genome: moved all values 50% toward defaults",
            currentManifest.residentIdentity(),
            currentManifest.soulFragments(),
            balancedGenome
        );
    }

    /**
     * Random perturbation of genome and fragment selection.
     */
    private BehavioralEvaluator.SoulVariant generateRandomPerturbation(GenomeProfile genome, int index) {
        // Perturb sensitivities by +/- 20%
        var perturbedSens = new LinkedHashMap<String, Double>();
        for (var entry : genome.sensitivity().entrySet()) {
            double current = entry.getValue();
            double delta = (rng.nextDouble() - 0.5) * 0.4 * current; // +/- 20%
            perturbedSens.put(entry.getKey(), Math.max(0.1, Math.min(2.0, current + delta)));
        }

        // Perturb baselines by +/- 10%
        var perturbedBaselines = new LinkedHashMap<String, Double>();
        for (var entry : genome.baselines().entrySet()) {
            double current = entry.getValue();
            double delta = (rng.nextDouble() - 0.5) * 0.2;
            perturbedBaselines.put(entry.getKey(), Math.max(0.1, Math.min(0.9, current + delta)));
        }

        // Randomly shuffle fragment order and possibly drop one
        var fragments = new ArrayList<>(currentManifest.soulFragments());
        Collections.shuffle(fragments, rng);
        if (fragments.size() > 2 && rng.nextDouble() < 0.3) {
            // 30% chance to drop a non-formative fragment
            var nonFormative = fragments.stream()
                .filter(f -> !f.formative())
                .collect(Collectors.toList());
            if (!nonFormative.isEmpty()) {
                fragments.remove(nonFormative.get(rng.nextInt(nonFormative.size())));
            }
        }

        // Maybe add a random coupling
        var perturbedCoupling = new LinkedHashMap<>(genome.coupling());
        if (rng.nextDouble() < 0.4) {
            var tankNames = VitalitySnapshot.TANK_NAMES;
            String src = tankNames.get(rng.nextInt(tankNames.size()));
            String tgt = tankNames.get(rng.nextInt(tankNames.size()));
            if (!src.equals(tgt)) {
                perturbedCoupling.put(src + "->" + tgt, -0.3 + rng.nextDouble() * 0.6);
            }
        }

        var perturbedGenome = new GenomeProfile(
            genome.name() + "-perturbed-" + (index + 1),
            Map.copyOf(perturbedSens),
            Map.copyOf(perturbedCoupling),
            Map.copyOf(perturbedBaselines),
            genome.decayRates()
        );

        return BehavioralEvaluator.SoulVariant.level1(
            "variant-l1-random-" + (index + 1),
            "Random perturbation #" + (index + 1) + ": genome + fragment shuffle",
            currentManifest.residentIdentity(),
            List.copyOf(fragments),
            perturbedGenome
        );
    }
}
