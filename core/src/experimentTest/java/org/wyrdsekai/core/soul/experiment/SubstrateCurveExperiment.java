package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Experiment 3: Substrate Sensitivity Curve.
 *
 * Tests whether soul fidelity degrades gracefully (not catastrophically)
 * across model sizes and architectures.
 *
 * Same FULL soul extracted from one model, restored on N models.
 * Measures divergence curve vs model size/architecture.
 *
 * Usage:
 * <pre>
 *   var exp = new SubstrateCurveExperiment(
 *       "http://localhost:11434/v1",
 *       "qwen2.5:7b",                          // primary model (baseline source)
 *       List.of("qwen3:0.6b", "qwen3:8b"),   // secondary models
 *       outputDir);
 *   var result = exp.run();
 *   System.out.println(result.summary());
 * </pre>
 */
public class SubstrateCurveExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String baseUrl;
    private final String primaryModel;
    private final List<String> secondaryModels;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    public SubstrateCurveExperiment(String baseUrl, String primaryModel,
                                    List<String> secondaryModels) {
        this(baseUrl, primaryModel, secondaryModels,
            SoulExperiment.DEFAULT_AGENT_PROMPT, Scenario.standardSuite(),
            null, null, null);
    }

    public SubstrateCurveExperiment(String baseUrl, String primaryModel,
                                    List<String> secondaryModels, String systemPrompt,
                                    List<Scenario> scenarios, Path outputDir,
                                    String embeddingUrl, String embeddingModel) {
        this.baseUrl = baseUrl;
        this.primaryModel = primaryModel;
        this.secondaryModels = secondaryModels;
        this.baseSystemPrompt = systemPrompt;
        this.scenarios = scenarios;
        this.outputDir = outputDir;
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Run the substrate sensitivity curve experiment.
     *
     * 1. Baseline on primary model
     * 2. Extract FULL + MEDIUM + MINIMAL soul
     * 3. Restore FULL soul on each secondary model
     * 4. Compare each against primary baseline
     */
    public CurveResult run() throws Exception {
        System.out.println("=== Substrate Sensitivity Curve ===");
        System.out.println("Primary: " + primaryModel);
        System.out.println("Secondary: " + secondaryModels);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Baseline on primary
        System.out.println("--- Baseline on " + primaryModel + " ---");
        var primaryInference = new InferenceHelper(baseUrl, primaryModel);
        var baseline = runScenarios(primaryInference, "substrate-baseline-" + primaryModel,
            baseSystemPrompt, null);
        save("substrate-baseline-" + sanitize(primaryModel), baseline);
        System.out.println("Baseline complete\n");

        // Step 2: Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        var soulMedium = SoulExtractor.extract(baseline, SoulExtractor.Detail.MEDIUM);
        save("substrate-soul-full.txt", soulFull);
        save("substrate-soul-medium.txt", soulMedium);

        System.out.println("Soul extracted: FULL ~" + SoulExperiment.estimateTokens(soulFull) + " tokens");
        System.out.println();

        // Step 3: Same-substrate restoration (control)
        System.out.println("--- Same-substrate: " + primaryModel + " + FULL soul ---");
        var sameSubstrate = runScenarios(primaryInference, "substrate-same-" + primaryModel,
            baseSystemPrompt, soulFull);
        save("substrate-same-" + sanitize(primaryModel), sameSubstrate);
        var sameReport = compare(baseline, sameSubstrate);
        System.out.println("Same-substrate divergence: " +
            String.format("%.1f%%", sameReport.overallDivergence() * 100) + "\n");

        // Step 4: Cross-substrate restoration
        var crossResults = new ArrayList<ModelResult>();
        crossResults.add(new ModelResult(primaryModel, "same", sameSubstrate, sameReport));

        for (var model : secondaryModels) {
            System.out.println("--- Cross-substrate: " + model + " + FULL soul ---");
            var crossInference = new InferenceHelper(baseUrl, model);
            var crossRestored = runScenarios(crossInference, "substrate-cross-" + model,
                baseSystemPrompt, soulFull);
            save("substrate-cross-" + sanitize(model), crossRestored);
            var crossReport = compare(baseline, crossRestored);
            crossResults.add(new ModelResult(model, "cross", crossRestored, crossReport));
            System.out.println("  " + model + " divergence: " +
                String.format("%.1f%%", crossReport.overallDivergence() * 100) + "\n");
        }

        var result = new CurveResult(primaryModel, baseline, soulFull, crossResults);
        save("substrate-curve-summary.txt", result.summary());
        System.out.println(result.summary());
        return result;
    }

    // --- Internal ---

    private BehavioralRecord runScenarios(InferenceHelper inf, String runId,
                                           String systemPrompt, String soulLayer) throws Exception {
        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        var effectivePrompt = soulLayer != null
            ? systemPrompt + "\n\n" + soulLayer
            : systemPrompt;

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();

            var userMessage = buildUserMessage(scenario);
            var response = inf.chat(effectivePrompt, userMessage);
            long elapsed = System.currentTimeMillis() - start;

            int tokens = response.split("\\s+").length;
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));

            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }

        return new BehavioralRecord(runId, "Wyrd", inf.model(), systemPrompt, soulLayer,
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

    private static String sanitize(String modelName) {
        return modelName.replaceAll("[^a-zA-Z0-9.-]", "_");
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

    /** Results for one model. */
    public record ModelResult(
        String model,
        String type,  // "same" or "cross"
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report
    ) {}

    /** Full curve results. */
    public record CurveResult(
        String primaryModel,
        BehavioralRecord baseline,
        String soul,
        List<ModelResult> modelResults
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Substrate Sensitivity Curve Results ===\n\n");
            sb.append("Primary model (soul source): ").append(primaryModel).append("\n");
            sb.append("Soul size: ~").append(SoulExperiment.estimateTokens(soul)).append(" tokens\n\n");

            sb.append("  MODEL                   TYPE   DIVERGENCE  SEMANTIC  VOCAB\n");
            for (var mr : modelResults) {
                var r = mr.report();
                sb.append(String.format("  %-23s %-5s  %5.1f%%     %s     %.0f%%%n",
                    mr.model(), mr.type(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A ",
                    r.vocabularyOverlap() * 100));
            }

            sb.append("\nINTERPRETATION:\n");
            sb.append("  Graceful degradation (cross < same + 20%%): Soul is substrate-portable.\n");
            sb.append("  Catastrophic (cross > 70%%): Prompt injection insufficient for this substrate.\n");
            sb.append("  Compare same-family vs cross-family for architecture sensitivity.\n");

            // Find degradation pattern
            if (modelResults.size() >= 2) {
                var same = modelResults.stream()
                    .filter(r -> "same".equals(r.type())).findFirst();
                var worstCross = modelResults.stream()
                    .filter(r -> "cross".equals(r.type()))
                    .max((a, b) -> Double.compare(
                        a.report().overallDivergence(), b.report().overallDivergence()));

                if (same.isPresent() && worstCross.isPresent()) {
                    double gap = worstCross.get().report().overallDivergence()
                        - same.get().report().overallDivergence();
                    sb.append(String.format("\n  Worst cross-substrate penalty: +%.1f%% over same-substrate%n",
                        gap * 100));
                    if (gap < 0.10) sb.append("  EXCELLENT: Soul transfers well across substrates.\n");
                    else if (gap < 0.20) sb.append("  GOOD: Moderate substrate sensitivity.\n");
                    else sb.append("  CONCERNING: Large substrate sensitivity gap.\n");
                }
            }

            return sb.toString();
        }
    }
}
