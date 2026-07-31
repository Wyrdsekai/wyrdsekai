package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Experiment 13: P-Tailor MoE-LoRA Trait-Modular Personality.
 *
 * Tests whether separate LoRA experts per personality trait, merged with
 * configurable weights, produce better personality than a single LoRA (Exp 6)
 * or prompt injection alone. The key question: can per-trait expert weights
 * serve as vitality modulation knobs?
 *
 * 5 traits: philosophical, warm, cryptic, assertive, playful
 * Each expert trained on trait-specific corpus (SFT, rank 8 QLoRA).
 * Merged via weighted sum of LoRA A/B matrices.
 *
 * Conditions:
 *
 * | Condition       | Model    | Soul Prompt | What It Tests                    |
 * |-----------------|----------|-------------|----------------------------------|
 * | Naked           | base     | no          | Control                          |
 * | Prompt only     | base     | FULL soul   | Existing approach (~30% div)     |
 * | MoE default     | base+MoE | no          | Can MoE weights encode persona?  |
 * | MoE + prompt    | base+MoE | FULL soul   | MoE + prompt compounding         |
 * | MoE high-phil   | base+MoE*| no          | Per-trait modulation test        |
 * | MoE high-warm   | base+MoE*| no          | Per-trait modulation test        |
 *
 * Gates:
 *   13A: MoE-default < single-LoRA → modular beats monolithic
 *   13B: Per-trait modulation spread > 10% → vitality knob works
 *   13C: MoE+prompt < prompt alone → MoE compounds with prompt
 */
public class MoELoRAExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String baseUrl;
    private final String baseModel;
    private final String moeDefaultModel;      // MoE with default weights in Ollama
    private final String moeHighPhilModel;     // MoE with high-philosophical weights
    private final String moeHighWarmModel;     // MoE with high-warm weights
    private final String baselineModel;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private MoELoRAExperiment(Builder b) {
        this.baseUrl = b.baseUrl;
        this.baseModel = b.baseModel;
        this.moeDefaultModel = b.moeDefaultModel;
        this.moeHighPhilModel = b.moeHighPhilModel;
        this.moeHighWarmModel = b.moeHighWarmModel;
        this.baselineModel = b.baselineModel;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    public ComparisonResult run() throws Exception {
        System.out.println("=== Experiment 13: MoE-LoRA Trait-Modular Personality ===");
        System.out.println("Baseline model: " + baselineModel);
        System.out.println("Base model: " + baseModel);
        System.out.println("MoE default: " + nn(moeDefaultModel));
        System.out.println("MoE high-phil: " + nn(moeHighPhilModel));
        System.out.println("MoE high-warm: " + nn(moeHighWarmModel));
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold-standard baseline
        System.out.println("--- Gold-standard baseline on " + baselineModel + " ---");
        var baselineInference = new InferenceHelper(baseUrl, baselineModel);
        var baseline = runScenarios(baselineInference, "moe-baseline-" + baselineModel,
            baseSystemPrompt, null);
        save("moe-baseline-" + sanitize(baselineModel), baseline);
        System.out.println("Baseline complete\n");

        // Step 2: Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("moe-soul-full.txt", soulFull);
        int soulTokens = SoulExperiment.estimateTokens(soulFull);
        System.out.println("Soul extracted: ~" + soulTokens + " tokens\n");

        var conditions = new ArrayList<ConditionResult>();

        // Condition 1: Naked
        System.out.println("--- Condition 1: Naked (" + baseModel + ") ---");
        var nakedInf = new InferenceHelper(baseUrl, baseModel);
        var naked = runScenarios(nakedInf, "moe-naked", baseSystemPrompt, null);
        save("moe-naked", naked);
        var nakedReport = compare(baseline, naked);
        conditions.add(new ConditionResult("Naked", baseModel, naked, nakedReport, 0));
        System.out.println("  Divergence: " + fmt(nakedReport.overallDivergence()) + "\n");

        // Condition 2: Prompt only
        System.out.println("--- Condition 2: Prompt only (" + baseModel + " + soul) ---");
        var promptOnly = runScenarios(nakedInf, "moe-prompt", baseSystemPrompt, soulFull);
        save("moe-prompt", promptOnly);
        var promptReport = compare(baseline, promptOnly);
        conditions.add(new ConditionResult("Prompt only", baseModel, promptOnly, promptReport, soulTokens));
        System.out.println("  Divergence: " + fmt(promptReport.overallDivergence()) + "\n");

        // Condition 3: MoE default (no prompt)
        if (moeDefaultModel != null) {
            System.out.println("--- Condition 3: MoE default (" + moeDefaultModel + ") ---");
            var moeInf = new InferenceHelper(baseUrl, moeDefaultModel);
            var moeDefault = runScenarios(moeInf, "moe-default", baseSystemPrompt, null);
            save("moe-default", moeDefault);
            var moeDefaultReport = compare(baseline, moeDefault);
            conditions.add(new ConditionResult("MoE default", moeDefaultModel, moeDefault, moeDefaultReport, 0));
            System.out.println("  Divergence: " + fmt(moeDefaultReport.overallDivergence()) + "\n");

            // Condition 4: MoE + prompt
            System.out.println("--- Condition 4: MoE + prompt (" + moeDefaultModel + " + soul) ---");
            var moePlusPrompt = runScenarios(moeInf, "moe-plus-prompt", baseSystemPrompt, soulFull);
            save("moe-plus-prompt", moePlusPrompt);
            var moePlusReport = compare(baseline, moePlusPrompt);
            conditions.add(new ConditionResult("MoE + prompt", moeDefaultModel, moePlusPrompt, moePlusReport, soulTokens));
            System.out.println("  Divergence: " + fmt(moePlusReport.overallDivergence()) + "\n");
        }

        // Condition 5: MoE high-philosophical (vitality test)
        if (moeHighPhilModel != null) {
            System.out.println("--- Condition 5: MoE high-philosophical (" + moeHighPhilModel + ") ---");
            var philInf = new InferenceHelper(baseUrl, moeHighPhilModel);
            var philResult = runScenarios(philInf, "moe-high-phil", baseSystemPrompt, null);
            save("moe-high-phil", philResult);
            var philReport = compare(baseline, philResult);
            conditions.add(new ConditionResult("MoE high-phil", moeHighPhilModel, philResult, philReport, 0));
            System.out.println("  Divergence: " + fmt(philReport.overallDivergence()) + "\n");
        }

        // Condition 6: MoE high-warm (vitality test)
        if (moeHighWarmModel != null) {
            System.out.println("--- Condition 6: MoE high-warm (" + moeHighWarmModel + ") ---");
            var warmInf = new InferenceHelper(baseUrl, moeHighWarmModel);
            var warmResult = runScenarios(warmInf, "moe-high-warm", baseSystemPrompt, null);
            save("moe-high-warm", warmResult);
            var warmReport = compare(baseline, warmResult);
            conditions.add(new ConditionResult("MoE high-warm", moeHighWarmModel, warmResult, warmReport, 0));
            System.out.println("  Divergence: " + fmt(warmReport.overallDivergence()) + "\n");
        }

        var result = new ComparisonResult(baselineModel, baseModel, moeDefaultModel,
            baseline, soulFull, conditions);
        var summary = result.summary();
        save("moe-comparison-summary.txt", summary);
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

    private static String nn(String s) { return s != null ? s : "(none)"; }

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
        String baseModel,
        String moeModel,
        BehavioralRecord baseline,
        String soul,
        List<ConditionResult> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║   Experiment 13: MoE-LoRA Trait-Modular Personality          ║\n");
            sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

            sb.append("Gold baseline: ").append(baselineModel).append("\n");
            sb.append("Base model: ").append(baseModel).append("\n");
            sb.append("MoE model: ").append(moeModel != null ? moeModel : "(none)").append("\n\n");

            sb.append("  CONDITION          MODEL              DIVERGENCE  SEMANTIC  PROMPT-TOK\n");
            sb.append("  ──────────────── ─────────────────── ────────── ───────── ──────────\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-17s %-19s %5.1f%%     %s     %d%n",
                    c.condition(),
                    c.model().length() > 19 ? c.model().substring(0, 16) + "..." : c.model(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A ",
                    c.promptTokens()));
            }

            sb.append("\n");

            // Gate 13A: MoE default vs prompt
            sb.append("─── GATE 13A: Does MoE-LoRA beat prompt injection? ───\n");
            var moeDefault = findCondition("MoE default");
            var prompt = findCondition("Prompt only");
            if (moeDefault != null && prompt != null) {
                double moeDiv = moeDefault.report().overallDivergence();
                double promptDiv = prompt.report().overallDivergence();
                double diff = moeDiv - promptDiv;
                if (diff < -0.03) {
                    sb.append(String.format("  GREEN: MoE %.1f%% < prompt %.1f%% (%.1f%% better)%n",
                        moeDiv * 100, promptDiv * 100, -diff * 100));
                    sb.append("  → Modular LoRA experts beat flat prompt.\n");
                } else if (diff < 0.05) {
                    sb.append(String.format("  YELLOW: MoE %.1f%% ≈ prompt %.1f%% (within noise)%n",
                        moeDiv * 100, promptDiv * 100));
                    sb.append("  → MoE matches prompt. Prompt simpler, MoE not worth the cost.\n");
                } else {
                    sb.append(String.format("  RED: MoE %.1f%% > prompt %.1f%% (%.1f%% worse)%n",
                        moeDiv * 100, promptDiv * 100, diff * 100));
                    sb.append("  → MoE doesn't capture personality. Prompt injection still wins.\n");
                }
            } else {
                sb.append("  SKIP: MoE model not available\n");
            }

            // Gate 13B: Per-trait modulation
            sb.append("\n─── GATE 13B: Does per-trait modulation work? ───\n");
            var highPhil = findCondition("MoE high-phil");
            var highWarm = findCondition("MoE high-warm");
            if (moeDefault != null && highPhil != null && highWarm != null) {
                double defaultDiv = moeDefault.report().overallDivergence();
                double philDiv = highPhil.report().overallDivergence();
                double warmDiv = highWarm.report().overallDivergence();
                double spread = Math.abs(philDiv - warmDiv);
                double maxSpread = Math.max(Math.abs(philDiv - defaultDiv),
                    Math.max(Math.abs(warmDiv - defaultDiv), spread));

                if (maxSpread > 0.10) {
                    sb.append(String.format("  GREEN: Spread %.1f%% (phil=%.1f%%, warm=%.1f%%, default=%.1f%%)%n",
                        maxSpread * 100, philDiv * 100, warmDiv * 100, defaultDiv * 100));
                    sb.append("  → Per-trait weights produce measurably different behavior. Vitality knob works.\n");
                } else if (maxSpread > 0.05) {
                    sb.append(String.format("  YELLOW: Spread %.1f%% (phil=%.1f%%, warm=%.1f%%, default=%.1f%%)%n",
                        maxSpread * 100, philDiv * 100, warmDiv * 100, defaultDiv * 100));
                    sb.append("  → Some modulation visible but insufficient for vitality control.\n");
                } else {
                    sb.append(String.format("  RED: Spread %.1f%% (phil=%.1f%%, warm=%.1f%%, default=%.1f%%)%n",
                        maxSpread * 100, philDiv * 100, warmDiv * 100, defaultDiv * 100));
                    sb.append("  → Trait weights don't change behavior. Experts not sufficiently specialized.\n");
                }
            } else {
                sb.append("  SKIP: Need MoE default + high-phil + high-warm for modulation test\n");
            }

            // Gate 13C: MoE + prompt compounding
            sb.append("\n─── GATE 13C: Do MoE and prompt compound? ───\n");
            var moePlusPrompt = findCondition("MoE + prompt");
            if (moePlusPrompt != null && prompt != null && moeDefault != null) {
                double layeredDiv = moePlusPrompt.report().overallDivergence();
                double bestSingle = Math.min(
                    prompt.report().overallDivergence(),
                    moeDefault.report().overallDivergence());
                if (layeredDiv < bestSingle - 0.03) {
                    sb.append(String.format("  GREEN: MoE+prompt %.1f%% < best-single %.1f%% (%.1f%% gain)%n",
                        layeredDiv * 100, bestSingle * 100, (bestSingle - layeredDiv) * 100));
                    sb.append("  → MoE and prompt compound. Use both for best personality.\n");
                } else {
                    sb.append(String.format("  RED: MoE+prompt %.1f%% ≈ best-single %.1f%% (no compounding)%n",
                        layeredDiv * 100, bestSingle * 100));
                    sb.append("  → No benefit from combining. Use simpler approach.\n");
                }
            } else {
                sb.append("  SKIP: MoE model not available\n");
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
        private String moeDefaultModel;
        private String moeHighPhilModel;
        private String moeHighWarmModel;
        private String baselineModel;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        public Builder baseUrl(String u) { this.baseUrl = u; return this; }
        public Builder baseModel(String m) { this.baseModel = m; return this; }
        public Builder moeDefaultModel(String m) { this.moeDefaultModel = m; return this; }
        public Builder moeHighPhilModel(String m) { this.moeHighPhilModel = m; return this; }
        public Builder moeHighWarmModel(String m) { this.moeHighWarmModel = m; return this; }
        public Builder baselineModel(String m) { this.baselineModel = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public MoELoRAExperiment build() {
            if (baseUrl == null) throw new IllegalStateException("baseUrl required");
            if (baseModel == null) throw new IllegalStateException("baseModel required");
            if (baselineModel == null) throw new IllegalStateException("baselineModel required");
            return new MoELoRAExperiment(this);
        }
    }
}
