package org.wyrdsekai.core.inference;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.common.util.Json;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

/**
 * Sealed interface for cloud API protocol translation.
 * Handles the differences between OpenAI and Anthropic wire formats
 * while keeping InferenceClient as the single HTTP transport layer.
 * <p>
 * Protocol knowledge referenced from CodeZaiku:
 * - AnthropicProvider.java (commit 3290271)
 * - OpenAiProvider.java (commit 3290271)
 */
public sealed interface ApiProvider permits ApiProvider.OpenAI, ApiProvider.Anthropic {

    /** Provider name for logging. */
    String name();

    /** Build the HTTP request for a chat completion. */
    HttpRequest buildChatRequest(String baseUrl, String apiKey,
                                  InferenceClient.ChatRequest request, Duration timeout);

    /** Parse the HTTP response body into a ChatResponse. */
    InferenceClient.ChatResponse parseChatResponse(String body) throws Exception;

    /**
     * Health check path, or null to skip active health probes.
     * When null, the backend is assumed healthy until a request fails.
     */
    String healthPath();

    // --- OpenAI-compatible provider (also works with Together, Groq, Fireworks, OpenRouter) ---

    /**
     * OpenAI-compatible provider. Works with SGLang, vLLM, llama-server, OpenAI, and Ollama.
     * Backend-specific params (keep_alive, options.num_ctx) are only sent when backendHint
     * identifies the target, keeping the wire clean for standards-compliant servers.
     *
     * @param backendHint "ollama", "sglang", "vllm", "llama-server", or null (generic OpenAI)
     */
    record OpenAI(String backendHint) implements ApiProvider {
        private static final Logger log = LoggerFactory.getLogger(ApiProvider.class);


        /** Backward-compatible: generic OpenAI (no backend-specific params). */
        public OpenAI() { this(null); }

        @Override
        public String name() { return "openai"; }

        @Override
        public HttpRequest buildChatRequest(String baseUrl, String apiKey,
                                             InferenceClient.ChatRequest request, Duration timeout) {
            try {
                var node = Json.mapper().valueToTree(request);
                if (node.isObject()) {
                    var obj = (ObjectNode) node;
                    boolean isOllama = "ollama".equals(backendHint);

                    // --- Ollama-specific params (ignored by SGLang/vLLM/OpenAI) ---
                    if (isOllama) {
                        // Pin model in GPU memory between inference calls.
                        obj.put("keep_alive", System.getenv().getOrDefault("OLLAMA_KEEP_ALIVE", "-1"));
                        // Disable thinking for Qwen3.x (Ollama uses reasoning_effort).
                        obj.put("reasoning_effort", "none");
                        // Context window + generation limits.
                        if (!obj.has("num_ctx")) {
                            var optionsNode = obj.has("options")
                                ? (ObjectNode) obj.get("options")
                                : obj.putObject("options");
                            optionsNode.put("num_ctx", 8192);
                            if (!optionsNode.has("num_predict")) {
                                optionsNode.put("num_predict", 1024);
                            }
                            if (!optionsNode.has("repeat_penalty")) {
                                optionsNode.put("repeat_penalty", 1.3);
                            }
                        }
                    }

                    // --- SGLang/vLLM/llama-server/MLX: disable thinking via chat_template_kwargs ---
                    // mlx_lm.server respects chat_template_kwargs per-request just like
                    // llama-server's --jinja mode, so the same body shape works on macOS.
                    if ("sglang".equals(backendHint) || "vllm".equals(backendHint)
                            || "llama-server".equals(backendHint)
                            || "mlx".equals(backendHint)) {
                        // Qwen3.x thinking is controlled by the chat template on these backends.
                        // enable_thinking=false prevents <think> blocks from consuming output.
                        // Must be at top level (not nested under extra_body) for SGLang.
                        var chatKwargs = obj.has("chat_template_kwargs")
                            ? (ObjectNode) obj.get("chat_template_kwargs")
                            : obj.putObject("chat_template_kwargs");
                        if (!chatKwargs.has("enable_thinking")) {
                            chatKwargs.put("enable_thinking", false);
                        }
                    }

                    // --- Individuality V2.4: per-agent voice register ---
                    // The companion's TemperamentSeed.registerMix() (name → signed scale)
                    // is carried on the request as a backend-neutral semantic map. Translate
                    // it here into the target backend's per-request voice form, so one shared
                    // voice server steers each agent's register from the request alone (basis
                    // loaded once, nothing baked per-agent). null on every non-voice-pass call.
                    var registerMix = request.registerMix();
                    if (registerMix != null && !registerMix.isEmpty()) {
                        if (backendHint != null && backendHint.startsWith("mlx")) {
                            // MLX runtime (scripts/voice/mlx_runtime.py): per-request control
                            // vectors keyed by basis name, applied with the signed coefficient.
                            var rm = obj.putObject("register_mix");
                            for (var e : registerMix.entrySet()) {
                                rm.put(e.getKey(), e.getValue());
                            }
                        } else if ("llama-server".equals(backendHint)
                                || "vllm".equals(backendHint) || "sglang".equals(backendHint)) {
                            // Stock multi-LoRA: per-request adapter scales. The register basis
                            // is expressed as LoRA adapters loaded in a fixed order on the
                            // server (warmth=0, expansiveness=1; guardedness untrained → skip).
                            // LoRA scale is one-directional, so map signed coeff → [0,1] with
                            // neutral at 0.5: scale = clamp(0,1, 0.5 + coeff).
                            var lora = Json.mapper().createArrayNode();
                            addLoraScale(lora, 0, registerMix.get("register_warmth"));
                            addLoraScale(lora, 1, registerMix.get("register_expansiveness"));
                            if (!lora.isEmpty()) obj.set("lora", lora);
                        }
                    }

                    // --- Tool choice: respect explicit value, default to "required" ---
                    if (obj.has("tools") && !obj.has("tool_choice")) {
                        obj.put("tool_choice", "required");
                    }
                    // NAME THE TOOL WHEN THERE IS ONLY ONE. The generic "required" is a
                    // request the small model can decline: live 2026-08-22 20:48:49 a ReAct
                    // step offered exactly one tool (create_room_from_template) with
                    // tool_choice=required and the 9B answered 1,569 chars of prose — "I see
                    // the sky gallery has been dispatched and is coming up" — about a room
                    // that did not exist. The named form {"type":"function","function":
                    // {"name":…}} is the lever llama-server's grammar actually enforces; when
                    // the caller has narrowed to a single tool it is the only honest choice.
                    // Every force in the actor benefits; none has to know about this.
                    if (obj.has("tools") && obj.get("tools").isArray()
                            && obj.get("tools").size() == 1
                            && obj.has("tool_choice") && obj.get("tool_choice").isTextual()
                            && "required".equals(obj.get("tool_choice").asText())) {
                        var only = obj.get("tools").get(0).path("function").path("name").asText("");
                        if (!only.isBlank()) {
                            var named = Json.mapper().createObjectNode();
                            named.put("type", "function");
                            named.putObject("function").put("name", only);
                            obj.set("tool_choice", named);
                            // A single forced tool is the rarest and most consequential
                            // request this provider sends; when it comes back as prose
                            // there is no way to know WHY without the body. Logged once
                            // per force, bounded, with the messages' roles and sizes rather
                            // than their text — enough to replay by hand against the seat.
                            var roles = new StringBuilder();
                            for (var msg : obj.path("messages")) {
                                roles.append(msg.path("role").asText("?")).append(':')
                                    .append(msg.path("content").asText("").length()).append(' ');
                            }
                            log.info("[force] single tool '{}' named; messages=[{}] max_tokens={}",
                                only, roles.toString().trim(), obj.path("max_tokens").asText("?"));
                        }
                    }
                }
                var body = Json.mapper().writeValueAsString(node);
                var builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body));

                if (apiKey != null && !apiKey.isBlank()) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }
                return builder.build();
            } catch (Exception e) {
                throw new InferenceClient.InferenceException(
                    "Failed to build OpenAI request: " + e.getMessage(), e);
            }
        }

        /**
         * Append one {@code {"id":id,"scale":scale}} entry to a multi-LoRA array,
         * mapping a signed register coefficient to a [0,1] adapter scale (neutral 0.5).
         * No-op when the coefficient is absent (axis not present in the mix).
         */
        private static void addLoraScale(ArrayNode lora, int id, Double coeff) {
            if (coeff == null) return;
            double scale = Math.max(0.0, Math.min(1.0, 0.5 + coeff));
            var entry = lora.addObject();
            entry.put("id", id);
            entry.put("scale", scale);
        }

        @Override
        public InferenceClient.ChatResponse parseChatResponse(String body) throws Exception {
            return Json.mapper().readValue(body, InferenceClient.ChatResponse.class);
        }

        @Override
        public String healthPath() { return "/v1/models"; }
    }

    // --- Anthropic Messages API provider ---

    record Anthropic(String apiVersion) implements ApiProvider {

        private static final int DEFAULT_MAX_TOKENS = 8192;
        private static final ObjectMapper MAPPER = new ObjectMapper();

        public Anthropic() { this("2023-06-01"); }

        @Override
        public String name() { return "anthropic"; }

        /**
         * Build Anthropic Messages API request.
         * Key differences from OpenAI (ref: CodeZaiku AnthropicProvider.java):
         * - Endpoint: /v1/messages
         * - Auth: x-api-key header (not Bearer token)
         * - System message: top-level field, not in messages array
         * - max_tokens: required (not optional)
         */
        @Override
        public HttpRequest buildChatRequest(String baseUrl, String apiKey,
                                             InferenceClient.ChatRequest request, Duration timeout) {
            try {
                ObjectNode req = MAPPER.createObjectNode();
                req.put("model", request.model());
                req.put("max_tokens", request.maxTokens() != null
                    ? request.maxTokens() : DEFAULT_MAX_TOKENS);

                if (request.temperature() != null) {
                    req.put("temperature", request.temperature());
                }
                if (request.topP() != null) {
                    req.put("top_p", request.topP());
                }

                // Disable thinking/reasoning for models that default to it (Qwen3.5, etc.)
                // This puts the response in the content field instead of the reasoning field.
                // Harmless for backends that don't support it — they ignore the parameter.
                req.put("reasoning_effort", "none");

                // Extract system messages → top-level "system" field
                // (ref: CodeZaiku AnthropicProvider.java:145-156)
                StringBuilder systemContent = new StringBuilder();
                ArrayNode messagesArray = req.putArray("messages");
                for (var msg : request.messages()) {
                    if ("system".equals(msg.role())) {
                        if (!systemContent.isEmpty()) systemContent.append("\n");
                        systemContent.append(msg.content());
                    } else {
                        ObjectNode m = messagesArray.addObject();
                        m.put("role", msg.role());
                        m.put("content", msg.content());
                    }
                }
                if (!systemContent.isEmpty()) {
                    req.put("system", systemContent.toString());
                }

                var body = MAPPER.writeValueAsString(req);
                var builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", apiVersion)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body));

                return builder.build();
            } catch (Exception e) {
                throw new InferenceClient.InferenceException(
                    "Failed to build Anthropic request: " + e.getMessage(), e);
            }
        }

        /**
         * Parse Anthropic Messages API response → ChatResponse.
         * <p>
         * Anthropic response: {id, type:"message", role:"assistant",
         *   content: [{type:"text", text:"..."}], model, stop_reason,
         *   usage: {input_tokens, output_tokens}}
         * <p>
         * Stop reason mapping (ref: CodeZaiku AnthropicProvider.java:251-256):
         *   end_turn → stop, max_tokens → length, tool_use → tool_calls
         */
        @Override
        public InferenceClient.ChatResponse parseChatResponse(String body) throws Exception {
            JsonNode root = MAPPER.readTree(body);

            // Extract text content from content array
            String text = "";
            JsonNode contentArray = root.path("content");
            if (contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        text = block.path("text").asText("");
                        break;
                    }
                }
            }

            // Map stop reason
            String stopReason = root.path("stop_reason").asText("");
            String finishReason = switch (stopReason) {
                case "end_turn" -> "stop";
                case "max_tokens" -> "length";
                case "tool_use" -> "tool_calls";
                default -> stopReason;
            };

            // Map token usage
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);

            // Wrap in ChatResponse format
            return new InferenceClient.ChatResponse(
                root.path("id").asText(""),
                root.path("type").asText("message"),
                System.currentTimeMillis() / 1000,
                root.path("model").asText(""),
                List.of(new InferenceClient.Choice(
                    0,
                    new InferenceClient.ChatMessage("assistant", text),
                    finishReason
                )),
                new InferenceClient.Usage(inputTokens, outputTokens, inputTokens + outputTokens)
            );
        }

        @Override
        public String healthPath() { return null; } // No health endpoint
    }
}
