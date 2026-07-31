package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Bridges capability discovery to provisioning and registration.
 *
 * When an agent encounters a task it can't handle:
 * 1. TaskDrivenDiscovery suggests matching capabilities from synced registries
 * 2. Agent (or Library room) decides to provision one
 * 3. This bridge provisions the MCP server and registers it in McpServiceRegistry
 * 4. world.mcp() calls now route to the newly provisioned server
 *
 * This is the missing link between "I found a tool" and "I can use the tool."
 */
public class CapabilityProvisioningBridge {

    private static final Logger log = LoggerFactory.getLogger(CapabilityProvisioningBridge.class);

    private final McpServerProvisioner provisioner;
    private final McpServiceRegistry registry;

    public CapabilityProvisioningBridge(McpServerProvisioner provisioner,
                                         McpServiceRegistry registry) {
        this.provisioner = provisioner;
        this.registry = registry;
    }

    /**
     * Provision a discovered capability and register it for use.
     *
     * @param discovered the capability from registry sync or task-driven discovery
     * @return the service ID if successful, empty if failed
     */
    public Optional<String> provisionAndRegister(McpRegistrySyncer.DiscoveredCapability discovered) {
        log.info("Provisioning discovered capability: {} ({})",
            discovered.name(), discovered.id());

        // Build provision request from discovered capability
        var request = McpServerProvisioner.ProvisionRequest.http(
            discovered.id(), discovered.endpoint());

        var result = provisioner.provision(request);
        if (!result.success()) {
            log.warn("Provisioning failed for {}: {}", discovered.id(), result.error());
            return Optional.empty();
        }

        // Register in McpServiceRegistry so world.mcp() can reach it
        var config = new McpServiceConfig(
            discovered.id(),
            discovered.name(),
            discovered.transport() != null ? discovered.transport() : "http",
            result.endpoint(),
            "local",    // provisioned locally, no cost
            null,       // no auth by default
            null,       // default rate limits
            true        // enabled immediately
        );

        registry.register(config);
        log.info("Provisioned and registered: {} at {}", discovered.id(), result.endpoint());

        return Optional.of(discovered.id());
    }

    /**
     * Deprovision a service and unregister it.
     *
     * @param serviceId the service to remove
     * @return true if successfully deprovisioned
     */
    public boolean deprovisionAndUnregister(String serviceId) {
        // Find the provisioned instance for this service
        var instance = provisioner.list().stream()
            .filter(i -> i.serviceId().equals(serviceId))
            .findFirst();

        if (instance.isEmpty()) {
            log.warn("No provisioned instance for service: {}", serviceId);
            return false;
        }

        boolean stopped = provisioner.deprovision(instance.get().instanceId());
        if (stopped) {
            registry.unregister(serviceId);
            log.info("Deprovisioned and unregistered: {}", serviceId);
        }
        return stopped;
    }

    /**
     * Check health of all provisioned services. Unregister any that died.
     *
     * @return number of unhealthy services removed
     */
    public int healthCheck() {
        int removed = 0;
        for (var instance : provisioner.list()) {
            if (!instance.healthy()) {
                log.warn("Provisioned service unhealthy, unregistering: {}", instance.serviceId());
                registry.unregister(instance.serviceId());
                removed++;
            }
        }
        return removed;
    }

    /** Get the underlying provisioner (for direct access in tests/admin). */
    public McpServerProvisioner provisioner() {
        return provisioner;
    }
}
