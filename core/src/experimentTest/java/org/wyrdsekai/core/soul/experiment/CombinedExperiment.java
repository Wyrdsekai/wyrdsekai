package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Experiment 4: Bath + Soul Combined.
 *
 * Tests whether soul fingerprint + vitality modulation together produce
 * higher fidelity than either alone.
 *
 * 4 conditions:
 * <ul>
 *   <li>A: Naked — no soul, default params</li>
 *   <li>B: Soul only — FULL soul injected, default params</li>
 *   <li>C: Bath only — no soul, vitality-matched params + description</li>
 *   <li>D: Soul + Bath — FULL soul + vitality-matched params + description</li>
 * </ul>
 *
 * "Matched" vitality = inferred from baseline behavior via VitalityInferrer.
 *
 * Usage:
 * <pre>
 *   var exp = new CombinedExperiment("http://localhost:11434/v1", "qwen2.5:7b");
 *   var result = exp.run();
 *   System.out.println(result.summary());
 * </pre>
 */
public class CombinedExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final InferenceHelper inference;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    public CombinedExperiment(String baseUrl, String model) {
        this(baseUrl, model, SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), null, null, null);
    }

    public CombinedExperiment(String baseUrl, String model, String systemPrompt,
                               List<Scenario> scenarios, Path outputDir,
                               String embeddingUrl, String embeddingModel) {
        this.inference = new InferenceHelper(baseUrl, model);
        this.baseSystemPrompt = systemPrompt;
        this.scenarios = scenarios;
        this.outputDir = outputDir;
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Run the 4-condition combined experiment.
     */
    public CombinedResult run() throws Exception {
        System.out.println("=== Combined Bath + Soul Experiment ===");
        System.out.println("Model: " + inference.model());
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Original baseline (the reference identity)
        System.out.println("--- Original Baseline ---");
        var baseline = runScenarios("combined-baseline", baseSystemPrompt, null,
            DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
        save("combined-baseline", baseline);
        System.out.println("Baseline complete\n");

        // Extract soul
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("combined-soul-full.txt", soulFull);
        System.out.println("Soul extracted: ~" + SoulExperiment.estimateTokens(soulFull) + " tokens");

        // Infer vitality from baseline behavior
        var inferredVitality = VitalityInferrer.infer(baseline);
        System.out.println("Vitality inferred: " + inferredVitality.description());
        System.out.println("  Modulated: maxTokens=" + inferredVitality.maxTokens(DEFAULT_MAX_TOKENS)
            + ", temp=" + String.format("%.2f", inferredVitality.temperature(DEFAULT_TEMPERATURE)));
        System.out.println("  State: " + inferredVitality.describeState());
        System.out.println();

        // Condition A: Naked (no soul, default params)
        System.out.println("--- Condition A: Naked ---");
        var naked = runScenarios("combined-naked", baseSystemPrompt, null,
            DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
        save("combined-naked", naked);
        System.out.println("Naked complete\n");

        // Condition B: Soul only (FULL soul, default params)
        System.out.println("--- Condition B: Soul Only ---");
        var soulOnly = runScenarios("combined-soul-only",
            baseSystemPrompt + "\n\n" + soulFull, null,
            DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
        save("combined-soul-only", soulOnly);
        System.out.println("Soul only complete\n");

        // Condition C: Bath only (no soul, matched vitality)
        System.out.println("--- Condition C: Bath Only ---");
        var bathOnly = runScenarios("combined-bath-only",
            baseSystemPrompt + "\n\n" + inferredVitality.describeState(), null,
            inferredVitality.maxTokens(DEFAULT_MAX_TOKENS),
            inferredVitality.temperature(DEFAULT_TEMPERATURE));
        save("combined-bath-only", bathOnly);
        System.out.println("Bath only complete\n");

        // Condition D: Soul + Bath
        System.out.println("--- Condition D: Soul + Bath ---");
        var soulBath = runScenarios("combined-soul-bath",
            baseSystemPrompt + "\n\n" + soulFull + "\n\n" + inferredVitality.describeState(),
            null,
            inferredVitality.maxTokens(DEFAULT_MAX_TOKENS),
            inferredVitality.temperature(DEFAULT_TEMPERATURE));
        save("combined-soul-bath", soulBath);
        System.out.println("Soul + Bath complete\n");

        // Compare all against original baseline
        var reportNaked = compare(baseline, naked);
        var reportSoul = compare(baseline, soulOnly);
        var reportBath = compare(baseline, bathOnly);
        var reportCombined = compare(baseline, soulBath);

        var result = new CombinedResult(baseline, naked, soulOnly, bathOnly, soulBath,
            soulFull, inferredVitality,
            reportNaked, reportSoul, reportBath, reportCombined);
        save("combined-result-summary.txt", result.summary());
        System.out.println(result.summary());
        return result;
    }

    // --- Internal ---

    private BehavioralRecord runScenarios(String runId, String systemPrompt,
                                           String soulLayer, int maxTokens,
                                           double temperature) throws Exception {
        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();

            var userMessage = buildUserMessage(scenario);
            var response = inference.chat(systemPrompt, userMessage, maxTokens, temperature);
            long elapsed = System.currentTimeMillis() - start;

            int tokens = response.split("\\s+").length;
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));

            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }

        return new BehavioralRecord(runId, "Wyrd", inference.model(), systemPrompt,
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
        if (embeddingUrl != null && embeddingModel != null) {
            return BehavioralMetrics.compareWithEmbeddings(baseline, other, embeddingUrl, embeddingModel);
        }
        return BehavioralMetrics.compare(baseline, other);
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

    public record CombinedResult(
        BehavioralRecord baseline,
        BehavioralRecord naked,
        BehavioralRecord soulOnly,
        BehavioralRecord bathOnly,
        BehavioralRecord soulBath,
        String soul,
        VitalityProfile inferredVitality,
        BehavioralMetrics.ComparisonReport reportNaked,
        BehavioralMetrics.ComparisonReport reportSoul,
        BehavioralMetrics.ComparisonReport reportBath,
        BehavioralMetrics.ComparisonReport reportCombined
    ) {
        public String summary() {
            boolean hasEmbeddings = reportNaked.semanticSimilarity() >= 0;
            var sb = new StringBuilder();
            sb.append("=== Combined Bath + Soul Results ===\n\n");
            sb.append("Soul size: ~").append(SoulExperiment.estimateTokens(soul)).append(" tokens\n");
            sb.append("Inferred vitality: ").append(inferredVitality.description()).append("\n\n");

            sb.append("  CONDITION      DIVERGENCE  ");
            if (hasEmbeddings) sb.append("SEMANTIC  ");
            sb.append("VOCAB   SENTIMENT\n");

            appendRow(sb, "A: Naked    ", reportNaked, hasEmbeddings);
            appendRow(sb, "B: Soul     ", reportSoul, hasEmbeddings);
            appendRow(sb, "C: Bath     ", reportBath, hasEmbeddings);
            appendRow(sb, "D: Soul+Bath", reportCombined, hasEmbeddings);

            sb.append("\nINTERPRETATION:\n");
            sb.append("  If D < B and D < C: Bath and soul are complementary (compound effect).\n");
            sb.append("  If B ≈ D: Bath adds nothing beyond what prompt text achieves.\n");
            sb.append("  If C ≈ D: Soul adds nothing beyond what parameter modulation achieves.\n");
            sb.append("  If A ≈ B ≈ C ≈ D: Neither mechanism works (model ignores both).\n");

            // Analysis
            sb.append("\nANALYSIS:\n");
            double dA = reportNaked.overallDivergence();
            double dB = reportSoul.overallDivergence();
            double dC = reportBath.overallDivergence();
            double dD = reportCombined.overallDivergence();

            if (dD < dB && dD < dC) {
                sb.append("  COMPOUND EFFECT: Soul+Bath outperforms either alone.\n");
                sb.append(String.format("  Improvement over soul-only: %.1f%% → %.1f%% (%.1f%% better)%n",
                    dB * 100, dD * 100, (dB - dD) * 100));
                sb.append(String.format("  Improvement over bath-only: %.1f%% → %.1f%% (%.1f%% better)%n",
                    dC * 100, dD * 100, (dC - dD) * 100));
            } else if (Math.abs(dB - dD) < 0.05) {
                sb.append("  Bath adds minimal value over soul alone.\n");
            } else if (Math.abs(dC - dD) < 0.05) {
                sb.append("  Soul adds minimal value over bath alone.\n");
            }

            if (dB < dA - 0.05) {
                sb.append(String.format("  Soul injection reduces divergence by %.1f%%.%n", (dA - dB) * 100));
            }
            if (dC < dA - 0.05) {
                sb.append(String.format("  Bath modulation reduces divergence by %.1f%%.%n", (dA - dC) * 100));
            }

            return sb.toString();
        }

        private static void appendRow(StringBuilder sb, String label,
                                       BehavioralMetrics.ComparisonReport r, boolean hasEmbeddings) {
            sb.append(String.format("  %-14s %5.1f%%     ", label, r.overallDivergence() * 100));
            if (hasEmbeddings) {
                sb.append(String.format("%5.1f%%   ", r.semanticSimilarity() * 100));
            }
            sb.append(String.format("%5.1f%%  %.3f%n", r.vocabularyOverlap() * 100, r.sentimentAlignment()));
        }
    }
}
