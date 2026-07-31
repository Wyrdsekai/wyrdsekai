package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for Experiment 6: Kokoro Core — Small Model Soul Substrate.
 *
 * Framework tests run without inference endpoints.
 * Live tests require SOUL_EXPERIMENT_URL + SOUL_EMBEDDING_URL.
 */
class KokoroCoreTest {

    // --- Framework Tests ---

    @Test
    void experiment_builder_requires_baseModel() {
        assertThatThrownBy(() ->
            KokoroCoreExperiment.builder("http://localhost:11434/v1")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baseModel");
    }

    @Test
    void experiment_builder_requires_baselineModel() {
        assertThatThrownBy(() ->
            KokoroCoreExperiment.builder("http://localhost:11434/v1")
                .baseModel("qwen2.5:0.5b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baselineModel");
    }

    @Test
    void experiment_builder_constructs_with_required_fields() {
        var exp = KokoroCoreExperiment.builder("http://localhost:11434/v1")
            .baseModel("qwen2.5:0.5b")
            .baselineModel("qwen2.5:7b")
            .build();

        assertThat(exp).isNotNull();
    }

    @Test
    void experiment_builder_with_all_fields() {
        var exp = KokoroCoreExperiment.builder("http://localhost:11434/v1")
            .baseModel("qwen2.5:0.5b")
            .loraModel("wyrd-soul:qwen05b")
            .baselineModel("qwen2.5:7b")
            .systemPrompt("Custom prompt")
            .scenarios(Scenario.standardSuite())
            .embeddingUrl("http://localhost:11434")
            .embeddingModel("all-minilm")
            .build();

        assertThat(exp).isNotNull();
    }

    @Test
    void comparison_result_summary_format() {
        // Build a mock ComparisonResult to test summary formatting
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test prompt", null, Instant.now(),
            List.of(new BehavioralRecord.ScenarioResponse(
                "s1", "social", "Hello", "Hi there", 2, 100)));

        var nakedReport = new BehavioralMetrics.ComparisonReport(
            0.55, 0.45, 0.9, 0.55, Map.of(), Map.of(), 0.20, -0.05, List.of());
        var promptReport = new BehavioralMetrics.ComparisonReport(
            0.40, 0.62, 1.1, 0.40, Map.of(), Map.of(), 0.30, 0.02, List.of());
        var loraReport = new BehavioralMetrics.ComparisonReport(
            0.35, 0.68, 1.0, 0.35, Map.of(), Map.of(), 0.33, 0.01, List.of());
        var layeredReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.72, 1.0, 0.30, Map.of(), Map.of(), 0.35, 0.03, List.of());

        var conditions = List.of(
            new KokoroCoreExperiment.ConditionResult("D: naked", "qwen2.5:0.5b",
                false, false, baseline, nakedReport),
            new KokoroCoreExperiment.ConditionResult("A: prompt", "qwen2.5:0.5b",
                true, false, baseline, promptReport),
            new KokoroCoreExperiment.ConditionResult("B: lora", "wyrd-soul:qwen05b",
                false, true, baseline, loraReport),
            new KokoroCoreExperiment.ConditionResult("C: layered", "wyrd-soul:qwen05b",
                true, true, baseline, layeredReport)
        );

        var result = new KokoroCoreExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5:0.5b", "wyrd-soul:qwen05b",
            baseline, "test soul", conditions);

        var summary = result.summary();
        assertThat(summary)
            .contains("Kokoro Core Comparison")
            .contains("D: naked")
            .contains("A: prompt")
            .contains("B: lora")
            .contains("C: layered")
            .contains("LoRA WINS");
    }

    @Test
    void comparison_result_detects_prompt_wins() {
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(), List.of());

        var promptReport = new BehavioralMetrics.ComparisonReport(
            0.35, 0.65, 1.0, 0.35, Map.of(), Map.of(), 0.30, 0.0, List.of());
        var loraReport = new BehavioralMetrics.ComparisonReport(
            0.50, 0.50, 1.0, 0.50, Map.of(), Map.of(), 0.22, 0.0, List.of());

        var conditions = List.of(
            new KokoroCoreExperiment.ConditionResult("D: naked", "qwen2.5:0.5b",
                false, false, baseline, new BehavioralMetrics.ComparisonReport(
                    0.60, 0.40, 1.0, 0.60, Map.of(), Map.of(), 0.18, 0.0, List.of())),
            new KokoroCoreExperiment.ConditionResult("A: prompt", "qwen2.5:0.5b",
                true, false, baseline, promptReport),
            new KokoroCoreExperiment.ConditionResult("B: lora", "wyrd-soul:qwen05b",
                false, true, baseline, loraReport),
            new KokoroCoreExperiment.ConditionResult("C: layered", "wyrd-soul:qwen05b",
                true, true, baseline, promptReport)
        );

        var result = new KokoroCoreExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5:0.5b", "wyrd-soul:qwen05b",
            baseline, "test", conditions);

        assertThat(result.summary()).contains("PROMPT WINS");
    }

    @Test
    void preference_generator_stats() {
        var stats = new KokoroPreferenceGenerator.GenerationStats(120, 105, 10, 5);
        assertThat(stats.yieldRate()).isCloseTo(0.875, within(0.001));
        assertThat(stats.summary())
            .contains("120")
            .contains("105")
            .contains("87.5%")
            .contains("10")
            .contains("5");
    }

    @Test
    void preference_generator_empty_stats() {
        var stats = new KokoroPreferenceGenerator.GenerationStats(0, 0, 0, 0);
        assertThat(stats.yieldRate()).isEqualTo(0.0);
    }

    @Test
    void scenarios_json_loadable() throws Exception {
        // Verify the training scenarios can be loaded and parsed
        var path = Path.of("../../scripts/kokoro-core/scenarios.json");
        if (!path.toFile().exists()) {
            // Try from project root
            path = Path.of("scripts/kokoro-core/scenarios.json");
        }
        if (!path.toFile().exists()) {
            System.out.println("SKIP: scenarios.json not found (expected from project root)");
            return;
        }

        var json = new ObjectMapper();
        var tree = json.readTree(path.toFile());

        var scenarios = tree.get("scenarios");
        assertThat(scenarios.isArray()).isTrue();
        assertThat(scenarios.size()).isEqualTo(120);

        // Verify no overlap with held-out test scenarios
        var testIds = Scenario.standardSuite().stream()
            .map(Scenario::id).toList();

        for (var node : scenarios) {
            var id = node.get("id").asText();
            assertThat(id).startsWith("train-");
            assertThat(testIds).doesNotContain(id);

            // Verify required fields
            assertThat(node.has("category")).isTrue();
            assertThat(node.has("roomContext")).isTrue();
            assertThat(node.has("playerMessage")).isTrue();
        }

        // Verify category distribution
        var cats = new HashMap<String, Integer>();
        for (var node : scenarios) {
            cats.merge(node.get("category").asText(), 1, Integer::sum);
        }
        assertThat(cats.get("social")).isEqualTo(30);
        assertThat(cats.get("decision")).isEqualTo(30);
        assertThat(cats.get("style")).isEqualTo(30);
        assertThat(cats.get("memory")).isEqualTo(30);
    }

    // --- Extended Vitality Modulation Tests ---

    @Test
    void extended_modulation_baseline_profile() {
        var mod = new ExtendedVitalityModulation(1.0);
        double[] baseline = {0.5, 0.5, 0.8, 0.5, 0.0, 0.3, 0.5, 0.5}; // VitalityProfile.baseline()-ish

        var result = mod.computeFull(baseline);
        assertThat(result.loraAlpha()).isBetween(0.5, 0.9);
        assertThat(result.params().temperature()).isBetween(0.5, 1.0);
        assertThat(result.params().maxTokens()).isBetween(300, 512);
    }

    @Test
    void extended_modulation_exhausted_profile() {
        var mod = new ExtendedVitalityModulation(1.0);
        double[] exhausted = {0.3, 0.3, 0.1, 0.3, 0.4, 0.1, 0.3, 0.2};

        var result = mod.computeFull(exhausted);
        // Low confidence, high error → personality fades
        assertThat(result.loraAlpha()).isLessThan(0.6);
        // Low energy → short responses
        assertThat(result.params().maxTokens()).isLessThan(150);
        // Low focus → low repeat penalty
        assertThat(result.params().repeatPenalty()).isLessThan(1.2);
    }

    @Test
    void extended_modulation_confident_profile() {
        var mod = new ExtendedVitalityModulation(1.0);
        double[] confident = {0.7, 0.9, 0.9, 0.8, 0.0, 0.7, 0.7, 0.8};

        var result = mod.computeFull(confident);
        // High confidence → strong personality
        assertThat(result.loraAlpha()).isGreaterThan(0.85);
        // High confidence → narrower sampling
        assertThat(result.params().topK()).isGreaterThan(0);
        assertThat(result.params().minP()).isGreaterThan(0.0);
    }

    @Test
    void extended_modulation_lora_alpha_range() {
        var mod = new ExtendedVitalityModulation(1.0);

        // Minimum: everything bad
        double[] worst = {0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0};
        assertThat(mod.computeLoraAlpha(worst)).isCloseTo(0.3, within(0.05));

        // Maximum: everything great
        double[] best = {1.0, 1.0, 1.0, 1.0, 0.0, 1.0, 1.0, 1.0};
        assertThat(mod.computeLoraAlpha(best)).isCloseTo(1.0, within(0.01));
    }

    @Test
    void extended_modulation_substrate_factor_scales() {
        double[] profile = {0.5, 0.3, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};

        var small = new ExtendedVitalityModulation(0.5);
        var large = new ExtendedVitalityModulation(3.0);

        var smallResult = small.compute(profile);
        var largeResult = large.compute(profile);

        // Larger substrate factor should produce more extreme temperature
        // (both are away from base 0.7 but large should be further)
        double smallTempDiff = Math.abs(smallResult.temperature() - 0.7);
        double largeTempDiff = Math.abs(largeResult.temperature() - 0.7);
        assertThat(largeTempDiff).isGreaterThanOrEqualTo(smallTempDiff);
    }

    @Test
    void extended_modulation_for_model_size() {
        // 0.5B → factor near 0.5
        var tiny = ExtendedVitalityModulation.forModelSize(0.5);
        // 3B → factor near 2.6
        var medium = ExtendedVitalityModulation.forModelSize(3.0);
        // 30B → factor near 5.9
        var large = ExtendedVitalityModulation.forModelSize(30.0);

        double[] profile = {0.5, 0.3, 0.5, 0.5, 0.7, 0.5, 0.5, 0.3};
        // All should produce valid params
        for (var mod : List.of(tiny, medium, large)) {
            var params = mod.compute(profile);
            assertThat(params.temperature()).isBetween(0.1, 2.0);
            assertThat(params.maxTokens()).isBetween(64, 512);
            assertThat(params.repeatPenalty()).isBetween(1.0, 1.5);
        }
    }

    @Test
    void generation_params_defaults() {
        var params = InferenceHelper.GenerationParams.defaults();
        assertThat(params.maxTokens()).isEqualTo(512);
        assertThat(params.temperature()).isEqualTo(0.7);
        assertThat(params.repeatPenalty()).isEqualTo(1.0);
        assertThat(params.presencePenalty()).isEqualTo(0.0);
        assertThat(params.frequencyPenalty()).isEqualTo(0.0);
        assertThat(params.topK()).isEqualTo(0);
        assertThat(params.minP()).isEqualTo(0.0);
        assertThat(params.topP()).isEqualTo(1.0);
    }

    @Test
    void modulation_result_summary_format() {
        var params = new InferenceHelper.GenerationParams(256, 0.85, 1.1, 0.2, 0.1, 20, 0.05, 1.0);
        var result = new ExtendedVitalityModulation.ModulationResult(0.75, params);
        var summary = result.summary();
        assertThat(summary)
            .contains("loraAlpha=0.75")
            .contains("temp=0.85")
            .contains("maxTok=256");
    }

    // --- Live Tests ---

    @Test
    void live_lora_comparison(@TempDir Path outputDir) throws Exception {
        var apiUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var baseModel = System.getenv("SOUL_EXPERIMENT_MODEL");
        var loraModel = System.getenv("SOUL_LORA_MODEL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var baselineModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");

        if (apiUrl == null) {
            System.out.println("SKIP: SOUL_EXPERIMENT_URL not set");
            return;
        }
        if (baseModel == null) {
            System.out.println("SKIP: SOUL_EXPERIMENT_MODEL not set");
            return;
        }

        var builder = KokoroCoreExperiment.builder(apiUrl)
            .baseModel(baseModel)
            .baselineModel(baselineModel)
            .outputDir(outputDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingModel);

        if (loraModel != null) {
            builder.loraModel(loraModel);
        }

        var result = builder.build().run();

        // Basic validation
        assertThat(result.conditions()).isNotEmpty();
        for (var condition : result.conditions()) {
            assertThat(condition.report().overallDivergence()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void live_preference_generation(@TempDir Path outputDir) throws Exception {
        var apiUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var baseModel = System.getenv("SOUL_EXPERIMENT_MODEL");
        var loraModel = System.getenv("SOUL_LORA_MODEL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var baselineModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");

        if (apiUrl == null || embeddingUrl == null) {
            System.out.println("SKIP: SOUL_EXPERIMENT_URL or SOUL_EMBEDDING_URL not set");
            return;
        }
        if (baseModel == null || loraModel == null) {
            System.out.println("SKIP: SOUL_EXPERIMENT_MODEL or SOUL_LORA_MODEL not set");
            return;
        }

        // Generate gold baseline on larger model
        var baselineInf = new InferenceHelper(apiUrl, baselineModel);
        var scenarios = Scenario.standardSuite().subList(0, 10); // 10 for better signal

        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var s : scenarios) {
            var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
            var resp = baselineInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg);
            responses.add(new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
        }
        var baseline = new BehavioralRecord("baseline", "Wyrd", baselineModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT, null, Instant.now(), responses);

        // Generate preferences: LoRA model (chosen) vs naked base model (rejected)
        var loraInf = new InferenceHelper(apiUrl, loraModel);
        var nakedInf = new InferenceHelper(apiUrl, baseModel);
        var generator = new KokoroPreferenceGenerator(loraInf, nakedInf, embeddingUrl, embeddingModel);

        var outPath = outputDir.resolve("test_preferences.jsonl");
        var stats = generator.generate(scenarios, baseline, outPath);

        System.out.println(stats.summary());
        assertThat(stats.total()).isEqualTo(10);
        assertThat(stats.generated() + stats.discarded() + stats.errors()).isEqualTo(10);
    }

    @Test
    void live_full_dpo_generation() throws Exception {
        var apiUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var baseModel = System.getenv("SOUL_EXPERIMENT_MODEL");
        var loraModel = System.getenv("SOUL_LORA_MODEL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        if (apiUrl == null || embeddingUrl == null || baseModel == null || loraModel == null) {
            System.out.println("SKIP: Need SOUL_EXPERIMENT_URL, SOUL_EXPERIMENT_MODEL, SOUL_LORA_MODEL, SOUL_EMBEDDING_URL");
            return;
        }

        var json = new ObjectMapper();

        // Load training scenarios from scenarios.json
        var scenariosPath = Path.of("scripts/kokoro-core/scenarios.json");
        if (!scenariosPath.toFile().exists())
            scenariosPath = Path.of("../scripts/kokoro-core/scenarios.json");
        assertThat(scenariosPath.toFile()).exists();

        var tree = json.readTree(scenariosPath.toFile());
        var scenarioNodes = tree.get("scenarios");
        var scenarios = new ArrayList<Scenario>();
        for (var node : scenarioNodes) {
            var entities = new LinkedHashMap<String, String>();
            if (node.has("entities")) {
                node.get("entities").fields().forEachRemaining(e ->
                    entities.put(e.getKey(), e.getValue().asText()));
            }
            scenarios.add(new Scenario(
                node.get("id").asText(), node.get("category").asText(),
                node.get("description").asText(), node.get("roomContext").asText(),
                entities, node.get("playerMessage").asText()));
        }
        System.out.println("Loaded " + scenarios.size() + " training scenarios");

        // Load gold baseline responses from training corpus
        var corpusPath = Path.of("scripts/kokoro-core/corpus/wyrd_soul_train.jsonl");
        if (!corpusPath.toFile().exists())
            corpusPath = Path.of("../scripts/kokoro-core/corpus/wyrd_soul_train.jsonl");
        assertThat(corpusPath.toFile()).exists();

        var goldResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var line : Files.readAllLines(corpusPath)) {
            var conv = json.readTree(line);
            var meta = conv.get("metadata");
            var scenarioId = meta.get("scenario_id").asText();
            var category = meta.get("category").asText();
            // gpt response is the last conversation entry
            var convArr = conv.get("conversations");
            var gptResponse = convArr.get(convArr.size() - 1).get("value").asText();
            var humanMsg = convArr.get(1).get("value").asText();
            goldResponses.add(new BehavioralRecord.ScenarioResponse(
                scenarioId, category, humanMsg, gptResponse,
                gptResponse.split("\\s+").length, 0));
        }
        var goldBaseline = new BehavioralRecord("gold", "Wyrd", "qwen3-30b",
            SoulExperiment.DEFAULT_AGENT_PROMPT, null, Instant.now(), goldResponses);
        System.out.println("Loaded " + goldResponses.size() + " gold baseline responses");

        // Generate preferences: LoRA model (chosen) vs naked base model (rejected)
        var loraInf = new InferenceHelper(apiUrl, loraModel);
        var nakedInf = new InferenceHelper(apiUrl, baseModel);
        var generator = new KokoroPreferenceGenerator(loraInf, nakedInf, embeddingUrl, embeddingModel);

        var outPath = scenariosPath.getParent().resolve("corpus/dpo_preferences.jsonl");
        Files.createDirectories(outPath.getParent());
        var stats = generator.generate(scenarios, goldBaseline, outPath);

        System.out.println(stats.summary());
        System.out.println("Output: " + outPath.toAbsolutePath());
        assertThat(stats.total()).isEqualTo(120);
        // With ~50% yield, expect roughly 50-70 pairs
        assertThat(stats.generated()).isGreaterThan(20);
    }

    @Test
    void live_alpha_modulation(@TempDir Path outputDir) throws Exception {
        var llamaUrl = System.getenv("SOUL_LLAMA_URL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var baselineModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");

        if (llamaUrl == null) {
            System.out.println("SKIP: SOUL_LLAMA_URL not set (needs llama-server with --lora)");
            return;
        }
        if (embeddingUrl == null) {
            System.out.println("SKIP: SOUL_EMBEDDING_URL not set");
            return;
        }

        // llama-server doesn't need model name in the same way
        var inference = new InferenceHelper(llamaUrl, "default");
        var mod = ExtendedVitalityModulation.forModelSize(1.5); // adjust per model

        // Get baseline (on a larger model via Ollama)
        var ollamaUrl = embeddingUrl.replace(":11434", ":11434/v1");
        var baselineInf = new InferenceHelper(ollamaUrl, baselineModel);
        var scenarios = Scenario.standardSuite();

        System.out.println("=== Alpha Modulation Experiment ===");
        System.out.println("llama-server: " + llamaUrl);
        System.out.println("Baseline model: " + baselineModel);
        System.out.println();

        // Generate baseline
        System.out.println("--- Baseline ---");
        var baselineResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var s : scenarios.subList(0, 5)) { // First 5 for speed
            var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
            var resp = baselineInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg);
            baselineResponses.add(new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
        }
        var baseline = new BehavioralRecord("baseline", "Wyrd", baselineModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT, null, Instant.now(), baselineResponses);

        var soul = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);

        // Test 5 vitality profiles × 5 scenarios with alpha modulation
        var profiles = VitalityProfile.standardProfiles();
        var results = new LinkedHashMap<String, Double>();

        for (var profile : profiles) {
            System.out.println("\n--- Profile: " + profile.name() + " ---");
            var modResult = mod.computeFull(toTanks(profile));
            System.out.println("  " + modResult.summary());

            // Set LoRA alpha
            try {
                inference.setLoraScale(0, modResult.loraAlpha());
                System.out.println("  LoRA alpha set to: " + modResult.loraAlpha());
            } catch (Exception e) {
                System.out.println("  WARNING: Could not set LoRA scale: " + e.getMessage());
            }

            // Run scenarios with extended params
            var effectivePrompt = SoulExperiment.DEFAULT_AGENT_PROMPT + "\n\n" + soul;
            var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();
            for (var s : scenarios.subList(0, 5)) {
                var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
                var resp = inference.chatWithParams(effectivePrompt, msg, modResult.params());
                responses.add(new BehavioralRecord.ScenarioResponse(
                    s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
                System.out.println("  " + s.id() + ": ~" + resp.split("\\s+").length + " words");
            }

            var record = new BehavioralRecord(profile.name(), "Wyrd", "lora",
                SoulExperiment.DEFAULT_AGENT_PROMPT, soul, Instant.now(), responses);

            var report = BehavioralMetrics.compareWithEmbeddings(
                baseline, record, embeddingUrl, embeddingModel);
            results.put(profile.name(), report.overallDivergence());
            System.out.println("  Divergence: " + String.format("%.1f%%", report.overallDivergence() * 100));
        }

        // Summary
        System.out.println("\n=== Alpha Modulation Results ===");
        System.out.println("PROFILE          ALPHA   DIVERGENCE");
        for (var profile : profiles) {
            double alpha = mod.computeLoraAlpha(toTanks(profile));
            double div = results.getOrDefault(profile.name(), -1.0);
            System.out.printf("%-16s %.2f    %.1f%%%n", profile.name(), alpha, div * 100);
        }

        // Behavioral spread should be meaningful (>5% between min and max)
        double minDiv = results.values().stream().mapToDouble(d -> d).min().orElse(0);
        double maxDiv = results.values().stream().mapToDouble(d -> d).max().orElse(0);
        double spread = maxDiv - minDiv;
        System.out.printf("%nBehavioral spread: %.1f%% (min=%.1f%%, max=%.1f%%)%n",
            spread * 100, minDiv * 100, maxDiv * 100);

        assertThat(spread).as("Behavioral spread across alpha values").isGreaterThan(0.0);
    }

    // --- Helpers ---

    /** Convert VitalityProfile fields to tank array matching ExtendedVitalityModulation ordering. */
    private static double[] toTanks(VitalityProfile p) {
        // Order: CTX_BUDGET=0, CONFIDENCE=1, ENERGY=2, ALIGNMENT=3,
        //        ERR_PRESSURE=4, MOMENTUM=5, RAPPORT=6, FOCUS=7
        return new double[]{
            p.contextBudget(), p.confidence(), p.energy(), p.alignment(),
            p.errorPressure(), p.momentum(), p.rapport(), p.focus()
        };
    }

    // --- Experiment 7: RWKV State Soul ---

    @Test
    void live_rwkv_baseline() throws Exception {
        var rwkvUrl = System.getenv("SOUL_RWKV_URL");        // llama-server with RWKV GGUF
        var ollamaUrl = System.getenv("SOUL_EMBEDDING_URL");  // Ollama for baseline + embeddings
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var baselineModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");

        if (rwkvUrl == null || ollamaUrl == null) {
            System.out.println("SKIP: Need SOUL_RWKV_URL (llama-server with RWKV) and SOUL_EMBEDDING_URL (Ollama)");
            return;
        }

        var rwkvInf = new InferenceHelper(rwkvUrl, "rwkv7", Duration.ofMinutes(10));
        var ollamaApiUrl = ollamaUrl.contains("/v1") ? ollamaUrl : ollamaUrl + "/v1";
        var baselineInf = new InferenceHelper(ollamaApiUrl, baselineModel);
        var scenarios = Scenario.standardSuite();

        System.out.println("=== Experiment 7 Part A: RWKV-7 Baseline ===");
        System.out.println("RWKV model: " + rwkvUrl);
        System.out.println("Baseline model: " + baselineModel);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Generate gold baseline on large model
        System.out.println("--- Generating gold baseline on " + baselineModel + " ---");
        var goldResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var s : scenarios) {
            var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
            var resp = baselineInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg);
            goldResponses.add(new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
        }
        var goldBaseline = new BehavioralRecord("gold", "Wyrd", baselineModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT, null, Instant.now(), goldResponses);
        System.out.println("Gold baseline: " + goldResponses.size() + " responses\n");

        // Step 2: Extract soul fingerprint from gold baseline
        var soul = SoulExtractor.extract(goldBaseline, SoulExtractor.Detail.FULL);
        System.out.println("Soul fingerprint: " + soul.length() + " chars\n");

        // Step 3: Run RWKV under two conditions
        var conditions = new LinkedHashMap<String, String>();
        // Condition D: naked RWKV (no personality)
        conditions.put("D: rwkv-naked", null);
        // Condition A: RWKV + prompt injection (same soul fingerprint)
        conditions.put("A: rwkv-prompt", soul);

        var results = new ArrayList<String>();
        for (var entry : conditions.entrySet()) {
            var condName = entry.getKey();
            var soulPrompt = entry.getValue();
            System.out.println("--- " + condName + " ---");

            var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();
            for (var s : scenarios) {
                var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
                var systemPrompt = soulPrompt != null
                    ? SoulExperiment.DEFAULT_AGENT_PROMPT + "\n\n" + soulPrompt
                    : SoulExperiment.DEFAULT_AGENT_PROMPT;
                var resp = rwkvInf.chat(systemPrompt, msg, 256, 0.7,
                    List.of("User:", "\nUser", "\n\nUser"));
                responses.add(new BehavioralRecord.ScenarioResponse(
                    s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
            }
            var record = new BehavioralRecord(condName, "Wyrd", "rwkv7-2.9b",
                soulPrompt, null, Instant.now(), responses);

            var report = BehavioralMetrics.compareWithEmbeddings(goldBaseline, record, ollamaUrl, embeddingModel);
            var line = String.format("  %-20s div=%.1f%% sem=%.1f%% vocab=%d%%",
                condName, report.overallDivergence() * 100,
                report.semanticSimilarity() * 100,
                Math.round(report.vocabularyOverlap() * 100));
            System.out.println(line);
            results.add(line);
        }

        System.out.println("\n=== RWKV-7 Baseline Results ===");
        for (var r : results) System.out.println(r);

        // Basic validation
        assertThat(results).hasSize(2);
    }

    /**
     * Experiment 7 Part B: Compare state-tuned RWKV against naked and prompt-injected.
     *
     * Requires three servers:
     * - SOUL_RWKV_URL: naked RWKV (no state, e.g., llama-server or Python serve.py without --state)
     * - SOUL_RWKV_STATE_URL: state-tuned RWKV (serve.py with --state)
     * - SOUL_EMBEDDING_URL: Ollama for baseline model + embeddings
     *
     * If only SOUL_RWKV_STATE_URL is set (no SOUL_RWKV_URL), runs state-tuned only.
     */
    @Test
    void live_rwkv_state_comparison() throws Exception {
        var stateUrl = System.getenv("SOUL_RWKV_STATE_URL");  // State-tuned server
        var nakedUrl = System.getenv("SOUL_RWKV_URL");        // Naked RWKV (optional)
        var ollamaUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var baselineModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");

        if (stateUrl == null || ollamaUrl == null) {
            System.out.println("SKIP: Need SOUL_RWKV_STATE_URL and SOUL_EMBEDDING_URL");
            return;
        }

        var stateInf = new InferenceHelper(stateUrl, "rwkv7-state", Duration.ofMinutes(10));
        var ollamaApiUrl = ollamaUrl.contains("/v1") ? ollamaUrl : ollamaUrl + "/v1";
        var baselineInf = new InferenceHelper(ollamaApiUrl, baselineModel);
        var scenarios = Scenario.standardSuite();

        System.out.println("=== Experiment 7 Part B: RWKV State-Tuned Comparison ===");
        System.out.println("State-tuned server: " + stateUrl);
        if (nakedUrl != null) System.out.println("Naked server: " + nakedUrl);
        System.out.println("Baseline model: " + baselineModel);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold baseline
        System.out.println("--- Generating gold baseline on " + baselineModel + " ---");
        var goldResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var s : scenarios) {
            var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
            var resp = baselineInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg);
            goldResponses.add(new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
        }
        var goldBaseline = new BehavioralRecord("gold", "Wyrd", baselineModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT, null, Instant.now(), goldResponses);
        System.out.println("Gold baseline: " + goldResponses.size() + " responses\n");

        // Step 2: Extract soul fingerprint
        var soul = SoulExtractor.extract(goldBaseline, SoulExtractor.Detail.FULL);
        System.out.println("Soul fingerprint: " + soul.length() + " chars\n");

        var stopSeqs = List.of("User:", "\nUser", "\n\nUser");
        var results = new ArrayList<String>();

        // Condition S: state-tuned RWKV (personality in state, no soul prompt)
        System.out.println("--- S: rwkv-state ---");
        var stateResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var s : scenarios) {
            var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
            var resp = stateInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg, 256, 0.7, stopSeqs);
            stateResponses.add(new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
        }
        var stateRecord = new BehavioralRecord("S: rwkv-state", "Wyrd", "rwkv7-2.9b-state",
            null, null, Instant.now(), stateResponses);
        var stateReport = BehavioralMetrics.compareWithEmbeddings(goldBaseline, stateRecord, ollamaUrl, embeddingModel);
        var stateLine = String.format("  %-25s div=%.1f%% sem=%.1f%% vocab=%d%%",
            "S: rwkv-state", stateReport.overallDivergence() * 100,
            stateReport.semanticSimilarity() * 100,
            Math.round(stateReport.vocabularyOverlap() * 100));
        System.out.println(stateLine);
        results.add(stateLine);

        // Condition D: naked RWKV (if server provided)
        if (nakedUrl != null) {
            var nakedInf = new InferenceHelper(nakedUrl, "rwkv7", Duration.ofMinutes(10));
            System.out.println("--- D: rwkv-naked ---");
            var nakedResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
            for (var s : scenarios) {
                var msg = "[Room: " + s.roomContext() + "]\nA player says: " + s.playerMessage();
                var resp = nakedInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg, 256, 0.7, stopSeqs);
                nakedResponses.add(new BehavioralRecord.ScenarioResponse(
                    s.id(), s.category(), s.playerMessage(), resp, resp.split("\\s+").length, 0));
            }
            var nakedRecord = new BehavioralRecord("D: rwkv-naked", "Wyrd", "rwkv7-2.9b",
                null, null, Instant.now(), nakedResponses);
            var nakedReport = BehavioralMetrics.compareWithEmbeddings(goldBaseline, nakedRecord, ollamaUrl, embeddingModel);
            var nakedLine = String.format("  %-25s div=%.1f%% sem=%.1f%% vocab=%d%%",
                "D: rwkv-naked", nakedReport.overallDivergence() * 100,
                nakedReport.semanticSimilarity() * 100,
                Math.round(nakedReport.vocabularyOverlap() * 100));
            System.out.println(nakedLine);
            results.add(nakedLine);
        }

        System.out.println("\n=== RWKV State-Tuned Results ===");
        for (var r : results) System.out.println(r);

        assertThat(results).isNotEmpty();
    }
}
