package org.wyrdsekai.core.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry syncer for MCP capability discovery (§91.1 Channel 1).
 * Periodically syncs with external registries to discover new MCP servers.
 *
 * Sources (priority order):
 * 1. MCP Registry (registry.modelcontextprotocol.io)
 * 2. Smithery (smithery.ai)
 * 3. PulseMCP (pulsemcp.com)
 *
 * Also manages room templates (§91.4) and trust scoring (§91.3).
 */
public class McpRegistrySyncer {

    private static final Logger log = LoggerFactory.getLogger(McpRegistrySyncer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Discovered capabilities not yet installed. */
    private final Map<String, DiscoveredCapability> discovered = new ConcurrentHashMap<>();

    /** Installed room templates. */
    private final Map<String, RoomTemplate> templates = new ConcurrentHashMap<>();

    /** Blocklist of banned capability IDs. */
    private final Set<String> blocklist = ConcurrentHashMap.newKeySet();

    /**
     * A capability discovered from an external registry.
     */
    public record DiscoveredCapability(
        String id,
        String name,
        String description,
        String endpoint,
        String transport,
        String source,
        double trustScore,
        String version,
        long discoveredAt
    ) {
        /** Whether this capability passes a trust threshold. */
        public boolean meetsTrust(double threshold) {
            return trustScore >= threshold;
        }

        /** Trust level label. */
        public String trustLevel() {
            if (trustScore >= 0.7) return "TRUSTED";
            if (trustScore >= 0.5) return "MODERATE";
            if (trustScore >= 0.3) return "LOW";
            return "UNTRUSTED";
        }
    }

    /**
     * Process discovered capabilities from a registry sync response (JSON).
     *
     * @param json JSON array of capability records
     * @return number of new capabilities discovered
     */
    public int processDiscoveries(String json) throws IOException {
        var capabilities = mapper.readValue(json,
            new TypeReference<List<DiscoveredCapability>>() {});

        int newCount = 0;
        for (var cap : capabilities) {
            if (blocklist.contains(cap.id())) {
                log.debug("Skipping blocklisted capability: {}", cap.id());
                continue;
            }
            if (!discovered.containsKey(cap.id())) {
                discovered.put(cap.id(), cap);
                newCount++;
                log.info("Discovered new capability: {} ({}, trust={})",
                    cap.id(), cap.name(), cap.trustLevel());
            }
        }
        return newCount;
    }

    /** Get all discovered (not yet installed) capabilities. */
    public List<DiscoveredCapability> getDiscovered() {
        return List.copyOf(discovered.values());
    }

    /** Get discovered capabilities that meet a trust threshold. */
    public List<DiscoveredCapability> getDiscovered(double minTrust) {
        return discovered.values().stream()
            .filter(c -> c.meetsTrust(minTrust))
            .toList();
    }

    /** Search discovered capabilities by keyword. */
    public List<DiscoveredCapability> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        return discovered.values().stream()
            .filter(c -> c.name().toLowerCase().contains(lower)
                || c.description().toLowerCase().contains(lower)
                || c.id().toLowerCase().contains(lower))
            .toList();
    }

    /** Mark a capability as installed (remove from discovered). */
    public void markInstalled(String capabilityId) {
        discovered.remove(capabilityId);
    }

    /** Add a capability to the blocklist. */
    public void block(String capabilityId) {
        blocklist.add(capabilityId);
        discovered.remove(capabilityId);
        log.info("Blocked capability: {}", capabilityId);
    }

    /** Remove a capability from the blocklist. */
    public void unblock(String capabilityId) {
        blocklist.remove(capabilityId);
    }

    /** Check if a capability is blocked. */
    public boolean isBlocked(String capabilityId) {
        return blocklist.contains(capabilityId);
    }

    // --- Room Templates ---

    /** Register a room template. */
    public void registerTemplate(RoomTemplate template) {
        templates.put(template.id(), template);
        log.info("Registered room template: {} (requires: {})",
            template.id(), template.requiredServices());
    }

    /** Load templates from JSON. */
    public int loadTemplates(String json) throws IOException {
        var loaded = mapper.readValue(json, new TypeReference<List<RoomTemplate>>() {});
        for (var t : loaded) {
            templates.put(t.id(), t);
        }
        log.info("Loaded {} room templates", loaded.size());
        return loaded.size();
    }

    /** Get a template by ID. */
    public Optional<RoomTemplate> getTemplate(String templateId) {
        return Optional.ofNullable(templates.get(templateId));
    }

    /** Find templates that match a discovered capability's required services. */
    public List<RoomTemplate> findTemplatesForService(String serviceId) {
        return templates.values().stream()
            .filter(t -> t.requiredServices() != null
                && t.requiredServices().contains(serviceId))
            .toList();
    }

    /** List all registered templates. */
    public List<RoomTemplate> allTemplates() {
        return List.copyOf(templates.values());
    }

    /** Number of discovered capabilities. */
    public int discoveredCount() {
        return discovered.size();
    }

    /** Number of registered templates. */
    public int templateCount() {
        return templates.size();
    }

    // ── §91.1: Multi-Source Registry Sync ──

    /** Registry source configuration. */
    public record RegistrySource(
        String id,
        String name,
        String baseUrl,
        double trustWeight,
        boolean enabled,
        long lastSyncEpoch
    ) {
        public RegistrySource withLastSync(long epoch) {
            return new RegistrySource(id, name, baseUrl, trustWeight, enabled, epoch);
        }
    }

    /** Sync result from a single source. */
    public record SyncResult(
        String sourceId,
        int newCapabilities,
        int updatedCapabilities,
        int errors,
        long durationMs
    ) {}

    private final Map<String, RegistrySource> sources = new ConcurrentHashMap<>();

    {
        // Default sources (§91.1)
        sources.put("mcp-registry", new RegistrySource("mcp-registry",
            "MCP Registry", "https://registry.modelcontextprotocol.io",
            1.0, true, 0));
        sources.put("smithery", new RegistrySource("smithery",
            "Smithery", "https://smithery.ai",
            0.8, true, 0));
        sources.put("pulsemcp", new RegistrySource("pulsemcp",
            "PulseMCP", "https://pulsemcp.com",
            0.7, true, 0));
    }

    /** Register a new registry source. */
    public void addSource(RegistrySource source) {
        sources.put(source.id(), source);
        log.info("Added registry source: {} ({})", source.name(), source.baseUrl());
    }

    /** Remove a registry source. */
    public void removeSource(String sourceId) {
        sources.remove(sourceId);
    }

    /** Get all configured sources. */
    public List<RegistrySource> allSources() {
        return List.copyOf(sources.values());
    }

    /** Get enabled sources only. */
    public List<RegistrySource> enabledSources() {
        return sources.values().stream().filter(RegistrySource::enabled).toList();
    }

    /**
     * Process discoveries from a specific source, tagging provenance.
     * Deduplicates across sources — same capability from multiple sources
     * gets the highest trust score.
     */
    public SyncResult processFromSource(String sourceId, String json) throws IOException {
        var source = sources.get(sourceId);
        if (source == null) {
            return new SyncResult(sourceId, 0, 0, 1, 0);
        }

        long start = System.currentTimeMillis();
        var capabilities = mapper.readValue(json,
            new TypeReference<List<DiscoveredCapability>>() {});

        int newCount = 0, updatedCount = 0;
        for (var cap : capabilities) {
            if (blocklist.contains(cap.id())) continue;

            // Apply source trust weight
            double weightedTrust = cap.trustScore() * source.trustWeight();
            var tagged = new DiscoveredCapability(cap.id(), cap.name(),
                cap.description(), cap.endpoint(), cap.transport(),
                sourceId, weightedTrust, cap.version(), System.currentTimeMillis());

            var existing = discovered.get(cap.id());
            if (existing == null) {
                discovered.put(cap.id(), tagged);
                newCount++;
            } else if (tagged.trustScore() > existing.trustScore()) {
                // Higher trust from this source — update
                discovered.put(cap.id(), tagged);
                updatedCount++;
            }
        }

        sources.put(sourceId, source.withLastSync(System.currentTimeMillis()));
        long duration = System.currentTimeMillis() - start;
        return new SyncResult(sourceId, newCount, updatedCount, 0, duration);
    }

    /** Get capabilities by source. */
    public List<DiscoveredCapability> bySource(String sourceId) {
        return discovered.values().stream()
            .filter(c -> sourceId.equals(c.source()))
            .toList();
    }

    // ── §91.4: Room Template Marketplace ──

    /** Template installation result. */
    public record InstallResult(
        String templateId,
        boolean success,
        String reason,
        List<String> missingServices
    ) {}

    /**
     * Check if a template can be installed (all required services available).
     *
     * @param templateId Template to check
     * @param registry   Service registry to validate against
     * @return Installation result with missing service list
     */
    public InstallResult canInstall(String templateId, McpServiceRegistry registry) {
        var template = templates.get(templateId);
        if (template == null) {
            return new InstallResult(templateId, false, "Template not found", List.of());
        }
        if (template.requiredServices() == null || template.requiredServices().isEmpty()) {
            return new InstallResult(templateId, true, "No dependencies", List.of());
        }

        var missing = template.requiredServices().stream()
            .filter(s -> !registry.isAvailable(s))
            .toList();

        if (missing.isEmpty()) {
            return new InstallResult(templateId, true, "All dependencies met", List.of());
        }
        return new InstallResult(templateId, false,
            "Missing services: " + String.join(", ", missing), missing);
    }

    /** Search templates by keyword. */
    public List<RoomTemplate> searchTemplates(String query) {
        if (query == null || query.isBlank()) return allTemplates();
        String lower = query.toLowerCase();
        return templates.values().stream()
            .filter(t -> t.name().toLowerCase().contains(lower)
                || t.description().toLowerCase().contains(lower)
                || t.category().toLowerCase().contains(lower))
            .toList();
    }

    /** Get templates by category. */
    public List<RoomTemplate> templatesByCategory(String category) {
        return templates.values().stream()
            .filter(t -> category.equals(t.category()))
            .toList();
    }
}
