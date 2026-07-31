package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reference implementation of McpServerProvisioner using the Docker Engine API.
 *
 * Pulls OCI images and runs them as containers with port mapping. Each MCP server
 * gets its own isolated container. Health checks via HTTP GET to the container's
 * mapped port.
 *
 * Talks to Docker Engine via the Unix socket API (/var/run/docker.sock) or
 * TCP API (configurable). Requires Docker to be running on the host.
 *
 * Container naming convention: wyrdsekai-mcp-{serviceId}
 * Network: host networking (simplest for MCP servers that bind to localhost)
 *
 * This is intentionally simple — ~150 lines of real logic. Production hardening
 * (resource limits, volume mounts, custom networks) can be added per deployment.
 */
public class DockerMcpProvisioner implements McpServerProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DockerMcpProvisioner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(30);
    private static final int HEALTH_CHECK_RETRIES = 10;
    private static final Duration HEALTH_CHECK_INTERVAL = Duration.ofSeconds(2);

    private final String dockerHost;
    private final HttpClient httpClient;
    private final int portRangeStart;
    private int nextPort;

    /** Tracks provisioned instances: instanceId → instance data */
    private final ConcurrentHashMap<String, ProvisionedInstance> instances = new ConcurrentHashMap<>();

    /**
     * @param dockerHost Docker Engine API URL (e.g., "http://localhost:2375"
     *                   or "unix:///var/run/docker.sock" — note: Unix socket
     *                   requires a custom HttpClient or socat proxy)
     * @param portRangeStart starting port for auto-assigned MCP server ports
     */
    public DockerMcpProvisioner(String dockerHost, int portRangeStart) {
        this.dockerHost = dockerHost;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.portRangeStart = portRangeStart;
        this.nextPort = portRangeStart;
    }

    /** Default: Docker on localhost TCP, MCP ports starting at 9100. */
    public DockerMcpProvisioner() {
        this("http://localhost:2375", 9100);
    }

    @Override
    public ProvisionResult provision(ProvisionRequest request) {
        var containerName = "wyrdsekai-mcp-" + request.serviceId();
        var port = request.preferredPort() > 0 ? request.preferredPort() : nextPort++;

        try {
            // Stop existing container with same name (idempotent re-provision)
            stopContainer(containerName);
            removeContainer(containerName);

            // Pull image
            log.info("Pulling image: {}", request.image());
            pullImage(request.image());

            // Create container
            log.info("Creating container: {} (port {})", containerName, port);
            var containerId = createContainer(containerName, request.image(), port, request.env());

            // Start container
            log.info("Starting container: {}", containerId);
            startContainer(containerId);

            // Wait for health
            var endpoint = "http://localhost:" + port;
            log.info("Waiting for health: {}", endpoint);
            if (!waitForHealth(endpoint)) {
                log.warn("MCP server {} did not become healthy", request.serviceId());
                return ProvisionResult.fail("Server started but health check failed at " + endpoint);
            }

            var instance = new ProvisionedInstance(
                containerId, request.serviceId(), endpoint,
                request.image(), Instant.now(), true);
            instances.put(containerId, instance);

            log.info("Provisioned MCP server: {} at {}", request.serviceId(), endpoint);
            return ProvisionResult.ok(containerId, endpoint);

        } catch (Exception e) {
            log.error("Failed to provision {}: {}", request.serviceId(), e.getMessage());
            return ProvisionResult.fail(e.getMessage());
        }
    }

    @Override
    public boolean deprovision(String instanceId) {
        try {
            stopContainer(instanceId);
            removeContainer(instanceId);
            instances.remove(instanceId);
            log.info("Deprovisioned MCP server: {}", instanceId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to deprovision {}: {}", instanceId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<ProvisionedInstance> list() {
        // Refresh health status
        var result = new ArrayList<ProvisionedInstance>();
        for (var entry : instances.entrySet()) {
            var inst = entry.getValue();
            boolean healthy = isEndpointHealthy(inst.endpoint());
            result.add(new ProvisionedInstance(
                inst.instanceId(), inst.serviceId(), inst.endpoint(),
                inst.image(), inst.startedAt(), healthy));
        }
        return List.copyOf(result);
    }

    @Override
    public boolean isHealthy(String instanceId) {
        var inst = instances.get(instanceId);
        return inst != null && isEndpointHealthy(inst.endpoint());
    }

    // ── Docker Engine API calls ──────────────────────────────────────────

    private void pullImage(String image) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(dockerHost + "/images/create?fromImage=" + image))
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(PULL_TIMEOUT)
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Image pull failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    @SuppressWarnings("unchecked")
    private String createContainer(String name, String image, int port,
                                    Map<String, String> env) throws Exception {
        var envList = env.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .toList();

        // Container config with port binding
        var hostConfig = Map.of(
            "PortBindings", Map.of(
                port + "/tcp", List.of(Map.of("HostPort", String.valueOf(port)))
            )
        );

        var body = Map.of(
            "Image", image,
            "Env", envList,
            "ExposedPorts", Map.of(port + "/tcp", Map.of()),
            "HostConfig", hostConfig
        );

        var req = HttpRequest.newBuilder()
            .uri(URI.create(dockerHost + "/containers/create?name=" + name))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .timeout(API_TIMEOUT)
            .build();

        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 201) {
            throw new IOException("Container create failed (" + resp.statusCode() + "): " + resp.body());
        }

        return JSON.readTree(resp.body()).get("Id").asText();
    }

    private void startContainer(String containerId) throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(dockerHost + "/containers/" + containerId + "/start"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(API_TIMEOUT)
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        // 204 = started, 304 = already running
        if (resp.statusCode() != 204 && resp.statusCode() != 304) {
            throw new IOException("Container start failed (" + resp.statusCode() + "): " + resp.body());
        }
    }

    private void stopContainer(String nameOrId) {
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(dockerHost + "/containers/" + nameOrId + "/stop?t=5"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(API_TIMEOUT)
                .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    private void removeContainer(String nameOrId) {
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(dockerHost + "/containers/" + nameOrId + "?force=true"))
                .DELETE()
                .timeout(API_TIMEOUT)
                .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    // ── Health checking ──────────────────────────────────────────────────

    private boolean waitForHealth(String endpoint) {
        for (int i = 0; i < HEALTH_CHECK_RETRIES; i++) {
            if (isEndpointHealthy(endpoint)) return true;
            try { Thread.sleep(HEALTH_CHECK_INTERVAL.toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private boolean isEndpointHealthy(String endpoint) {
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
