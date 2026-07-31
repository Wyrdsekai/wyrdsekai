package org.wyrdsekai.core.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Sealed interface representing an inference backend.
 * Local backends use OpenAI-compatible HTTP, cloud backends use ApiProvider
 * for protocol translation, Claude CLI uses subprocess.
 */
public sealed interface InferenceBackend
        permits InferenceBackend.LlamaServer, InferenceBackend.Ollama,
                InferenceBackend.VLLM, InferenceBackend.SGLang,
                InferenceBackend.Mlx,
                InferenceBackend.Cloud, InferenceBackend.ClaudeCli,
                InferenceBackend.NatsRemote {

    String name();
    String type();
    InferenceClient client();  // may be null for ClaudeCli
    int priority();
    List<String> models();
    CompletableFuture<Boolean> healthCheck();

    /** Perform a chat completion. Default delegates to client(). */
    default CompletableFuture<InferenceClient.ChatResponse> chatCompletion(
            InferenceClient.ChatRequest request) {
        return client().chatCompletion(request);
    }

    /**
     * Perform a chat completion with per-token streaming. Backends that support
     * streaming invoke {@code tokenCallback} for each token as it arrives, then
     * complete the future with the assembled response. Backends without native
     * streaming support degrade to {@link #chatCompletion(InferenceClient.ChatRequest)}
     * and deliver the entire response text as a single callback invocation — the
     * caller gets a consistent API either way.
     *
     * @param tokenCallback invoked for each token chunk; may be {@code null} for
     *                      non-streaming callers (equivalent to {@link #chatCompletion}).
     */
    default CompletableFuture<InferenceClient.ChatResponse> chatCompletionStreaming(
            InferenceClient.ChatRequest request, Consumer<String> tokenCallback) {
        return chatCompletion(request).thenApply(resp -> {
            if (tokenCallback != null && resp.choices() != null && !resp.choices().isEmpty()) {
                var content = resp.choices().getFirst().message().content();
                if (content != null && !content.isEmpty()) {
                    try { tokenCallback.accept(content); } catch (Exception ignored) {}
                }
            }
            return resp;
        });
    }

    /** Convenience: single-turn completion. Builds ChatRequest, calls chatCompletion, extracts text. */
    default CompletableFuture<String> complete(String model, String systemPrompt, String userMessage,
                                                int maxTokens, double temperature) {
        var messages = new ArrayList<InferenceClient.ChatMessage>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new InferenceClient.ChatMessage("system", systemPrompt));
        }
        messages.add(new InferenceClient.ChatMessage("user", userMessage));

        var request = new InferenceClient.ChatRequest(model, messages, maxTokens, temperature);
        return chatCompletion(request).thenApply(resp -> {
            if (resp.choices() == null || resp.choices().isEmpty()) {
                throw new InferenceClient.InferenceException("No choices in chat response");
            }
            return resp.choices().getFirst().message().content();
        });
    }

    /** Display URL for status output. */
    default String url() {
        return client() != null ? client().getBaseUrl() : "(local)";
    }

    /** llama-server: child process managed by LlamaServerManager. Health: GET /health */
    record LlamaServer(String name, InferenceClient client, int priority,
                        List<String> models,
                        LlamaServerManager manager) implements InferenceBackend {
        @Override public String type() { return "llama-server"; }
        @Override public CompletableFuture<Boolean> healthCheck() {
            return client.healthCheck("/health");
        }
    }

    /** Ollama: Docker container or local install. Health: GET /api/tags */
    record Ollama(String name, InferenceClient client, int priority,
                   List<String> models) implements InferenceBackend {
        @Override public String type() { return "ollama"; }
        @Override public CompletableFuture<Boolean> healthCheck() {
            return client.healthCheck("/api/tags");
        }
    }

    /** vLLM: Docker container. Health: GET /health */
    record VLLM(String name, InferenceClient client, int priority,
                 List<String> models) implements InferenceBackend {
        @Override public String type() { return "vllm"; }
        @Override public CompletableFuture<Boolean> healthCheck() {
            return client.healthCheck("/health");
        }
    }

    /** SGLang: Docker container. Health: GET /health */
    record SGLang(String name, InferenceClient client, int priority,
                   List<String> models) implements InferenceBackend {
        @Override public String type() { return "sglang"; }
        @Override public CompletableFuture<Boolean> healthCheck() {
            return client.healthCheck("/health");
        }
    }

    /**
     * MLX runtime (macOS only): {@code mlx_lm.server} serving Qwen3.5+DeltaNet
     * voice model with optional V8 control-vector hook. OpenAI-compatible HTTP,
     * so the wire path is identical to llama-server — this record exists so
     * status output, capability-tier inference, and any future MLX-specific
     * tuning (sampler defaults, adapter swap, etc.) have a distinct seam.
     *
     * <p>The {@code displayUrl} preserves the original {@code mlx://host:port}
     * scheme for logs; the internal {@link InferenceClient} is always built
     * against {@code http://host:port} since {@code java.net.http} won't accept
     * a custom scheme. §"Phase 2".
     *
     * <p>Health: GET /v1/models (mlx_lm.server doesn't ship /health). Probes
     * via {@code /v1/models} return HTTP 200 once the model is loaded.
     */
    record Mlx(String name, InferenceClient client, int priority,
                List<String> models, String displayUrl) implements InferenceBackend {
        @Override public String type() { return "mlx"; }
        @Override public String url() {
            return displayUrl != null && !displayUrl.isBlank()
                ? displayUrl
                : (client != null ? client.getBaseUrl() : "(mlx)");
        }
        @Override public CompletableFuture<Boolean> healthCheck() {
            // mlx_lm.server returns 200 on /v1/models once the model is loaded.
            return client.healthCheck("/v1/models");
        }

        @Override public CompletableFuture<InferenceClient.ChatResponse> chatCompletion(
                InferenceClient.ChatRequest request) {
            return client.chatCompletion(rewriteModel(request));
        }

        /**
         * mlx_lm.server dispatches by the {@code model} field in the request.
         * If the requested name isn't the one passed to {@code --model}, it
         * tries to fetch from HuggingFace and 404s. Production callers send
         * the canonical path; the E2E harness defaults to "test-model" which
         * trips the fetch. Rewrite to a model the server actually serves:
         * prefer an absolute-path entry from {@code /v1/models}, fall back
         * to the last discovered entry, pass through unchanged if the
         * requested name is already known or {@code models} is empty.
         */
        private InferenceClient.ChatRequest rewriteModel(InferenceClient.ChatRequest req) {
            if (models == null || models.isEmpty()) return req;
            if (req.model() != null && models.contains(req.model())) return req;
            String served = null;
            for (var m : models) {
                if (m != null && m.startsWith("/")) { served = m; break; }
            }
            if (served == null) served = models.get(models.size() - 1);
            return new InferenceClient.ChatRequest(
                served, req.messages(), req.maxTokens(), req.temperature(),
                req.topP(), req.stop(), req.grammar(), req.format(),
                req.tools(), req.toolChoice(), req.presencePenalty(),
                req.repeatPenalty());
        }
    }

    /** Cloud: remote API (OpenAI, Anthropic) via ApiProvider. Health depends on provider. */
    record Cloud(String name, InferenceClient client, int priority,
                  List<String> models) implements InferenceBackend {
        @Override public String type() { return "cloud"; }
        @Override public CompletableFuture<Boolean> healthCheck() {
            var path = client.getProvider().healthPath();
            if (path == null) return CompletableFuture.completedFuture(true);
            return client.healthCheck(path);
        }
    }

    /**
     * NATS-relay remote: inference via NATS req/reply across zones. Uses the
     * relay's existing household-scoped connection — no separate HTTP proxy
     * port is required.
     *
     * <p>The concrete NATS wiring lives in the {@code between} module; this
     * record holds a functional caller so core doesn't depend on between.</p>
     */
    record NatsRemote(String name, int priority, List<String> models,
                       String targetZone, String sourceZone,
                       RemoteCaller caller)
            implements InferenceBackend {
        @Override public String type() { return "nats-remote"; }
        @Override public InferenceClient client() { return null; }
        @Override public String url() { return "nats://" + targetZone; }
        /**
         * There is no cheap honest liveness probe over NATS req/reply — a real
         * probe would cost a full round-trip (and a dead peer would only be
         * detected by the ~120s request timeout, which is exactly what we're
         * trying to avoid). So this returns an optimistic {@code true} used
         * only as the initial seed when the backend is first discovered.
         *
         * <p>The authoritative liveness signal is the discovery miss-counter in
         * {@code Main.java}: when the borrowed peer stops announcing its
         * endpoint, the discovery loop marks this backend DOWN in the router via
         * {@link InferenceRouter.SetBackendHealth} (~15s), and the router's
         * periodic health loop deliberately skips NatsRemote so it can't
         * resurrect a peer the discovery loop just buried. See task #36.</p>
         */
        @Override public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }
        @Override public CompletableFuture<InferenceClient.ChatResponse> chatCompletion(
                InferenceClient.ChatRequest request) {
            return caller.call(targetZone, sourceZone, request, null);
        }
        @Override public CompletableFuture<InferenceClient.ChatResponse> chatCompletionStreaming(
                InferenceClient.ChatRequest request, Consumer<String> tokenCallback) {
            return caller.call(targetZone, sourceZone, request, tokenCallback);
        }

        /**
         * Functional interface for invoking remote inference. Implemented in the
         * {@code between} module using NATS. The {@code tokenCallback} is invoked
         * for each streaming token chunk (may be {@code null} for non-streaming).
         */
        @FunctionalInterface
        public interface RemoteCaller {
            CompletableFuture<InferenceClient.ChatResponse> call(
                String targetZone, String sourceZone,
                InferenceClient.ChatRequest request,
                Consumer<String> tokenCallback);
        }
    }

    /** Claude CLI: subprocess via OAuth, no API key needed. */
    record ClaudeCli(String name, ClaudeCliInference cli, int priority,
                      List<String> models) implements InferenceBackend {
        @Override public String type() { return "claude-cli"; }
        @Override public InferenceClient client() { return null; }
        @Override public String url() { return "claude-cli://oauth"; }
        @Override public CompletableFuture<Boolean> healthCheck() {
            return CompletableFuture.completedFuture(true);
        }
        @Override public CompletableFuture<InferenceClient.ChatResponse> chatCompletion(
                InferenceClient.ChatRequest request) {
            return cli.chatCompletion(request);
        }
    }
}
