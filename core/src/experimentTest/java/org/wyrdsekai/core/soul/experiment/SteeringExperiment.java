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
 * Experiment 8 Part B: Steering Vector vs Prompt Injection Comparison.
 *
 * For each model, runs 20 standard scenarios under 5 conditions:
 *
 * | Condition       | Soul Prompt | Steering Vector | Server Port |
 * |-----------------|-------------|-----------------|-------------|
 * | D: naked        | no          | no              | nakedUrl    |
 * | A: prompt       | FULL soul   | no              | nakedUrl    |
 * | B: steer        | no          | @1.0            | steerUrl    |
 * | C: steer+prompt | FULL soul   | @1.0            | steerUrl    |
 * | E: steer-half   | no          | @0.5            | steerScaledUrl |
 *
 * Steering is server-side (llama.cpp --control-vector), so InferenceHelper
 * works unmodified — just point it at different ports.
 */
public class SteeringExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String nakedUrl;        // Port with no steering vector
    private final String steerUrl;        // Port with vector @1.0
    private final String steerScaledUrl;  // Port with vector @0.5
    private final String baseModel;       // Display name
    private final String baselineModel;   // Gold-standard model
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private SteeringExperiment(Builder b) {
        this.nakedUrl = b.nakedUrl;
        this.steerUrl = b.steerUrl;
        this.steerScaledUrl = b.steerScaledUrl;
        this.baseModel = b.baseModel;
        this.baselineModel = b.baselineModel;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    /**
     * Run the 5-condition steering comparison.
     */
    public ComparisonResult run() throws Exception {
        System.out.println("=== Experiment 8: Steering Vector Comparison ===");
        System.out.println("Baseline model: " + baselineModel);
        System.out.println("Target model: " + baseModel);
        System.out.println("Naked URL: " + nakedUrl);
        System.out.println("Steer URL: " + steerUrl);
        System.out.println("Steer-half URL: " + (steerScaledUrl != null ? steerScaledUrl : "(none)"));
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold baseline (via naked server, baseline model — or Ollama if different)
        System.out.println("--- Gold-standard baseline on " + baselineModel + " ---");
        var baselineInference = new InferenceHelper(nakedUrl, baselineModel);
        var baseline = runScenarios(baselineInference, "steer-baseline-" + baselineModel,
            baseSystemPrompt, null);
        save("steer-baseline-" + sanitize(baselineModel), baseline);
        System.out.println("Baseline complete\n");

        // Step 2: Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("steer-soul-full.txt", soulFull);
        System.out.println("Soul extracted: ~" + SoulExperiment.estimateTokens(soulFull) + " tokens\n");

        var conditions = new ArrayList<ConditionResult>();

        // Condition D: naked (no soul, no steering)
        System.out.println("--- Condition D: naked ---");
        var nakedInference = new InferenceHelper(nakedUrl, baseModel);
        var naked = runScenarios(nakedInference, "steer-naked",
            baseSystemPrompt, null);
        save("steer-naked", naked);
        var nakedReport = compare(baseline, naked);
        conditions.add(new ConditionResult("D: naked", false, false, 0.0, naked, nakedReport));
        System.out.println("  Divergence: " + fmt(nakedReport.overallDivergence()) + "\n");

        // Condition A: prompt only (soul prompt, no steering)
        System.out.println("--- Condition A: prompt only ---");
        var promptOnly = runScenarios(nakedInference, "steer-prompt",
            baseSystemPrompt, soulFull);
        save("steer-prompt", promptOnly);
        var promptReport = compare(baseline, promptOnly);
        conditions.add(new ConditionResult("A: prompt", true, false, 0.0, promptOnly, promptReport));
        System.out.println("  Divergence: " + fmt(promptReport.overallDivergence()) + "\n");

        // Condition B: steer only (no soul prompt, vector @1.0)
        if (steerUrl != null) {
            System.out.println("--- Condition B: steer only (@1.0) ---");
            var steerInference = new InferenceHelper(steerUrl, baseModel);
            var steerOnly = runScenarios(steerInference, "steer-vector",
                baseSystemPrompt, null);
            save("steer-vector", steerOnly);
            var steerReport = compare(baseline, steerOnly);
            conditions.add(new ConditionResult("B: steer", false, true, 1.0, steerOnly, steerReport));
            System.out.println("  Divergence: " + fmt(steerReport.overallDivergence()) + "\n");

            // Condition C: steer + prompt (soul prompt + vector @1.0)
            System.out.println("--- Condition C: steer + prompt ---");
            var steerPrompt = runScenarios(steerInference, "steer-combined",
                baseSystemPrompt, soulFull);
            save("steer-combined", steerPrompt);
            var combinedReport = compare(baseline, steerPrompt);
            conditions.add(new ConditionResult("C: steer+prompt", true, true, 1.0, steerPrompt, combinedReport));
            System.out.println("  Divergence: " + fmt(combinedReport.overallDivergence()) + "\n");
        }

        // Condition E: steer-half (no soul prompt, vector @0.5)
        if (steerScaledUrl != null) {
            System.out.println("--- Condition E: steer-half (@0.5) ---");
            var halfInference = new InferenceHelper(steerScaledUrl, baseModel);
            var steerHalf = runScenarios(halfInference, "steer-half",
                baseSystemPrompt, null);
            save("steer-half", steerHalf);
            var halfReport = compare(baseline, steerHalf);
            conditions.add(new ConditionResult("E: steer-half", false, true, 0.5, steerHalf, halfReport));
            System.out.println("  Divergence: " + fmt(halfReport.overallDivergence()) + "\n");
        }

        var result = new ComparisonResult(baselineModel, baseModel,
            baseline, soulFull, conditions);
        var summary = result.summary();
        save("steer-comparison-summary.txt", summary);
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
        boolean hasSoulPrompt,
        boolean hasSteering,
        double steeringScale,
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report
    ) {}

    /** Full comparison result across all conditions. */
    public record ComparisonResult(
        String baselineModel,
        String targetModel,
        BehavioralRecord baseline,
        String soul,
        List<ConditionResult> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Steering Vector Comparison Results ===\n\n");
            sb.append("Gold baseline: ").append(baselineModel).append("\n");
            sb.append("Target model: ").append(targetModel).append("\n");
            sb.append("Soul size: ~").append(SoulExperiment.estimateTokens(soul)).append(" tokens\n\n");

            sb.append("  CONDITION       SOUL  STEER  SCALE  DIVERGENCE  SEMANTIC  VOCAB\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-16s %-5s %-5s  %.1f    %5.1f%%     %s     %.0f%%%n",
                    c.condition(),
                    c.hasSoulPrompt ? "yes" : "no",
                    c.hasSteering ? "yes" : "no",
                    c.steeringScale,
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A ",
                    r.vocabularyOverlap() * 100));
            }

            // Analysis
            var prompt = conditions.stream()
                .filter(c -> "A: prompt".equals(c.condition())).findFirst();
            var steer = conditions.stream()
                .filter(c -> "B: steer".equals(c.condition())).findFirst();
            var combined = conditions.stream()
                .filter(c -> "C: steer+prompt".equals(c.condition())).findFirst();
            var steerHalf = conditions.stream()
                .filter(c -> "E: steer-half".equals(c.condition())).findFirst();

            sb.append("\nINTERPRETATION:\n");

            if (prompt.isPresent() && steer.isPresent()) {
                double pDiv = prompt.get().report().overallDivergence();
                double sDiv = steer.get().report().overallDivergence();
                double diff = sDiv - pDiv;

                if (diff < -0.05) {
                    sb.append(String.format(
                        "  STEERING WINS: steer %.1f%% vs prompt %.1f%% (%.1f%% improvement)%n",
                        sDiv * 100, pDiv * 100, -diff * 100));
                } else if (diff < 0.05) {
                    sb.append(String.format(
                        "  TIED: steer %.1f%% ~ prompt %.1f%% (within noise)%n",
                        sDiv * 100, pDiv * 100));
                } else {
                    sb.append(String.format(
                        "  PROMPT WINS: prompt %.1f%% vs steer %.1f%% (%.1f%% gap)%n",
                        pDiv * 100, sDiv * 100, diff * 100));
                }
            }

            if (combined.isPresent() && prompt.isPresent() && steer.isPresent()) {
                double cDiv = combined.get().report().overallDivergence();
                double bestOther = Math.min(
                    prompt.get().report().overallDivergence(),
                    steer.get().report().overallDivergence());

                if (cDiv < bestOther - 0.03) {
                    sb.append(String.format(
                        "  COMPOUNDING: combined %.1f%% beats best-single %.1f%%%n",
                        cDiv * 100, bestOther * 100));
                    sb.append("  -> Steering + prompt compound. Use both (Layer 1 + Layer 2).\n");
                } else {
                    sb.append(String.format(
                        "  NO COMPOUNDING: combined %.1f%% ~ best-single %.1f%%%n",
                        cDiv * 100, bestOther * 100));
                }
            }

            if (steer.isPresent() && steerHalf.isPresent()) {
                double fullDiv = steer.get().report().overallDivergence();
                double halfDiv = steerHalf.get().report().overallDivergence();
                double spread = Math.abs(halfDiv - fullDiv);
                sb.append(String.format(
                    "  MODULABILITY: @1.0=%.1f%% @0.5=%.1f%% spread=%.1f%%%n",
                    fullDiv * 100, halfDiv * 100, spread * 100));
                if (spread > 0.05) {
                    sb.append("  -> Steering strength modulates behavior. Vitality can drive alpha.\n");
                } else {
                    sb.append("  -> Steering strength has minimal effect on personality divergence.\n");
                }
            }

            return sb.toString();
        }
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String nakedUrl;
        private String steerUrl;
        private String steerScaledUrl;
        private String baseModel;
        private String baselineModel;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        public Builder nakedUrl(String u) { this.nakedUrl = u; return this; }
        public Builder steerUrl(String u) { this.steerUrl = u; return this; }
        public Builder steerScaledUrl(String u) { this.steerScaledUrl = u; return this; }
        public Builder baseModel(String m) { this.baseModel = m; return this; }
        public Builder baselineModel(String m) { this.baselineModel = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public SteeringExperiment build() {
            if (nakedUrl == null) throw new IllegalStateException("nakedUrl required");
            if (baseModel == null) throw new IllegalStateException("baseModel required");
            if (baselineModel == null) throw new IllegalStateException("baselineModel required");
            return new SteeringExperiment(this);
        }
    }
}
