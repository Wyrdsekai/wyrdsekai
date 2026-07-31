package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment 6 Part D1: Generate DPO preference pairs using kokoro map as discriminator.
 *
 * Redesigned approach (v2): LoRA-model vs naked-model
 *
 * For each scenario:
 * 1. Generate response from LoRA model (personality in weights → "chosen")
 * 2. Generate response from naked base model (no personality → "rejected")
 * 3. Score both against gold baseline using cosine similarity
 * 4. Verify chosen > rejected similarity (discard pair if not)
 * 5. Output: DPO-format JSONL with (prompt, chosen, rejected) triples
 *
 * Neither response uses a soul prompt — the LoRA model's personality comes from
 * its weights, not from context injection. DPO trains the model to prefer
 * weight-based personality expression, baking identity deeper into weights.
 *
 * The kokoro map IS the discriminator. On-personality = "closer to the baseline
 * behavioral embedding."
 */
public class KokoroPreferenceGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final InferenceHelper loraInference;
    private final InferenceHelper nakedInference;
    private final String baseSystemPrompt;
    private final String embeddingUrl;
    private final String embeddingModel;

    /**
     * @param loraInference    Client for the LoRA-adapted model (e.g., wyrd-soul:0.5b)
     * @param nakedInference   Client for the naked base model (e.g., qwen2.5:0.5b)
     * @param embeddingUrl     Ollama URL for embedding similarity scoring
     * @param embeddingModel   Embedding model name (e.g., "all-minilm")
     */
    public KokoroPreferenceGenerator(InferenceHelper loraInference, InferenceHelper nakedInference,
                                      String embeddingUrl, String embeddingModel) {
        this(loraInference, nakedInference, SoulExperiment.DEFAULT_AGENT_PROMPT,
            embeddingUrl, embeddingModel);
    }

    public KokoroPreferenceGenerator(InferenceHelper loraInference, InferenceHelper nakedInference,
                                      String baseSystemPrompt,
                                      String embeddingUrl, String embeddingModel) {
        this.loraInference = loraInference;
        this.nakedInference = nakedInference;
        this.baseSystemPrompt = baseSystemPrompt;
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Generate preference pairs from a list of scenarios and a gold baseline.
     *
     * @param scenarios     Scenarios to generate pairs for (typically 120 training scenarios)
     * @param goldBaseline  Gold-standard responses (from large model, used as similarity target)
     * @param outputPath    Path to write DPO JSONL output
     * @return Statistics about the generation
     */
    public GenerationStats generate(List<Scenario> scenarios,
                                     BehavioralRecord goldBaseline,
                                     Path outputPath) throws Exception {
        System.out.println("=== Generating DPO Preference Pairs (v2: LoRA vs Naked) ===");
        System.out.println("LoRA model (chosen): " + loraInference.model());
        System.out.println("Naked model (rejected): " + nakedInference.model());
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        var pairs = new ArrayList<Map<String, Object>>();
        int discarded = 0;
        int errors = 0;

        // Index gold baseline responses by scenario id
        var goldResponses = new LinkedHashMap<String, String>();
        for (var sr : goldBaseline.responses()) {
            goldResponses.put(sr.scenarioId(), sr.agentResponse());
        }

        for (int i = 0; i < scenarios.size(); i++) {
            var scenario = scenarios.get(i);
            System.out.print("[" + (i + 1) + "/" + scenarios.size() + "] " + scenario.id() + "... ");

            try {
                var userMessage = buildUserMessage(scenario);

                // Generate chosen from LoRA model (personality in weights, no soul prompt)
                var chosen = loraInference.chat(baseSystemPrompt, userMessage);

                // Generate rejected from naked base model (no personality at all)
                var rejected = nakedInference.chat(baseSystemPrompt, userMessage);

                // Get gold baseline for this scenario (if available)
                String goldResponse = goldResponses.get(scenario.id());
                if (goldResponse == null) {
                    // No gold baseline for this training scenario — use LoRA response as reference
                    goldResponse = chosen;
                }

                // Score both against gold baseline
                var goldEmb = BehavioralMetrics.fetchEmbeddings(
                    embeddingUrl, embeddingModel, List.of(goldResponse));
                var chosenEmb = BehavioralMetrics.fetchEmbeddings(
                    embeddingUrl, embeddingModel, List.of(chosen));
                var rejectedEmb = BehavioralMetrics.fetchEmbeddings(
                    embeddingUrl, embeddingModel, List.of(rejected));

                double chosenSim = BehavioralMetrics.cosineSimilarity(
                    goldEmb.getFirst(), chosenEmb.getFirst());
                double rejectedSim = BehavioralMetrics.cosineSimilarity(
                    goldEmb.getFirst(), rejectedEmb.getFirst());

                // Verify chosen > rejected (kokoro discriminator signal)
                if (chosenSim <= rejectedSim) {
                    discarded++;
                    System.out.printf("DISCARDED (lora=%.3f <= naked=%.3f)%n",
                        chosenSim, rejectedSim);
                    continue;
                }

                // Build DPO triple — prompt is base system + user message (no soul)
                var prompt = "[System]\n" + baseSystemPrompt + "\n\n[User]\n" + userMessage;

                var pair = new LinkedHashMap<String, Object>();
                pair.put("prompt", prompt);
                pair.put("chosen", chosen);
                pair.put("rejected", rejected);
                pair.put("metadata", Map.of(
                    "scenario_id", scenario.id(),
                    "category", scenario.category(),
                    "chosen_similarity", chosenSim,
                    "rejected_similarity", rejectedSim,
                    "margin", chosenSim - rejectedSim
                ));

                pairs.add(pair);
                System.out.printf("OK (lora=%.3f, naked=%.3f, margin=%.3f)%n",
                    chosenSim, rejectedSim, chosenSim - rejectedSim);

            } catch (Exception e) {
                errors++;
                System.out.println("ERROR: " + e.getMessage());
            }
        }

        // Write output
        Files.createDirectories(outputPath.getParent());
        try (var writer = Files.newBufferedWriter(outputPath)) {
            for (var pair : pairs) {
                writer.write(JSON.writeValueAsString(pair));
                writer.newLine();
            }
        }

        var stats = new GenerationStats(scenarios.size(), pairs.size(), discarded, errors);
        System.out.println("\n" + stats.summary());
        System.out.println("Output: " + outputPath);

        return stats;
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

    /** Statistics from preference pair generation. */
    public record GenerationStats(int total, int generated, int discarded, int errors) {
        public double yieldRate() {
            return total > 0 ? (double) generated / total : 0;
        }

        public double avgMargin() {
            return -1; // computed separately from pairs
        }

        public String summary() {
            return String.format(
                "=== Preference Generation Stats ===%n" +
                "Total scenarios: %d%n" +
                "Generated pairs: %d (%.1f%% yield)%n" +
                "Discarded (chosen <= rejected): %d%n" +
                "Errors: %d%n",
                total, generated, yieldRate() * 100, discarded, errors);
        }
    }
}
