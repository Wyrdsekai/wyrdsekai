package org.wyrdsekai.core.mcp;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provisions MCP servers on demand — the "hands" of capability discovery.
 *
 * When the Library discovers a capability the household doesn't have,
 * the provisioner spins it up. When it's no longer needed, tears it down.
 *
 * Implementations:
 *   - DockerMcpProvisioner: pulls and runs OCI images (reference implementation)
 *   - ProcessMcpProvisioner: spawns local processes (lightweight, no containers)
 *   - Future: NanoClawProvisioner, PodmanProvisioner, etc.
 *
 * The provisioner does NOT decide what to provision — that's the Library's job
 * (via TaskDrivenDiscovery + McpRegistrySyncer). The provisioner just executes.
 */
public interface McpServerProvisioner {

    /**
     * Provision an MCP server from a registry entry.
     *
     * @param request what to provision (image/command, transport, port preferences)
     * @return result with the endpoint URL if successful
     */
    ProvisionResult provision(ProvisionRequest request);

    /**
     * Stop and remove a provisioned MCP server.
     *
     * @param instanceId the instance ID returned from provision()
     * @return true if successfully deprovisioned
     */
    boolean deprovision(String instanceId);

    /**
     * List all currently provisioned MCP servers.
     */
    List<ProvisionedInstance> list();

    /**
     * Check if a specific instance is healthy.
     */
    boolean isHealthy(String instanceId);

    /**
     * What to provision.
     *
     * @param serviceId     MCP service identifier (e.g., "searxng", "home-assistant")
     * @param image         OCI image or command to run (e.g., "mcp/searxng:latest")
     * @param transport     "http", "stdio", "websocket"
     * @param preferredPort preferred port (0 = auto-assign)
     * @param env           environment variables to pass
     * @param labels        metadata labels for tracking
     */
    record ProvisionRequest(
        String serviceId,
        String image,
        String transport,
        int preferredPort,
        Map<String, String> env,
        Map<String, String> labels
    ) {
        public static ProvisionRequest http(String serviceId, String image) {
            return new ProvisionRequest(serviceId, image, "http", 0,
                Map.of(), Map.of());
        }

        public static ProvisionRequest http(String serviceId, String image, int port) {
            return new ProvisionRequest(serviceId, image, "http", port,
                Map.of(), Map.of());
        }

        public ProvisionRequest withEnv(String key, String value) {
            var newEnv = new HashMap<>(env);
            newEnv.put(key, value);
            return new ProvisionRequest(serviceId, image, transport, preferredPort,
                Map.copyOf(newEnv), labels);
        }
    }

    /**
     * Result of a provisioning attempt.
     *
     * @param success    whether the server started successfully
     * @param instanceId unique ID for this running instance (for deprovision/health)
     * @param endpoint   the URL to reach the MCP server (e.g., "http://localhost:9100")
     * @param error      error message if failed
     */
    record ProvisionResult(
        boolean success,
        String instanceId,
        String endpoint,
        String error
    ) {
        public static ProvisionResult ok(String instanceId, String endpoint) {
            return new ProvisionResult(true, instanceId, endpoint, null);
        }

        public static ProvisionResult fail(String error) {
            return new ProvisionResult(false, null, null, error);
        }
    }

    /**
     * A currently running provisioned MCP server.
     *
     * @param instanceId  unique instance identifier
     * @param serviceId   which MCP service this is
     * @param endpoint    URL to reach it
     * @param image       what was provisioned (image name or command)
     * @param startedAt   when it was started
     * @param healthy     current health status
     */
    record ProvisionedInstance(
        String instanceId,
        String serviceId,
        String endpoint,
        String image,
        Instant startedAt,
        boolean healthy
    ) {}
}
