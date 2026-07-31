package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Manages a NATS server for E2E tests.
 * Supports two modes:
 * <ol>
 *   <li><b>Docker</b> (preferred): Uses docker-compose.e2e.yml nats service
 *   <li><b>Local</b> (fallback): Spawns nats-server binary directly
 * </ol>
 *
 * <p>Mode is selected automatically: Docker if available, otherwise local binary.
 * Override with {@code WYRDSEKAI_E2E_NATS=docker} or {@code WYRDSEKAI_E2E_NATS=local}.
 */
public final class NatsServerFixture {

    private static final Logger log = LoggerFactory.getLogger(NatsServerFixture.class);
    private static final String NATS_SERVER = System.getenv().getOrDefault(
        "NATS_SERVER_PATH", "nats-server");
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(500);

    private final int clientPort;
    private final int monitorPort;
    private final boolean useDocker;

    private Process process; // local mode
    private DockerComposeFixture docker; // docker mode

    public NatsServerFixture(int clientPort, int monitorPort) {
        this.clientPort = clientPort;
        this.monitorPort = monitorPort;
        this.useDocker = resolveMode();
    }

    public NatsServerFixture() {
        this(PortAllocator.allocate(), PortAllocator.allocate());
    }

    /**
     * Check if nats-server binary is available on PATH.
     */
    public static boolean isNatsServerAvailable() {
        try {
            var proc = new ProcessBuilder(NATS_SERVER, "--version")
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
     * JUnit assumption: skip test if neither Docker nor nats-server available.
     */
    public static void assumeAvailable() {
        var docker = DockerComposeFixture.isDockerAvailable();
        var local = isNatsServerAvailable();
        Assumptions.assumeTrue(docker || local,
            "Neither Docker nor nats-server available for NATS");
    }

    /**
     * Start NATS server.
     */
    public void start() throws Exception {
        if (useDocker) {
            startDocker();
        } else {
            startLocal();
        }
    }

    /**
     * Stop the NATS server.
     */
    public void stop() {
        if (useDocker) {
            stopDocker();
        } else {
            stopLocal();
        }
    }

    public boolean isRunning() {
        if (useDocker) {
            return docker != null;
        }
        return process != null && process.isAlive();
    }

    public int clientPort() {
        return clientPort;
    }

    public int monitorPort() {
        return monitorPort;
    }

    public String natsUrl() {
        return "nats://127.0.0.1:" + clientPort;
    }

    public String monitorUrl() {
        return "http://127.0.0.1:" + monitorPort;
    }

    // ─── Docker mode ──────────────────────────────────────────────────────

    private void startDocker() throws IOException, InterruptedException {
        log.info("Starting NATS via Docker on ports {}/{}", clientPort, monitorPort);
        docker = new DockerComposeFixture()
            .env("NATS_PORT", String.valueOf(clientPort))
            .env("NATS_MONITOR_PORT", String.valueOf(monitorPort));
        docker.up("nats");

        var healthy = docker.waitForHealth("NATS",
            "http://127.0.0.1:" + monitorPort + "/healthz", HEALTH_TIMEOUT);
        if (!healthy) {
            stopDocker();
            throw new IllegalStateException("NATS Docker health check timed out");
        }
        log.info("NATS Docker ready on port {}", clientPort);
    }

    private void stopDocker() {
        if (docker != null) {
            try {
                docker.stop("nats");
            } catch (Exception e) {
                log.warn("Error stopping NATS Docker: {}", e.getMessage());
            }
            docker = null;
        }
    }

    // ─── Local mode (fallback) ────────────────────────────────────────────

    private void startLocal() throws IOException, InterruptedException {
        log.info("Starting nats-server locally on ports {}/{}", clientPort, monitorPort);

        var pb = new ProcessBuilder(
            NATS_SERVER,
            "--port", String.valueOf(clientPort),
            "--http_port", String.valueOf(monitorPort),
            "--jetstream"
        );
        pb.redirectErrorStream(true);
        pb.inheritIO();

        process = pb.start();

        // Poll health endpoint
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        var healthReq = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + monitorPort + "/healthz"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();

        var deadline = System.currentTimeMillis() + HEALTH_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var resp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    log.info("nats-server ready on port {}", clientPort);
                    return;
                }
            } catch (Exception e) {
                // Not ready yet
            }

            if (!process.isAlive()) {
                throw new IllegalStateException(
                    "nats-server exited with code " + process.exitValue());
            }

            Thread.sleep(HEALTH_POLL_INTERVAL.toMillis());
        }

        stopLocal();
        throw new IllegalStateException("nats-server health check timed out");
    }

    private void stopLocal() {
        if (process != null && process.isAlive()) {
            log.info("Stopping nats-server on port {}", clientPort);
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

    private static boolean resolveMode() {
        var mode = System.getenv().getOrDefault("WYRDSEKAI_E2E_NATS", "auto");
        return switch (mode) {
            case "docker" -> true;
            case "local" -> false;
            default -> DockerComposeFixture.isDockerAvailable();
        };
    }
}
