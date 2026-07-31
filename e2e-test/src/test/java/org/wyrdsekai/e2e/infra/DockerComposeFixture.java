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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Docker Compose services for E2E tests.
 * All inference backends (sglang, vllm, llama-server) and
 * infrastructure services (nats) run as Docker containers.
 *
 * <p>This is the single source of truth for Docker lifecycle in tests.
 * Individual fixtures (SGLangServerFixture, etc.) delegate to this class.
 *
 * <p>Resolves the compose file at {@code docker/docker-compose.e2e.yml}
 * relative to the project root.
 */
public final class DockerComposeFixture {

    private static final Logger log = LoggerFactory.getLogger(DockerComposeFixture.class);
    private static final String PROJECT_NAME = "wyrdsekai-e2e";

    private final Path composeFile;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final List<String> profiles = new ArrayList<>();

    public DockerComposeFixture() {
        this.composeFile = resolveComposeFile();
    }

    /** Add an environment variable to pass to docker compose. */
    public DockerComposeFixture env(String key, String value) {
        environment.put(key, value);
        return this;
    }

    /** Add a compose profile to activate (e.g., "sglang", "vllm", "llama"). */
    public DockerComposeFixture profile(String profile) {
        profiles.add(profile);
        return this;
    }

    /**
     * Start specific services.
     * Uses {@code --force-recreate} to handle stale containers from previous runs
     * that were not cleaned up (e.g., after a test crash or Ctrl-C).
     *
     * @param services service names to start (e.g., "nats", "sglang")
     */
    public void up(String... services) throws IOException, InterruptedException {
        var cmd = composeCmd();
        cmd.add("up");
        cmd.add("-d");
        cmd.add("--force-recreate");
        for (var s : services) cmd.add(s);

        log.info("Starting Docker services: {}", List.of(services));
        exec(cmd);
    }

    /**
     * Start specific services if not already running.
     * Unlike {@link #up}, does NOT force-recreate existing containers —
     * running containers are left untouched.
     * Used by {@link DockerInfraExtension} to avoid restarting healthy services.
     *
     * @param services service names to start (e.g., "nats", "llama-phone")
     */
    public void startIfNeeded(String... services) throws IOException, InterruptedException {
        var cmd = composeCmd();
        cmd.add("up");
        cmd.add("-d");
        for (var s : services) cmd.add(s);

        log.info("Ensuring Docker services (no-recreate): {}", List.of(services));
        exec(cmd);
    }

    /**
     * Stop a specific service (for degradation tests).
     */
    public void stop(String service) throws IOException, InterruptedException {
        var cmd = composeCmd();
        cmd.add("stop");
        cmd.add(service);

        log.info("Stopping Docker service: {}", service);
        exec(cmd);
    }

    /**
     * Restart a specific service.
     */
    public void restart(String service) throws IOException, InterruptedException {
        var cmd = composeCmd();
        cmd.add("restart");
        cmd.add(service);

        log.info("Restarting Docker service: {}", service);
        exec(cmd);
    }

    /**
     * Tear down all services.
     */
    public void down() throws IOException, InterruptedException {
        var cmd = composeCmd();
        // Include all profiles so profiled containers get stopped
        cmd.addAll(List.of("--profile", "sglang", "--profile", "vllm",
            "--profile", "llama", "--profile", "relay"));
        cmd.add("down");

        log.info("Tearing down Docker services");
        exec(cmd);
    }

    /**
     * Wait for a service to be healthy by polling an HTTP endpoint.
     *
     * @param serviceName human-readable name (for logging)
     * @param url         health check URL
     * @param timeout     max wait time
     * @return true if healthy, false if timed out
     */
    public boolean waitForHealth(String serviceName, String url, Duration timeout) {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        var healthReq = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var resp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    log.info("{} healthy ({}ms)", serviceName,
                        timeout.toMillis() - (deadline - System.currentTimeMillis()));
                    return true;
                }
            } catch (Exception e) {
                // Not ready yet
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.warn("{} not healthy after {}s", serviceName, timeout.toSeconds());
        return false;
    }

    /**
     * Check if Docker is available.
     */
    public static boolean isDockerAvailable() {
        try {
            var proc = new ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start();
            proc.getInputStream().readAllBytes(); // drain output
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if nvidia-container-toolkit is available (GPU passthrough).
     */
    public static boolean isGpuAvailable() {
        try {
            var proc = new ProcessBuilder("nvidia-smi")
                .redirectErrorStream(true)
                .start();
            proc.getInputStream().readAllBytes();
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * JUnit assumption: skip if Docker not available.
     */
    public static void assumeDockerAvailable() {
        Assumptions.assumeTrue(isDockerAvailable(),
            "Docker not available (docker info failed)");
    }

    /**
     * JUnit assumption: skip if GPU not available.
     */
    public static void assumeGpuAvailable() {
        Assumptions.assumeTrue(isGpuAvailable(),
            "GPU not available (nvidia-smi failed)");
    }

    // --- Internal ---

    private List<String> composeCmd() {
        var cmd = new ArrayList<String>();
        cmd.addAll(List.of("docker", "compose",
            "-f", composeFile.toString(),
            "-p", PROJECT_NAME));
        for (var profile : profiles) {
            cmd.addAll(List.of("--profile", profile));
        }
        return cmd;
    }

    private void exec(List<String> cmd) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd);
        // Merge our env vars with inherited env
        pb.environment().putAll(environment);
        pb.inheritIO();

        log.debug("Executing: {}", String.join(" ", cmd));
        var proc = pb.start();
        var exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new IOException("Docker compose command failed (exit " + exitCode + "): " +
                String.join(" ", cmd));
        }
    }

    private static Path resolveComposeFile() {
        var candidates = List.of(
            Path.of("docker/docker-compose.e2e.yml"),
            Path.of("../docker/docker-compose.e2e.yml"),
            Path.of("../../docker/docker-compose.e2e.yml")
        );
        for (var p : candidates) {
            if (p.toFile().exists()) return p.toAbsolutePath();
        }
        // Fallback: let docker compose fail with a clear error
        return Path.of("docker/docker-compose.e2e.yml").toAbsolutePath();
    }
}
