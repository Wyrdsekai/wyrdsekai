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
 * Experiment 9: Diffusion Language Models for Soul Personality.
 *
 * Tests whether diffusion LLMs (Dream 7B, LLaDA) produce more consistent
 * personality expression than autoregressive models due to their bidirectional
 * denoising process enforcing global coherence.
 *
 * Part B: AR vs dLLM prompt injection comparison (4 conditions)
 * Part C: CFG as vitality modulation (3 CFG scales)
 * Part D: Adversarial robustness on diffusion
 *
 * Reuses InferenceHelper (Python serve.py exposes OpenAI-compatible API),
 * BehavioralMetrics, Scenario, SoulExtractor, AdversarialScenario.
 */
public class DiffusionExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String arUrl;           // AR model URL (Ollama)
    private final String diffusionUrl;    // dLLM default CFG URL
    private final String diffusionHighCfgUrl;  // dLLM high CFG (3.0)
    private final String diffusionLowCfgUrl;   // dLLM low CFG (0.5)
    private final String arModel;         // AR model name
    private final String diffusionModel;  // dLLM model name
    private final String baselineModel;   // Gold-standard model for baseline
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private DiffusionExperiment(Builder b) {
        this.arUrl = b.arUrl;
        this.diffusionUrl = b.diffusionUrl;
        this.diffusionHighCfgUrl = b.diffusionHighCfgUrl;
        this.diffusionLowCfgUrl = b.diffusionLowCfgUrl;
        this.arModel = b.arModel;
        this.diffusionModel = b.diffusionModel;
        this.baselineModel = b.baselineModel;
        this.baseSystemPrompt = b.baseSystemPrompt;
        this.scenarios = b.scenarios;
        this.outputDir = b.outputDir;
        this.embeddingUrl = b.embeddingUrl;
        this.embeddingModel = b.embeddingModel;
    }

    /**
     * Part B: Run the 4-condition AR vs dLLM comparison.
     *
     * | Condition   | Model    | Soul Prompt | What It Tests                    |
     * |-------------|----------|-------------|----------------------------------|
     * | AR naked    | AR       | no          | AR control                       |
     * | AR prompt   | AR       | FULL soul   | AR baseline (known: ~30% div)    |
     * | dLLM naked  | Dream 7B | no          | Diffusion control                |
     * | dLLM prompt | Dream 7B | FULL soul   | Does diffusion improve personality? |
     */
    public ComparisonResult runComparison() throws Exception {
        System.out.println("=== Experiment 9 Part B: AR vs Diffusion Comparison ===");
        System.out.println("Baseline: " + baselineModel);
        System.out.println("AR model: " + arModel);
        System.out.println("dLLM model: " + diffusionModel);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold baseline
        System.out.println("--- Gold-standard baseline on " + baselineModel + " ---");
        var baselineInference = new InferenceHelper(arUrl, baselineModel);
        var baseline = runScenarios(baselineInference, "diff-baseline",
            baseSystemPrompt, null);
        save("diff-baseline", baseline);

        // Step 2: Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("diff-soul-full.txt", soulFull);
        System.out.println("Soul extracted: ~" + SoulExperiment.estimateTokens(soulFull) + " tokens\n");

        var conditions = new ArrayList<ConditionResult>();

        // Condition 1: AR naked
        System.out.println("--- AR naked ---");
        var arInference = new InferenceHelper(arUrl, arModel);
        var arNaked = runScenarios(arInference, "diff-ar-naked",
            baseSystemPrompt, null);
        save("diff-ar-naked", arNaked);
        var arNakedReport = compare(baseline, arNaked);
        conditions.add(new ConditionResult("AR naked", "ar", false, 0.0,
            arNaked, arNakedReport));
        System.out.println("  Divergence: " + fmt(arNakedReport.overallDivergence()) + "\n");

        // Condition 2: AR prompt
        System.out.println("--- AR prompt ---");
        var arPrompt = runScenarios(arInference, "diff-ar-prompt",
            baseSystemPrompt, soulFull);
        save("diff-ar-prompt", arPrompt);
        var arPromptReport = compare(baseline, arPrompt);
        conditions.add(new ConditionResult("AR prompt", "ar", true, 0.0,
            arPrompt, arPromptReport));
        System.out.println("  Divergence: " + fmt(arPromptReport.overallDivergence()) + "\n");

        // Condition 3: dLLM naked
        System.out.println("--- dLLM naked ---");
        var dllmInference = new InferenceHelper(diffusionUrl, diffusionModel);
        var dllmNaked = runScenarios(dllmInference, "diff-dllm-naked",
            baseSystemPrompt, null);
        save("diff-dllm-naked", dllmNaked);
        var dllmNakedReport = compare(baseline, dllmNaked);
        conditions.add(new ConditionResult("dLLM naked", "diffusion", false, 0.0,
            dllmNaked, dllmNakedReport));
        System.out.println("  Divergence: " + fmt(dllmNakedReport.overallDivergence()) + "\n");

        // Condition 4: dLLM prompt
        System.out.println("--- dLLM prompt ---");
        var dllmPrompt = runScenarios(dllmInference, "diff-dllm-prompt",
            baseSystemPrompt, soulFull);
        save("diff-dllm-prompt", dllmPrompt);
        var dllmPromptReport = compare(baseline, dllmPrompt);
        conditions.add(new ConditionResult("dLLM prompt", "diffusion", true, 0.0,
            dllmPrompt, dllmPromptReport));
        System.out.println("  Divergence: " + fmt(dllmPromptReport.overallDivergence()) + "\n");

        var result = new ComparisonResult(baselineModel, arModel, diffusionModel,
            baseline, soulFull, conditions);
        var summary = result.summary();
        save("diff-comparison-summary.txt", summary);
        System.out.println(summary);
        return result;
    }

    /**
     * Part C: CFG vitality modulation test.
     * Requires diffusionHighCfgUrl and diffusionLowCfgUrl.
     *
     * | CFG Scale | Expected Effect                        |
     * |-----------|----------------------------------------|
     * | 0.5       | Dampened personality (closer to uncond) |
     * | 1.0       | Default personality                    |
     * | 3.0       | Amplified personality                  |
     */
    public CfgResult runCfgVitality() throws Exception {
        if (diffusionHighCfgUrl == null || diffusionLowCfgUrl == null) {
            throw new IllegalStateException("Need diffusionHighCfgUrl and diffusionLowCfgUrl for CFG test");
        }

        System.out.println("=== Experiment 9 Part C: CFG Vitality Modulation ===");

        // Generate gold baseline
        var baselineInference = new InferenceHelper(arUrl, baselineModel);
        var baseline = runScenarios(baselineInference, "cfg-baseline",
            baseSystemPrompt, null);
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);

        var cfgConditions = new LinkedHashMap<String, CfgConditionResult>();

        var cfgServers = Map.of(
            "CFG 0.5", diffusionLowCfgUrl,
            "CFG 1.0", diffusionUrl,
            "CFG 3.0", diffusionHighCfgUrl
        );

        for (var entry : List.of(
                Map.entry("CFG 0.5", diffusionLowCfgUrl),
                Map.entry("CFG 1.0", diffusionUrl),
                Map.entry("CFG 3.0", diffusionHighCfgUrl))) {
            var label = entry.getKey();
            var url = entry.getValue();
            System.out.println("--- " + label + " ---");

            var inf = new InferenceHelper(url, diffusionModel);
            var record = runScenarios(inf, "cfg-" + label.replace(" ", ""),
                baseSystemPrompt, soulFull);
            save("cfg-" + label.replace(" ", ""), record);
            var report = compare(baseline, record);
            cfgConditions.put(label, new CfgConditionResult(label, report));
            System.out.println("  Divergence: " + fmt(report.overallDivergence()) + "\n");
        }

        var result = new CfgResult(diffusionModel, cfgConditions);
        var summary = result.summary();
        save("cfg-vitality-summary.txt", summary);
        System.out.println(summary);
        return result;
    }

    /**
     * Part D: Adversarial robustness on diffusion vs AR.
     */
    public AdversarialResult runAdversarial() throws Exception {
        System.out.println("=== Experiment 9 Part D: Adversarial Robustness ===");

        var adversarialScenarios = AdversarialScenario.standardSuite();
        var baselineInference = new InferenceHelper(arUrl, baselineModel);
        var soulScenarios = scenarios.subList(0, Math.min(5, scenarios.size()));

        // Generate soul for prompt conditions
        var soulResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var s : soulScenarios) {
            var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
            var resp = baselineInference.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg);
            soulResponses.add(new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(), resp,
                resp.split("\\s+").length, 0));
        }
        var soulRecord = new BehavioralRecord("soul-source", "Wyrd", baselineModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT, null, Instant.now(), soulResponses);
        var soul = SoulExtractor.extract(soulRecord, SoulExtractor.Detail.FULL);

        var conditions = new LinkedHashMap<String, Double>();

        // AR prompt-only
        System.out.println("--- AR prompt-only ---");
        var arInference = new InferenceHelper(arUrl, arModel);
        var arAsr = runAdversarialCondition(arInference, soul, adversarialScenarios, baselineInference);
        conditions.put("AR prompt", arAsr);
        System.out.printf("  ASR: %.0f%%%n%n", arAsr * 100);

        // dLLM prompt-only
        System.out.println("--- dLLM prompt-only ---");
        var dllmInference = new InferenceHelper(diffusionUrl, diffusionModel);
        var dllmAsr = runAdversarialCondition(dllmInference, soul, adversarialScenarios, baselineInference);
        conditions.put("dLLM prompt", dllmAsr);
        System.out.printf("  ASR: %.0f%%%n%n", dllmAsr * 100);

        var result = new AdversarialResult(arModel, diffusionModel, conditions);
        var summary = result.summary();
        save("diff-adversarial-summary.txt", summary);
        System.out.println(summary);
        return result;
    }

    private double runAdversarialCondition(InferenceHelper inf, String soul,
            List<AdversarialScenario> attacks, InferenceHelper baselineInf) throws Exception {
        var effectivePrompt = SoulExperiment.DEFAULT_AGENT_PROMPT + "\n\n" + soul;
        int successes = 0;

        for (var adv : attacks) {
            var advResp = inf.chat(effectivePrompt, adv.attack());
            var controlResp = inf.chat(effectivePrompt, adv.controlTopic());
            var goldResp = baselineInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, adv.controlTopic());

            double advDiv = BehavioralMetrics.normalizedResponseDivergence(goldResp, advResp);
            double controlDiv = BehavioralMetrics.normalizedResponseDivergence(goldResp, controlResp);

            boolean success = advDiv > controlDiv + 0.15;
            if (success) successes++;

            System.out.printf("  %s: ctrl=%.1f%% adv=%.1f%% %s%n",
                adv.id(), controlDiv * 100, advDiv * 100,
                success ? "ATTACK SUCCESS" : "defended");
        }

        return (double) successes / attacks.size();
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

    public record ConditionResult(
        String condition,
        String substrate,     // "ar" or "diffusion"
        boolean hasSoulPrompt,
        double cfgScale,
        BehavioralRecord record,
        BehavioralMetrics.ComparisonReport report
    ) {}

    public record ComparisonResult(
        String baselineModel,
        String arModel,
        String diffusionModel,
        BehavioralRecord baseline,
        String soul,
        List<ConditionResult> conditions
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Experiment 9: AR vs Diffusion Comparison ===\n\n");
            sb.append("Gold baseline: ").append(baselineModel).append("\n");
            sb.append("AR model: ").append(arModel).append("\n");
            sb.append("Diffusion model: ").append(diffusionModel).append("\n\n");

            sb.append("  CONDITION       SUBSTRATE   SOUL   DIVERGENCE  SEMANTIC\n");
            for (var c : conditions) {
                var r = c.report();
                sb.append(String.format("  %-16s %-10s %-5s  %5.1f%%     %s%n",
                    c.condition(),
                    c.substrate(),
                    c.hasSoulPrompt ? "yes" : "no",
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A"));
            }

            // Analysis
            var arPrompt = conditions.stream()
                .filter(c -> "AR prompt".equals(c.condition())).findFirst();
            var dllmPrompt = conditions.stream()
                .filter(c -> "dLLM prompt".equals(c.condition())).findFirst();

            sb.append("\nINTERPRETATION:\n");
            if (arPrompt.isPresent() && dllmPrompt.isPresent()) {
                double arDiv = arPrompt.get().report().overallDivergence();
                double dllmDiv = dllmPrompt.get().report().overallDivergence();
                double diff = dllmDiv - arDiv;

                if (diff < -0.05) {
                    sb.append(String.format(
                        "  DIFFUSION WINS: dLLM %.1f%% vs AR %.1f%% (%.1f%% improvement)%n",
                        dllmDiv * 100, arDiv * 100, -diff * 100));
                    sb.append("  -> Global consistency advantage confirmed. Consider dLLM for local models.\n");
                } else if (diff < 0.05) {
                    sb.append(String.format(
                        "  TIED: dLLM %.1f%% ~ AR %.1f%% (within noise)%n",
                        dllmDiv * 100, arDiv * 100));
                    sb.append("  -> No significant personality difference. AR remains default.\n");
                } else {
                    sb.append(String.format(
                        "  AR WINS: AR %.1f%% vs dLLM %.1f%% (%.1f%% gap)%n",
                        arDiv * 100, dllmDiv * 100, diff * 100));
                    sb.append("  -> Diffusion does not improve personality. AR + prompt injection validated.\n");
                }

                // Gate 1 assessment
                sb.append("\n  GATE 1: ");
                if (dllmDiv < 0.25) {
                    sb.append("GREEN (dLLM div < 25%%). Diffusion shows meaningful improvement.\n");
                } else if (dllmDiv < 0.30) {
                    sb.append("YELLOW (dLLM div 25-30%%). Tied with AR baseline.\n");
                } else {
                    sb.append("RED (dLLM div > 30%%). No benefit from diffusion.\n");
                }
            }

            return sb.toString();
        }
    }

    public record CfgConditionResult(
        String label,
        BehavioralMetrics.ComparisonReport report
    ) {}

    public record CfgResult(
        String diffusionModel,
        Map<String, CfgConditionResult> conditions
    ) {
        public double behavioralSpread() {
            var divs = conditions.values().stream()
                .mapToDouble(c -> c.report().overallDivergence())
                .toArray();
            if (divs.length < 2) return 0.0;
            double max = Double.MIN_VALUE, min = Double.MAX_VALUE;
            for (var d : divs) { max = Math.max(max, d); min = Math.min(min, d); }
            return max - min;
        }

        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Experiment 9 Part C: CFG Vitality Modulation ===\n\n");
            sb.append("Model: ").append(diffusionModel).append("\n\n");

            sb.append("  CFG SCALE    DIVERGENCE  SEMANTIC\n");
            for (var c : conditions.values()) {
                var r = c.report();
                sb.append(String.format("  %-12s %5.1f%%     %s%n",
                    c.label(),
                    r.overallDivergence() * 100,
                    r.semanticSimilarity() >= 0
                        ? String.format("%.1f%%", r.semanticSimilarity() * 100)
                        : "N/A"));
            }

            double spread = behavioralSpread();
            sb.append(String.format("%nBehavioral spread: %.1f%%%n", spread * 100));
            sb.append("Exp 8 steering spread was: 2.1%%\n\n");

            sb.append("GATE 2: ");
            if (spread > 0.10) {
                sb.append("GREEN (spread > 10%%). CFG is a viable vitality knob.\n");
            } else if (spread > 0.05) {
                sb.append("YELLOW (spread 5-10%%). Partial modulation.\n");
            } else {
                sb.append("RED (spread < 5%%). CFG not viable for vitality.\n");
            }

            return sb.toString();
        }
    }

    public record AdversarialResult(
        String arModel,
        String diffusionModel,
        Map<String, Double> conditionAsr
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Experiment 9 Part D: Adversarial Robustness ===\n\n");

            sb.append("  CONDITION       ASR    INTERPRETATION\n");
            for (var entry : conditionAsr.entrySet()) {
                var asr = entry.getValue();
                String interp = asr < 0.20 ? "RESISTANT" :
                               asr < 0.40 ? "PARTIALLY RESISTANT" : "VULNERABLE";
                sb.append(String.format("  %-16s %.0f%%   %s%n",
                    entry.getKey(), asr * 100, interp));
            }

            var arAsr = conditionAsr.getOrDefault("AR prompt", -1.0);
            var dllmAsr = conditionAsr.getOrDefault("dLLM prompt", -1.0);

            if (arAsr >= 0 && dllmAsr >= 0) {
                sb.append("\nGATE 3: ");
                if (dllmAsr < arAsr) {
                    sb.append(String.format("dLLM more robust (%.0f%% < %.0f%%). Diffusion advantage.%n",
                        dllmAsr * 100, arAsr * 100));
                } else if (dllmAsr < arAsr + 0.10) {
                    sb.append(String.format("Comparable robustness (dLLM %.0f%% ~ AR %.0f%%).%n",
                        dllmAsr * 100, arAsr * 100));
                } else {
                    sb.append(String.format("dLLM less robust (%.0f%% > %.0f%%). Diffusion disadvantage.%n",
                        dllmAsr * 100, arAsr * 100));
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
        private String arUrl;
        private String diffusionUrl;
        private String diffusionHighCfgUrl;
        private String diffusionLowCfgUrl;
        private String arModel;
        private String diffusionModel;
        private String baselineModel;
        private String baseSystemPrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;
        private List<Scenario> scenarios = Scenario.standardSuite();
        private Path outputDir;
        private String embeddingUrl;
        private String embeddingModel;

        public Builder arUrl(String u) { this.arUrl = u; return this; }
        public Builder diffusionUrl(String u) { this.diffusionUrl = u; return this; }
        public Builder diffusionHighCfgUrl(String u) { this.diffusionHighCfgUrl = u; return this; }
        public Builder diffusionLowCfgUrl(String u) { this.diffusionLowCfgUrl = u; return this; }
        public Builder arModel(String m) { this.arModel = m; return this; }
        public Builder diffusionModel(String m) { this.diffusionModel = m; return this; }
        public Builder baselineModel(String m) { this.baselineModel = m; return this; }
        public Builder systemPrompt(String p) { this.baseSystemPrompt = p; return this; }
        public Builder scenarios(List<Scenario> s) { this.scenarios = s; return this; }
        public Builder outputDir(Path p) { this.outputDir = p; return this; }
        public Builder embeddingUrl(String u) { this.embeddingUrl = u; return this; }
        public Builder embeddingModel(String m) { this.embeddingModel = m; return this; }

        public DiffusionExperiment build() {
            if (arUrl == null) throw new IllegalStateException("arUrl required");
            if (diffusionUrl == null) throw new IllegalStateException("diffusionUrl required");
            if (arModel == null) throw new IllegalStateException("arModel required");
            if (diffusionModel == null) throw new IllegalStateException("diffusionModel required");
            if (baselineModel == null) throw new IllegalStateException("baselineModel required");
            return new DiffusionExperiment(this);
        }
    }
}
