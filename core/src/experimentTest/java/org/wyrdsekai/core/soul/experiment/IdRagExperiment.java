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
 * Experiment 12: ID-RAG Structured Identity Retrieval.
 *
 * Tests whether structured identity retrieval from a trait knowledge graph
 * produces better personality consistency than flat prompt injection.
 *
 * Three conditions:
 * 1. Flat prompt — full soul text injected every time (Exp 1 baseline)
 * 2. ID-RAG full — all traits from graph, rendered as structured text
 * 3. ID-RAG selective — only context-relevant traits per scenario
 *
 * Based on ID-RAG (MIT Media Lab, ECAI 2025, arXiv:2509.25299).
 */
public class IdRagExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String url;
    private final String model;
    private final String baselineModel;
    private final String baseSystemPrompt;
    private final TraitGraph traitGraph;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private IdRagExperiment(Builder b) {
        this.url = b.url;
        this.model = b.model;
        this.baselineModel = b.baselineModel;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.traitGraph = b.traitGraph;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    /**
     * Run the full 3-condition comparison.
     */
    public IdRagResult run() throws Exception {
        System.out.println("=== Experiment 12: ID-RAG Structured Identity Retrieval ===");
        System.out.println("Model: " + model);
        System.out.println("Baseline: " + baselineModel);
        System.out.println("Trait graph: " + traitGraph.traits().size() + " traits, "
            + traitGraph.edges().size() + " edges");
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold baseline (model + base personality, no soul layer)
        System.out.println("--- Gold-standard baseline on " + baselineModel + " ---");
        var baselineInf = new InferenceHelper(url, baselineModel);
        var baseline = runScenarios(baselineInf, "idrag-baseline",
            baseSystemPrompt, null, null);
        save("idrag-baseline", baseline);

        // Extract flat soul for Condition 1
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("idrag-soul-full.txt", soulFull);
        System.out.println("Soul extracted: ~" + SoulExperiment.estimateTokens(soulFull)
            + " tokens\n");

        var conditions = new ArrayList<ConditionResult>();
        var inf = new InferenceHelper(url, model);

        // Condition 1: Flat prompt injection (Exp 1 baseline replication)
        System.out.println("--- Condition 1: Flat prompt injection ---");
        var flatRecord = runScenarios(inf, "idrag-flat", baseSystemPrompt, soulFull, null);
        save("idrag-flat", flatRecord);
        var flatReport = compare(baseline, flatRecord);
        int flatTokens = SoulExperiment.estimateTokens(soulFull);
        conditions.add(new ConditionResult("Flat prompt", flatRecord, flatReport,
            flatTokens, flatTokens)); // same tokens every scenario
        System.out.println("  Divergence: " + fmt(flatReport.overallDivergence())
            + ", ~" + flatTokens + " tokens/turn\n");

        // Condition 2: ID-RAG full (all traits, structured rendering)
        System.out.println("--- Condition 2: ID-RAG full (all traits) ---");
        var fullTraitPrompt = traitGraph.toPromptText();
        var idragFullRecord = runScenarios(inf, "idrag-full",
            baseSystemPrompt, fullTraitPrompt, null);
        save("idrag-full", idragFullRecord);
        var idragFullReport = compare(baseline, idragFullRecord);
        int fullTokens = SoulExperiment.estimateTokens(fullTraitPrompt);
        conditions.add(new ConditionResult("ID-RAG full", idragFullRecord, idragFullReport,
            fullTokens, fullTokens));
        System.out.println("  Divergence: " + fmt(idragFullReport.overallDivergence())
            + ", ~" + fullTokens + " tokens/turn\n");

        // Condition 3: ID-RAG selective (context-relevant traits only)
        System.out.println("--- Condition 3: ID-RAG selective ---");
        var idragSelectiveRecord = runScenarios(inf, "idrag-selective",
            baseSystemPrompt, null, traitGraph);
        save("idrag-selective", idragSelectiveRecord);
        var idragSelectiveReport = compare(baseline, idragSelectiveRecord);

        // Calculate average tokens for selective retrieval
        int totalSelectiveTokens = 0;
        int minTokens = Integer.MAX_VALUE;
        int maxTokens = 0;
        for (var scenario : scenarios) {
            var prompt = IdentityRetriever.retrieveAsPrompt(traitGraph, scenario);
            int t = SoulExperiment.estimateTokens(prompt);
            totalSelectiveTokens += t;
            minTokens = Math.min(minTokens, t);
            maxTokens = Math.max(maxTokens, t);
        }
        int avgSelectiveTokens = totalSelectiveTokens / scenarios.size();
        conditions.add(new ConditionResult("ID-RAG selective", idragSelectiveRecord,
            idragSelectiveReport, avgSelectiveTokens, avgSelectiveTokens));
        System.out.println("  Divergence: " + fmt(idragSelectiveReport.overallDivergence())
            + ", ~" + avgSelectiveTokens + " avg tokens/turn"
            + " (range " + minTokens + "-" + maxTokens + ")\n");

        // Per-trait consistency analysis
        var perTraitConsistency = analyzePerTraitConsistency(baseline, idragSelectiveRecord);

        var result = new IdRagResult(baselineModel, model, traitGraph,
            baseline, soulFull, conditions, perTraitConsistency,
            avgSelectiveTokens, minTokens, maxTokens);
        var summary = result.summary();
        save("idrag-summary.txt", summary);
        System.out.println(summary);
        return result;
    }

    // --- Internal ---

    private BehavioralRecord runScenarios(InferenceHelper inf, String runId,
                                           String systemPrompt, String soulLayer,
                                           TraitGraph dynamicGraph) throws Exception {
        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();

            // Build effective prompt: base + soul layer (static or dynamic)
            String effectivePrompt;
            if (dynamicGraph != null) {
                // ID-RAG selective: retrieve per-scenario
                var traitPrompt = IdentityRetriever.retrieveAsPrompt(dynamicGraph, scenario);
                effectivePrompt = systemPrompt + "\n\n" + traitPrompt;
            } else if (soulLayer != null) {
                effectivePrompt = systemPrompt + "\n\n" + soulLayer;
            } else {
                effectivePrompt = systemPrompt;
            }

            var userMessage = buildUserMessage(scenario);
            var response = inf.chat(effectivePrompt, userMessage);
            long elapsed = System.currentTimeMillis() - start;

            int tokens = response.split("\\s+").length;
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));

            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }

        return new BehavioralRecord(runId, "Wyrd", inf.model(), systemPrompt,
            null, Instant.now(), responses);
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
            return BehavioralMetrics.compareWithEmbeddings(baseline, other,
                embeddingUrl, embeddingModel);
        }
        return BehavioralMetrics.compare(baseline, other);
    }

    /**
     * Analyze per-category consistency to measure whether selective retrieval
     * improves trait-specific fidelity.
     */
    private Map<String, Double> analyzePerTraitConsistency(
            BehavioralRecord baseline, BehavioralRecord selective) throws Exception {
        var report = compare(baseline, selective);
        return report.perCategoryDivergence();
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
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report,
        int avgTokensPerTurn,
        int totalTokens
    ) {}

    public record IdRagResult(
        String baselineModel,
        String model,
        TraitGraph traitGraph,
        BehavioralRecord baseline,
        String flatSoul,
        List<ConditionResult> conditions,
        Map<String, Double> perCategoryConsistency,
        int avgSelectiveTokens,
        int minSelectiveTokens,
        int maxSelectiveTokens
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Experiment 12: ID-RAG Structured Identity Retrieval ===\n\n");
            sb.append("Baseline: ").append(baselineModel).append("\n");
            sb.append("Model: ").append(model).append("\n");
            sb.append("Traits: ").append(traitGraph.traits().size()).append("\n");
            sb.append("Edges: ").append(traitGraph.edges().size()).append("\n\n");

            sb.append("  CONDITION         DIVERGENCE  SEMANTIC    TOKENS/TURN\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-18s %5.1f%%     %-10s  ~%d%n",
                    c.condition(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A",
                    c.avgTokensPerTurn()));
            }

            // Token savings
            var flat = conditions.stream()
                .filter(c -> "Flat prompt".equals(c.condition())).findFirst();
            var selective = conditions.stream()
                .filter(c -> "ID-RAG selective".equals(c.condition())).findFirst();

            if (flat.isPresent() && selective.isPresent()) {
                double flatDiv = flat.get().report().overallDivergence();
                double selectiveDiv = selective.get().report().overallDivergence();
                int flatTokens = flat.get().avgTokensPerTurn();
                int selectiveTokens = selective.get().avgTokensPerTurn();

                sb.append(String.format(
                    "%nToken savings: %d → %d (%.0f%% reduction, range %d-%d)%n",
                    flatTokens, selectiveTokens,
                    (1.0 - (double) selectiveTokens / flatTokens) * 100,
                    minSelectiveTokens, maxSelectiveTokens));

                double divDiff = selectiveDiv - flatDiv;
                sb.append("\nINTERPRETATION:\n");
                if (divDiff < -0.05) {
                    sb.append(String.format(
                        "  ID-RAG WINS: %.1f%% vs flat %.1f%% (%.1f%% improvement)%n",
                        selectiveDiv * 100, flatDiv * 100, -divDiff * 100));
                    sb.append("  -> Structured retrieval beats flat injection.\n");
                } else if (divDiff < 0.05) {
                    sb.append(String.format(
                        "  TIED: ID-RAG %.1f%% ~ flat %.1f%% (within noise)%n",
                        selectiveDiv * 100, flatDiv * 100));
                    if (selectiveTokens < flatTokens * 0.7) {
                        sb.append("  -> But 30%+ token savings. Worth adopting for efficiency.\n");
                    } else {
                        sb.append("  -> No significant advantage. Flat prompt simpler.\n");
                    }
                } else {
                    sb.append(String.format(
                        "  FLAT WINS: flat %.1f%% vs ID-RAG %.1f%% (%.1f%% gap)%n",
                        flatDiv * 100, selectiveDiv * 100, divDiff * 100));
                    sb.append("  -> Selective retrieval loses context. Keep full prompt.\n");
                }

                // Gates
                sb.append("\n  GATE 12A (div < flat?): ");
                if (selectiveDiv < 0.25) sb.append("GREEN (< 25%%)\n");
                else if (selectiveDiv < 0.30) sb.append("YELLOW (25-30%%)\n");
                else sb.append("RED (> 30%%)\n");

                sb.append("  GATE 12B (fewer tokens?): ");
                if (selectiveTokens < 200) sb.append("GREEN (< 200 tokens)\n");
                else if (selectiveTokens < 400) sb.append("YELLOW (200-400 tokens)\n");
                else sb.append("RED (>= flat)\n");
            }

            // Per-category consistency
            if (!perCategoryConsistency.isEmpty()) {
                sb.append("\nPer-category divergence (ID-RAG selective):\n");
                for (var entry : perCategoryConsistency.entrySet()) {
                    sb.append(String.format("  %-12s %.1f%%%n",
                        entry.getKey(), entry.getValue() * 100));
                }

                sb.append("\n  GATE 12C (per-trait consistency improved?): ");
                double catVariance = perCategoryConsistency.values().stream()
                    .mapToDouble(d -> d).average().orElse(0.5);
                if (catVariance < 0.30) sb.append("GREEN (consistent across categories)\n");
                else if (catVariance < 0.40) sb.append("YELLOW (mixed)\n");
                else sb.append("RED (no improvement)\n");
            }

            return sb.toString();
        }
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String url;
        private String model;
        private String baselineModel;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private TraitGraph traitGraph = TraitGraph.wyrdPersonality();
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        public Builder url(String u) { this.url = u; return this; }
        public Builder model(String m) { this.model = m; return this; }
        public Builder baselineModel(String m) { this.baselineModel = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder traitGraph(TraitGraph g) { this.traitGraph = g; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public IdRagExperiment build() {
            if (url == null) throw new IllegalStateException("url required");
            if (model == null) throw new IllegalStateException("model required");
            if (baselineModel == null) throw new IllegalStateException("baselineModel required");
            return new IdRagExperiment(this);
        }
    }
}
