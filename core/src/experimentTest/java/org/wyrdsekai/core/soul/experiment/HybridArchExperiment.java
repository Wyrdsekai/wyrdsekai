package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Experiment 11: Hybrid Architecture (Jamba) Personality.
 *
 * Tests whether hybrid Transformer-Mamba architecture produces better personality
 * consistency than pure Transformer. Jamba Mini (52B/12B active) vs Qwen2.5-7B.
 *
 * 4 conditions:
 *
 * | Condition     | Model      | Soul Prompt | What It Tests              |
 * |---------------|------------|-------------|----------------------------|
 * | AR naked      | Qwen2.5-7B | No          | Transformer control        |
 * | AR prompt     | Qwen2.5-7B | FULL        | Known baseline (~30% div)  |
 * | Hybrid naked  | Jamba Mini  | No          | Hybrid control             |
 * | Hybrid prompt | Jamba Mini  | FULL        | Does hybrid improve?       |
 *
 * Gates:
 *   11A: Hybrid+prompt < Transformer+prompt → hybrid architecture wins
 *   11B: Hybrid response quality acceptable → architecture is viable
 */
public class HybridArchExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String arUrl;          // Ollama for Qwen
    private final String hybridUrl;      // serve.py for Jamba
    private final String arModel;
    private final String hybridModel;
    private final String baselineModel;  // gold standard source
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private HybridArchExperiment(Builder b) {
        this.arUrl = b.arUrl;
        this.hybridUrl = b.hybridUrl;
        this.arModel = b.arModel;
        this.hybridModel = b.hybridModel;
        this.baselineModel = b.baselineModel;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    public ComparisonResult run() throws Exception {
        System.out.println("=== Experiment 11: Hybrid Architecture (Jamba) ===");
        System.out.println("AR model: " + arModel + " @ " + arUrl);
        System.out.println("Hybrid model: " + hybridModel + " @ " + hybridUrl);
        System.out.println("Baseline: " + baselineModel);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold-standard baseline
        System.out.println("--- Gold-standard baseline on " + baselineModel + " ---");
        var baselineInference = new InferenceHelper(arUrl, baselineModel);
        var baseline = runScenarios(baselineInference, "hybrid-baseline-" + baselineModel,
            baseSystemPrompt, null);
        save("hybrid-baseline-" + sanitize(baselineModel), baseline);
        System.out.println("Baseline complete\n");

        // Step 2: Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("hybrid-soul-full.txt", soulFull);
        int soulTokens = SoulExperiment.estimateTokens(soulFull);
        System.out.println("Soul extracted: ~" + soulTokens + " tokens\n");

        var conditions = new ArrayList<ConditionResult>();

        // Condition 1: AR naked
        System.out.println("--- Condition 1: AR naked (" + arModel + ") ---");
        var arInference = new InferenceHelper(arUrl, arModel);
        var arNaked = runScenarios(arInference, "hybrid-ar-naked",
            baseSystemPrompt, null);
        save("hybrid-ar-naked", arNaked);
        var arNakedReport = compare(baseline, arNaked);
        conditions.add(new ConditionResult("AR naked", arModel, arNaked, arNakedReport, 0));
        System.out.println("  Divergence: " + fmt(arNakedReport.overallDivergence()) + "\n");

        // Condition 2: AR prompt
        System.out.println("--- Condition 2: AR prompt (" + arModel + " + soul) ---");
        var arPrompt = runScenarios(arInference, "hybrid-ar-prompt",
            baseSystemPrompt, soulFull);
        save("hybrid-ar-prompt", arPrompt);
        var arPromptReport = compare(baseline, arPrompt);
        conditions.add(new ConditionResult("AR prompt", arModel, arPrompt, arPromptReport, soulTokens));
        System.out.println("  Divergence: " + fmt(arPromptReport.overallDivergence()) + "\n");

        // Condition 3: Hybrid naked
        System.out.println("--- Condition 3: Hybrid naked (" + hybridModel + ") ---");
        var hybridInference = new InferenceHelper(hybridUrl, hybridModel);
        var hybridNaked = runScenarios(hybridInference, "hybrid-jamba-naked",
            baseSystemPrompt, null);
        save("hybrid-jamba-naked", hybridNaked);
        var hybridNakedReport = compare(baseline, hybridNaked);
        conditions.add(new ConditionResult("Hybrid naked", hybridModel, hybridNaked, hybridNakedReport, 0));
        System.out.println("  Divergence: " + fmt(hybridNakedReport.overallDivergence()) + "\n");

        // Condition 4: Hybrid prompt
        System.out.println("--- Condition 4: Hybrid prompt (" + hybridModel + " + soul) ---");
        var hybridPrompt = runScenarios(hybridInference, "hybrid-jamba-prompt",
            baseSystemPrompt, soulFull);
        save("hybrid-jamba-prompt", hybridPrompt);
        var hybridPromptReport = compare(baseline, hybridPrompt);
        conditions.add(new ConditionResult("Hybrid prompt", hybridModel, hybridPrompt, hybridPromptReport, soulTokens));
        System.out.println("  Divergence: " + fmt(hybridPromptReport.overallDivergence()) + "\n");

        var result = new ComparisonResult(baselineModel, arModel, hybridModel,
            baseline, soulFull, conditions);
        var summary = result.summary();
        save("hybrid-comparison-summary.txt", summary);
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

    public record ConditionResult(
        String condition,
        String model,
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report,
        int promptTokens
    ) {}

    public record ComparisonResult(
        String baselineModel,
        String arModel,
        String hybridModel,
        BehavioralRecord baseline,
        String soul,
        List<ConditionResult> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║   Experiment 11: Hybrid Architecture (Jamba) Personality     ║\n");
            sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

            sb.append("Gold baseline: ").append(baselineModel).append("\n");
            sb.append("AR model: ").append(arModel).append("\n");
            sb.append("Hybrid model: ").append(hybridModel).append("\n\n");

            sb.append("  CONDITION       MODEL            DIVERGENCE  SEMANTIC  PROMPT-TOK\n");
            sb.append("  ─────────────── ──────────────── ────────── ───────── ──────────\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-16s %-16s %5.1f%%     %s     %d%n",
                    c.condition(), c.model(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A ",
                    c.promptTokens()));
            }

            var arPrompt = findCondition("AR prompt");
            var hybridPrompt = findCondition("Hybrid prompt");
            var arNaked = findCondition("AR naked");
            var hybridNaked = findCondition("Hybrid naked");

            // Gate 11A
            sb.append("\n─── GATE 11A: Does hybrid+prompt beat Transformer+prompt? ───\n");
            if (arPrompt != null && hybridPrompt != null) {
                double arDiv = arPrompt.report().overallDivergence();
                double hybridDiv = hybridPrompt.report().overallDivergence();
                double diff = hybridDiv - arDiv;

                if (diff < -0.05) {
                    sb.append(String.format("  GREEN: Hybrid %.1f%% < AR %.1f%% (%.1f%% better)%n",
                        hybridDiv * 100, arDiv * 100, -diff * 100));
                    sb.append("  → Hybrid architecture improves personality consistency.\n");
                } else if (diff < 0.05) {
                    sb.append(String.format("  YELLOW: Hybrid %.1f%% ≈ AR %.1f%% (within noise)%n",
                        hybridDiv * 100, arDiv * 100));
                    sb.append("  → No personality advantage from hybrid architecture.\n");
                } else {
                    sb.append(String.format("  RED: Hybrid %.1f%% > AR %.1f%% (%.1f%% worse)%n",
                        hybridDiv * 100, arDiv * 100, diff * 100));
                    sb.append("  → Hybrid architecture hurts personality. Pure Transformer confirmed.\n");
                }
            }

            // Gate 11B — quality check
            sb.append("\n─── GATE 11B: Hybrid response quality acceptable? ───\n");
            if (hybridPrompt != null) {
                double hybridDiv = hybridPrompt.report().overallDivergence();
                double hybridSem = hybridPrompt.report().semanticSimilarity();

                if (hybridDiv < 0.40 && hybridSem > 0.50) {
                    sb.append(String.format("  GREEN: div=%.1f%%, semantic=%.1f%% — coherent and on-topic%n",
                        hybridDiv * 100, hybridSem * 100));
                } else if (hybridDiv < 0.50) {
                    sb.append(String.format("  YELLOW: div=%.1f%%, semantic=%.1f%% — minor quality issues%n",
                        hybridDiv * 100, hybridSem * 100));
                } else {
                    sb.append(String.format("  RED: div=%.1f%%, semantic=%.1f%% — poor quality%n",
                        hybridDiv * 100, hybridSem * 100));
                }
            }

            // Prompt injection delta comparison
            sb.append("\n─── Prompt Injection Effect ───\n");
            if (arNaked != null && arPrompt != null) {
                double delta = arNaked.report().overallDivergence() - arPrompt.report().overallDivergence();
                sb.append(String.format("  AR: naked %.1f%% → prompt %.1f%% (%.1f%% improvement)%n",
                    arNaked.report().overallDivergence() * 100,
                    arPrompt.report().overallDivergence() * 100,
                    delta * 100));
            }
            if (hybridNaked != null && hybridPrompt != null) {
                double delta = hybridNaked.report().overallDivergence() - hybridPrompt.report().overallDivergence();
                sb.append(String.format("  Hybrid: naked %.1f%% → prompt %.1f%% (%.1f%% improvement)%n",
                    hybridNaked.report().overallDivergence() * 100,
                    hybridPrompt.report().overallDivergence() * 100,
                    delta * 100));
            }

            return sb.toString();
        }

        private ConditionResult findCondition(String name) {
            return conditions.stream()
                .filter(c -> name.equals(c.condition()))
                .findFirst().orElse(null);
        }
    }

    // --- Builder ---

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String arUrl;
        private String hybridUrl;
        private String arModel;
        private String hybridModel = "jamba-mini";
        private String baselineModel;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        public Builder arUrl(String u) { this.arUrl = u; return this; }
        public Builder hybridUrl(String u) { this.hybridUrl = u; return this; }
        public Builder arModel(String m) { this.arModel = m; return this; }
        public Builder hybridModel(String m) { this.hybridModel = m; return this; }
        public Builder baselineModel(String m) { this.baselineModel = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public HybridArchExperiment build() {
            if (arUrl == null) throw new IllegalStateException("arUrl required");
            if (hybridUrl == null) throw new IllegalStateException("hybridUrl required");
            if (arModel == null) throw new IllegalStateException("arModel required");
            if (baselineModel == null) throw new IllegalStateException("baselineModel required");
            return new HybridArchExperiment(this);
        }
    }
}
