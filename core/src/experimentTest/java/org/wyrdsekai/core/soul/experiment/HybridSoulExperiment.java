package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Experiment 17: Hybrid Soul Retrieval.
 *
 * Tests whether MEDIUM-resident + semantic retrieval of DEEP fragments
 * can match DEEP-flat quality at much lower resident token cost.
 *
 * Prior results:
 *   MEDIUM = 29.4% div, 69.7% semantic (71 tokens)
 *   DEEP   = 26.2% div, 76.5% semantic (3927 tokens)
 *   Gap    = 3.2% div,  6.8% semantic
 *
 * Hypothesis: retrieving 1-5 relevant DEEP fragments per scenario
 * can close the gap without the full 3927-token cost.
 *
 * 5 conditions:
 *   1. MEDIUM only (baseline, ~71 tokens)
 *   2. MEDIUM + top-1 retrieved fragment
 *   3. MEDIUM + top-3 retrieved fragments
 *   4. MEDIUM + top-5 retrieved fragments
 *   5. DEEP flat (ceiling, ~3927 tokens)
 *
 * Gates:
 *   17A: Does hybrid beat MEDIUM? (div < MEDIUM's)
 *   17B: Does hybrid match DEEP? (div within 2% of DEEP)
 *   17C: What's the sweet spot? (best tokens-to-quality ratio)
 */
public class HybridSoulExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String baseUrl;
    private final String model;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private HybridSoulExperiment(Builder b) {
        this.baseUrl = b.baseUrl;
        this.model = b.model;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    public HybridResult run() throws Exception {
        System.out.println("=== Experiment 17: Hybrid Soul Retrieval ===");
        System.out.println("Model: " + model);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println("Embedding: " + embeddingModel + " @ " + embeddingUrl);
        System.out.println();

        var inference = new InferenceHelper(baseUrl, model);

        // Step 1: Gold-standard baseline
        System.out.println("--- Gold-standard baseline ---");
        var baseline = runScenarios(inference, "hybrid-baseline",
            baseSystemPrompt, null);
        save("hybrid-baseline", baseline);
        System.out.println("Baseline complete: " + baseline.responses().size() + " responses\n");

        // Step 2: Extract MEDIUM soul and DEEP fragments
        var mediumSoul = SoulExtractor.extract(baseline, SoulExtractor.Detail.MEDIUM);
        var deepSoul = SoulExtractor.extract(baseline, SoulExtractor.Detail.DEEP);
        var fragments = SoulExtractor.fragmentDeep(baseline);

        System.out.println("MEDIUM soul: ~" + SoulExperiment.estimateTokens(mediumSoul) + " tokens");
        System.out.println("DEEP soul:   ~" + SoulExperiment.estimateTokens(deepSoul) + " tokens");
        System.out.println("Fragments:   " + fragments.size());
        for (var f : fragments) {
            System.out.println("  " + f.id() + " [" + f.category() + "]: ~"
                + SoulExperiment.estimateTokens(f.text()) + " tokens");
        }
        System.out.println();

        save("hybrid-medium-soul.txt", mediumSoul);
        save("hybrid-deep-soul.txt", deepSoul);

        // Step 3: Embed all fragments for retrieval
        System.out.println("--- Embedding fragments ---");
        var fragmentTexts = fragments.stream().map(SoulExtractor.SoulFragment::text).toList();
        var fragmentEmbeddings = BehavioralMetrics.fetchEmbeddings(
            embeddingUrl, embeddingModel, fragmentTexts);
        System.out.println("Embedded " + fragmentEmbeddings.size() + " fragments\n");

        // Step 4: Run conditions
        var conditions = new ArrayList<HybridCondition>();

        // Condition 1: MEDIUM only
        System.out.println("--- Condition 1: MEDIUM only ---");
        var mediumRecord = runScenarios(inference, "hybrid-medium",
            baseSystemPrompt, mediumSoul);
        save("hybrid-medium", mediumRecord);
        var mediumReport = compare(baseline, mediumRecord);
        int mediumTokens = SoulExperiment.estimateTokens(mediumSoul);
        conditions.add(new HybridCondition("MEDIUM only", 0, mediumTokens,
            mediumRecord, mediumReport));
        printConditionResult(conditions.getLast());

        // Conditions 2-4: MEDIUM + top-K retrieved
        for (int k : List.of(1, 3, 5)) {
            String label = "MEDIUM + top-" + k;
            System.out.println("--- Condition: " + label + " ---");
            var record = runWithRetrieval(inference, "hybrid-top" + k, baseline,
                mediumSoul, fragments, fragmentEmbeddings, k);
            save("hybrid-top" + k, record);
            var report = compare(baseline, record);
            int avgTokens = estimateAverageTokens(mediumSoul, fragments, fragmentEmbeddings, k);
            conditions.add(new HybridCondition(label, k, avgTokens, record, report));
            printConditionResult(conditions.getLast());
        }

        // Condition 5: DEEP flat
        System.out.println("--- Condition 5: DEEP flat ---");
        var deepRecord = runScenarios(inference, "hybrid-deep",
            baseSystemPrompt, deepSoul);
        save("hybrid-deep", deepRecord);
        var deepReport = compare(baseline, deepRecord);
        int deepTokens = SoulExperiment.estimateTokens(deepSoul);
        conditions.add(new HybridCondition("DEEP flat", -1, deepTokens,
            deepRecord, deepReport));
        printConditionResult(conditions.getLast());

        var result = new HybridResult(model, baseline, mediumSoul, deepSoul,
            fragments, conditions);
        var summary = result.summary();
        save("hybrid-summary.txt", summary);
        System.out.println(summary);
        return result;
    }

    // --- Retrieval logic ---

    /**
     * Run scenarios with per-scenario fragment retrieval.
     * For each scenario, embed the player message, find top-K most similar fragments,
     * and append them to the MEDIUM soul.
     */
    private BehavioralRecord runWithRetrieval(InferenceHelper inf, String runId,
            BehavioralRecord baseline, String mediumSoul,
            List<SoulExtractor.SoulFragment> fragments, List<double[]> fragmentEmbeddings,
            int topK) throws Exception {

        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");

            // Embed scenario player message for retrieval
            var queryEmbedding = BehavioralMetrics.fetchEmbeddings(
                embeddingUrl, embeddingModel, List.of(scenario.playerMessage()));

            // Rank fragments by cosine similarity
            var ranked = rankFragments(queryEmbedding.getFirst(),
                fragments, fragmentEmbeddings, topK);

            // Build augmented soul: MEDIUM + retrieved fragments
            var augmentedSoul = buildAugmentedSoul(mediumSoul, ranked);

            long start = System.currentTimeMillis();
            var userMessage = buildUserMessage(scenario);
            var effectivePrompt = baseSystemPrompt + "\n\n" + augmentedSoul;
            var response = inf.chat(effectivePrompt, userMessage);
            long elapsed = System.currentTimeMillis() - start;

            int tokens = response.split("\\s+").length;
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));

            System.out.println(elapsed + "ms, ~" + tokens + " words (+"
                + ranked.size() + " fragments)");
        }

        return new BehavioralRecord(runId, "Wyrd", inf.model(), baseSystemPrompt,
            mediumSoul + " [+retrieval]", Instant.now(), responses);
    }

    /**
     * Rank fragments by cosine similarity to query, return top K.
     * Excludes the identity-core fragment (it's always resident as MEDIUM).
     */
    static List<SoulExtractor.SoulFragment> rankFragments(double[] queryEmbedding,
            List<SoulExtractor.SoulFragment> fragments, List<double[]> fragmentEmbeddings,
            int topK) {

        record Scored(SoulExtractor.SoulFragment fragment, double score) {}

        var scored = new ArrayList<Scored>();
        for (int i = 0; i < fragments.size(); i++) {
            var f = fragments.get(i);
            // Skip identity-core — it's always resident as MEDIUM
            if ("identity-core".equals(f.id())) continue;
            double sim = BehavioralMetrics.cosineSimilarity(queryEmbedding, fragmentEmbeddings.get(i));
            scored.add(new Scored(f, sim));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream()
            .limit(topK)
            .map(Scored::fragment)
            .toList();
    }

    static String buildAugmentedSoul(String mediumSoul,
            List<SoulExtractor.SoulFragment> retrievedFragments) {
        if (retrievedFragments.isEmpty()) return mediumSoul;

        var sb = new StringBuilder(mediumSoul);
        sb.append("\n\n--- Retrieved Context ---\n");
        for (var f : retrievedFragments) {
            sb.append("\n[").append(f.label()).append("]\n");
            sb.append(f.text()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Estimate average token cost across scenarios for a given K.
     * Uses actual fragment sizes — in practice each scenario retrieves different fragments.
     */
    private int estimateAverageTokens(String mediumSoul,
            List<SoulExtractor.SoulFragment> fragments, List<double[]> fragmentEmbeddings,
            int topK) {
        int mediumTokens = SoulExperiment.estimateTokens(mediumSoul);
        // Average fragment size (excluding identity-core)
        double avgFragmentTokens = fragments.stream()
            .filter(f -> !"identity-core".equals(f.id()))
            .mapToInt(f -> SoulExperiment.estimateTokens(f.text()))
            .average().orElse(0);
        return mediumTokens + (int)(topK * avgFragmentTokens);
    }

    // --- Infrastructure ---

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

        return new BehavioralRecord(runId, "Wyrd", inf.model(), systemPrompt,
            soulLayer, Instant.now(), responses);
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
        return BehavioralMetrics.compareWithEmbeddings(baseline, other,
            embeddingUrl, embeddingModel);
    }

    private void printConditionResult(HybridCondition c) {
        System.out.println("  Divergence: " + fmt(c.report().overallDivergence()));
        System.out.println("  Semantic:   " + fmt(c.report().semanticSimilarity()));
        System.out.println("  ~" + c.avgTokens() + " tokens\n");
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

    public record HybridCondition(
        String label,
        int retrievedK,     // -1 = DEEP flat, 0 = MEDIUM only, 1/3/5 = hybrid
        int avgTokens,
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report
    ) {}

    public record HybridResult(
        String model,
        BehavioralRecord baseline,
        String mediumSoul,
        String deepSoul,
        List<SoulExtractor.SoulFragment> fragments,
        List<HybridCondition> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║   Experiment 17: Hybrid Soul Retrieval                       ║\n");
            sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

            sb.append("Model: ").append(model).append("\n");
            sb.append("Fragments: ").append(fragments.size()).append("\n\n");

            sb.append("  CONDITION        TOKENS  DIVERGENCE  SEMANTIC    VOCAB-OVERLAP\n");
            sb.append("  ──────────────── ─────── ────────── ─────────── ──────────────\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-17s %5d   %5.1f%%      %5.1f%%      %5.1f%%%n",
                    c.label(), c.avgTokens(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() * 100,
                    r.vocabularyOverlap() * 100));
            }

            // Find key conditions
            var medium = findCondition("MEDIUM only");
            var deep = findCondition("DEEP flat");
            var top1 = findCondition("MEDIUM + top-1");
            var top3 = findCondition("MEDIUM + top-3");
            var top5 = findCondition("MEDIUM + top-5");

            // Gate 17A: Does hybrid beat MEDIUM?
            sb.append("\n─── GATE 17A: Does hybrid beat MEDIUM? ───\n");
            if (medium != null) {
                double mediumDiv = medium.report().overallDivergence();
                var bestHybrid = conditions.stream()
                    .filter(c -> c.retrievedK() > 0)
                    .min(Comparator.comparingDouble(c -> c.report().overallDivergence()));
                if (bestHybrid.isPresent()) {
                    double hybridDiv = bestHybrid.get().report().overallDivergence();
                    double improvement = mediumDiv - hybridDiv;
                    if (improvement > 0.03) {
                        sb.append(String.format("  GREEN: %s %.1f%% vs MEDIUM %.1f%% (%.1f%% better)%n",
                            bestHybrid.get().label(), hybridDiv * 100, mediumDiv * 100, improvement * 100));
                        sb.append("  → Retrieval adds measurable value over flat MEDIUM.\n");
                    } else if (improvement > 0.01) {
                        sb.append(String.format("  YELLOW: %s %.1f%% vs MEDIUM %.1f%% (%.1f%% marginal)%n",
                            bestHybrid.get().label(), hybridDiv * 100, mediumDiv * 100, improvement * 100));
                        sb.append("  → Small improvement. May not justify retrieval complexity.\n");
                    } else {
                        sb.append(String.format("  RED: Best hybrid %.1f%% ≈ MEDIUM %.1f%% (no improvement)%n",
                            hybridDiv * 100, mediumDiv * 100));
                        sb.append("  → Retrieval doesn't help. MEDIUM captures what matters.\n");
                    }
                }
            }

            // Gate 17B: Does hybrid match DEEP?
            sb.append("\n─── GATE 17B: Does hybrid match DEEP? ───\n");
            if (deep != null) {
                double deepDiv = deep.report().overallDivergence();
                var bestHybrid = conditions.stream()
                    .filter(c -> c.retrievedK() > 0)
                    .min(Comparator.comparingDouble(c -> c.report().overallDivergence()));
                if (bestHybrid.isPresent()) {
                    double hybridDiv = bestHybrid.get().report().overallDivergence();
                    double gap = hybridDiv - deepDiv;
                    if (gap < 0.02) {
                        sb.append(String.format("  GREEN: %s %.1f%% ≈ DEEP %.1f%% (within 2%%)%n",
                            bestHybrid.get().label(), hybridDiv * 100, deepDiv * 100));
                        sb.append("  → Hybrid achieves DEEP quality at fraction of token cost!\n");
                    } else if (gap < 0.05) {
                        sb.append(String.format("  YELLOW: %s %.1f%% vs DEEP %.1f%% (%.1f%% gap)%n",
                            bestHybrid.get().label(), hybridDiv * 100, deepDiv * 100, gap * 100));
                        sb.append("  → Close but not equivalent. Partial retrieval benefit.\n");
                    } else {
                        sb.append(String.format("  RED: Best hybrid %.1f%% vs DEEP %.1f%% (%.1f%% gap)%n",
                            hybridDiv * 100, deepDiv * 100, gap * 100));
                        sb.append("  → Retrieval can't match flat DEEP. Resident context matters.\n");
                    }
                }
            }

            // Gate 17C: Sweet spot
            sb.append("\n─── GATE 17C: Token efficiency sweet spot ───\n");
            sb.append("  Token efficiency (divergence per token):\n");
            HybridCondition bestEfficiency = null;
            double bestRatio = Double.MAX_VALUE;
            for (var c : conditions) {
                double div = c.report().overallDivergence();
                double ratio = div / Math.max(1, c.avgTokens());
                sb.append(String.format("    %-17s %5d tok → %5.1f%% div (%.5f div/tok)%n",
                    c.label(), c.avgTokens(), div * 100, ratio));
                if (ratio < bestRatio) {
                    bestRatio = ratio;
                    bestEfficiency = c;
                }
            }
            if (bestEfficiency != null) {
                sb.append("  → Best efficiency: ").append(bestEfficiency.label());
                sb.append(" (").append(bestEfficiency.avgTokens()).append(" tokens)\n");
            }

            // Comparison with Exp 15 results
            sb.append("\n─── Comparison with Exp 15 ───\n");
            sb.append("  Exp 15 MEDIUM: 29.4% div, 69.7% semantic (~71 tokens)\n");
            sb.append("  Exp 15 DEEP:   26.2% div, 76.5% semantic (~3927 tokens)\n");
            if (medium != null && deep != null) {
                sb.append(String.format("  Exp 17 MEDIUM: %.1f%% div, %.1f%% semantic (~%d tokens)%n",
                    medium.report().overallDivergence() * 100,
                    medium.report().semanticSimilarity() * 100,
                    medium.avgTokens()));
                sb.append(String.format("  Exp 17 DEEP:   %.1f%% div, %.1f%% semantic (~%d tokens)%n",
                    deep.report().overallDivergence() * 100,
                    deep.report().semanticSimilarity() * 100,
                    deep.avgTokens()));
            }

            // Trend
            sb.append("\n─── TREND ───\n");
            sb.append("  Tokens → Divergence:\n");
            for (var c : conditions) {
                int barLen = (int) (c.report().overallDivergence() * 50);
                sb.append(String.format("  %5d  ", c.avgTokens()));
                sb.append("█".repeat(Math.max(1, barLen)));
                sb.append(String.format(" %.1f%%%n", c.report().overallDivergence() * 100));
            }

            return sb.toString();
        }

        private HybridCondition findCondition(String label) {
            return conditions.stream()
                .filter(c -> c.label().equals(label))
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

        public HybridSoulExperiment build() {
            if (baseUrl == null) throw new IllegalStateException("baseUrl required");
            if (model == null) throw new IllegalStateException("model required");
            if (embeddingUrl == null) throw new IllegalStateException("embeddingUrl required");
            if (embeddingModel == null) throw new IllegalStateException("embeddingModel required");
            return new HybridSoulExperiment(this);
        }
    }
}
