package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Manages an SGLang inference server via Docker for E2E tests.
 * SGLang exposes an OpenAI-compatible API at /v1/chat/completions
 * and health at /health.
 *
 * <p>Requires Docker with nvidia-container-toolkit for GPU passthrough.
 * Models are HuggingFace repo IDs (e.g., "Qwen/Qwen3-8B").
 *
 * <p>Container: {@code lmsysorg/sglang:latest}, port 8000 (mapped to host port).
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code SGLANG_MODEL} — HuggingFace model ID
 *   <li>{@code SGLANG_PORT} — Host port (default: 8000)
 *   <li>{@code SGLANG_IMAGE} — Docker image override
 *   <li>{@code SGLANG_EXTRA_ARGS} — Extra launch args (default: "--quantization fp8")
 * </ul>
 */
public final class SGLangServerFixture implements InferenceServerFixture {

    private static final Logger log = LoggerFactory.getLogger(SGLangServerFixture.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(600);

    private final String modelId;
    private final int port;
    private final int contextSize;
    private final DockerComposeFixture docker;
    private boolean started;

    /**
     * @param modelId     HuggingFace model ID (e.g., "Qwen/Qwen3-8B")
     * @param port        host port to map
     * @param contextSize context length
     */
    public SGLangServerFixture(String modelId, int port, int contextSize) {
        this.modelId = modelId;
        this.port = port;
        this.contextSize = contextSize;
        this.docker = new DockerComposeFixture()
            .profile("sglang")
            .env("SGLANG_MODEL", modelId)
            .env("SGLANG_PORT", String.valueOf(port))
            .env("SGLANG_MAX_MODEL_LEN", String.valueOf(contextSize));
    }

    public SGLangServerFixture(String modelId, int port) {
        this(modelId, port, 16384);
    }

    /**
     * JUnit assumption: skip test if Docker or GPU unavailable.
     */
    public static void assumeAvailable() {
        DockerComposeFixture.assumeDockerAvailable();
        DockerComposeFixture.assumeGpuAvailable();
    }

    /**
     * JUnit assumption: skip if no GPU available.
     */
    public static void assumeGpuAvailable() {
        DockerComposeFixture.assumeGpuAvailable();
    }

    @Override
    public void start() throws IOException, InterruptedException {
        log.info("Starting SGLang Docker container with model {} on port {}", modelId, port);
        docker.up("sglang");

        // SGLang takes a long time: model download + compilation + loading
        var healthy = docker.waitForHealth("SGLang",
            "http://localhost:" + port + "/health", HEALTH_TIMEOUT);
        if (!healthy) {
            stop();
            throw new IllegalStateException(
                "SGLang health check timed out after " + HEALTH_TIMEOUT.toSeconds() + "s");
        }

        started = true;
        log.info("SGLang ready on port {} (model: {})", port, modelId);
    }

    @Override
    public void stop() {
        if (started) {
            log.info("Stopping SGLang container");
            try {
                docker.stop("sglang");
            } catch (Exception e) {
                log.warn("Error stopping SGLang: {}", e.getMessage());
            }
            started = false;
        }
    }

    @Override
    public void restart() throws IOException, InterruptedException {
        log.info("Restarting SGLang container");
        docker.restart("sglang");
        var healthy = docker.waitForHealth("SGLang",
            "http://localhost:" + port + "/health", HEALTH_TIMEOUT);
        if (!healthy) {
            throw new IllegalStateException("SGLang restart health check failed");
        }
        started = true;
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    @Override
    public String baseUrl() {
        return "http://localhost:" + port;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public BackendType backendType() {
        return BackendType.SGLANG;
    }
}
