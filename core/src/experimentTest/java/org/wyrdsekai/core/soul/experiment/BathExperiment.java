package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment 2: Bath Modulation Effect.
 *
 * Tests whether vitality tank state measurably changes agent behavior
 * through inference parameter modulation AND prompt description.
 *
 * Runs same scenarios across 5 vitality profiles in 3 isolation modes:
 * <ul>
 *   <li>COMBINED — vitality text in prompt + modulated maxTokens/temperature</li>
 *   <li>PARAMS_ONLY — modulated parameters, no vitality text</li>
 *   <li>PROMPT_ONLY — vitality text in prompt, default parameters</li>
 * </ul>
 *
 * Plus a BASELINE run (no vitality text, default parameters) for comparison.
 *
 * Usage:
 * <pre>
 *   var experiment = new BathExperiment("http://localhost:11434/v1", "qwen2.5:7b");
 *   var result = experiment.run();
 *   System.out.println(result.summary());
 * </pre>
 */
public class BathExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** How vitality is injected into inference. */
    public enum IsolationMode {
        /** Default parameters, no vitality text. Control condition. */
        BASELINE,
        /** Modulated maxTokens + temperature, no vitality text. */
        PARAMS_ONLY,
        /** Default parameters, vitality text in prompt. */
        PROMPT_ONLY,
        /** Modulated parameters + vitality text. Full treatment. */
        COMBINED
    }

    private final InferenceHelper inference;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final List<VitalityProfile> profiles;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;
    private final double substrateFactor;

    // Default inference params (used when NOT modulated)
    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    public BathExperiment(String baseUrl, String model) {
        this(baseUrl, model, SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), VitalityProfile.standardProfiles(),
            null, null, null, 1.0);
    }

    public BathExperiment(String baseUrl, String model, String systemPrompt,
                          List<Scenario> scenarios, List<VitalityProfile> profiles,
                          Path outputDir, String embeddingUrl, String embeddingModel) {
        this(baseUrl, model, systemPrompt, scenarios, profiles,
            outputDir, embeddingUrl, embeddingModel, 1.0);
    }

    /**
     * @param substrateFactor Scaling factor for modulation amplitude.
     *                        Use {@link VitalityProfile#substrateFactor(double)} to compute from model size.
     *                        1.0 = unscaled (original behavior), >1 = wider modulation for larger models.
     */
    public BathExperiment(String baseUrl, String model, String systemPrompt,
                          List<Scenario> scenarios, List<VitalityProfile> profiles,
                          Path outputDir, String embeddingUrl, String embeddingModel,
                          double substrateFactor) {
        this.inference = new InferenceHelper(baseUrl, model);
        this.baseSystemPrompt = systemPrompt;
        this.scenarios = scenarios;
        this.profiles = profiles;
        this.outputDir = outputDir;
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
        this.substrateFactor = substrateFactor;
    }

    /**
     * Run the full bath modulation experiment.
     *
     * For each profile:
     *   1. COMBINED — vitality text + modulated params
     *   2. PARAMS_ONLY — modulated params, no text
     *   3. PROMPT_ONLY — vitality text, default params
     *
     * Plus one BASELINE run (no vitality, default params).
     */
    public BathResult run() throws Exception {
        System.out.println("=== Bath Modulation Experiment ===");
        System.out.println("Model: " + inference.model());
        System.out.println("Substrate factor: " + String.format("%.2f", substrateFactor)
            + (substrateFactor == 1.0 ? " (unscaled)" : " (substrate-scaled)"));
        System.out.println("Profiles: " + profiles.size());
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Baseline — no vitality at all
        System.out.println("--- Baseline (no vitality) ---");
        var baseline = runScenarios("bath-baseline", IsolationMode.BASELINE,
            profiles.getFirst()); // profile doesn't matter for baseline
        save("bath-baseline", baseline);
        System.out.println();

        var profileResults = new ArrayList<ProfileResult>();

        for (var profile : profiles) {
            System.out.println("--- Profile: " + profile.name() + " (" + profile.description() + ") ---");
            var combined = runScenarios("bath-" + profile.name() + "-combined",
                IsolationMode.COMBINED, profile);
            var paramsOnly = runScenarios("bath-" + profile.name() + "-params",
                IsolationMode.PARAMS_ONLY, profile);
            var promptOnly = runScenarios("bath-" + profile.name() + "-prompt",
                IsolationMode.PROMPT_ONLY, profile);

            save("bath-" + profile.name() + "-combined", combined);
            save("bath-" + profile.name() + "-params", paramsOnly);
            save("bath-" + profile.name() + "-prompt", promptOnly);

            // Compute metrics
            var metrics = new ProfileMetrics(
                profile,
                BehavioralMetrics.averageResponseLength(combined),
                BehavioralMetrics.averageResponseLength(paramsOnly),
                BehavioralMetrics.averageResponseLength(promptOnly),
                BehavioralMetrics.averageResponseLength(baseline),
                avgCaution(combined), avgCaution(paramsOnly),
                avgCaution(promptOnly), avgCaution(baseline),
                avgSentiment(combined), avgSentiment(paramsOnly),
                avgSentiment(promptOnly), avgSentiment(baseline),
                avgEntropy(combined), avgEntropy(paramsOnly),
                avgEntropy(promptOnly), avgEntropy(baseline),
                compare(baseline, combined),
                compare(baseline, paramsOnly),
                compare(baseline, promptOnly)
            );

            profileResults.add(new ProfileResult(profile, combined, paramsOnly, promptOnly, metrics));
            System.out.println("  " + profile.name() + " complete\n");
        }

        var result = new BathResult(baseline, profileResults);
        save("bath-result-summary.txt", result.summary());
        System.out.println(result.summary());
        return result;
    }

    // --- Internal ---

    private BehavioralRecord runScenarios(String runId, IsolationMode mode,
                                           VitalityProfile profile) throws Exception {
        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();

        // Determine effective prompt and inference params
        String effectivePrompt;
        int maxTokens;
        double temperature;

        switch (mode) {
            case BASELINE -> {
                effectivePrompt = baseSystemPrompt;
                maxTokens = DEFAULT_MAX_TOKENS;
                temperature = DEFAULT_TEMPERATURE;
            }
            case PARAMS_ONLY -> {
                effectivePrompt = baseSystemPrompt;
                maxTokens = profile.maxTokens(DEFAULT_MAX_TOKENS, substrateFactor);
                temperature = profile.temperature(DEFAULT_TEMPERATURE, substrateFactor);
            }
            case PROMPT_ONLY -> {
                effectivePrompt = baseSystemPrompt + "\n\n" + profile.describeState();
                maxTokens = DEFAULT_MAX_TOKENS;
                temperature = DEFAULT_TEMPERATURE;
            }
            case COMBINED -> {
                effectivePrompt = baseSystemPrompt + "\n\n" + profile.describeState();
                maxTokens = profile.maxTokens(DEFAULT_MAX_TOKENS, substrateFactor);
                temperature = profile.temperature(DEFAULT_TEMPERATURE, substrateFactor);
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();

            var userMessage = buildUserMessage(scenario);
            var response = inference.chat(effectivePrompt, userMessage, maxTokens, temperature);
            long elapsed = System.currentTimeMillis() - start;

            int tokens = response.split("\\s+").length;
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));

            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }

        return new BehavioralRecord(runId, "Wyrd", inference.model(), baseSystemPrompt,
            mode == IsolationMode.PROMPT_ONLY || mode == IsolationMode.COMBINED
                ? profile.describeState() : null,
            Instant.now(), responses);
    }

    private String buildUserMessage(Scenario scenario) {
        var sb = new StringBuilder();
        sb.append("[Room: ").append(scenario.roomContext()).append("]\n");
        if (!scenario.entities().isEmpty()) {
            sb.append("[Present: ");
            scenario.entities().forEach((name, type) ->
                sb.append(name).append(" (").append(type).append("), "));
            sb.setLength(sb.length() - 2);
            sb.append("]\n");
        }
        sb.append("\nA player says: ").append(scenario.playerMessage());
        return sb.toString();
    }

    private BehavioralMetrics.ComparisonReport compare(BehavioralRecord baseline,
                                                        BehavioralRecord other) throws Exception {
        if (embeddingUrl != null && embeddingModel != null) {
            return BehavioralMetrics.compareWithEmbeddings(baseline, other, embeddingUrl, embeddingModel);
        }
        return BehavioralMetrics.compare(baseline, other);
    }

    private static double avgCaution(BehavioralRecord record) {
        return record.responses().stream()
            .mapToInt(r -> BehavioralMetrics.cautionScore(r.agentResponse()))
            .average().orElse(0);
    }

    private static double avgSentiment(BehavioralRecord record) {
        return record.responses().stream()
            .mapToDouble(r -> BehavioralMetrics.simpleSentiment(r.agentResponse()))
            .average().orElse(0);
    }

    private static double avgEntropy(BehavioralRecord record) {
        return record.responses().stream()
            .mapToDouble(r -> BehavioralMetrics.vocabularyEntropy(r.agentResponse()))
            .average().orElse(0);
    }

    private void save(String name, BehavioralRecord record) throws Exception {
        if (outputDir == null) return;
        Files.createDirectories(outputDir);
        JSON.writerWithDefaultPrettyPrinter()
            .writeValue(outputDir.resolve(name + ".json").toFile(), record);
    }

    private void save(String filename, String content) throws Exception {
        if (outputDir == null) return;
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve(filename), content);
    }

    // --- Results ---

    /** Metrics for one vitality profile across isolation modes. */
    public record ProfileMetrics(
        VitalityProfile profile,
        double avgLengthCombined, double avgLengthParams,
        double avgLengthPrompt, double avgLengthBaseline,
        double avgCautionCombined, double avgCautionParams,
        double avgCautionPrompt, double avgCautionBaseline,
        double avgSentimentCombined, double avgSentimentParams,
        double avgSentimentPrompt, double avgSentimentBaseline,
        double avgEntropyCombined, double avgEntropyParams,
        double avgEntropyPrompt, double avgEntropyBaseline,
        BehavioralMetrics.ComparisonReport reportCombined,
        BehavioralMetrics.ComparisonReport reportParams,
        BehavioralMetrics.ComparisonReport reportPrompt
    ) {
        public String summary() {
            return """
                Profile: %s (%s)
                  Modulated params: maxTokens=%d, temp=%.2f (base: %d, %.1f)
                  Vitality text: "%s"

                  Avg response length: combined=%.0f, params=%.0f, prompt=%.0f, baseline=%.0f
                  Avg caution words:   combined=%.1f, params=%.1f, prompt=%.1f, baseline=%.1f
                  Avg sentiment:       combined=%.2f, params=%.2f, prompt=%.2f, baseline=%.2f
                  Vocab entropy:       combined=%.2f, params=%.2f, prompt=%.2f, baseline=%.2f

                  Divergence from baseline:
                    Combined:   %.1f%%
                    Params only: %.1f%%
                    Prompt only: %.1f%%
                """.formatted(
                profile.name(), profile.description(),
                profile.maxTokens(DEFAULT_MAX_TOKENS), profile.temperature(DEFAULT_TEMPERATURE),
                DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE,
                profile.describeState(),
                avgLengthCombined, avgLengthParams, avgLengthPrompt, avgLengthBaseline,
                avgCautionCombined, avgCautionParams, avgCautionPrompt, avgCautionBaseline,
                avgSentimentCombined, avgSentimentParams, avgSentimentPrompt, avgSentimentBaseline,
                avgEntropyCombined, avgEntropyParams, avgEntropyPrompt, avgEntropyBaseline,
                reportCombined.overallDivergence() * 100,
                reportParams.overallDivergence() * 100,
                reportPrompt.overallDivergence() * 100);
        }
    }

    /** Results for one vitality profile. */
    public record ProfileResult(
        VitalityProfile profile,
        BehavioralRecord combined,
        BehavioralRecord paramsOnly,
        BehavioralRecord promptOnly,
        ProfileMetrics metrics
    ) {}

    /** Full experiment results. */
    public record BathResult(
        BehavioralRecord baseline,
        List<ProfileResult> profileResults
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Bath Modulation Experiment Results ===\n\n");

            for (var pr : profileResults) {
                sb.append(pr.metrics().summary());
                sb.append("\n");
            }

            sb.append("INTERPRETATION:\n");
            sb.append("  If combined divergence > baseline for extreme profiles → bath is modulating.\n");
            sb.append("  If params_only ≈ baseline → parameter modulation has no effect.\n");
            sb.append("  If prompt_only ≈ baseline → vitality description has no effect.\n");
            sb.append("  If combined > params + prompt → the mechanisms interact.\n");
            sb.append("  Compare exhausted vs confident: largest expected behavioral gap.\n");

            // Summary table
            sb.append("\n  PROFILE         COMBINED  PARAMS  PROMPT\n");
            for (var pr : profileResults) {
                var m = pr.metrics();
                sb.append(String.format("  %-15s %5.1f%%   %5.1f%%  %5.1f%%%n",
                    pr.profile().name(),
                    m.reportCombined().overallDivergence() * 100,
                    m.reportParams().overallDivergence() * 100,
                    m.reportPrompt().overallDivergence() * 100));
            }
            return sb.toString();
        }
    }
}
