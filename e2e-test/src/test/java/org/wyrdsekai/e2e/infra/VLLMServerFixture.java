package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Manages a vLLM inference server via Docker for E2E tests.
 * vLLM exposes an OpenAI-compatible API at /v1/chat/completions
 * and health at /health.
 *
 * <p>Requires Docker with nvidia-container-toolkit and >= 24GB VRAM.
 * Models are HuggingFace repo IDs (e.g., "cpatonn/Qwen3-Coder-30B-A3B-Instruct-AWQ-4bit").
 *
 * <p>Container: {@code vllm/vllm-openai:v0.16.0-cu130}, port 8000 (mapped to host 8100).
 *
 * <p>Note: vLLM needs 24GB+ VRAM — it will NOT run on a 16GB GPU.
 * Tests using vLLM should call {@link #assumeReady()} which checks
 * both Docker availability and VRAM via nvidia-smi.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code VLLM_MODEL} — HuggingFace model ID
 *   <li>{@code VLLM_PORT} — Host port (default: 8100)
 *   <li>{@code VLLM_IMAGE} — Docker image override
 *   <li>{@code VLLM_MIN_VRAM_GB} — Minimum VRAM (default: 24)
 * </ul>
 */
public final class VLLMServerFixture implements InferenceServerFixture {

    private static final Logger log = LoggerFactory.getLogger(VLLMServerFixture.class);
    private static final int MIN_VRAM_GB = Integer.parseInt(
        System.getenv().getOrDefault("VLLM_MIN_VRAM_GB", "24"));
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(600);

    private final String modelId;
    private final int port;
    private final DockerComposeFixture docker;
    private boolean started;

    /**
     * @param modelId HuggingFace model ID
     * @param port    host port to map
     */
    public VLLMServerFixture(String modelId, int port) {
        this.modelId = modelId;
        this.port = port;
        this.docker = new DockerComposeFixture()
            .profile("vllm")
            .env("VLLM_MODEL", modelId)
            .env("VLLM_PORT", String.valueOf(port));
    }

    /**
     * Check available VRAM in GB via nvidia-smi.
     */
    public static int availableVramGb() {
        try {
            var proc = new ProcessBuilder("nvidia-smi",
                "--query-gpu=memory.total", "--format=csv,noheader,nounits")
                .redirectErrorStream(true)
                .start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (proc.exitValue() == 0 && !output.isEmpty()) {
                // nvidia-smi reports in MiB; first line, convert to GB
                var mib = Integer.parseInt(output.lines().findFirst().orElse("0").trim());
                return mib / 1024;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * JUnit assumption: skip if Docker not available.
     */
    public static void assumeAvailable() {
        DockerComposeFixture.assumeDockerAvailable();
    }

    /**
     * JUnit assumption: skip if insufficient VRAM.
     */
    public static void assumeSufficientVram() {
        var vram = availableVramGb();
        Assumptions.assumeTrue(vram >= MIN_VRAM_GB,
            "Insufficient VRAM for vLLM: " + vram + "GB available, " +
            MIN_VRAM_GB + "GB required (set VLLM_MIN_VRAM_GB to override)");
    }

    /**
     * Combined check: Docker + GPU + sufficient VRAM.
     */
    public static void assumeReady() {
        assumeAvailable();
        DockerComposeFixture.assumeGpuAvailable();
        assumeSufficientVram();
    }

    @Override
    public void start() throws IOException, InterruptedException {
        log.info("Starting vLLM Docker container with model {} on port {}", modelId, port);
        docker.up("vllm");

        // vLLM takes even longer than sglang (model download + CUDA compilation)
        var healthy = docker.waitForHealth("vLLM",
            "http://localhost:" + port + "/health", HEALTH_TIMEOUT);
        if (!healthy) {
            stop();
            throw new IllegalStateException(
                "vLLM health check timed out after " + HEALTH_TIMEOUT.toSeconds() + "s");
        }

        started = true;
        log.info("vLLM ready on port {} (model: {})", port, modelId);
    }

    @Override
    public void stop() {
        if (started) {
            log.info("Stopping vLLM container");
            try {
                docker.stop("vllm");
            } catch (Exception e) {
                log.warn("Error stopping vLLM: {}", e.getMessage());
            }
            started = false;
        }
    }

    @Override
    public void restart() throws IOException, InterruptedException {
        log.info("Restarting vLLM container");
        docker.restart("vllm");
        var healthy = docker.waitForHealth("vLLM",
            "http://localhost:" + port + "/health", HEALTH_TIMEOUT);
        if (!healthy) {
            throw new IllegalStateException("vLLM restart health check failed");
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
        return BackendType.VLLM;
    }
}
