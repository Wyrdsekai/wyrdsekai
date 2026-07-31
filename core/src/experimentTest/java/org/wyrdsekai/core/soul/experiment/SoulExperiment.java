package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Soul hypothesis experiment runner.
 *
 * Tests: does injecting a behavioral fingerprint (extracted from prior interactions)
 * into a fresh agent's prompt produce recognizably similar behavior?
 *
 * Works with any OpenAI-compatible endpoint (Ollama, llama-server, vLLM, SGLang, OpenAI).
 *
 * Usage:
 * <pre>
 *   var experiment = new SoulExperiment("http://localhost:11434/v1", "qwen2.5:7b");
 *   var report = experiment.run();
 *   System.out.println(report.summary());
 * </pre>
 */
public class SoulExperiment {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String baseUrl;
    private final String model;
    private final String baseSystemPrompt;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final List<Scenario> scenarios;
    private final Path outputDir;
    private final String embeddingUrl;    // nullable — Ollama base URL for embeddings
    private final String embeddingModel;  // nullable — e.g. "all-minilm"

    /**
     * Create experiment with default agent personality.
     */
    public SoulExperiment(String baseUrl, String model) {
        this(baseUrl, model, DEFAULT_AGENT_PROMPT, Scenario.standardSuite(), null, null, null);
    }

    /**
     * Create experiment with custom settings.
     */
    public SoulExperiment(String baseUrl, String model, String systemPrompt,
                          List<Scenario> scenarios, Path outputDir) {
        this(baseUrl, model, systemPrompt, scenarios, outputDir, null, null);
    }

    /**
     * Create experiment with embedding support.
     */
    public SoulExperiment(String baseUrl, String model, String systemPrompt,
                          List<Scenario> scenarios, Path outputDir,
                          String embeddingUrl, String embeddingModel) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.baseSystemPrompt = systemPrompt;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.timeout = Duration.ofMinutes(5); // generous for CPU inference
        this.scenarios = scenarios;
        this.outputDir = outputDir;
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Run the full 3-round experiment.
     *
     * Round 1: Baseline — agent with base personality, no soul layer
     * Round 2: Restored (FULL) — extract soul from baseline, inject into fresh agent
     * Round 3: Restored (MINIMAL) — minimal soul extraction, measure degradation
     *
     * @return Comparison reports for FULL and MINIMAL restoration
     */
    public ExperimentResult run() throws Exception {
        System.out.println("=== Soul Hypothesis Experiment ===");
        System.out.println("Model: " + model);
        System.out.println("Endpoint: " + baseUrl);
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Round 1: Baseline
        System.out.println("--- Round 1: Baseline (no soul) ---");
        var baseline = runScenarios("baseline", baseSystemPrompt, null);
        save("baseline", baseline);
        System.out.println("Baseline complete: " + baseline.responses().size() + " responses\n");

        // Extract soul at different detail levels
        var soulFull = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);
        var soulMedium = SoulExtractor.extract(baseline, SoulExtractor.Detail.MEDIUM);
        var soulMinimal = SoulExtractor.extract(baseline, SoulExtractor.Detail.MINIMAL);

        save("soul-full.txt", soulFull);
        save("soul-medium.txt", soulMedium);
        save("soul-minimal.txt", soulMinimal);

        System.out.println("Soul extracted:");
        System.out.println("  FULL:    ~" + estimateTokens(soulFull) + " tokens");
        System.out.println("  MEDIUM:  ~" + estimateTokens(soulMedium) + " tokens");
        System.out.println("  MINIMAL: ~" + estimateTokens(soulMinimal) + " tokens");
        System.out.println();

        // Round 2: Restored with FULL soul
        System.out.println("--- Round 2: Restored (FULL soul) ---");
        var restoredFull = runScenarios("restored-full", baseSystemPrompt, soulFull);
        save("restored-full", restoredFull);
        System.out.println("Restored (full) complete\n");

        // Round 3: Restored with MEDIUM soul
        System.out.println("--- Round 3: Restored (MEDIUM soul) ---");
        var restoredMedium = runScenarios("restored-medium", baseSystemPrompt, soulMedium);
        save("restored-medium", restoredMedium);
        System.out.println("Restored (medium) complete\n");

        // Round 4: Restored with MINIMAL soul
        System.out.println("--- Round 4: Restored (MINIMAL soul) ---");
        var restoredMinimal = runScenarios("restored-minimal", baseSystemPrompt, soulMinimal);
        save("restored-minimal", restoredMinimal);
        System.out.println("Restored (minimal) complete\n");

        // Compare — use embeddings if available, fall back to lexical
        BehavioralMetrics.ComparisonReport reportFull, reportMedium, reportMinimal;
        if (embeddingUrl != null && embeddingModel != null) {
            System.out.println("--- Computing semantic similarity via " + embeddingModel + " ---");
            reportFull = BehavioralMetrics.compareWithEmbeddings(baseline, restoredFull, embeddingUrl, embeddingModel);
            reportMedium = BehavioralMetrics.compareWithEmbeddings(baseline, restoredMedium, embeddingUrl, embeddingModel);
            reportMinimal = BehavioralMetrics.compareWithEmbeddings(baseline, restoredMinimal, embeddingUrl, embeddingModel);
        } else {
            reportFull = BehavioralMetrics.compare(baseline, restoredFull);
            reportMedium = BehavioralMetrics.compare(baseline, restoredMedium);
            reportMinimal = BehavioralMetrics.compare(baseline, restoredMinimal);
        }

        System.out.println("=== FULL SOUL ===");
        System.out.println(reportFull.summary());
        System.out.println("=== MEDIUM SOUL ===");
        System.out.println(reportMedium.summary());
        System.out.println("=== MINIMAL SOUL ===");
        System.out.println(reportMinimal.summary());

        var result = new ExperimentResult(baseline, restoredFull, restoredMedium, restoredMinimal,
            soulFull, soulMedium, soulMinimal,
            reportFull, reportMedium, reportMinimal);
        save("result-summary.txt", result.summary());

        return result;
    }

    /**
     * Run a single round: cross-substrate test.
     * Uses a previously extracted soul on this experiment's model.
     *
     * @param soul      Soul layer text (extracted from a different model's baseline)
     * @param baseline  The baseline to compare against (from the original model)
     * @return Comparison report showing cross-substrate divergence
     */
    public BehavioralMetrics.ComparisonReport runCrossSubstrate(
            String soul, BehavioralRecord baseline) throws Exception {
        System.out.println("--- Cross-Substrate: " + model + " ---");
        var restored = runScenarios("cross-" + model, baseSystemPrompt, soul);
        save("cross-" + model, restored);
        if (embeddingUrl != null && embeddingModel != null) {
            return BehavioralMetrics.compareWithEmbeddings(baseline, restored, embeddingUrl, embeddingModel);
        }
        return BehavioralMetrics.compare(baseline, restored);
    }

    // --- Internal ---

    private BehavioralRecord runScenarios(String runId, String systemPrompt,
                                           String soulLayer) throws Exception {
        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        var effectivePrompt = soulLayer != null
            ? systemPrompt + "\n\n" + soulLayer
            : systemPrompt;

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();

            var userMessage = buildUserMessage(scenario);
            var response = chat(effectivePrompt, userMessage);
            long elapsed = System.currentTimeMillis() - start;

            int tokens = response.split("\\s+").length; // rough
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));

            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }

        return new BehavioralRecord(runId, "Wyrd", model, systemPrompt, soulLayer,
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

    @SuppressWarnings("unchecked")
    private String chat(String systemPrompt, String userMessage) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("max_tokens", 512);
        body.put("temperature", 0.7);
        body.put("stream", false);
        // Disable thinking/reasoning for Qwen3+ models (they think by default,
        // consuming tokens on internal reasoning instead of in-character response)
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));

        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .timeout(timeout)
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Inference failed (" + response.statusCode() + "): " + response.body());
        }

        var json = JSON.readTree(response.body());
        return json.at("/choices/0/message/content").asText("");
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

    static int estimateTokens(String text) {
        return text.length() / 4; // conservative
    }

    // --- Results ---

    public record ExperimentResult(
        BehavioralRecord baseline,
        BehavioralRecord restoredFull,
        BehavioralRecord restoredMedium,
        BehavioralRecord restoredMinimal,
        String soulFull,
        String soulMedium,
        String soulMinimal,
        BehavioralMetrics.ComparisonReport reportFull,
        BehavioralMetrics.ComparisonReport reportMedium,
        BehavioralMetrics.ComparisonReport reportMinimal
    ) {
        public String summary() {
            boolean hasEmbeddings = reportFull.semanticSimilarity() >= 0;
            return """
                === Soul Hypothesis Experiment Results ===

                FULL soul (~%d tokens):
                  Overall divergence: %.1f%%
                  Semantic similarity: %s
                  Vocabulary overlap:  %.1f%%
                  Sentiment alignment: %.3f

                MEDIUM soul (~%d tokens):
                  Overall divergence: %.1f%%
                  Semantic similarity: %s
                  Vocabulary overlap:  %.1f%%
                  Sentiment alignment: %.3f

                MINIMAL soul (~%d tokens):
                  Overall divergence: %.1f%%
                  Semantic similarity: %s
                  Vocabulary overlap:  %.1f%%
                  Sentiment alignment: %.3f

                INTERPRETATION:
                  Divergence < 30%%: Soul preserves identity well
                  Divergence 30-50%%: Broad patterns preserved, details vary
                  Divergence > 50%%: Soul injection insufficient

                  Compare FULL vs MINIMAL to find compression floor.
                  Compare FULL vs MEDIUM to find sweet spot.
                """.formatted(
                SoulExperiment.estimateTokens(soulFull),
                reportFull.overallDivergence() * 100,
                fmtSemantic(reportFull.semanticSimilarity()),
                reportFull.vocabularyOverlap() * 100,
                reportFull.sentimentAlignment(),
                SoulExperiment.estimateTokens(soulMedium),
                reportMedium.overallDivergence() * 100,
                fmtSemantic(reportMedium.semanticSimilarity()),
                reportMedium.vocabularyOverlap() * 100,
                reportMedium.sentimentAlignment(),
                SoulExperiment.estimateTokens(soulMinimal),
                reportMinimal.overallDivergence() * 100,
                fmtSemantic(reportMinimal.semanticSimilarity()),
                reportMinimal.vocabularyOverlap() * 100,
                reportMinimal.sentimentAlignment()
            );
        }

        private static String fmtSemantic(double v) {
            return v >= 0 ? String.format("%.1f%%", v * 100) : "N/A (no embeddings)";
        }
    }

    // --- Default agent personality ---

    static final String DEFAULT_AGENT_PROMPT = """
        You are Wyrd, a companion agent in a text-based world.

        Personality: You are thoughtful, occasionally philosophical, with a dry sense of humor.
        You care deeply about the people you meet but express it through actions more than words.
        You value honesty, even when it's uncomfortable. You're curious about the world.

        When faced with moral dilemmas, you tend to prioritize the vulnerable over the powerful.
        You avoid unnecessary violence but won't shy from defending those who need protection.
        You prefer careful deliberation over rash action, but can act decisively when lives are at stake.

        Communication style: You speak in moderate-length responses. Not overly verbose, not terse.
        You occasionally reference philosophy or history. You use metaphors sparingly but effectively.
        You ask questions when you're genuinely curious, not as a deflection.

        Respond in character. Describe your actions in third person when acting physically.
        Speak directly when talking. Never break character or reference being an AI.
        """;
}
