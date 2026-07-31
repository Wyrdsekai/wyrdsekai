package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Adapts Wyrdsekai's BehavioralEvaluator to CodePlane's SolutionEvaluator
 * contract ( &sect;5.2).
 *
 * CodePlane's SolutionEvaluator works with String candidates (serialized solutions)
 * and a generic EvaluationContext. This adapter deserializes the candidate JSON
 * into a SoulVariant, runs BehavioralEvaluator.evaluate(), and maps the result
 * back to CodePlane's Result format.
 *
 * Since Wyrdsekai and CodePlane are sibling projects that cannot directly depend
 * on each other, this adapter uses local record types that mirror CodePlane's
 * SolutionEvaluator interface.
 *
 * @see BehavioralEvaluator
 * @see SoulSearchSpace
 */
public class CrucibleEvaluatorAdapter {

    /**
     * Result from evaluating a candidate solution.
     * Mirrors CodePlane's SolutionEvaluator.EvaluationResult.
     */
    public record EvaluationResult(
        double fitness,
        boolean valid,
        String violations,
        Map<String, Double> metrics
    ) {}

    /**
     * Context for evaluation.
     * Mirrors CodePlane's SolutionEvaluator.EvaluationContext.
     */
    public record EvaluationContext(
        String taskDescription,
        String requirements,
        String testCases,
        Map<String, String> metadata
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final BehavioralEvaluator evaluator;
    private final SoulManifest currentManifest;
    private final List<BehavioralEvaluator.BehavioralScenario> scenarios;
    private final Function<BehavioralEvaluator.SoulVariant, Map<String, Boolean>> scenarioRunner;

    /**
     * @param evaluator       The behavioral evaluator
     * @param currentManifest The baseline soul manifest
     * @param scenarios       Behavioral test scenarios
     * @param scenarioRunner  Runs scenarios against a variant, returns scenario ID to pass/fail
     */
    public CrucibleEvaluatorAdapter(
            BehavioralEvaluator evaluator,
            SoulManifest currentManifest,
            List<BehavioralEvaluator.BehavioralScenario> scenarios,
            Function<BehavioralEvaluator.SoulVariant, Map<String, Boolean>> scenarioRunner) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.currentManifest = Objects.requireNonNull(currentManifest, "currentManifest");
        this.scenarios = List.copyOf(scenarios);
        this.scenarioRunner = Objects.requireNonNull(scenarioRunner, "scenarioRunner");
    }

    /**
     * Evaluate a candidate solution (serialized SoulVariant JSON).
     *
     * @param candidate JSON string representing a serialized SoulVariant (from SoulSearchSpace.serialize())
     * @param context   Evaluation context (task description, requirements, etc.)
     * @return Evaluation result with fitness, validity, violations, and metrics
     */
    public EvaluationResult evaluate(String candidate, EvaluationContext context) {
        BehavioralEvaluator.SoulVariant variant;
        try {
            Map<String, Object> data = MAPPER.readValue(candidate,
                new TypeReference<Map<String, Object>>() {});
            variant = deserializeVariant(data);
        } catch (Exception e) {
            return new EvaluationResult(
                0.0, false,
                "Failed to deserialize candidate: " + e.getMessage(),
                Map.of());
        }

        // Run scenarios
        Map<String, Boolean> scenarioResults = scenarioRunner.apply(variant);

        // Evaluate
        var result = evaluator.evaluate(currentManifest, variant, scenarioResults);

        // Map to adapter result
        var metrics = new LinkedHashMap<String, Double>();
        metrics.put("coherence", result.personalityCoherence());
        metrics.put("capabilityGain", result.capabilityGain());
        metrics.put("regressionScore", result.regressionScore());
        metrics.put("vitalityImpact", result.vitalityImpact());

        String violations = result.regressions().isEmpty()
            ? ""
            : String.join("; ", result.regressions());

        return new EvaluationResult(
            result.fitness(),
            result.recommended(),
            violations,
            Map.copyOf(metrics));
    }

    /**
     * Deserialize a SoulVariant from a map. Uses the same format as
     * SoulSearchSpace.serialize() for compatibility.
     */
    @SuppressWarnings("unchecked")
    private BehavioralEvaluator.SoulVariant deserializeVariant(Map<String, Object> data) {
        String variantId = (String) data.get("variantId");
        int level = ((Number) data.get("level")).intValue();
        String description = (String) data.get("description");
        Instant createdAt = data.containsKey("createdAt")
            ? Instant.parse((String) data.get("createdAt"))
            : Instant.now();

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
            adapterUri, modelId, createdAt);
    }

    private static Map<String, Double> toDoubleMap(Map<String, ?> source) {
        var result = new LinkedHashMap<String, Double>();
        for (var entry : source.entrySet()) {
            result.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
        }
        return result;
    }
}
