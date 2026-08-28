package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SearchSpace implementation that bridges Wyrdsekai's soul Crucible
 * with CodeZaiku's evolutionary search framework ( &sect;5.1).
 *
 * Explores the space of soul parameters: genome sensitivities, coupling
 * coefficients, baselines, fragment selection, and (at Level 2/3) adapter
 * URIs or model choices.
 *
 * Type parameters:
 *   P = BehavioralEvaluator.SoulVariant (what gets evolved)
 *   R = BehavioralEvaluator.EvaluationResult (what gets measured)
 *
 * The search space is constrained by variant level:
 * - Level 1 (prompt): genome, identity, fragments (free, reversible)
 * - Level 2 (LoRA): adapter URI (expensive, requires training)
 * - Level 3 (model): model ID (most expensive, requires availability)
 *
 * @see SearchSpace
 * @see BehavioralEvaluator
 */
public class SoulSearchSpace
    implements SearchSpace<BehavioralEvaluator.SoulVariant, BehavioralEvaluator.EvaluationResult> {

    /** Candidate model IDs for Level 3 evolution. */
    private static final List<String> CANDIDATE_MODELS = List.of(
        "qwen2.5:3b", "qwen2.5:7b", "qwen2.5:14b",
        "llama3.2:3b", "llama3.1:8b",
        "gemma2:9b", "mistral:7b"
    );

    /** Fragment selection strategies. */
    private static final List<String> FRAGMENT_STRATEGIES = List.of(
        "top-k", "random-k", "thematic"
    );

    private final SoulManifest currentManifest;
    private final BehavioralEvaluator evaluator;
    private final List<BehavioralEvaluator.BehavioralScenario> scenarios;
    private final int variantLevel;
    private final Function<BehavioralEvaluator.SoulVariant, Map<String, Boolean>> scenarioRunner;

    /**
     * @param currentManifest The baseline soul being evolved
     * @param evaluator       Runs structural evaluation against scenarios
     * @param scenarios       Registered behavioral test scenarios
     * @param variantLevel    1 (prompt), 2 (LoRA), 3 (model)
     * @param scenarioRunner  Runs scenarios against a variant, returns scenario ID to pass/fail
     */
    public SoulSearchSpace(SoulManifest currentManifest,
                           BehavioralEvaluator evaluator,
                           List<BehavioralEvaluator.BehavioralScenario> scenarios,
                           int variantLevel,
                           Function<BehavioralEvaluator.SoulVariant, Map<String, Boolean>> scenarioRunner) {
        if (variantLevel < 1 || variantLevel > 3) {
            throw new IllegalArgumentException("variantLevel must be 1, 2, or 3; got " + variantLevel);
        }
        this.currentManifest = Objects.requireNonNull(currentManifest, "currentManifest");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.scenarios = List.copyOf(scenarios);
        this.variantLevel = variantLevel;
        this.scenarioRunner = Objects.requireNonNull(scenarioRunner, "scenarioRunner");
    }

    @Override
    public String name() {
        return "crucible-soul-search";
    }

    @Override
    public SpaceDescription describe() {
        var dims = new ArrayList<SpaceDescription.Dimension>();

        // Resident identity (freeform text, represented as discrete "strategy" dimension)
        dims.add(new SpaceDescription.Dimension.Discrete(
            "identity-strategy", "How to generate the resident identity text",
            List.of("keep", "abbreviate", "elaborate", "rephrase"), "keep"));

        // Fragment count
        dims.add(new SpaceDescription.Dimension.IntRange(
            "fragment-count", "Number of soul fragments to include (1-9)",
            1, 9, 3));

        // Fragment selection strategy
        dims.add(new SpaceDescription.Dimension.Discrete(
            "fragment-strategy", "How fragments are selected for context",
            FRAGMENT_STRATEGIES, "top-k"));

        // 12 genome sensitivity multipliers
        for (String tank : VitalitySnapshot.TANK_NAMES) {
            dims.add(new SpaceDescription.Dimension.Continuous(
                "sensitivity-" + tank,
                "Sensitivity multiplier for " + tank + " tank",
                0.3, 1.7, 1.0));
        }

        // Genome coupling adjustments (represented as a continuous range)
        dims.add(new SpaceDescription.Dimension.Continuous(
            "coupling-adjustment", "Global coupling strength adjustment",
            -0.5, 0.5, 0.0));

        // Genome baseline shifts
        for (String tank : VitalitySnapshot.TANK_NAMES) {
            dims.add(new SpaceDescription.Dimension.Continuous(
                "baseline-shift-" + tank,
                "Baseline shift for " + tank + " tank",
                -0.3, 0.3, 0.0));
        }

        return new SpaceDescription(
            "crucible-soul-search",
            List.copyOf(dims),
            "0.4*coherence + 0.3*capability - 0.2*regression + 0.1*vitality (higher = better)",
            "Explores soul variant parameters for Crucible growth. " +
            "Level " + variantLevel + " search over genome, identity, and fragment parameters."
        );
    }

    @Override
    public BehavioralEvaluator.SoulVariant randomPoint(Random rng) {
        String variantId = "rnd-" + UUID.randomUUID().toString().substring(0, 8);

        return switch (variantLevel) {
            case 1 -> randomLevel1(variantId, rng);
            case 2 -> randomLevel2(variantId, rng);
            case 3 -> randomLevel3(variantId, rng);
            default -> throw new IllegalStateException("Invalid level: " + variantLevel);
        };
    }

    @Override
    public BehavioralEvaluator.SoulVariant crossover(
            BehavioralEvaluator.SoulVariant parent1,
            BehavioralEvaluator.SoulVariant parent2,
            Random rng) {
        String variantId = "xo-" + UUID.randomUUID().toString().substring(0, 8);

        return switch (variantLevel) {
            case 1 -> crossoverLevel1(variantId, parent1, parent2, rng);
            case 2 -> crossoverLevel2(variantId, parent1, parent2, rng);
            case 3 -> crossoverLevel3(variantId, parent1, parent2, rng);
            default -> throw new IllegalStateException("Invalid level: " + variantLevel);
        };
    }

    @Override
    public BehavioralEvaluator.SoulVariant mutate(
            BehavioralEvaluator.SoulVariant point, double mutationRate, Random rng) {
        if (variantLevel != 1) {
            // Level 2 (LoRA) mutation is a training operation, not a parameter tweak.
            // Level 3 (model) mutation requires downloading a new model.
            // Both are no-ops in parameter space.
            return point;
        }
        if (mutationRate <= 0.0) {
            return point;
        }

        String variantId = "mut-" + UUID.randomUUID().toString().substring(0, 8);
        return mutateLevel1(variantId, point, mutationRate, rng);
    }

    @Override
    public BehavioralEvaluator.EvaluationResult evaluate(BehavioralEvaluator.SoulVariant point) {
        Map<String, Boolean> scenarioResults = scenarioRunner.apply(point);
        return evaluator.evaluate(currentManifest, point, scenarioResults);
    }

    @Override
    public double fitness(BehavioralEvaluator.EvaluationResult result) {
        return result.fitness();
    }

    @Override
    public Map<String, Object> serialize(BehavioralEvaluator.SoulVariant point) {
        var map = new LinkedHashMap<String, Object>();
        map.put("variantId", point.variantId());
        map.put("level", point.level());
        map.put("description", point.description());
        map.put("createdAt", point.createdAt().toString());

        if (point.proposedResidentIdentity() != null) {
            map.put("proposedResidentIdentity", point.proposedResidentIdentity());
        }
        if (point.proposedFragments() != null) {
            var fragmentIds = point.proposedFragments().stream()
                .map(SoulFragment::id)
                .toList();
            map.put("fragmentIds", fragmentIds);
            // Also store full fragment data for deserialization
            var fragmentData = new ArrayList<Map<String, Object>>();
            for (var frag : point.proposedFragments()) {
                var fd = new LinkedHashMap<String, Object>();
                fd.put("id", frag.id());
                fd.put("category", frag.category());
                fd.put("label", frag.label());
                fd.put("text", frag.text());
                fd.put("formative", frag.formative());
                if (frag.embeddingModel() != null) {
                    fd.put("embeddingModel", frag.embeddingModel());
                }
                fragmentData.add(fd);
            }
            map.put("fragments", fragmentData);
        }
        if (point.proposedGenome() != null) {
            var genomeMap = new LinkedHashMap<String, Object>();
            genomeMap.put("name", point.proposedGenome().name());
            genomeMap.put("sensitivity", new LinkedHashMap<>(point.proposedGenome().sensitivity()));
            genomeMap.put("coupling", new LinkedHashMap<>(point.proposedGenome().coupling()));
            genomeMap.put("baselines", new LinkedHashMap<>(point.proposedGenome().baselines()));
            genomeMap.put("decayRates", new LinkedHashMap<>(point.proposedGenome().decayRates()));
            map.put("genome", genomeMap);
        }
        if (point.adapterUri() != null) {
            map.put("adapterUri", point.adapterUri());
        }
        if (point.proposedModelId() != null) {
            map.put("proposedModelId", point.proposedModelId());
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    @Override
    public BehavioralEvaluator.SoulVariant deserialize(Map<String, Object> data) {
        String variantId = (String) data.get("variantId");
        int level = ((Number) data.get("level")).intValue();
        String description = (String) data.get("description");
        Instant createdAt = Instant.parse((String) data.get("createdAt"));

        String identity = (String) data.get("proposedResidentIdentity");

        List<SoulFragment> fragments = null;
        if (data.containsKey("fragments")) {
            var fragmentDataList = (List<Map<String, Object>>) data.get("fragments");
            fragments = new ArrayList<>();
            for (var fd : fragmentDataList) {
                fragments.add(SoulFragment.unembedded(
                    (String) fd.get("id"),
                    (String) fd.getOrDefault("category", "personality"),
                    (String) fd.getOrDefault("label", ""),
                    (String) fd.get("text")
                ));
            }
        }

        GenomeProfile genome = null;
        if (data.containsKey("genome")) {
            var gm = (Map<String, Object>) data.get("genome");
            genome = new GenomeProfile(
                (String) gm.get("name"),
                toDoubleMap((Map<String, ?>) gm.get("sensitivity")),
                toDoubleMap((Map<String, ?>) gm.get("coupling")),
                toDoubleMap((Map<String, ?>) gm.get("baselines")),
                toDoubleMap((Map<String, ?>) gm.get("decayRates"))
            );
        }

        String adapterUri = (String) data.get("adapterUri");
        String modelId = (String) data.get("proposedModelId");

        return new BehavioralEvaluator.SoulVariant(
            variantId, level, description,
            identity, fragments, genome,
            adapterUri, modelId, createdAt
        );
    }

    // ── Level 1: Prompt-level evolution ──

    private BehavioralEvaluator.SoulVariant randomLevel1(String variantId, Random rng) {
        // Randomize genome
        GenomeProfile genome = randomGenome("rnd-genome-" + variantId.substring(4), rng);

        // Keep identity from current manifest
        String identity = currentManifest.residentIdentity();

        // Shuffle fragments: take a random subset of current fragments
        List<SoulFragment> currentFragments = currentManifest.soulFragments();
        List<SoulFragment> shuffled = new ArrayList<>(currentFragments);
        Collections.shuffle(shuffled, rng);
        int count = currentFragments.isEmpty()
            ? 0
            : 1 + rng.nextInt(Math.min(9, currentFragments.size()));
        List<SoulFragment> selectedFragments = shuffled.subList(0, Math.min(count, shuffled.size()));

        return BehavioralEvaluator.SoulVariant.level1(
            variantId, "Random Level 1 variant",
            identity, List.copyOf(selectedFragments), genome);
    }

    private BehavioralEvaluator.SoulVariant randomLevel2(String variantId, Random rng) {
        // Generate a random adapter URI placeholder
        String adapterId = "adapter://random-" + UUID.randomUUID().toString().substring(0, 8);
        return BehavioralEvaluator.SoulVariant.level2(
            variantId, "Random Level 2 variant", adapterId);
    }

    private BehavioralEvaluator.SoulVariant randomLevel3(String variantId, Random rng) {
        String modelId = CANDIDATE_MODELS.get(rng.nextInt(CANDIDATE_MODELS.size()));
        return BehavioralEvaluator.SoulVariant.level3(
            variantId, "Random Level 3 variant", modelId);
    }

    // ── Level 1: Crossover ──

    private BehavioralEvaluator.SoulVariant crossoverLevel1(
            String variantId,
            BehavioralEvaluator.SoulVariant p1,
            BehavioralEvaluator.SoulVariant p2,
            Random rng) {
        // Identity from parent 1
        String identity = p1.proposedResidentIdentity() != null
            ? p1.proposedResidentIdentity()
            : currentManifest.residentIdentity();

        // Genome: blend sensitivities, baselines, decay; union couplings
        GenomeProfile genome = blendGenomes(p1.proposedGenome(), p2.proposedGenome(), rng);

        // Fragments: merge from both parents, deduplicated by ID
        List<SoulFragment> fragments = mergeFragments(
            p1.proposedFragments(), p2.proposedFragments());

        return BehavioralEvaluator.SoulVariant.level1(
            variantId, "Crossover of " + p1.variantId() + " x " + p2.variantId(),
            identity, fragments, genome);
    }

    private BehavioralEvaluator.SoulVariant crossoverLevel2(
            String variantId,
            BehavioralEvaluator.SoulVariant p1,
            BehavioralEvaluator.SoulVariant p2,
            Random rng) {
        String adapter = rng.nextBoolean() ? p1.adapterUri() : p2.adapterUri();
        return BehavioralEvaluator.SoulVariant.level2(
            variantId, "Crossover of " + p1.variantId() + " x " + p2.variantId(),
            adapter);
    }

    private BehavioralEvaluator.SoulVariant crossoverLevel3(
            String variantId,
            BehavioralEvaluator.SoulVariant p1,
            BehavioralEvaluator.SoulVariant p2,
            Random rng) {
        String model = rng.nextBoolean() ? p1.proposedModelId() : p2.proposedModelId();
        return BehavioralEvaluator.SoulVariant.level3(
            variantId, "Crossover of " + p1.variantId() + " x " + p2.variantId(),
            model);
    }

    // ── Level 1: Mutation ──

    private BehavioralEvaluator.SoulVariant mutateLevel1(
            String variantId,
            BehavioralEvaluator.SoulVariant point,
            double rate,
            Random rng) {
        GenomeProfile original = point.proposedGenome() != null
            ? point.proposedGenome()
            : currentManifest.genome();

        // Gaussian perturbation of sensitivities
        var newSens = new LinkedHashMap<String, Double>();
        for (var entry : original.sensitivity().entrySet()) {
            double perturbation = rng.nextGaussian() * rate * 0.3;
            double newVal = entry.getValue() + perturbation;
            newSens.put(entry.getKey(), clamp(newVal, 0.3, 1.7));
        }

        // Gaussian perturbation of baselines
        var newBases = new LinkedHashMap<String, Double>();
        for (var entry : original.baselines().entrySet()) {
            double perturbation = rng.nextGaussian() * rate * 0.1;
            double newVal = entry.getValue() + perturbation;
            newBases.put(entry.getKey(), clamp(newVal, 0.2, 0.8));
        }

        // Gaussian perturbation of decay rates
        var newDecay = new LinkedHashMap<String, Double>();
        for (var entry : original.decayRates().entrySet()) {
            double perturbation = rng.nextGaussian() * rate * 0.05;
            double newVal = entry.getValue() + perturbation;
            newDecay.put(entry.getKey(), clamp(newVal, 0.05, 0.4));
        }

        // Coupling: occasionally add/remove a coupling
        var newCoupling = new LinkedHashMap<>(original.coupling());
        if (rng.nextDouble() < rate) {
            var tankList = VitalitySnapshot.TANK_NAMES;
            String src = tankList.get(rng.nextInt(tankList.size()));
            String tgt = tankList.get(rng.nextInt(tankList.size()));
            if (!src.equals(tgt)) {
                String key = src + "->" + tgt;
                if (newCoupling.containsKey(key) && rng.nextBoolean()) {
                    newCoupling.remove(key);
                } else {
                    newCoupling.put(key, clamp(rng.nextGaussian() * 0.3, -0.5, 0.5));
                }
            }
        }

        GenomeProfile mutatedGenome = new GenomeProfile(
            "mut-" + variantId.substring(4), newSens, newCoupling, newBases, newDecay);

        // Randomly swap one fragment if we have fragments
        List<SoulFragment> fragments = point.proposedFragments() != null
            ? new ArrayList<>(point.proposedFragments())
            : new ArrayList<>(currentManifest.soulFragments());

        if (fragments.size() > 1 && rng.nextDouble() < rate) {
            int i = rng.nextInt(fragments.size());
            int j = rng.nextInt(fragments.size());
            if (i != j) {
                Collections.swap(fragments, i, j);
            }
        }

        String identity = point.proposedResidentIdentity() != null
            ? point.proposedResidentIdentity()
            : currentManifest.residentIdentity();

        return BehavioralEvaluator.SoulVariant.level1(
            variantId, "Mutated from " + point.variantId(),
            identity, List.copyOf(fragments), mutatedGenome);
    }

    // ── Helpers ──

    /**
     * Generate a random genome using the provided RNG for reproducibility.
     * Mirrors GenomeProfile.randomized() but accepts a seeded Random.
     */
    private GenomeProfile randomGenome(String name, Random rng) {
        var sens = new LinkedHashMap<String, Double>();
        var bases = new LinkedHashMap<String, Double>();
        var decay = new LinkedHashMap<String, Double>();

        for (var tank : VitalitySnapshot.TANK_NAMES) {
            sens.put(tank, 0.3 + rng.nextDouble() * 1.4);   // 0.3-1.7
            bases.put(tank, 0.3 + rng.nextDouble() * 0.4);   // 0.3-0.7
            decay.put(tank, 0.05 + rng.nextDouble() * 0.35); // 0.05-0.4
        }

        // Generate 1-3 random coupling effects
        var coupling = new LinkedHashMap<String, Double>();
        int couplings = 1 + rng.nextInt(3);
        var tankList = VitalitySnapshot.TANK_NAMES;
        for (int i = 0; i < couplings; i++) {
            String src = tankList.get(rng.nextInt(tankList.size()));
            String tgt = tankList.get(rng.nextInt(tankList.size()));
            if (!src.equals(tgt)) {
                coupling.put(src + "->" + tgt, -0.5 + rng.nextDouble() * 1.0);
            }
        }

        return new GenomeProfile(name, sens, coupling, bases, decay);
    }

    /**
     * Blend two genomes by averaging sensitivities, baselines, and decay rates.
     * Couplings are unioned.
     */
    private GenomeProfile blendGenomes(GenomeProfile g1, GenomeProfile g2, Random rng) {
        GenomeProfile a = g1 != null ? g1 : currentManifest.genome();
        GenomeProfile b = g2 != null ? g2 : currentManifest.genome();

        var sens = new LinkedHashMap<String, Double>();
        var bases = new LinkedHashMap<String, Double>();
        var decay = new LinkedHashMap<String, Double>();

        for (var tank : VitalitySnapshot.TANK_NAMES) {
            double alpha = rng.nextDouble();
            sens.put(tank, a.sensitivity().getOrDefault(tank, 1.0) * alpha
                         + b.sensitivity().getOrDefault(tank, 1.0) * (1 - alpha));
            bases.put(tank, a.baselines().getOrDefault(tank, 0.5) * alpha
                          + b.baselines().getOrDefault(tank, 0.5) * (1 - alpha));
            decay.put(tank, a.decayRates().getOrDefault(tank, 0.15) * alpha
                          + b.decayRates().getOrDefault(tank, 0.15) * (1 - alpha));
        }

        // Union couplings from both parents
        var coupling = new LinkedHashMap<String, Double>();
        coupling.putAll(a.coupling());
        for (var entry : b.coupling().entrySet()) {
            coupling.merge(entry.getKey(), entry.getValue(),
                (v1, v2) -> (v1 + v2) / 2.0);
        }

        return new GenomeProfile("blend-" + UUID.randomUUID().toString().substring(0, 8),
            sens, coupling, bases, decay);
    }

    /**
     * Merge fragments from two parents, deduplicated by ID.
     * When both parents have the same fragment ID, keep the first parent's version.
     */
    private List<SoulFragment> mergeFragments(List<SoulFragment> frags1, List<SoulFragment> frags2) {
        var seen = new LinkedHashMap<String, SoulFragment>();

        if (frags1 != null) {
            for (var f : frags1) {
                seen.putIfAbsent(f.id(), f);
            }
        }
        if (frags2 != null) {
            for (var f : frags2) {
                seen.putIfAbsent(f.id(), f);
            }
        }

        // If no fragments from either parent, fall back to current manifest
        if (seen.isEmpty()) {
            return List.copyOf(currentManifest.soulFragments());
        }

        return List.copyOf(seen.values());
    }

    /** Convert a map with Number values to Map<String, Double>. */
    private static Map<String, Double> toDoubleMap(Map<String, ?> source) {
        var result = new LinkedHashMap<String, Double>();
        for (var entry : source.entrySet()) {
            result.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
        }
        return result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
