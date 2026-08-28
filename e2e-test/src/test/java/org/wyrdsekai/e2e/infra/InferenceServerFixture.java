package org.wyrdsekai.e2e.infra;

import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Common interface for all inference server fixtures.
 * All backends (llama-server, sglang, vllm) expose an OpenAI-compatible
 * API at /v1/chat/completions and health at /health — this interface
 * captures the shared lifecycle so tests can be backend-agnostic.
 *
 * <p>Select backend via {@code WYRDSEKAI_E2E_BACKEND} env var:
 * {@code sglang} (default), {@code vllm}, {@code llama-server}.
 *
 * <p>All backends run as Docker containers via docker-compose.e2e.yml.
 * llama-server also supports direct binary execution (legacy/fallback).
 */
public interface InferenceServerFixture {

    /** Start the server and block until healthy. */
    void start() throws IOException, InterruptedException;

    /** Stop the server process/container. */
    void stop();

    /** Restart (stop + start). */
    void restart() throws IOException, InterruptedException;

    /** Whether the server is alive. */
    boolean isRunning();

    /** HTTP base URL (e.g., http://localhost:8080). */
    String baseUrl();

    /** Port number. */
    int port();

    /**
     * Create an InferenceBackend for this fixture.
     * All backends expose OpenAI-compatible API, so the client is identical.
     */
    default InferenceBackend createBackend(String name, int priority) {
        var client = new InferenceClient(baseUrl());
        return switch (backendType()) {
            case LLAMA_SERVER -> new InferenceBackend.LlamaServer(
                name, client, priority, List.of(), null);
            case SGLANG -> new InferenceBackend.SGLang(
                name, client, priority, List.of());
            case VLLM -> new InferenceBackend.VLLM(
                name, client, priority, List.of());
            case CLAUDE_CLI -> throw new UnsupportedOperationException(
                "Use ClaudeCliFixture.createBackend() directly — no HTTP client needed");
        };
    }

    /** Which backend type this fixture represents. */
    BackendType backendType();

    enum BackendType { LLAMA_SERVER, SGLANG, VLLM, CLAUDE_CLI }

    /**
     * Factory: create the right fixture based on env var.
     * Default is sglang (proven in CodeZaiku E2E).
     *
     * @param modelId     HuggingFace model ID (sglang/vllm) or GGUF filename (llama-server)
     * @param port        port to bind
     * @param contextSize context window size
     * @return the appropriate fixture
     */
    static InferenceServerFixture create(String modelId, int port, int contextSize) {
        var backend = System.getenv().getOrDefault("WYRDSEKAI_E2E_BACKEND", "sglang");
        return switch (backend) {
            case "vllm" -> new VLLMServerFixture(modelId, port);
            case "llama-server", "llama" -> new LlamaServerFixture(
                Path.of(modelId), port, contextSize);
            case "claude" -> new ClaudeCliFixture();
            default -> new SGLangServerFixture(modelId, port, contextSize);
        };
    }

    /**
     * Factory for GGUF model path (llama-server uses file paths, not repo IDs).
     */
    static InferenceServerFixture create(
            Path modelPath, int port, int contextSize) {
        var backend = System.getenv().getOrDefault("WYRDSEKAI_E2E_BACKEND", "sglang");
        return switch (backend) {
            case "sglang" -> new SGLangServerFixture(modelPath.toString(), port, contextSize);
            case "vllm" -> new VLLMServerFixture(modelPath.toString(), port);
            case "claude" -> new ClaudeCliFixture();
            default -> new LlamaServerFixture(modelPath, port, contextSize);
        };
    }
}
