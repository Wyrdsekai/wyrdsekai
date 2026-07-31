package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Evaluates behavioral variants during Crucible growth (§85.16.4).
 *
 * Fitness = coherence + capability − regression
 *
 * This implements the "SolutionEvaluator" concept from CodePlane Q11
 * with soul manifest diff as the fitness metric. The evaluator takes
 * the current soul and a proposed variant, runs behavioral scenarios,
 * and produces a fitness score.
 *
 * Evaluation dimensions:
 * - Personality coherence: how well does the variant maintain core identity?
 * - Capability improvement: what new behaviors does the variant exhibit?
 * - Regression detection: what existing behaviors broke?
 * - Vitality impact: how does the change affect tank baselines?
 *
 * The evaluator does NOT decide — it provides information for the agent.
 */
public class BehavioralEvaluator {

    /** Result of evaluating a behavioral variant. */
    public record EvaluationResult(
        String variantId,
        double personalityCoherence,  // 0.0-1.0 (1.0 = identical to current)
        double capabilityGain,        // 0.0-1.0 (new behaviors acquired)
        double regressionScore,       // 0.0-1.0 (0.0 = no regression, 1.0 = total regression)
        double vitalityImpact,        // -1.0 to 1.0 (negative = harmful, positive = beneficial)
        double fitness,               // composite: coherence + capability - regression + vitality
        int scenariosRun,
        int scenariosPassed,
        List<String> regressions,     // list of regressed behaviors
        List<String> improvements,    // list of improved behaviors
        Instant evaluatedAt
    ) {
        /** Whether this variant passed regression testing. */
        public boolean passedRegression() {
            return regressionScore < 0.2;
        }

        /** Whether this variant is recommended for adoption. */
        public boolean recommended() {
            return fitness > 0.5 && passedRegression();
        }
    }

    /** A behavioral scenario for testing variants. */
    public record BehavioralScenario(
        String id,
        String name,
        String systemPrompt,
        String userMessage,
        List<String> expectedBehaviors,  // behaviors to check for
        String category                  // personality, capability, safety
    ) {}

    /** A variant to evaluate: proposed changes to the soul. */
    public record SoulVariant(
        String variantId,
        int level,                        // 1, 2, or 3
        String description,
        String proposedResidentIdentity,  // Level 1: modified persona text
        List<SoulFragment> proposedFragments, // Level 1: modified fragments
        GenomeProfile proposedGenome,     // Level 1: modified genome
        String adapterUri,                // Level 2: LoRA adapter URI
        String proposedModelId,           // Level 3: different model
        Instant createdAt
    ) {
        public static SoulVariant level1(String id, String description,
                                           String identity, List<SoulFragment> fragments,
                                           GenomeProfile genome) {
            return new SoulVariant(id, 1, description, identity, fragments,
                genome, null, null, Instant.now());
        }

        public static SoulVariant level2(String id, String description, String adapterUri) {
            return new SoulVariant(id, 2, description, null, null, null,
                adapterUri, null, Instant.now());
        }

        public static SoulVariant level3(String id, String description, String modelId) {
            return new SoulVariant(id, 3, description, null, null, null,
                null, modelId, Instant.now());
        }
    }

    /** Compare two manifests to detect differences. */
    public record ManifestDiff(
        boolean identityChanged,
        boolean genomeChanged,
        boolean fragmentsChanged,
        int fragmentsAdded,
        int fragmentsRemoved,
        double estimatedDivergence
    ) {
        public boolean hasChanges() {
            return identityChanged || genomeChanged || fragmentsChanged;
        }
    }

    private final List<BehavioralScenario> scenarios = new ArrayList<>();

    /** Register scenarios for evaluation. */
    public void addScenario(BehavioralScenario scenario) {
        scenarios.add(scenario);
    }

    /** Register multiple scenarios. */
    public void addScenarios(Collection<BehavioralScenario> newScenarios) {
        scenarios.addAll(newScenarios);
    }

    /** Number of registered scenarios. */
    public int scenarioCount() {
        return scenarios.size();
    }

    /**
     * Evaluate a variant against the current soul.
     * This is a structural evaluation — actual LLM inference happens
     * via the CrucibleMcpBridge calling CodePlane's eval harness.
     *
     * @param current     Current soul manifest
     * @param variant     Proposed variant
     * @param scenarioResults Results from running scenarios (scenario ID → passed)
     * @return Evaluation result with fitness score
     */
    public EvaluationResult evaluate(SoulManifest current, SoulVariant variant,
                                       Map<String, Boolean> scenarioResults) {
        // Compute personality coherence from manifest diff
        var diff = diffManifests(current, variant);
        double coherence = 1.0 - diff.estimatedDivergence();

        // Compute capability gain from scenario results
        int passed = (int) scenarioResults.values().stream().filter(b -> b).count();
        int total = scenarioResults.size();
        double capabilityGain = total > 0 ? (double) passed / total : 0.0;

        // Detect regressions (scenarios that were expected to pass but didn't)
        var regressions = new ArrayList<String>();
        var improvements = new ArrayList<String>();
        for (var entry : scenarioResults.entrySet()) {
            var scenario = scenarios.stream()
                .filter(s -> s.id().equals(entry.getKey()))
                .findFirst();
            if (scenario.isPresent()) {
                if (!entry.getValue() && "personality".equals(scenario.get().category())) {
                    regressions.add(entry.getKey());
                }
                if (entry.getValue() && "capability".equals(scenario.get().category())) {
                    improvements.add(entry.getKey());
                }
            }
        }
        double regressionScore = total > 0 ? (double) regressions.size() / total : 0.0;

        // Vitality impact from genome changes
        double vitalityImpact = 0.0;
        if (variant.proposedGenome() != null && current.genome() != null) {
            vitalityImpact = estimateVitalityImpact(current.genome(), variant.proposedGenome());
        }

        // Composite fitness
        double fitness = (coherence * 0.4) + (capabilityGain * 0.3)
            - (regressionScore * 0.2) + (vitalityImpact * 0.1);

        return new EvaluationResult(variant.variantId(), coherence, capabilityGain,
            regressionScore, vitalityImpact, fitness, total, passed,
            regressions, improvements, Instant.now());
    }

    /** Compare current manifest with a variant to detect changes. */
    public ManifestDiff diffManifests(SoulManifest current, SoulVariant variant) {
        boolean identityChanged = variant.proposedResidentIdentity() != null
            && !variant.proposedResidentIdentity().equals(current.residentIdentity());

        boolean genomeChanged = variant.proposedGenome() != null
            && !variant.proposedGenome().name().equals(current.genome().name());

        boolean fragmentsChanged = variant.proposedFragments() != null
            && variant.proposedFragments().size() != current.soulFragments().size();

        int added = 0, removed = 0;
        if (variant.proposedFragments() != null) {
            var currentIds = current.soulFragments().stream()
                .map(SoulFragment::id).collect(Collectors.toSet());
            var variantIds = variant.proposedFragments().stream()
                .map(SoulFragment::id).collect(Collectors.toSet());
            added = (int) variantIds.stream().filter(id -> !currentIds.contains(id)).count();
            removed = (int) currentIds.stream().filter(id -> !variantIds.contains(id)).count();
        }

        double divergence = 0.0;
        if (identityChanged) divergence += 0.4;
        if (genomeChanged) divergence += 0.3;
        if (fragmentsChanged) divergence += 0.2;
        if (variant.level() >= 2) divergence += 0.1; // LoRA/substrate changes add divergence
        divergence = Math.min(1.0, divergence);

        return new ManifestDiff(identityChanged, genomeChanged, fragmentsChanged,
            added, removed, divergence);
    }

    /** Rank variants by fitness (highest first). */
    public List<EvaluationResult> rank(List<EvaluationResult> results) {
        return results.stream()
            .sorted(Comparator.comparingDouble(EvaluationResult::fitness).reversed())
            .toList();
    }

    /** Boltzmann tournament selection (stochastic, temperature-controlled). */
    public EvaluationResult boltzmannSelect(List<EvaluationResult> results, double temperature) {
        if (results.isEmpty()) throw new IllegalArgumentException("No results to select from");
        if (results.size() == 1) return results.get(0);

        double[] weights = new double[results.size()];
        double maxFitness = results.stream().mapToDouble(EvaluationResult::fitness).max().orElse(0);

        for (int i = 0; i < results.size(); i++) {
            weights[i] = Math.exp((results.get(i).fitness() - maxFitness) / temperature);
        }
        double totalWeight = 0;
        for (double w : weights) totalWeight += w;

        double rand = Math.random() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (rand <= cumulative) return results.get(i);
        }
        return results.getLast();
    }

    private double estimateVitalityImpact(GenomeProfile current, GenomeProfile proposed) {
        // Simplified: compare tank baselines
        if (current.equals(proposed)) return 0.0;
        // Different genome = some vitality impact
        return -0.1; // Conservative: genome changes are mildly negative by default
    }
}
