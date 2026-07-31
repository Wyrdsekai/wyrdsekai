package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP server provisioner that spawns local processes (no containers needed).
 * Lighter weight than Docker — suitable for CLI tools, local servers, and scripts.
 *
 * <p>The "image" field in ProvisionRequest is treated as a command to execute.
 * Environment variables are passed through. The process must expose an HTTP
 * endpoint for MCP communication.</p>
 */
public class ProcessMcpProvisioner implements McpServerProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ProcessMcpProvisioner.class);

    private record RunningProcess(
        String instanceId,
        String serviceId,
        String endpoint,
        String command,
        Process process,
        Instant startedAt
    ) {}

    private final Map<String, RunningProcess> running = new ConcurrentHashMap<>();

    @Override
    public ProvisionResult provision(ProvisionRequest request) {
        var instanceId = "proc-" + request.serviceId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        var command = request.image(); // image field = command for process provisioner
        var port = request.preferredPort() > 0 ? request.preferredPort() : findFreePort();

        try {
            var envMap = new HashMap<>(System.getenv());
            envMap.putAll(request.env());
            envMap.put("PORT", String.valueOf(port));

            var parts = command.split("\\s+");
            var pb = new ProcessBuilder(parts)
                .redirectErrorStream(true);
            pb.environment().putAll(envMap);

            var process = pb.start();

            // Wait briefly for startup
            Thread.sleep(1000);
            if (!process.isAlive()) {
                return ProvisionResult.fail("Process exited immediately: " + command);
            }

            var endpoint = "http://localhost:" + port;
            running.put(instanceId, new RunningProcess(
                instanceId, request.serviceId(), endpoint, command, process, Instant.now()));

            log.info("Provisioned MCP server '{}' via process: {} → {}",
                request.serviceId(), command, endpoint);
            return ProvisionResult.ok(instanceId, endpoint);

        } catch (IOException | InterruptedException e) {
            return ProvisionResult.fail("Failed to start process: " + e.getMessage());
        }
    }

    @Override
    public boolean deprovision(String instanceId) {
        var proc = running.remove(instanceId);
        if (proc == null) return false;
        proc.process().destroyForcibly();
        log.info("Deprovisioned MCP server '{}' ({})", proc.serviceId(), instanceId);
        return true;
    }

    @Override
    public List<ProvisionedInstance> list() {
        return running.values().stream()
            .map(p -> new ProvisionedInstance(
                p.instanceId(), p.serviceId(), p.endpoint(),
                p.command(), p.startedAt(), p.process().isAlive()))
            .toList();
    }

    @Override
    public boolean isHealthy(String instanceId) {
        var proc = running.get(instanceId);
        return proc != null && proc.process().isAlive();
    }

    private static int findFreePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 9200 + new Random().nextInt(100);
        }
    }
}
