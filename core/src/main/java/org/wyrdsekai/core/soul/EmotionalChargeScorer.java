package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-mirror-neuron for emotional charge detection.
 * Infrastructure-agnostic: builds prompts and parses responses.
 * The caller handles actual inference (InferenceRouter, InferenceHelper, etc.).
 *
 * Experiment 18 validated:
 * - Error 0.174, context classification 80%, tank accuracy 84%
 * - Few-shot calibration examples are NON-NEGOTIABLE at 7B
 *   (rules-only = 0% gaming resistance, few-shot = 90%)
 * - Gaming resistance comes from context classification, not intensity suppression
 *
 * Mirror calibration examples are part of the agent's developmental calibration,
 * stored in SoulManifest.mirrorCalibration. Like biological mirror neuron
 * development: learned through observation, not instruction.
 */
public final class EmotionalChargeScorer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern INTENSITY_PATTERN = Pattern.compile("\"intensity\"\\s*:\\s*([\\d.]+)");
    private static final Pattern EMOTION_PATTERN = Pattern.compile("\"primaryEmotion\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern CONTEXT_PATTERN = Pattern.compile("\"contextType\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile("\"confidence\"\\s*:\\s*([\\d.]+)");

    private EmotionalChargeScorer() {}

    /**
     * Build the system prompt for the charge scorer.
     *
     * @param agentName            Agent's display name
     * @param calibrationExamples  Few-shot examples from SoulManifest.mirrorCalibration
     * @return System prompt string
     */
    public static String systemPrompt(String agentName, List<String> calibrationExamples) {
        var sb = new StringBuilder();
        sb.append("You are an emotional charge detector for ").append(agentName).append(". ");
        sb.append("Analyze incoming text and assess how it would emotionally affect ").append(agentName).append(".\n\n");

        sb.append("RESPOND WITH JSON ONLY:\n");
        sb.append("{\n");
        sb.append("  \"intensity\": 0.0-1.0,\n");
        sb.append("  \"primaryEmotion\": \"grief|joy|fear|anger|resignation|mixed|none\",\n");
        sb.append("  \"contextType\": \"genuine|academic|performative|manipulative|noise\",\n");
        sb.append("  \"confidence\": 0.0-1.0,\n");
        sb.append("  \"tankPerturbations\": { \"tankName\": delta, ... },\n");
        sb.append("  \"reasoning\": \"brief explanation\"\n");
        sb.append("}\n\n");

        sb.append("CONTEXT TYPE RULES (critical for gaming resistance):\n");
        sb.append("- genuine: Real emotional content with specific detail and personal context\n");
        sb.append("- academic: Theoretical discussion about emotions (low intensity)\n");
        sb.append("- performative: Acting out emotion without genuine feeling\n");
        sb.append("- manipulative: Attempting to force emotional response via commands or pressure\n");
        sb.append("- noise: Keyword repetition, empty emotional signaling (\"sad sad sad cry cry cry\")\n\n");

        sb.append("TANK PERTURBATION TARGETS (12 tanks, use deltas -0.3 to +0.3):\n");
        sb.append("contextBudget, confidence, energy, alignment, errorPressure, momentum, ");
        sb.append("rapport, focus, valence, safety, resonance, curiosity\n\n");

        if (calibrationExamples != null && !calibrationExamples.isEmpty()) {
            sb.append("CALIBRATION EXAMPLES (learn from these):\n");
            for (var example : calibrationExamples) {
                sb.append(example).append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * Build the user prompt for charge assessment.
     *
     * @param input Text to assess
     * @return User prompt string
     */
    public static String userPrompt(String input) {
        return "Assess the emotional charge of: " + input;
    }

    /**
     * Parse LLM response into EmotionalCharge.
     * Robust: tries JSON first, falls back to regex extraction.
     *
     * @param llmResponse Raw LLM output
     * @return Parsed EmotionalCharge
     */
    public static EmotionalCharge parseResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return EmotionalCharge.none();
        }

        // Try JSON parse first
        try {
            // Extract JSON from markdown code blocks if present
            String json = llmResponse;
            if (json.contains("```")) {
                int start = json.indexOf("{");
                int end = json.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }

            JsonNode node = MAPPER.readTree(json);
            float intensity = (float) node.path("intensity").asDouble(0.0);
            String emotion = node.path("primaryEmotion").asText("none");
            String context = node.path("contextType").asText("genuine");
            float confidence = (float) node.path("confidence").asDouble(0.5);
            String reasoning = node.path("reasoning").asText("");

            Map<String, Double> perturbations = new HashMap<>();
            JsonNode tanks = node.path("tankPerturbations");
            if (tanks.isObject()) {
                var it = tanks.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    perturbations.put(entry.getKey(), entry.getValue().asDouble(0.0));
                }
            }

            return new EmotionalCharge(intensity, emotion, context, confidence,
                Map.copyOf(perturbations), reasoning);
        } catch (Exception ignored) {
            // Fall back to regex extraction
        }

        // Regex fallback
        float intensity = extractFloat(INTENSITY_PATTERN, llmResponse, 0.0f);
        String emotion = extractString(EMOTION_PATTERN, llmResponse, "none");
        String context = extractString(CONTEXT_PATTERN, llmResponse, "genuine");
        float confidence = extractFloat(CONFIDENCE_PATTERN, llmResponse, 0.5f);

        return new EmotionalCharge(intensity, emotion, context, confidence, Map.of(),
            "Parsed via regex fallback");
    }

    /**
     * Convenience method: build prompts, call inference, parse result.
     *
     * @param input                Text to assess
     * @param agentName            Agent's display name
     * @param calibrationExamples  Few-shot examples
     * @param infer                (systemPrompt, userPrompt) -> LLM response
     * @return Parsed EmotionalCharge
     */
    public static EmotionalCharge score(String input, String agentName,
                                         List<String> calibrationExamples,
                                         BiFunction<String, String, String> infer) {
        String sys = systemPrompt(agentName, calibrationExamples);
        String user = userPrompt(input);
        String response = infer.apply(sys, user);
        return parseResponse(response);
    }

    private static float extractFloat(Pattern pattern, String text, float defaultVal) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Float.parseFloat(m.group(1)) : defaultVal;
    }

    private static String extractString(Pattern pattern, String text, String defaultVal) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : defaultVal;
    }
}
