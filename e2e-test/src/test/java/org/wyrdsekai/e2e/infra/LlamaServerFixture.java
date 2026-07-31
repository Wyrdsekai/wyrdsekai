package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Manages a llama-server process for E2E tests.
 * Starts a llama-server with a GGUF model, polls health until ready,
 * and stops the process on cleanup.
 *
 * <p>Supports multiple concurrent instances (different ports).
 * Use {@link #assumeAvailable()} in @BeforeAll to skip tests
 * when llama-server or models are missing.
 */
public final class LlamaServerFixture implements InferenceServerFixture {

    private static final Logger log = LoggerFactory.getLogger(LlamaServerFixture.class);
    private static final String LLAMA_SERVER = System.getenv().getOrDefault(
        "LLAMA_SERVER_PATH", "llama-server");
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(500);

    private final Path modelPath;
    private final int port;
    private final int contextSize;
    private Process process;

    public LlamaServerFixture(Path modelPath, int port, int contextSize) {
        this.modelPath = modelPath;
        this.port = port;
        this.contextSize = contextSize;
    }

    public LlamaServerFixture(Path modelPath, int port) {
        this(modelPath, port, 2048);
    }

    /**
     * Check if llama-server binary is available on PATH.
     */
    public static boolean isLlamaServerAvailable() {
        try {
            var proc = new ProcessBuilder(LLAMA_SERVER, "--version")
                .redirectErrorStream(true)
                .start();
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * JUnit assumption: skip test if llama-server is not available.
     */
    public static void assumeAvailable() {
        Assumptions.assumeTrue(isLlamaServerAvailable(),
            "llama-server not found on PATH (set LLAMA_SERVER_PATH)");
    }

    /**
     * JUnit assumption: skip test if model file is not cached.
     */
    public static void assumeModelAvailable(NodeProfile profile) {
        var modelPath = ModelManager.ensureModel(profile);
        Assumptions.assumeTrue(modelPath != null,
            "Model " + profile.modelFilename() + " not available " +
            "(set WYRDSEKAI_ALLOW_MODEL_DOWNLOAD=true to download)");
    }

    /**
     * Start llama-server with the configured model.
     *
     * @throws IOException if process fails to start
     * @throws InterruptedException if health poll is interrupted
     * @throws IllegalStateException if health check times out
     */
    public void start() throws IOException, InterruptedException {
        log.info("Starting llama-server on port {} with model {}",
            port, modelPath.getFileName());

        var pb = new ProcessBuilder(
            LLAMA_SERVER,
            "--model", modelPath.toString(),
            "--port", String.valueOf(port),
            "--ctx-size", String.valueOf(contextSize),
            "--jinja",
            "-fa"
        );
        pb.redirectErrorStream(true);
        pb.inheritIO();

        process = pb.start();

        // Poll health endpoint until ready
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        var healthReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/health"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();

        var deadline = System.currentTimeMillis() + HEALTH_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var resp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    log.info("llama-server ready on port {} (model: {})",
                        port, modelPath.getFileName());
                    return;
                }
            } catch (Exception e) {
                // Not ready yet
            }

            if (!process.isAlive()) {
                throw new IllegalStateException(
                    "llama-server exited with code " + process.exitValue());
            }

            Thread.sleep(HEALTH_POLL_INTERVAL.toMillis());
        }

        stop();
        throw new IllegalStateException(
            "llama-server health check timed out after " + HEALTH_TIMEOUT.toSeconds() + "s");
    }

    /**
     * Stop the llama-server process.
     */
    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping llama-server on port {}", port);
            process.destroy();
            try {
                if (!process.waitFor(Duration.ofSeconds(5))) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Check if the process is running.
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Restart the llama-server (for degradation tests).
     */
    public void restart() throws IOException, InterruptedException {
        stop();
        start();
    }

    @Override
    public BackendType backendType() {
        return BackendType.LLAMA_SERVER;
    }
}
