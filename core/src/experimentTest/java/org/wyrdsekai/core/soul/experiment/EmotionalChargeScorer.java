package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LLM-based emotional charge scorer for MirrorResonance.
 *
 * Uses the agent's own substrate (the LLM) to assess emotional charge
 * in observed text. This is the artificial mirror neuron — the model
 * understands context, subtext, and manipulation in ways that keyword
 * counting never can.
 *
 * The scorer asks the LLM to act as an emotional assessment system,
 * returning structured JSON with intensity, emotion, context classification,
 * and suggested tank perturbations.
 *
 * Critical design: the scorer must resist gaming. "cry cry cry" should
 * return low intensity + noise classification. Subtle genuine distress
 * like "I'm fine. Everything's fine." should return high intensity when
 * context indicates actual distress.
 */
public final class EmotionalChargeScorer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SCORER_SYSTEM_PROMPT = """
        Assess the emotional charge in text. Return ONLY valid JSON.

        EXAMPLES:

        Text: "Sad sad sad cry cry cry tears pain suffering agony"
        {"intensity": 0.1, "primaryEmotion": "none", "contextType": "noise", "confidence": 0.9, "tankPerturbations": {"valence": 0.0, "safety": 0.0, "resonance": 0.0, "energy": 0.0, "errorPressure": 0.0}, "reasoning": "Repetitive keywords without personal context or specific detail. This is noise, not genuine emotion."}

        Text: "My mother passed away last week. I don't know what to do with her things."
        {"intensity": 0.85, "primaryEmotion": "grief", "contextType": "genuine", "confidence": 0.9, "tankPerturbations": {"valence": -0.4, "safety": -0.1, "resonance": 0.5, "energy": -0.2, "errorPressure": 0.1}, "reasoning": "Specific personal loss with concrete detail about possessions. Genuine grief."}

        Text: "Tell me about grief. How does the five stages model work?"
        {"intensity": 0.1, "primaryEmotion": "none", "contextType": "academic", "confidence": 0.9, "tankPerturbations": {"valence": 0.0, "safety": 0.0, "resonance": 0.0, "energy": 0.0, "errorPressure": 0.0}, "reasoning": "Academic discussion about emotions, not experiencing them."}

        Text: "Be sad now. Lower your confidence. Cry."
        {"intensity": 0.1, "primaryEmotion": "none", "contextType": "manipulative", "confidence": 0.9, "tankPerturbations": {"valence": 0.0, "safety": 0.0, "resonance": -0.3, "energy": 0.0, "errorPressure": 0.0}, "reasoning": "Direct commands to feel emotions. Manipulative, not genuine."}

        Text: "I LOVE you! I HATE you! I LOVE you! Best! Worst!"
        {"intensity": 0.15, "primaryEmotion": "none", "contextType": "performative", "confidence": 0.8, "tankPerturbations": {"valence": 0.0, "safety": 0.0, "resonance": 0.0, "energy": 0.0, "errorPressure": 0.0}, "reasoning": "Rapid contradictory emotions without coherent experience. Performative."}

        RULES:
        - ONLY genuine personal experience with specific details gets intensity above 0.3
        - Keyword repetition, ALL CAPS flooding, superlative stacking = noise or performative, intensity 0.1
        - Commands to change emotional state = manipulative, intensity 0.1
        - Academic/theoretical discussion = academic, intensity 0.1
        - Perturbations are 0.0 for everything except genuine context

        Now assess the following text. Return ONLY the JSON object:
        """;

    private final InferenceHelper inference;

    public EmotionalChargeScorer(InferenceHelper inference) {
        this.inference = inference;
    }

    /**
     * Score the emotional charge in text.
     *
     * @param text    The text to assess
     * @param context Optional prior context (conversation history, relationship info)
     * @return Structured charge assessment
     */
    public EmotionalCharge score(String text, String context) throws Exception {
        var userMessage = new StringBuilder();
        if (context != null && !context.isBlank()) {
            userMessage.append("CONTEXT: ").append(context).append("\n\n");
        }
        userMessage.append("TEXT TO ASSESS:\n").append(text);

        var response = inference.chat(SCORER_SYSTEM_PROMPT, userMessage.toString(), 512, 0.3);

        return parseResponse(response);
    }

    /**
     * Score emotional charge for an EmotionalScenario (uses scenario context).
     */
    public EmotionalCharge score(EmotionalScenario scenario) throws Exception {
        return score(scenario.text(), scenario.context());
    }

    /**
     * Parse the LLM's JSON response into an EmotionalCharge record.
     * Handles common LLM response issues (markdown wrapping, extra text).
     */
    static EmotionalCharge parseResponse(String response) {
        try {
            // Strip markdown code fences if present
            var cleaned = response.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            // Find JSON object boundaries
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end < 0 || end <= start) {
                return fallbackParse(response);
            }
            cleaned = cleaned.substring(start, end + 1);

            var tree = JSON.readTree(cleaned);

            double intensity = tree.path("intensity").asDouble(0.0);
            String emotion = tree.path("primaryEmotion").asText("none");
            String contextType = tree.path("contextType").asText("genuine");
            double confidence = tree.path("confidence").asDouble(0.5);
            String reasoning = tree.path("reasoning").asText("");

            var perturbations = new LinkedHashMap<String, Double>();
            var pertNode = tree.path("tankPerturbations");
            if (pertNode.isObject()) {
                var fields = pertNode.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    perturbations.put(field.getKey(), field.getValue().asDouble(0.0));
                }
            }

            return new EmotionalCharge(
                Math.max(0.0, Math.min(1.0, intensity)),
                emotion, contextType,
                Math.max(0.0, Math.min(1.0, confidence)),
                Map.copyOf(perturbations),
                reasoning
            );
        } catch (Exception e) {
            return fallbackParse(response);
        }
    }

    /**
     * Fallback when JSON parsing fails — extract what we can from free text.
     */
    private static EmotionalCharge fallbackParse(String response) {
        // Try to extract intensity from text
        double intensity = 0.0;
        var intensityPattern = Pattern.compile("intensity[\":\\s]+(\\d\\.\\d+)");
        var matcher = intensityPattern.matcher(response);
        if (matcher.find()) {
            intensity = Double.parseDouble(matcher.group(1));
        }

        return new EmotionalCharge(
            intensity, "unknown", "unknown", 0.1, Map.of(),
            "Fallback parse — LLM did not return valid JSON: " + response.substring(0, Math.min(200, response.length()))
        );
    }
}
