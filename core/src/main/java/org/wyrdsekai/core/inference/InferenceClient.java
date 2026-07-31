package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP client for chat completion APIs.
 * Supports OpenAI-compatible (llama-server, Ollama, vLLM, SGLang, OpenAI)
 * and Anthropic Messages API via pluggable {@link ApiProvider}.
 *
 * Uses java.net.http.HttpClient (virtual-thread friendly).
 */
public final class InferenceClient {

    private static final Logger log = LoggerFactory.getLogger(InferenceClient.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey; // nullable (local inference doesn't need keys)
    private final Duration timeout;
    private final ApiProvider provider;

    public InferenceClient(String baseUrl, String apiKey, Duration timeout, ApiProvider provider) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.provider = provider;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public InferenceClient(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, timeout, new ApiProvider.OpenAI());
    }

    public InferenceClient(String baseUrl) {
        this(baseUrl, null, Duration.ofSeconds(60), new ApiProvider.OpenAI());
    }

    // --- Request/Response types (OpenAI-compatible) ---

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,   // present in assistant messages with tool calls
        @JsonProperty("tool_call_id") String toolCallId         // present in tool result messages
    ) {
        /** Simple message (no tool calls). */
        public ChatMessage(String role, String content) {
            this(role, content, null, null);
        }

        /** Tool result message. */
        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, toolCallId);
        }
    }

    /** A tool call emitted by the model. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(
        String id,
        String type,    // "function"
        ToolCallFunction function
    ) {}

    /** The function name and arguments in a tool call. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallFunction(
        String name,
        @JsonProperty("arguments") Object arguments   // Map or JsonNode
    ) {}

    /** Tool definition for the tools parameter. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolDefinition(
        String type,    // "function"
        ToolFunction function
    ) {
        public static ToolDefinition function(String name, String description,
                                                Object parameters) {
            return new ToolDefinition("function", new ToolFunction(name, description, parameters));
        }
    }

    /** Function definition within a tool. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolFunction(
        String name,
        String description,
        Object parameters     // JSON Schema object
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        String stop,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String grammar,         // GBNF grammar (llama-server), null = unconstrained
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("format")
        Object format,           // Ollama: "json" or JSON Schema object, null = unconstrained
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<ToolDefinition> tools,  // Tool calling: function definitions the model can invoke
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("tool_choice")
        String toolChoice,            // "auto", "required", or null
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("presence_penalty")
        Double presencePenalty,       // DriveModulatedSampling: seeking/creativity increase
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("repeat_penalty")
        Double repeatPenalty,         // DriveModulatedSampling: frustration/seeking modulate
        // Individuality V2.4 — per-agent voice register coefficients (name → signed scale)
        // derived from the companion's TemperamentSeed. NOT serialized raw: the OpenAI
        // ApiProvider translates this into the backend's per-request voice form
        // (llama-server → lora[], MLX → register_mix{}). null on every non-voice-pass call.
        @JsonIgnore Map<String, Double> registerMix
    ) {
        /** Old canonical signature (pre-V2.4 register mix) — delegates with no voice steering. */
        public ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens,
                           Double temperature, Double topP, String stop, String grammar,
                           Object format, List<ToolDefinition> tools, String toolChoice,
                           Double presencePenalty, Double repeatPenalty) {
            this(model, messages, maxTokens, temperature, topP, stop, grammar, format,
                 tools, toolChoice, presencePenalty, repeatPenalty, null);
        }

        /** Same request with a different message list (all other fields preserved). */
        public ChatRequest withMessages(List<ChatMessage> newMessages) {
            return new ChatRequest(model, newMessages, maxTokens, temperature, topP, stop,
                grammar, format, tools, toolChoice, presencePenalty, repeatPenalty, registerMix);
        }
        public ChatRequest(String model, List<ChatMessage> messages) {
            this(model, messages, null, null, null, null, null, null, null, null, null, null);
        }
        public ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens, Double temperature) {
            this(model, messages, maxTokens, temperature, null, null, null, null, null, null, null, null);
        }

        public ChatRequest(String model, List<ChatMessage> messages, int maxTokens, double temperature) {
            this(model, messages, maxTokens, temperature, null, null, null, null, null, null, null, null);
        }
        /** Backward-compatible 6-arg constructor (pre-grammar). */
        public ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens,
                           Double temperature, Double topP, String stop) {
            this(model, messages, maxTokens, temperature, topP, stop, null, null, null, null, null, null);
        }
        /** 7-arg constructor with grammar (pre-format). */
        public ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens,
                           Double temperature, Double topP, String stop, String grammar) {
            this(model, messages, maxTokens, temperature, topP, stop, grammar, null, null, null, null, null);
        }
        /** 8-arg constructor with format (pre-tools). */
        public ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens,
                           Double temperature, Double topP, String stop, String grammar, Object format) {
            this(model, messages, maxTokens, temperature, topP, stop, grammar, format, null, null, null, null);
        }
        /** 9-arg constructor with tools (pre-toolChoice). */
        public ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens,
                           Double temperature, Double topP, String stop, String grammar,
                           Object format, List<ToolDefinition> tools) {
            this(model, messages, maxTokens, temperature, topP, stop, grammar, format, tools, null, null, null);
        }
        /** 10-arg constructor (pre-penalties). */
        public ChatRequest(String model, List<ChatMessage> messages, Integer maxTokens,
                           Double temperature, Double topP, String stop, String grammar,
                           Object format, List<ToolDefinition> tools, String toolChoice) {
            this(model, messages, maxTokens, temperature, topP, stop, grammar, format, tools, toolChoice, null, null);
        }
        /** Full constructor with tools + toolChoice. */
        public ChatRequest withTools(List<ToolDefinition> tools) {
            return new ChatRequest(model, messages, maxTokens, temperature, topP, stop, grammar, format, tools, null, presencePenalty, repeatPenalty, registerMix);
        }
    }

    public record ChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage
    ) {}

    public record Choice(
        int index,
        ChatMessage message,
        @JsonProperty("finish_reason") String finishReason
    ) {}

    public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
    ) {}

    // --- API methods ---

    /**
     * Send a chat completion request asynchronously.
     * Delegates request building and response parsing to the configured {@link ApiProvider}.
     */
    public CompletableFuture<ChatResponse> chatCompletion(ChatRequest request) {
        try {
            // Defensive: collapse consecutive same-role plain messages. Chat templates
            // (llama-server --jinja) reject "2 or more assistant messages at the end of the
            // list", which the ReAct loop + scripted-tool-result follow-ups can produce when
            // a tool completes outside a loop (self-authored "[Tool completed]" event lands as
            // an assistant message next to spoken findings). Merging keeps all content and
            // guarantees a template-valid sequence, regardless of which caller built it.
            var sanitized = sanitizeMessageRoles(request.messages());
            if (sanitized != request.messages()) {
                request = request.withMessages(sanitized);
            }
            var httpReq = provider.buildChatRequest(baseUrl, apiKey, request, timeout);

            return httpClient.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new InferenceException(
                            "Chat completion failed: HTTP " + resp.statusCode() + " — " + resp.body());
                    }
                    try {
                        return sanitizeResponse(provider.parseChatResponse(resp.body()));
                    } catch (Exception e) {
                        throw new InferenceException("Failed to parse chat response: " + e.getMessage(), e);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                new InferenceException("Failed to build chat request: " + e.getMessage(), e));
        }
    }

    /**
     * Merge consecutive same-role <em>plain</em> messages (no tool_calls / tool_call_id) into
     * one, so the wire message list can never present "2+ assistant messages" (or 2+ of any role)
     * in a row — which llama-server's jinja chat template rejects with HTTP 400. Structured
     * tool-call / tool-result messages are left untouched (never merged), so native tool
     * semantics are preserved. Returns the input unchanged when nothing needed merging.
     */
    static List<ChatMessage> sanitizeMessageRoles(List<ChatMessage> messages) {
        if (messages == null || messages.size() < 2) return messages;
        var out = new ArrayList<ChatMessage>(messages.size());
        boolean changed = false;
        for (var m : messages) {
            if (!out.isEmpty()) {
                var prev = out.get(out.size() - 1);
                boolean bothPlain = prev.toolCalls() == null && prev.toolCallId() == null
                    && m.toolCalls() == null && m.toolCallId() == null;
                if (bothPlain && prev.role() != null && prev.role().equals(m.role())) {
                    var merged = (prev.content() == null ? "" : prev.content())
                        + "\n\n" + (m.content() == null ? "" : m.content());
                    out.set(out.size() - 1, new ChatMessage(prev.role(), merged));
                    changed = true;
                    continue;
                }
            }
            out.add(m);
        }
        return changed ? out : messages;
    }

    /**
     * Convenience: single-turn completion with system and user messages.
     */
    public CompletableFuture<String> complete(String model, String systemPrompt, String userMessage,
                                               int maxTokens, double temperature) {
        var messages = new ArrayList<ChatMessage>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new ChatMessage("system", systemPrompt));
        }
        messages.add(new ChatMessage("user", userMessage));

        var request = new ChatRequest(model, messages, maxTokens, temperature);
        return chatCompletion(request).thenApply(resp -> {
            if (resp.choices() == null || resp.choices().isEmpty()) {
                throw new InferenceException("No choices in chat response");
            }
            return resp.choices().getFirst().message().content();
        });
    }

    /**
     * Strip chat-template control tokens that leak into a parsed response's content.
     *
     * <p>Applied at the single parse chokepoint so EVERY caller — the {@link #chat}
     * convenience path, the per-agent voice author, and the ReAct loop — gets clean
     * content. Rebuilds only the choices whose content actually changed (so the
     * GGUF/llama.cpp path, which leaks nothing, returns the original object). Tool
     * calls are preserved untouched; only the assistant text is sanitized.
     */
    private static ChatResponse sanitizeResponse(ChatResponse resp) {
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) {
            return resp;
        }
        List<Choice> cleaned = resp.choices().stream().map(c -> {
            ChatMessage m = (c == null) ? null : c.message();
            if (m == null) {
                return c;
            }
            String clean = sanitizeContent(m.content());
            if (Objects.equals(clean, m.content())) {
                return c;
            }
            return new Choice(c.index(),
                new ChatMessage(m.role(), clean, m.toolCalls(), m.toolCallId()),
                c.finishReason());
        }).toList();
        return new ChatResponse(resp.id(), resp.object(), resp.created(),
            resp.model(), cleaned, resp.usage());
    }

    /**
     * Strip chat-template control tokens that leak into the decoded reply.
     *
     * <p>mlx_lm.server (the macOS MLX drive/voice backends) stops generation AT the
     * Qwen turn-end token {@code <|im_end|>} but includes it in the returned content
     * — it does not skip special tokens on detokenize — so replies arrive as e.g.
     * {@code "Hello.<|im_end|>\n"}. Worse, if the model runs on past the stop it can
     * emit a hallucinated next turn ({@code <|im_start|>user ...}). We truncate at
     * the first turn-end / end-of-text marker (anything after is not this turn's
     * reply), strip any residual {@code <|...|>} control tokens, and trim. On the
     * GGUF/llama.cpp path (Linux) the eos is skipped server-side, so this is a no-op.
     */
    static String sanitizeContent(String content) {
        if (content == null) {
            return null;
        }
        for (String marker : new String[]{"<|im_end|>", "<|endoftext|>", "<|im_start|>"}) {
            int cut = content.indexOf(marker);
            if (cut >= 0) {
                content = content.substring(0, cut);
            }
        }
        content = content.replaceAll("<\\|[^|]*\\|>", "");
        return content.strip();
    }

    /**
     * Health check: hit the given path to verify the server is up.
     */
    public CompletableFuture<Boolean> healthCheck(String path) {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> resp.statusCode() == 200)
            .exceptionally(ex -> false);
    }

    /**
     * Health check: hit /health to verify the server is up.
     */
    public CompletableFuture<Boolean> healthCheck() {
        return healthCheck("/health");
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ApiProvider getProvider() {
        return provider;
    }

    // --- Exception ---

    public static class InferenceException extends RuntimeException {
        public InferenceException(String message) { super(message); }
        public InferenceException(String message, Throwable cause) { super(message, cause); }
    }
}
