package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Manages individual llama-server Docker containers for relay/household tests.
 * Each instance runs a separate container with its own model and port.
 * Containers can be started, stopped, and restarted independently —
 * critical for degradation/cascade tests.
 *
 * <p>Uses the compose file's relay-profile services (llama-phone, llama-laptop)
 * or spawns standalone containers via {@code docker run} for flexibility.
 */
public final class LlamaDockerFixture {

    private static final Logger log = LoggerFactory.getLogger(LlamaDockerFixture.class);
    private static final String IMAGE = "ghcr.io/ggml-org/llama.cpp:server-cuda";
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(180);

    private final String name;
    private final String modelFile;
    private final int hostPort;
    private final int contextSize;
    private final String containerName;

    private boolean running;

    /**
     * @param name       human-readable name (e.g., "phone-0.6b", "laptop-4b")
     * @param modelFile  GGUF filename inside data/models/ (e.g., "qwen3-0.6b-q4_k_m.gguf")
     * @param hostPort   host port to map to container's 8080
     * @param contextSize context window size
     */
    public LlamaDockerFixture(String name, String modelFile, int hostPort, int contextSize) {
        this.name = name;
        this.modelFile = modelFile;
        this.hostPort = hostPort;
        this.contextSize = contextSize;
        this.containerName = "wyrdsekai-e2e-llama-" + name;
    }

    /**
     * Check that Docker is available and the llama.cpp image exists.
     */
    public static void assumeAvailable() {
        DockerComposeFixture.assumeDockerAvailable();
        try {
            var proc = new ProcessBuilder("docker", "image", "inspect", IMAGE)
                .redirectErrorStream(true)
                .start();
            proc.getInputStream().readAllBytes();
            proc.waitFor();
            Assumptions.assumeTrue(proc.exitValue() == 0,
                "Docker image " + IMAGE + " not available — pull it first");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Docker image check failed: " + e.getMessage());
        }
    }

    /**
     * Check that a GGUF model file exists in data/models/.
     */
    public static void assumeModelAvailable(String modelFile) {
        var modelsDir = resolveModelsDir();
        var modelPath = modelsDir.resolve(modelFile);
        Assumptions.assumeTrue(modelPath.toFile().exists(),
            "Model file not found: " + modelPath +
            " — download via: uvx --from huggingface_hub hf download <repo> " +
            modelFile + " --local-dir data/models");
    }

    /**
     * Start the Docker container.
     */
    public void start() throws IOException, InterruptedException {
        // Remove any stale container with the same name
        killContainer();

        var modelsDir = resolveModelsDir().toAbsolutePath().toString();

        log.info("Starting llama-server Docker '{}' on port {} with model {}",
            name, hostPort, modelFile);

        var cmd = List.of(
            "docker", "run", "-d",
            "--name", containerName,
            "--gpus", "all",
            "-p", hostPort + ":8080",
            "-v", modelsDir + ":/models:ro",
            IMAGE,
            "--model", "/models/" + modelFile,
            "--host", "0.0.0.0",
            "--port", "8080",
            "--ctx-size", String.valueOf(contextSize),
            "--jinja", "--flash-attn", "on"
        );

        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var output = new String(proc.getInputStream().readAllBytes());
        var exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to start container " + containerName +
                " (exit " + exitCode + "): " + output);
        }

        // Poll health
        if (!waitForHealth()) {
            stop();
            throw new IllegalStateException(
                "llama-server Docker '" + name + "' health check timed out");
        }

        running = true;
        log.info("llama-server Docker '{}' ready on port {}", name, hostPort);
    }

    /**
     * Stop the Docker container.
     */
    public void stop() {
        log.info("Stopping llama-server Docker '{}'", name);
        killContainer();
        running = false;
    }

    /**
     * Restart the Docker container (for degradation/recovery tests).
     */
    public void restart() throws IOException, InterruptedException {
        stop();
        start();
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return hostPort;
    }

    public String baseUrl() {
        return "http://localhost:" + hostPort;
    }

    /**
     * Create an InferenceBackend pointing at this container.
     */
    public InferenceBackend createBackend(String backendName, int priority) {
        var client = new InferenceClient(baseUrl());
        return new InferenceBackend.LlamaServer(
            backendName, client, priority, List.of(), null);
    }

    // --- Internal ---

    private boolean waitForHealth() {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        var req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + hostPort + "/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        var deadline = System.currentTimeMillis() + HEALTH_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return true;
                }
            } catch (Exception e) {
                // Not ready yet
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void killContainer() {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                .redirectErrorStream(true)
                .start()
                .waitFor();
        } catch (Exception e) {
            // Ignore — container may not exist
        }
    }

    private static Path resolveModelsDir() {
        var candidates = List.of(
            Path.of("data/models"),
            Path.of("../data/models"),
            Path.of("../../data/models")
        );
        for (var p : candidates) {
            if (p.toFile().isDirectory()) return p;
        }
        return Path.of("data/models");
    }
}
