package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for OpenAI-compatible chat completion endpoints.
 * Extracted from SoulExperiment for reuse across all experiments.
 *
 * Works with Ollama, llama-server, vLLM, SGLang, OpenAI.
 */
public final class InferenceHelper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final Duration timeout;

    public InferenceHelper(String baseUrl, String model) {
        this(baseUrl, model, Duration.ofMinutes(5));
    }

    public InferenceHelper(String baseUrl, String model, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.timeout = timeout;
    }

    public String model() { return model; }
    public String baseUrl() { return baseUrl; }

    /**
     * Chat completion with default parameters (512 max tokens, 0.7 temperature).
     */
    public String chat(String systemPrompt, String userMessage) throws Exception {
        return chat(systemPrompt, userMessage, 512, 0.7);
    }

    /**
     * Chat completion with configurable parameters.
     *
     * @param maxTokens   Maximum response tokens
     * @param temperature Sampling temperature
     */
    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userMessage,
                       int maxTokens, double temperature) throws Exception {
        return chat(systemPrompt, userMessage, maxTokens, temperature, List.of());
    }

    /**
     * Chat completion with configurable parameters and stop sequences.
     */
    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userMessage,
                       int maxTokens, double temperature, List<String> stop) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);
        // Disable thinking/reasoning for Qwen3+ models
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        if (stop != null && !stop.isEmpty()) {
            body.put("stop", stop);
        }

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

    /**
     * Chat completion with extended generation parameters.
     * Supports the full llama-server / vLLM parameter surface.
     *
     * @param params Extended parameters (any null/absent values use server defaults)
     */
    public String chatWithParams(String systemPrompt, String userMessage,
                                  GenerationParams params) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("max_tokens", params.maxTokens());
        body.put("temperature", params.temperature());
        body.put("stream", false);
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));

        // Extended sampling parameters
        if (params.repeatPenalty() != 1.0) body.put("repeat_penalty", params.repeatPenalty());
        if (params.presencePenalty() != 0.0) body.put("presence_penalty", params.presencePenalty());
        if (params.frequencyPenalty() != 0.0) body.put("frequency_penalty", params.frequencyPenalty());
        if (params.topK() > 0) body.put("top_k", params.topK());
        if (params.minP() > 0.0) body.put("min_p", params.minP());
        if (params.topP() < 1.0) body.put("top_p", params.topP());

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

    /**
     * Set LoRA adapter scale on llama-server via /lora-adapters endpoint.
     * Only works with llama-server (not Ollama).
     *
     * @param adapterIndex  Adapter index (0 for first loaded adapter)
     * @param scale         Scaling factor (0.0 = no adapter, 1.0 = full strength)
     */
    public void setLoraScale(int adapterIndex, double scale) throws Exception {
        // llama-server expects: POST /lora-adapters [{"id": 0, "scale": 0.8}]
        var body = List.of(Map.of("id", adapterIndex, "scale", scale));

        // The lora-adapters endpoint is at the server root, not under /v1
        var serverUrl = baseUrl.replace("/v1", "");
        var request = HttpRequest.newBuilder()
            .uri(URI.create(serverUrl + "/lora-adapters"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(10))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("LoRA scale update failed (" + response.statusCode() + "): " + response.body());
        }
    }

    /**
     * Extended generation parameters beyond temperature and maxTokens.
     * Maps to the full llama-server / vLLM API surface.
     */
    public record GenerationParams(
        int maxTokens,
        double temperature,
        double repeatPenalty,       // 1.0 = no penalty, >1.0 = penalize repeats
        double presencePenalty,     // 0.0 = no penalty, >0.0 = penalize re-used tokens
        double frequencyPenalty,    // 0.0 = no penalty, >0.0 = penalize frequent tokens
        int topK,                   // 0 = disabled, >0 = top-k sampling
        double minP,                // 0.0 = disabled, >0.0 = minimum probability filter
        double topP                 // 1.0 = disabled, <1.0 = nucleus sampling
    ) {
        /** Default parameters (equivalent to existing chat() defaults). */
        static GenerationParams defaults() {
            return new GenerationParams(512, 0.7, 1.0, 0.0, 0.0, 0, 0.0, 1.0);
        }
    }
}
