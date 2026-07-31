package org.wyrdsekai.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of available MCP services (§86.3).
 * Loads service configurations from zone config JSON.
 * Supports hot-reload for adding/removing services at runtime.
 */
public class McpServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpServiceRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, McpServiceConfig> services = new ConcurrentHashMap<>();

    /** Load services from a JSON config file. */
    public void loadFromFile(Path configPath) throws IOException {
        var json = Files.readString(configPath);
        loadFromJson(json);
    }

    /** Load services from a JSON string. */
    public void loadFromJson(String json) throws IOException {
        var root = mapper.readTree(json);
        var servicesNode = root.has("mcp_services") ? root.get("mcp_services") : root;
        var configs = mapper.readValue(servicesNode.traverse(),
            new TypeReference<List<McpServiceConfig>>() {});

        for (var config : configs) {
            services.put(config.id(), config);
            log.info("Registered MCP service: {} ({}, tier={})",
                config.id(), config.name(), config.tier());
        }
        log.info("Loaded {} MCP service configurations", configs.size());
    }

    /** Register a single service config. */
    public void register(McpServiceConfig config) {
        services.put(config.id(), config);
        log.info("Registered MCP service: {}", config.id());
    }

    /** Unregister a service. */
    public void unregister(String serviceId) {
        services.remove(serviceId);
    }

    /** Get a service config by ID. */
    public Optional<McpServiceConfig> get(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    /** Check if a service is registered and enabled. */
    public boolean isAvailable(String serviceId) {
        var config = services.get(serviceId);
        return config != null && config.enabled();
    }

    /** List all registered service IDs. */
    public Set<String> serviceIds() {
        return Set.copyOf(services.keySet());
    }

    /** List all enabled services. */
    public List<McpServiceConfig> enabledServices() {
        return services.values().stream()
            .filter(McpServiceConfig::enabled)
            .toList();
    }

    /** Count of registered services. */
    public int size() {
        return services.size();
    }

    // ── §86.3: Hot-Reload Support ──

    /** Listener for registry change events. */
    @FunctionalInterface
    public interface RegistryListener {
        void onRegistryChanged(String serviceId, ChangeType type);
    }

    public enum ChangeType { REGISTERED, UNREGISTERED, UPDATED }

    private final List<RegistryListener> listeners = new CopyOnWriteArrayList<>();

    /** Add a listener for registry changes (used by world.mcp() hot-reload). */
    public void addListener(RegistryListener listener) {
        listeners.add(listener);
    }

    /** Remove a listener. */
    public void removeListener(RegistryListener listener) {
        listeners.remove(listener);
    }

    /**
     * Reload from a JSON config string, detecting additions, removals, and updates.
     * Thread-safe — can be called from a file watcher callback.
     *
     * @return summary of changes (added, removed, updated counts)
     */
    public ReloadResult reload(String json) throws IOException {
        var root = mapper.readTree(json);
        var servicesNode = root.has("mcp_services") ? root.get("mcp_services") : root;
        var incoming = mapper.readValue(servicesNode.traverse(),
            new TypeReference<List<McpServiceConfig>>() {});

        int added = 0, removed = 0, updated = 0;

        var incomingIds = new HashSet<String>();
        for (var config : incoming) {
            incomingIds.add(config.id());
            var existing = services.get(config.id());
            if (existing == null) {
                services.put(config.id(), config);
                notifyListeners(config.id(), ChangeType.REGISTERED);
                added++;
            } else if (!existing.equals(config)) {
                services.put(config.id(), config);
                notifyListeners(config.id(), ChangeType.UPDATED);
                updated++;
            }
        }

        // Remove services not in the new config
        var removed_ids = new ArrayList<String>();
        for (var id : services.keySet()) {
            if (!incomingIds.contains(id)) {
                removed_ids.add(id);
            }
        }
        for (var id : removed_ids) {
            services.remove(id);
            notifyListeners(id, ChangeType.UNREGISTERED);
            removed++;
        }

        log.info("Registry reloaded: {} added, {} removed, {} updated",
            added, removed, updated);
        return new ReloadResult(added, removed, updated);
    }

    /** Result of a config reload. */
    public record ReloadResult(int added, int removed, int updated) {
        public boolean hasChanges() { return added > 0 || removed > 0 || updated > 0; }
    }

    private void notifyListeners(String serviceId, ChangeType type) {
        for (var listener : listeners) {
            try {
                listener.onRegistryChanged(serviceId, type);
            } catch (Exception e) {
                log.warn("Registry listener error for {}: {}", serviceId, e.getMessage());
            }
        }
    }
}
