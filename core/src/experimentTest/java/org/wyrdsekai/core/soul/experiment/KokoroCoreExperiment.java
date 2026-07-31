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
 * Experiment 6 Part C: LoRA vs Prompt Injection Comparison.
 *
 * For each model size, runs 20 standard scenarios under 4 conditions:
 *
 * | Condition | Model     | Soul Prompt | What It Tests                      |
 * |-----------|-----------|-------------|-------------------------------------|
 * | D: naked  | base      | no          | Control                             |
 * | A: prompt | base      | FULL soul   | Existing approach (prompt injection) |
 * | B: lora   | base+LoRA | no          | Personality in weights alone         |
 * | C: layered| base+LoRA | FULL soul   | Weights + context combined           |
 *
 * Compares all conditions against a gold-standard baseline (e.g., Qwen3-30B-A3B).
 *
 * Usage:
 * <pre>
 *   var exp = new KokoroCoreExperiment(builder);
 *   var result = exp.run();
 *   System.out.println(result.summary());
 * </pre>
 */
public class KokoroCoreExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String baseUrl;
    private final String baseModel;       // e.g. "qwen2.5:0.5b"
    private final String loraModel;       // e.g. "wyrd-soul:qwen05b"
    private final String baselineModel;   // e.g. "qwen2.5:7b" (gold standard source)
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private KokoroCoreExperiment(Builder b) {
        this.baseUrl = b.baseUrl;
        this.baseModel = b.baseModel;
        this.loraModel = b.loraModel;
        this.baselineModel = b.baselineModel;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    /**
     * Run the LoRA vs Prompt Injection comparison.
     *
     * 1. Gold-standard baseline (large model, no soul)
     * 2. Extract FULL soul from baseline
     * 3. Run 4 conditions on target model(s)
     * 4. Compare each condition against gold baseline
     */
    public ComparisonResult run() throws Exception {
        System.out.println("=== Kokoro Core: LoRA vs Prompt Injection ===");
        System.out.println("Baseline model: " + baselineModel);
        System.out.println("Base model: " + baseModel);
        System.out.println("LoRA model: " + (loraModel != null ? loraModel : "(none)"));
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold-standard baseline
        System.out.println("--- Gold-standard baseline on " + baselineModel + " ---");
        var baselineInference = new InferenceHelper(baseUrl, baselineModel);
        var baseline = runScenarios(baselineInference, "kokoro-baseline-" + baselineModel,
            baseSystemPrompt, null);
        save("kokoro-baseline-" + sanitize(baselineModel), baseline);
        System.out.println("Baseline complete\n");

        // Step 2: Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("kokoro-soul-full.txt", soulFull);
        System.out.println("Soul extracted: ~" + SoulExperiment.estimateTokens(soulFull) + " tokens\n");

        var conditions = new ArrayList<ConditionResult>();

        // Condition D: naked (base model, no soul)
        System.out.println("--- Condition D: naked (" + baseModel + ", no soul) ---");
        var nakedInference = new InferenceHelper(baseUrl, baseModel);
        var naked = runScenarios(nakedInference, "kokoro-naked-" + baseModel,
            baseSystemPrompt, null);
        save("kokoro-naked-" + sanitize(baseModel), naked);
        var nakedReport = compare(baseline, naked);
        conditions.add(new ConditionResult("D: naked", baseModel, false, false, naked, nakedReport));
        System.out.println("  Divergence: " + fmt(nakedReport.overallDivergence()) + "\n");

        // Condition A: prompt only (base model + soul prompt)
        System.out.println("--- Condition A: prompt only (" + baseModel + " + soul) ---");
        var promptOnly = runScenarios(nakedInference, "kokoro-prompt-" + baseModel,
            baseSystemPrompt, soulFull);
        save("kokoro-prompt-" + sanitize(baseModel), promptOnly);
        var promptReport = compare(baseline, promptOnly);
        conditions.add(new ConditionResult("A: prompt", baseModel, true, false, promptOnly, promptReport));
        System.out.println("  Divergence: " + fmt(promptReport.overallDivergence()) + "\n");

        // Condition B: LoRA only (LoRA model, no soul prompt)
        if (loraModel != null) {
            System.out.println("--- Condition B: LoRA only (" + loraModel + ", no soul) ---");
            var loraInference = new InferenceHelper(baseUrl, loraModel);
            var loraOnly = runScenarios(loraInference, "kokoro-lora-" + loraModel,
                baseSystemPrompt, null);
            save("kokoro-lora-" + sanitize(loraModel), loraOnly);
            var loraReport = compare(baseline, loraOnly);
            conditions.add(new ConditionResult("B: lora", loraModel, false, true, loraOnly, loraReport));
            System.out.println("  Divergence: " + fmt(loraReport.overallDivergence()) + "\n");

            // Condition C: layered (LoRA model + soul prompt)
            System.out.println("--- Condition C: layered (" + loraModel + " + soul) ---");
            var layered = runScenarios(loraInference, "kokoro-layered-" + loraModel,
                baseSystemPrompt, soulFull);
            save("kokoro-layered-" + sanitize(loraModel), layered);
            var layeredReport = compare(baseline, layered);
            conditions.add(new ConditionResult("C: layered", loraModel, true, true, layered, layeredReport));
            System.out.println("  Divergence: " + fmt(layeredReport.overallDivergence()) + "\n");
        }

        var result = new ComparisonResult(baselineModel, baseModel, loraModel,
            baseline, soulFull, conditions);
        var summary = result.summary();
        save("kokoro-comparison-summary.txt", summary);
        System.out.println(summary);
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

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private static String fmt(double v) {
        return String.format("%.1f%%", v * 100);
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

    /** Result for a single condition. */
    public record ConditionResult(
        String condition,
        String model,
        boolean hasSoulPrompt,
        boolean hasLoRA,
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report
    ) {}

    /** Full comparison result. */
    public record ComparisonResult(
        String baselineModel,
        String baseModel,
        String loraModel,
        BehavioralRecord baseline,
        String soul,
        List<ConditionResult> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Kokoro Core Comparison Results ===\n\n");
            sb.append("Gold baseline: ").append(baselineModel).append("\n");
            sb.append("Base model: ").append(baseModel).append("\n");
            sb.append("LoRA model: ").append(loraModel != null ? loraModel : "(none)").append("\n");
            sb.append("Soul size: ~").append(SoulExperiment.estimateTokens(soul)).append(" tokens\n\n");

            sb.append("  CONDITION    MODEL                    DIVERGENCE  SEMANTIC  VOCAB\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-12s %-24s %5.1f%%     %s     %.0f%%%n",
                    c.condition(), c.model(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A ",
                    r.vocabularyOverlap() * 100));
            }

            // Analysis
            var prompt = conditions.stream()
                .filter(c -> "A: prompt".equals(c.condition())).findFirst();
            var lora = conditions.stream()
                .filter(c -> "B: lora".equals(c.condition())).findFirst();
            var layered = conditions.stream()
                .filter(c -> "C: layered".equals(c.condition())).findFirst();

            sb.append("\nINTERPRETATION:\n");
            if (prompt.isPresent() && lora.isPresent()) {
                double pDiv = prompt.get().report().overallDivergence();
                double lDiv = lora.get().report().overallDivergence();
                double diff = lDiv - pDiv;

                if (diff < -0.05) {
                    sb.append(String.format(
                        "  LoRA WINS: %.1f%% vs prompt %.1f%% (%.1f%% improvement)%n",
                        lDiv * 100, pDiv * 100, -diff * 100));
                    sb.append("  → Personality in weights beats personality in context.\n");
                } else if (diff < 0.05) {
                    sb.append(String.format(
                        "  TIED: LoRA %.1f%% ≈ prompt %.1f%% (within noise)%n",
                        lDiv * 100, pDiv * 100));
                    sb.append("  → Both approaches equivalent at this model size.\n");
                } else {
                    sb.append(String.format(
                        "  PROMPT WINS: prompt %.1f%% vs LoRA %.1f%% (%.1f%% gap)%n",
                        pDiv * 100, lDiv * 100, diff * 100));
                    sb.append("  → LoRA training insufficient or model too small.\n");
                }
            }

            if (layered.isPresent() && prompt.isPresent() && lora.isPresent()) {
                double lDiv = layered.get().report().overallDivergence();
                double bestOther = Math.min(
                    prompt.get().report().overallDivergence(),
                    lora.get().report().overallDivergence());

                if (lDiv < bestOther - 0.03) {
                    sb.append(String.format(
                        "  COMPOUNDING: layered %.1f%% beats best-of-other %.1f%%%n",
                        lDiv * 100, bestOther * 100));
                    sb.append("  → Weights + context compound. Use both.\n");
                } else {
                    sb.append(String.format(
                        "  NO COMPOUNDING: layered %.1f%% ≈ best-other %.1f%%%n",
                        lDiv * 100, bestOther * 100));
                }
            }

            return sb.toString();
        }
    }

    // --- Builder ---

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    public static class Builder {
        private final String baseUrl;
        private String baseModel;
        private String loraModel;
        private String baselineModel;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        Builder(String baseUrl) { this.baseUrl = baseUrl; }

        public Builder baseModel(String m) { this.baseModel = m; return this; }
        public Builder loraModel(String m) { this.loraModel = m; return this; }
        public Builder baselineModel(String m) { this.baselineModel = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public KokoroCoreExperiment build() {
            if (baseModel == null) throw new IllegalStateException("baseModel required");
            if (baselineModel == null) throw new IllegalStateException("baselineModel required");
            return new KokoroCoreExperiment(this);
        }
    }
}
