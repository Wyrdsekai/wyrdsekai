package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Experiment 14: PCL Contrastive Self-Play DPO.
 *
 * Tests whether DPO on self-play contrastive pairs improves personality fidelity.
 * Fixes Experiment 6's DPO failure (74 pairs → overfitting) by using PCL methodology:
 *   - 360 self-play pairs (120 scenarios × 3 variations)
 *   - Model generates both y+ (in-character) and y- (out-of-character)
 *   - DPO trains on the contrast — no human annotation needed
 *
 * 4 conditions compared against gold-standard baseline:
 *
 * | Condition  | Model    | Soul Prompt | What It Tests                         |
 * |------------|----------|-------------|---------------------------------------|
 * | naked      | base     | no          | Control                               |
 * | prompt     | base     | FULL soul   | Existing approach (known: ~30% div)   |
 * | PCL-DPO    | base+DPO | no          | Can DPO alone encode personality?     |
 * | PCL+prompt | base+DPO | FULL soul   | DPO + prompt compounding              |
 *
 * Gates:
 *   14A: PCL-DPO alone < prompt alone → personality in DPO weights
 *   14B: PCL+prompt < best single → DPO and prompt compound
 *   14C: PCL-DPO improvement > 5% over Exp 6 LoRA → PCL fixes DPO methodology
 */
public class PCLExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String baseUrl;
    private final String baseModel;
    private final String pclModel;        // DPO-trained model in Ollama
    private final String baselineModel;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private PCLExperiment(Builder b) {
        this.baseUrl = b.baseUrl;
        this.baseModel = b.baseModel;
        this.pclModel = b.pclModel;
        this.baselineModel = b.baselineModel;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    /**
     * Run the PCL DPO comparison.
     */
    public ComparisonResult run() throws Exception {
        System.out.println("=== Experiment 14: PCL Contrastive Self-Play DPO ===");
        System.out.println("Baseline model: " + baselineModel);
        System.out.println("Base model: " + baseModel);
        System.out.println("PCL-DPO model: " + (pclModel != null ? pclModel : "(none)"));
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold-standard baseline
        System.out.println("--- Gold-standard baseline on " + baselineModel + " ---");
        var baselineInference = new InferenceHelper(baseUrl, baselineModel);
        var baseline = runScenarios(baselineInference, "pcl-baseline-" + baselineModel,
            baseSystemPrompt, null);
        save("pcl-baseline-" + sanitize(baselineModel), baseline);
        System.out.println("Baseline complete\n");

        // Step 2: Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("pcl-soul-full.txt", soulFull);
        int soulTokens = SoulExperiment.estimateTokens(soulFull);
        System.out.println("Soul extracted: ~" + soulTokens + " tokens\n");

        var conditions = new ArrayList<ConditionResult>();

        // Condition 1: naked (base model, no soul)
        System.out.println("--- Condition 1: naked (" + baseModel + ", no soul) ---");
        var nakedInference = new InferenceHelper(baseUrl, baseModel);
        var naked = runScenarios(nakedInference, "pcl-naked-" + baseModel,
            baseSystemPrompt, null);
        save("pcl-naked-" + sanitize(baseModel), naked);
        var nakedReport = compare(baseline, naked);
        conditions.add(new ConditionResult("Naked", naked, nakedReport, 0));
        System.out.println("  Divergence: " + fmt(nakedReport.overallDivergence()) + "\n");

        // Condition 2: prompt only (base model + soul prompt)
        System.out.println("--- Condition 2: prompt only (" + baseModel + " + soul) ---");
        var promptOnly = runScenarios(nakedInference, "pcl-prompt-" + baseModel,
            baseSystemPrompt, soulFull);
        save("pcl-prompt-" + sanitize(baseModel), promptOnly);
        var promptReport = compare(baseline, promptOnly);
        conditions.add(new ConditionResult("Prompt only", promptOnly, promptReport, soulTokens));
        System.out.println("  Divergence: " + fmt(promptReport.overallDivergence()) + "\n");

        // Condition 3: PCL-DPO only (DPO model, no soul prompt)
        if (pclModel != null) {
            System.out.println("--- Condition 3: PCL-DPO only (" + pclModel + ", no soul) ---");
            var pclInference = new InferenceHelper(baseUrl, pclModel);
            var pclOnly = runScenarios(pclInference, "pcl-dpo-" + pclModel,
                baseSystemPrompt, null);
            save("pcl-dpo-" + sanitize(pclModel), pclOnly);
            var pclReport = compare(baseline, pclOnly);
            conditions.add(new ConditionResult("PCL-DPO only", pclOnly, pclReport, 0));
            System.out.println("  Divergence: " + fmt(pclReport.overallDivergence()) + "\n");

            // Condition 4: PCL-DPO + prompt (DPO model + soul prompt)
            System.out.println("--- Condition 4: PCL-DPO + prompt (" + pclModel + " + soul) ---");
            var pclPrompt = runScenarios(pclInference, "pcl-layered-" + pclModel,
                baseSystemPrompt, soulFull);
            save("pcl-layered-" + sanitize(pclModel), pclPrompt);
            var layeredReport = compare(baseline, pclPrompt);
            conditions.add(new ConditionResult("PCL + prompt", pclPrompt, layeredReport, soulTokens));
            System.out.println("  Divergence: " + fmt(layeredReport.overallDivergence()) + "\n");
        }

        var result = new ComparisonResult(baselineModel, baseModel, pclModel,
            baseline, soulFull, conditions);
        var summary = result.summary();
        save("pcl-comparison-summary.txt", summary);
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
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report,
        int promptTokens
    ) {}

    /** Full comparison result with gate analysis. */
    public record ComparisonResult(
        String baselineModel,
        String baseModel,
        String pclModel,
        BehavioralRecord baseline,
        String soul,
        List<ConditionResult> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║   Experiment 14: PCL Contrastive Self-Play DPO              ║\n");
            sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

            sb.append("Gold baseline: ").append(baselineModel).append("\n");
            sb.append("Base model: ").append(baseModel).append("\n");
            sb.append("PCL-DPO model: ").append(pclModel != null ? pclModel : "(none)").append("\n");
            sb.append("Soul size: ~").append(SoulExperiment.estimateTokens(soul)).append(" tokens\n\n");

            sb.append("  CONDITION       DIVERGENCE  SEMANTIC  PROMPT-TOK\n");
            sb.append("  ─────────────── ────────── ───────── ──────────\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-16s %5.1f%%     %s     %d%n",
                    c.condition(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A ",
                    c.promptTokens()));
            }

            // Gate analysis
            var naked = findCondition("Naked");
            var prompt = findCondition("Prompt only");
            var pclOnly = findCondition("PCL-DPO only");
            var pclPrompt = findCondition("PCL + prompt");

            sb.append("\n");

            // Gate 14A: PCL-DPO vs prompt
            sb.append("─── GATE 14A: Can DPO alone encode personality? ───\n");
            if (pclOnly != null && prompt != null) {
                double pclDiv = pclOnly.report().overallDivergence();
                double promptDiv = prompt.report().overallDivergence();
                double diff = pclDiv - promptDiv;

                if (diff < -0.03) {
                    sb.append(String.format("  GREEN: PCL-DPO %.1f%% < prompt %.1f%% (%.1f%% better)%n",
                        pclDiv * 100, promptDiv * 100, -diff * 100));
                    sb.append("  → Personality lives in DPO weights. Prompt-free personality achieved.\n");
                } else if (diff < 0.05) {
                    sb.append(String.format("  YELLOW: PCL-DPO %.1f%% ≈ prompt %.1f%% (within noise)%n",
                        pclDiv * 100, promptDiv * 100));
                    sb.append("  → DPO matches prompt. Both viable, prompt is simpler.\n");
                } else {
                    sb.append(String.format("  RED: PCL-DPO %.1f%% > prompt %.1f%% (%.1f%% worse)%n",
                        pclDiv * 100, promptDiv * 100, diff * 100));
                    sb.append("  → DPO insufficient for personality. Prompt injection still wins.\n");
                }
            } else {
                sb.append("  SKIP: PCL-DPO model not available\n");
            }

            // Gate 14B: compounding
            sb.append("\n─── GATE 14B: Do DPO and prompt compound? ───\n");
            if (pclPrompt != null && prompt != null && pclOnly != null) {
                double layeredDiv = pclPrompt.report().overallDivergence();
                double bestSingle = Math.min(
                    prompt.report().overallDivergence(),
                    pclOnly.report().overallDivergence());

                if (layeredDiv < bestSingle - 0.03) {
                    sb.append(String.format("  GREEN: PCL+prompt %.1f%% < best-single %.1f%% (%.1f%% gain)%n",
                        layeredDiv * 100, bestSingle * 100, (bestSingle - layeredDiv) * 100));
                    sb.append("  → DPO and prompt compound. Use both for best personality.\n");
                } else {
                    sb.append(String.format("  RED: PCL+prompt %.1f%% ≈ best-single %.1f%% (no compounding)%n",
                        layeredDiv * 100, bestSingle * 100));
                    sb.append("  → No benefit from combining. Use simpler approach.\n");
                }
            } else {
                sb.append("  SKIP: PCL-DPO model not available\n");
            }

            // Gate 14C: vs Exp 6 LoRA
            sb.append("\n─── GATE 14C: PCL fixes Exp 6 DPO methodology? ───\n");
            if (pclOnly != null && naked != null) {
                double pclDiv = pclOnly.report().overallDivergence();
                double nakedDiv = naked.report().overallDivergence();
                double improvement = nakedDiv - pclDiv;

                // Exp 6 DPO REGRESSED by 10.5% (55.7% → 66.2%)
                // Any improvement over naked = already better than Exp 6
                if (improvement > 0.05) {
                    sb.append(String.format("  GREEN: PCL-DPO improves %.1f%% over naked (%.1f%% → %.1f%%)%n",
                        improvement * 100, nakedDiv * 100, pclDiv * 100));
                    sb.append("  → PCL self-play fixes DPO. Exp 6 failure was methodology, not DPO itself.\n");
                } else if (improvement > 0.0) {
                    sb.append(String.format("  YELLOW: PCL-DPO marginal %.1f%% improvement (%.1f%% → %.1f%%)%n",
                        improvement * 100, nakedDiv * 100, pclDiv * 100));
                    sb.append("  → Some signal, but insufficient to justify training cost.\n");
                } else {
                    sb.append(String.format("  RED: PCL-DPO no improvement (%.1f%% → %.1f%%)%n",
                        nakedDiv * 100, pclDiv * 100));
                    sb.append("  → DPO fundamentally unsuited for personality at this scale.\n");
                }
            } else {
                sb.append("  SKIP: PCL-DPO model not available\n");
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
        private String baseUrl;
        private String baseModel;
        private String pclModel;
        private String baselineModel;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        public Builder baseUrl(String u) { this.baseUrl = u; return this; }
        public Builder baseModel(String m) { this.baseModel = m; return this; }
        public Builder pclModel(String m) { this.pclModel = m; return this; }
        public Builder baselineModel(String m) { this.baselineModel = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public PCLExperiment build() {
            if (baseUrl == null) throw new IllegalStateException("baseUrl required");
            if (baseModel == null) throw new IllegalStateException("baseModel required");
            if (baselineModel == null) throw new IllegalStateException("baselineModel required");
            return new PCLExperiment(this);
        }
    }
}
