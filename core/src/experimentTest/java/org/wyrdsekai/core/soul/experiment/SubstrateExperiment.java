package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Experiment 5: CfC + Bath Dynamic Substrate.
 *
 * Tests whether a purpose-built CfC neural network with bath dynamics
 * can predict agent behavioral responses better than prompt injection.
 *
 * The CfC does NOT generate text. It predicts the embedding vector of what
 * the agent WOULD say, given what it was asked and its current vitality state.
 *
 * Protocol:
 *   1. Generate baseline behavioral records (via LLM inference)
 *   2. Embed all scenario prompts and responses via Ollama all-minilm
 *   3. Extract soul, configure CfC substrate
 *   4. Train: optimize substrate to predict response embeddings from scenario embeddings
 *   5. Evaluate: CfC prediction accuracy vs prompt-injected LM divergence
 *
 * Usage:
 * <pre>
 *   var exp = new SubstrateExperiment(
 *       "http://localhost:11434/v1", "qwen2.5:7b",
 *       "http://localhost:11434", "all-minilm", outputDir);
 *   var result = exp.run();
 *   System.out.println(result.summary());
 * </pre>
 */
public class SubstrateExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final InferenceHelper inference;
    private final String embeddingUrl;
    private final String embeddingModel;
    private final String baseSystemPrompt;
    private final List<Scenario> scenarios;
    private final Path outputDir;

    // Hyperparameters
    static final int EPOCHS = 200;
    static final double LEARNING_RATE = 0.001;
    // Interleaved test indices: one from each category
    static final int[] TEST_INDICES = { 3, 7, 11, 15, 19 };

    public SubstrateExperiment(String baseUrl, String model,
                                String embeddingUrl, String embeddingModel,
                                Path outputDir) {
        this(baseUrl, model, embeddingUrl, embeddingModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT, Scenario.standardSuite(), outputDir);
    }

    public SubstrateExperiment(String baseUrl, String model,
                                String embeddingUrl, String embeddingModel,
                                String systemPrompt, List<Scenario> scenarios,
                                Path outputDir) {
        this.inference = new InferenceHelper(baseUrl, model);
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
        this.baseSystemPrompt = systemPrompt;
        this.scenarios = scenarios;
        this.outputDir = outputDir;
    }

    /** Run the full CfC substrate experiment. */
    public SubstrateResult run() throws Exception {
        System.out.println("=== Experiment 5: CfC + Bath Dynamic Substrate ===\n");

        // Step 1: Generate baseline
        System.out.println("--- Generating baseline ---");
        var baseline = generateBaseline();
        save("substrate-baseline", baseline);
        System.out.println("Baseline complete\n");

        // Step 2: Extract soul
        var soulText = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        save("substrate-soul.txt", soulText);
        System.out.println("Soul extracted: ~" + SoulExperiment.estimateTokens(soulText) + " tokens");

        // Step 3: Infer vitality from baseline
        var vitalityProfile = VitalityInferrer.infer(baseline);
        double[] tankValues = {
            0.5,                          // contextBudget (not inferred)
            vitalityProfile.confidence(),
            vitalityProfile.energy(),
            0.5,                          // alignment (not inferred)
            vitalityProfile.errorPressure(),
            vitalityProfile.momentum(),
            vitalityProfile.rapport(),
            vitalityProfile.focus()
        };
        System.out.printf("Inferred vitality: energy=%.2f conf=%.2f err=%.2f focus=%.2f%n",
            tankValues[2], tankValues[1], tankValues[4], tankValues[7]);

        // Step 4: Embed all scenarios and responses
        System.out.println("\n--- Embedding scenarios and responses ---");
        var embedded = embedAll(baseline);
        System.out.println("Embedded " + embedded.scenarioEmbeddings().size() + " scenario-response pairs\n");

        // Step 5: Configure and train CfC substrate
        var config = SubstrateConfig.fromSoul(soulText, 384);
        System.out.printf("Substrate: %d cells × %d state = %d hidden%n",
            config.numCells(), config.cellStateSize(), config.hiddenDim());

        System.out.println("\n--- Training CfC substrate ---");
        var cfcResult = trainSubstrate(config, embedded, tankValues);
        System.out.printf("Training complete: test cosSim=%.1f%%, test loss=%.4f%n",
            cfcResult.testCosSim() * 100, cfcResult.testLoss());

        // Step 6: Prompt injection baseline for comparison
        System.out.println("\n--- Prompt injection baseline ---");
        var promptResult = evaluatePromptBaseline(baseline, soulText, embedded);
        System.out.printf("Prompt injection: cosSim=%.1f%%, divergence=%.1f%%%n",
            promptResult.semanticSim() * 100, promptResult.divergence() * 100);

        // Step 7: Bath sensitivity analysis
        System.out.println("\n--- Bath sensitivity analysis ---");
        var sensitivity = bathSensitivity(config, embedded, tankValues);

        var result = new SubstrateResult(baseline, soulText, config, cfcResult,
            promptResult, tankValues, sensitivity);

        System.out.println("\n" + result.summary());
        save("substrate-result.txt", result.summary());
        return result;
    }

    // --- Internal ---

    private BehavioralRecord generateBaseline() throws Exception {
        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();
            var userMsg = buildUserMessage(scenario);
            var response = inference.chat(baseSystemPrompt, userMsg);
            long elapsed = System.currentTimeMillis() - start;
            int tokens = response.split("\\s+").length;
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));
            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }
        return new BehavioralRecord("substrate-baseline", "Wyrd", inference.model(),
            baseSystemPrompt, null, Instant.now(), responses);
    }

    private EmbeddedData embedAll(BehavioralRecord baseline) throws Exception {
        var scenarioTexts = new ArrayList<String>();
        var responseTexts = new ArrayList<String>();
        var ids = new ArrayList<String>();

        for (var resp : baseline.responses()) {
            ids.add(resp.scenarioId());
            // Find the scenario to build the full prompt text
            var scenario = scenarios.stream()
                .filter(s -> s.id().equals(resp.scenarioId())).findFirst().orElseThrow();
            scenarioTexts.add(buildUserMessage(scenario));
            responseTexts.add(resp.agentResponse());
        }

        var scenarioEmbs = BehavioralMetrics.fetchEmbeddings(embeddingUrl, embeddingModel, scenarioTexts);
        var responseEmbs = BehavioralMetrics.fetchEmbeddings(embeddingUrl, embeddingModel, responseTexts);
        return new EmbeddedData(scenarioEmbs, responseEmbs, ids);
    }

    private TrainResult trainSubstrate(SubstrateConfig config, EmbeddedData data,
                                        double[] tankValues) {
        var substrate = new LiquidSubstrate(config);

        // Split: test = interleaved indices, train = rest
        var testSet = new HashSet<Integer>();
        for (int idx : TEST_INDICES) {
            if (idx < data.scenarioEmbeddings().size()) testSet.add(idx);
        }

        var trainInputs = new ArrayList<double[]>();
        var trainTargets = new ArrayList<double[]>();
        var testInputs = new ArrayList<double[]>();
        var testTargets = new ArrayList<double[]>();

        for (int i = 0; i < data.scenarioEmbeddings().size(); i++) {
            if (testSet.contains(i)) {
                testInputs.add(data.scenarioEmbeddings().get(i));
                testTargets.add(data.responseEmbeddings().get(i));
            } else {
                trainInputs.add(data.scenarioEmbeddings().get(i));
                trainTargets.add(data.responseEmbeddings().get(i));
            }
        }

        var lossHistory = new ArrayList<Double>();
        var rng = new Random(config.seed());
        int trainCount = trainInputs.size();
        var indices = new int[trainCount];
        for (int i = 0; i < trainCount; i++) indices[i] = i;

        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            // Shuffle training order
            for (int i = trainCount - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp;
            }

            double epochLoss = 0;
            for (int idx : indices) {
                substrate.reset();
                epochLoss += substrate.trainStep(
                    trainInputs.get(idx), trainTargets.get(idx),
                    tankValues, 1.0, LEARNING_RATE);
            }
            epochLoss /= trainCount;
            lossHistory.add(epochLoss);

            if (epoch % 50 == 0 || epoch == EPOCHS - 1) {
                System.out.printf("  Epoch %d: train loss=%.4f%n", epoch, epochLoss);
            }
        }

        // Evaluate on test set
        double testLoss = 0, testCosSim = 0;
        for (int i = 0; i < testInputs.size(); i++) {
            substrate.reset();
            var predicted = substrate.process(testInputs.get(i), tankValues, 1.0);
            double cs = BehavioralMetrics.cosineSimilarity(predicted, testTargets.get(i));
            testLoss += (1.0 - cs);
            testCosSim += cs;
        }
        if (!testInputs.isEmpty()) {
            testLoss /= testInputs.size();
            testCosSim /= testInputs.size();
        }

        return new TrainResult(lossHistory.getLast(), testLoss, testCosSim,
            EPOCHS, substrate.paramCount(), lossHistory);
    }

    private PromptResult evaluatePromptBaseline(BehavioralRecord baseline,
                                                 String soulText, EmbeddedData embedded) throws Exception {
        // Generate responses with soul injected
        var restoredResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        var effectivePrompt = baseSystemPrompt + "\n\n" + soulText;

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();
            var response = inference.chat(effectivePrompt, buildUserMessage(scenario));
            long elapsed = System.currentTimeMillis() - start;
            int tokens = response.split("\\s+").length;
            restoredResponses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));
            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }

        var restored = new BehavioralRecord("substrate-restored", "Wyrd", inference.model(),
            baseSystemPrompt, soulText, Instant.now(), restoredResponses);
        save("substrate-restored", restored);

        // Compute embedding similarity on test indices only
        var restoredTexts = restoredResponses.stream()
            .map(BehavioralRecord.ScenarioResponse::agentResponse).toList();
        var restoredEmbs = BehavioralMetrics.fetchEmbeddings(embeddingUrl, embeddingModel, restoredTexts);

        double testCosSim = 0;
        int count = 0;
        for (int idx : TEST_INDICES) {
            if (idx < restoredEmbs.size()) {
                testCosSim += BehavioralMetrics.cosineSimilarity(
                    embedded.responseEmbeddings().get(idx), restoredEmbs.get(idx));
                count++;
            }
        }
        testCosSim = count > 0 ? testCosSim / count : 0;

        var report = BehavioralMetrics.compareWithEmbeddings(baseline, restored,
            embeddingUrl, embeddingModel);

        return new PromptResult(report.overallDivergence(), testCosSim,
            report.semanticSimilarity());
    }

    /**
     * Bath sensitivity analysis: perturb each tank ±0.2 and measure prediction change.
     */
    private double[] bathSensitivity(SubstrateConfig config, EmbeddedData data,
                                      double[] tankValues) {
        var substrate = new LiquidSubstrate(config);
        // Retrain quickly (50 epochs) to get a trained substrate for sensitivity testing
        var trainInputs = new ArrayList<double[]>();
        var trainTargets = new ArrayList<double[]>();
        var testSet = new HashSet<Integer>();
        for (int idx : TEST_INDICES) if (idx < data.scenarioEmbeddings().size()) testSet.add(idx);
        for (int i = 0; i < data.scenarioEmbeddings().size(); i++) {
            if (!testSet.contains(i)) {
                trainInputs.add(data.scenarioEmbeddings().get(i));
                trainTargets.add(data.responseEmbeddings().get(i));
            }
        }
        for (int epoch = 0; epoch < 50; epoch++) {
            for (int i = 0; i < trainInputs.size(); i++) {
                substrate.reset();
                substrate.trainStep(trainInputs.get(i), trainTargets.get(i),
                    tankValues, 1.0, LEARNING_RATE);
            }
        }

        // For each tank, perturb and measure output change
        double[] sensitivity = new double[BathDynamics.CHANNEL_COUNT];
        String[] tankNames = { "contextBudget", "confidence", "energy", "alignment",
            "errorPressure", "momentum", "rapport", "focus" };

        for (int c = 0; c < BathDynamics.CHANNEL_COUNT; c++) {
            double totalDelta = 0;
            int count = 0;
            for (int i = 0; i < trainInputs.size(); i++) {
                substrate.reset();
                var baseOutput = substrate.process(trainInputs.get(i), tankValues, 1.0);

                var perturbedTanks = tankValues.clone();
                perturbedTanks[c] = Math.min(1.0, tankValues[c] + 0.3);
                substrate.reset();
                var perturbedOutput = substrate.process(trainInputs.get(i), perturbedTanks, 1.0);

                double cosSim = BehavioralMetrics.cosineSimilarity(baseOutput, perturbedOutput);
                totalDelta += (1.0 - cosSim);
                count++;
            }
            sensitivity[c] = count > 0 ? totalDelta / count : 0;
            System.out.printf("  %s: %.4f impact%n", tankNames[c], sensitivity[c]);
        }
        return sensitivity;
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

    // --- Result records ---

    record EmbeddedData(
        List<double[]> scenarioEmbeddings,
        List<double[]> responseEmbeddings,
        List<String> scenarioIds
    ) {}

    record TrainResult(
        double trainLoss,
        double testLoss,
        double testCosSim,
        int epochs,
        int paramCount,
        List<Double> lossHistory
    ) {}

    record PromptResult(
        double divergence,
        double semanticSim,       // on test indices only
        double overallSemanticSim // on all indices
    ) {}

    public record SubstrateResult(
        BehavioralRecord baseline,
        String soul,
        SubstrateConfig config,
        TrainResult cfcResult,
        PromptResult promptResult,
        double[] tankValues,
        double[] bathSensitivity
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Experiment 5: CfC + Bath Dynamic Substrate Results ===\n\n");

            sb.append(String.format("Substrate: %d cells × %d state = %d hidden, %d trainable params%n",
                config.numCells(), config.cellStateSize(), config.hiddenDim(), cfcResult.paramCount()));
            sb.append(String.format("Training: %d epochs, lr=%.4f, %d train / %d test%n",
                cfcResult.epochs(), LEARNING_RATE,
                baseline.responses().size() - TEST_INDICES.length, TEST_INDICES.length));

            sb.append("\nCfC Substrate:\n");
            sb.append(String.format("  Train loss (final):  %.4f%n", cfcResult.trainLoss()));
            sb.append(String.format("  Test loss:           %.4f%n", cfcResult.testLoss()));
            sb.append(String.format("  Test cosine sim:     %.1f%%%n", cfcResult.testCosSim() * 100));

            sb.append("\nPrompt Injection Baseline:\n");
            sb.append(String.format("  Divergence:          %.1f%%%n", promptResult.divergence() * 100));
            sb.append(String.format("  Test cosine sim:     %.1f%%%n", promptResult.semanticSim() * 100));
            sb.append(String.format("  Overall cosine sim:  %.1f%%%n", promptResult.overallSemanticSim() * 100));

            sb.append("\nCOMPARISON:\n");
            double cfcSim = cfcResult.testCosSim();
            double promptSim = promptResult.semanticSim();
            sb.append(String.format("  CfC test cosine sim:     %.1f%%%n", cfcSim * 100));
            sb.append(String.format("  Prompt test cosine sim:  %.1f%%%n", promptSim * 100));
            double diff = cfcSim - promptSim;
            sb.append(String.format("  Difference: %+.1f%%%n", diff * 100));

            sb.append("\nINTERPRETATION:\n");
            if (diff > 0.05) {
                sb.append("  CfC OUTPERFORMS prompt injection for behavioral prediction.\n");
                sb.append("  The dynamic substrate encodes personality more faithfully.\n");
            } else if (diff > -0.05) {
                sb.append("  CfC MATCHES prompt injection.\n");
                sb.append("  Valuable for speed (<1ms vs seconds) and offline operation.\n");
            } else {
                sb.append("  CfC UNDERPERFORMS prompt injection.\n");
                sb.append("  May need more behavioral data or larger substrate.\n");
            }

            String[] tankNames = { "ctxBudget", "confidence", "energy", "alignment",
                "errPressure", "momentum", "rapport", "focus" };
            sb.append("\nBath Sensitivity (per-tank impact on output):\n");
            for (int i = 0; i < bathSensitivity.length; i++) {
                sb.append(String.format("  %-12s %.4f%s%n", tankNames[i], bathSensitivity[i],
                    bathSensitivity[i] > 0.01 ? " *" : ""));
            }

            return sb.toString();
        }
    }
}
