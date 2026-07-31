package org.wyrdsekai.core.soul.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs Experiments 1-4 in sequence and produces a combined report.
 *
 * Usage:
 * <pre>
 *   var suite = new FullExperimentSuite(
 *       "http://gpu-host:8090/v1", "qwen2.5:7b",
 *       List.of("qwen3:0.6b", "qwen3:8b"),
 *       outputDir,
 *       "http://gpu-host:11434", "all-minilm");
 *   var report = suite.run();
 *   System.out.println(report);
 * </pre>
 */
public class FullExperimentSuite {

    private final String baseUrl;
    private final String primaryModel;
    private final List<String> secondaryModels;
    private final Path outputDir;
    private final String embeddingUrl;
    private final String embeddingModel;

    public FullExperimentSuite(String baseUrl, String primaryModel,
                                List<String> secondaryModels, Path outputDir,
                                String embeddingUrl, String embeddingModel) {
        this.baseUrl = baseUrl;
        this.primaryModel = primaryModel;
        this.secondaryModels = secondaryModels;
        this.outputDir = outputDir;
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Run all experiments and produce a combined decision gate report.
     */
    public String run() throws Exception {
        var sb = new StringBuilder();
        sb.append("=" .repeat(60)).append("\n");
        sb.append("  KOKORO HYPOTHESIS — FULL EXPERIMENT SUITE\n");
        sb.append("=".repeat(60)).append("\n\n");

        // Experiment 1: Prompt Injection
        sb.append(">>> EXPERIMENT 1: Prompt Injection Fidelity <<<\n\n");
        var exp1Dir = outputDir != null ? outputDir.resolve("exp1-prompt") : null;
        var exp1 = new SoulExperiment(baseUrl, primaryModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), exp1Dir,
            embeddingUrl, embeddingModel);
        var result1 = exp1.run();
        sb.append(result1.summary()).append("\n");

        // Experiment 2: Bath Modulation
        sb.append(">>> EXPERIMENT 2: Bath Modulation Effect <<<\n\n");
        var exp2Dir = outputDir != null ? outputDir.resolve("exp2-bath") : null;
        var exp2 = new BathExperiment(baseUrl, primaryModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), VitalityProfile.standardProfiles(),
            exp2Dir, embeddingUrl, embeddingModel);
        var result2 = exp2.run();
        sb.append(result2.summary()).append("\n");

        // Experiment 3: Substrate Curve (only if secondary models provided)
        SubstrateCurveExperiment.CurveResult result3 = null;
        if (!secondaryModels.isEmpty()) {
            sb.append(">>> EXPERIMENT 3: Substrate Sensitivity Curve <<<\n\n");
            var exp3Dir = outputDir != null ? outputDir.resolve("exp3-substrate") : null;
            var exp3 = new SubstrateCurveExperiment(baseUrl, primaryModel, secondaryModels,
                SoulExperiment.DEFAULT_AGENT_PROMPT,
                Scenario.standardSuite(), exp3Dir,
                embeddingUrl, embeddingModel);
            result3 = exp3.run();
            sb.append(result3.summary()).append("\n");
        } else {
            sb.append(">>> EXPERIMENT 3: SKIPPED (no secondary models) <<<\n\n");
        }

        // Experiment 4: Combined
        sb.append(">>> EXPERIMENT 4: Combined Bath + Soul <<<\n\n");
        var exp4Dir = outputDir != null ? outputDir.resolve("exp4-combined") : null;
        var exp4 = new CombinedExperiment(baseUrl, primaryModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), exp4Dir,
            embeddingUrl, embeddingModel);
        var result4 = exp4.run();
        sb.append(result4.summary()).append("\n");

        // Decision gate evaluation
        sb.append(evaluateGates(result1, result2, result3, result4));

        var report = sb.toString();
        if (outputDir != null) {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("full-suite-report.txt"), report);
        }
        return report;
    }

    private String evaluateGates(SoulExperiment.ExperimentResult exp1,
                                  BathExperiment.BathResult exp2,
                                  SubstrateCurveExperiment.CurveResult exp3,
                                  CombinedExperiment.CombinedResult exp4) {
        var sb = new StringBuilder();
        sb.append("=".repeat(60)).append("\n");
        sb.append("  DECISION GATE 1: PROMPT INJECTION + BATH\n");
        sb.append("=".repeat(60)).append("\n\n");

        // Exp 1 evaluation
        double fullDiv = exp1.reportFull().overallDivergence();
        sb.append("Exp 1 — Prompt injection (FULL soul): ");
        if (fullDiv < 0.30) {
            sb.append(String.format("%.1f%% — EXCELLENT. Proceed with Phases 2-10.%n", fullDiv * 100));
        } else if (fullDiv < 0.50) {
            sb.append(String.format("%.1f%% — GOOD. Proceed, but Exp 5 (LTC) priority elevated.%n", fullDiv * 100));
        } else {
            sb.append(String.format("%.1f%% — INSUFFICIENT. Exp 5 becomes critical path.%n", fullDiv * 100));
        }

        // Exp 2 evaluation — check if exhausted/confident differ from baseline
        sb.append("\nExp 2 — Bath modulation: ");
        var exhausted = exp2.profileResults().stream()
            .filter(pr -> "exhausted".equals(pr.profile().name())).findFirst();
        var confident = exp2.profileResults().stream()
            .filter(pr -> "confident".equals(pr.profile().name())).findFirst();
        if (exhausted.isPresent() && confident.isPresent()) {
            double exhDiv = exhausted.get().metrics().reportCombined().overallDivergence();
            double conDiv = confident.get().metrics().reportCombined().overallDivergence();
            double gap = Math.abs(exhDiv - conDiv);
            if (gap > 0.10) {
                sb.append(String.format("%.1f%% gap between exhausted/confident — BATH IS REAL.%n", gap * 100));
            } else if (gap > 0.05) {
                sb.append(String.format("%.1f%% gap — MARGINAL bath effect.%n", gap * 100));
            } else {
                sb.append(String.format("%.1f%% gap — BATH IS COSMETIC. Rethink vitality→inference mapping.%n", gap * 100));
            }
        }

        // Exp 3 evaluation
        if (exp3 != null) {
            sb.append("\nExp 3 — Substrate sensitivity: ");
            var worstCross = exp3.modelResults().stream()
                .filter(r -> "cross".equals(r.type()))
                .max((a, b) -> Double.compare(
                    a.report().overallDivergence(), b.report().overallDivergence()));
            if (worstCross.isPresent()) {
                double worst = worstCross.get().report().overallDivergence();
                if (worst < 0.50) {
                    sb.append(String.format("Worst cross: %.1f%% — GRACEFUL degradation. Soul is portable.%n", worst * 100));
                } else if (worst < 0.70) {
                    sb.append(String.format("Worst cross: %.1f%% — MODERATE degradation. LTC may help for small models.%n", worst * 100));
                } else {
                    sb.append(String.format("Worst cross: %.1f%% — CATASTROPHIC. LTC substrate REQUIRED for phone tier.%n", worst * 100));
                }
            }
        }

        // Exp 4 evaluation
        sb.append("\nExp 4 — Combined effect: ");
        double dNaked = exp4.reportNaked().overallDivergence();
        double dSoul = exp4.reportSoul().overallDivergence();
        double dBath = exp4.reportBath().overallDivergence();
        double dCombined = exp4.reportCombined().overallDivergence();

        if (dCombined < dSoul && dCombined < dBath) {
            sb.append("COMPOUND. Soul+Bath outperforms both alone.\n");
        } else if (Math.abs(dSoul - dCombined) < 0.05) {
            sb.append("Bath adds NOTHING via transformer. May still matter on LTC.\n");
        } else if (Math.abs(dBath - dCombined) < 0.05) {
            sb.append("Soul adds NOTHING. Rethink extraction approach.\n");
        } else {
            sb.append("INCONCLUSIVE. Neither mechanism clearly dominates.\n");
        }

        // Overall recommendation
        sb.append("\n--- RECOMMENDATION ---\n");
        if (fullDiv < 0.30 && dCombined < dSoul) {
            sb.append("GREEN: Prompt injection excellent, bath compounds. Full speed on Phases 2-10.\n");
            sb.append("Exp 5 (LTC) runs in parallel as research.\n");
        } else if (fullDiv < 0.50) {
            sb.append("YELLOW: Prompt injection works but imperfect. Proceed with Phases 2-10.\n");
            sb.append("Exp 5 (LTC) elevated priority — likely needed for phone tier.\n");
        } else {
            sb.append("RED: Prompt injection insufficient. Exp 5 (LTC) becomes critical path.\n");
            sb.append("Pause Phases 2-10 until substrate question resolved.\n");
        }

        return sb.toString();
    }
}
