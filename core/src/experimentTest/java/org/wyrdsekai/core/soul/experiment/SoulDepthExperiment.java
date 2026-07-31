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
 * Experiment 15: Soul Depth Sweep.
 *
 * Tests whether richer soul text (more examples, episodic memories, style guides)
 * improves personality fidelity beyond the current FULL extraction.
 *
 * Prior result: MEDIUM (71 tok) ≈ FULL (461 tok) at ~30% divergence.
 * Question: does going deeper help, or is there a fidelity ceiling?
 *
 * 5 conditions at increasing depth:
 *
 * | Condition      | Detail Level   | ~Tokens | Content                              |
 * |----------------|----------------|---------|--------------------------------------|
 * | MINIMAL        | MINIMAL        | ~18     | Core sentence + dominant trait        |
 * | MEDIUM         | MEDIUM         | ~71     | Key traits + compressed memories      |
 * | FULL           | FULL           | ~461    | Fingerprint + memories + style        |
 * | FULL_EXAMPLES  | FULL_EXAMPLES  | ~1000   | FULL + 10 complete example exchanges  |
 * | DEEP           | DEEP           | ~2000   | FULL + examples + values + episodic   |
 *
 * Gates:
 *   15A: Does deeper soul text reduce divergence below FULL's ~30%?
 *   15B: Where is the diminishing returns point?
 *   15C: Does depth hurt? (context pollution / instruction confusion)
 */
public class SoulDepthExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String baseUrl;
    private final String model;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private SoulDepthExperiment(Builder b) {
        this.baseUrl = b.baseUrl;
        this.model = b.model;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    public DepthResult run() throws Exception {
        System.out.println("=== Experiment 15: Soul Depth Sweep ===");
        System.out.println("Model: " + model);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        var inference = new InferenceHelper(baseUrl, model);

        // Step 1: Gold-standard baseline
        System.out.println("--- Gold-standard baseline ---");
        var baseline = runScenarios(inference, "depth-baseline",
            baseSystemPrompt, null);
        save("depth-baseline", baseline);
        System.out.println("Baseline complete: " + baseline.responses().size() + " responses\n");

        // Step 2: Extract soul at all 5 depths
        var depths = SoulExtractor.Detail.values();
        var souls = new LinkedHashMap<SoulExtractor.Detail, String>();
        for (var detail : depths) {
            var soul = SoulExtractor.extract(baseline, detail);
            souls.put(detail, soul);
            int tokens = SoulExperiment.estimateTokens(soul);
            System.out.println("  " + detail + ": ~" + tokens + " tokens (" + soul.length() + " chars)");
            save("depth-soul-" + detail.name().toLowerCase() + ".txt", soul);
        }
        System.out.println();

        // Step 3: Run each depth condition
        var conditions = new ArrayList<DepthCondition>();
        for (var detail : depths) {
            var soul = souls.get(detail);
            int soulTokens = SoulExperiment.estimateTokens(soul);
            System.out.println("--- " + detail + " (~" + soulTokens + " tokens) ---");

            var record = runScenarios(inference, "depth-" + detail.name().toLowerCase(),
                baseSystemPrompt, soul);
            save("depth-" + detail.name().toLowerCase(), record);
            var report = compare(baseline, record);

            conditions.add(new DepthCondition(detail, soulTokens, soul.length(),
                record, report));
            System.out.println("  Divergence: " + fmt(report.overallDivergence()));
            if (report.semanticSimilarity() >= 0) {
                System.out.println("  Semantic:   " + fmt(report.semanticSimilarity()));
            }
            System.out.println();
        }

        var result = new DepthResult(model, baseline, souls, conditions);
        var summary = result.summary();
        save("depth-summary.txt", summary);
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

    public record DepthCondition(
        SoulExtractor.Detail detail,
        int soulTokens,
        int soulChars,
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report
    ) {}

    public record DepthResult(
        String model,
        BehavioralRecord baseline,
        Map<SoulExtractor.Detail, String> souls,
        List<DepthCondition> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║   Experiment 15: Soul Depth Sweep                            ║\n");
            sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

            sb.append("Model: ").append(model).append("\n\n");

            sb.append("  DEPTH           TOKENS  DIVERGENCE  SEMANTIC    VOCAB-OVERLAP  SENTIMENT\n");
            sb.append("  ─────────────── ─────── ────────── ─────────── ────────────── ──────────\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-16s %5d   %5.1f%%     %s     %5.1f%%        %.3f%n",
                    c.detail().name(),
                    c.soulTokens(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%5.1f%%  ", r.semanticSimilarity() * 100)
                        : "  N/A   ",
                    r.vocabularyOverlap() * 100,
                    r.sentimentAlignment()));
            }

            // Gate 15A: Does depth reduce divergence below FULL?
            sb.append("\n─── GATE 15A: Does deeper soul text beat FULL? ───\n");
            var full = findCondition(SoulExtractor.Detail.FULL);
            var fullExamples = findCondition(SoulExtractor.Detail.FULL_EXAMPLES);
            var deep = findCondition(SoulExtractor.Detail.DEEP);
            if (full != null && fullExamples != null && deep != null) {
                double fullDiv = full.report().overallDivergence();
                double examplesDiv = fullExamples.report().overallDivergence();
                double deepDiv = deep.report().overallDivergence();
                double bestDeeper = Math.min(examplesDiv, deepDiv);
                double improvement = fullDiv - bestDeeper;

                if (improvement > 0.05) {
                    sb.append(String.format("  GREEN: Best deeper %.1f%% vs FULL %.1f%% (%.1f%% improvement)%n",
                        bestDeeper * 100, fullDiv * 100, improvement * 100));
                    sb.append("  → Richer soul text measurably improves personality fidelity.\n");
                } else if (improvement > 0.02) {
                    sb.append(String.format("  YELLOW: Best deeper %.1f%% vs FULL %.1f%% (%.1f%% marginal gain)%n",
                        bestDeeper * 100, fullDiv * 100, improvement * 100));
                    sb.append("  → Small improvement. May not justify token cost.\n");
                } else {
                    sb.append(String.format("  RED: Best deeper %.1f%% ≈ FULL %.1f%% (no improvement)%n",
                        bestDeeper * 100, fullDiv * 100));
                    sb.append("  → Depth ceiling reached at FULL. Extra tokens wasted.\n");
                }
            }

            // Gate 15B: Diminishing returns point
            sb.append("\n─── GATE 15B: Where are diminishing returns? ───\n");
            sb.append("  Token efficiency (divergence per token spent):\n");
            DepthCondition bestEfficiency = null;
            double bestRatio = Double.MAX_VALUE;
            for (var c : conditions) {
                double div = c.report().overallDivergence();
                double ratio = div / Math.max(1, c.soulTokens());
                sb.append(String.format("    %-16s %5d tok → %5.1f%% div (%.4f div/tok)%n",
                    c.detail().name(), c.soulTokens(), div * 100, ratio));
                if (ratio < bestRatio) {
                    bestRatio = ratio;
                    bestEfficiency = c;
                }
            }
            if (bestEfficiency != null) {
                sb.append("  → Best efficiency: ").append(bestEfficiency.detail().name()).append("\n");
            }

            // Gate 15C: Does depth hurt?
            sb.append("\n─── GATE 15C: Does depth hurt? ───\n");
            if (full != null && deep != null) {
                double fullDiv = full.report().overallDivergence();
                double deepDiv = deep.report().overallDivergence();
                if (deepDiv > fullDiv + 0.05) {
                    sb.append(String.format("  WARNING: DEEP %.1f%% > FULL %.1f%% (%.1f%% worse)%n",
                        deepDiv * 100, fullDiv * 100, (deepDiv - fullDiv) * 100));
                    sb.append("  → Context pollution detected. Too much soul text confuses the model.\n");
                } else if (deepDiv > fullDiv + 0.02) {
                    sb.append(String.format("  CAUTION: DEEP %.1f%% slightly > FULL %.1f%%  (%.1f%%)%n",
                        deepDiv * 100, fullDiv * 100, (deepDiv - fullDiv) * 100));
                    sb.append("  → Marginal degradation. Diminishing returns confirmed.\n");
                } else {
                    sb.append(String.format("  OK: DEEP %.1f%% ≤ FULL %.1f%%. No context pollution.%n",
                        deepDiv * 100, fullDiv * 100));
                }
            }

            // Trend line
            sb.append("\n─── TREND ───\n");
            sb.append("  Tokens → Divergence:\n");
            for (var c : conditions) {
                int barLen = (int) (c.report().overallDivergence() * 50);
                sb.append(String.format("  %5d  ", c.soulTokens()));
                sb.append("█".repeat(Math.max(1, barLen)));
                sb.append(String.format(" %.1f%%%n", c.report().overallDivergence() * 100));
            }

            return sb.toString();
        }

        private DepthCondition findCondition(SoulExtractor.Detail detail) {
            return conditions.stream()
                .filter(c -> c.detail() == detail)
                .findFirst().orElse(null);
        }
    }

    // --- Builder ---

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String baseUrl;
        private String model;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        public Builder baseUrl(String u) { this.baseUrl = u; return this; }
        public Builder model(String m) { this.model = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public SoulDepthExperiment build() {
            if (baseUrl == null) throw new IllegalStateException("baseUrl required");
            if (model == null) throw new IllegalStateException("model required");
            return new SoulDepthExperiment(this);
        }
    }
}
