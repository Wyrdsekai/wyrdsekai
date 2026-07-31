package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of agent capabilities for service discovery.
 * Agents publish CapabilityAdvertisement on startup and periodically.
 * Other agents query: "who can do library_search?" → matching agents.
 *
 * Thread-safe singleton. Initialized alongside AgentEventStream.
 */
public class AgentCapabilityRegistry {

    private static volatile AgentCapabilityRegistry instance;

    /** Advertisement from an agent about what it can do. */
    public record CapabilityAdvertisement(
        String agentDid,
        String agentName,
        Set<String> capabilities,
        double availability,   // 0.0 (busy) to 1.0 (idle)
        Instant timestamp
    ) {}

    /** agentDid → latest advertisement */
    private final Map<String, CapabilityAdvertisement> advertisements = new ConcurrentHashMap<>();

    /** Stale threshold — advertisements older than this are ignored. */
    private static final long STALE_MS = 120_000; // 2 minutes

    private AgentCapabilityRegistry() {}

    public static void init() {
        instance = new AgentCapabilityRegistry();
    }

    public static AgentCapabilityRegistry get() {
        return instance;
    }

    /** Register or update an agent's capabilities. */
    public void advertise(CapabilityAdvertisement ad) {
        advertisements.put(ad.agentDid(), ad);
    }

    /** Find agents that can perform a specific capability. */
    public List<CapabilityAdvertisement> findAgentsForCapability(String capability) {
        var now = Instant.now();
        return advertisements.values().stream()
            .filter(ad -> !isStale(ad, now))
            .filter(ad -> ad.capabilities().contains(capability))
            .sorted(Comparator.comparingDouble(CapabilityAdvertisement::availability).reversed())
            .toList();
    }

    /** Find the best agent for a capability (highest availability, not stale). */
    public Optional<CapabilityAdvertisement> bestAgentForCapability(String capability) {
        var candidates = findAgentsForCapability(capability);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    /** Get all non-stale advertisements. */
    public List<CapabilityAdvertisement> allActive() {
        var now = Instant.now();
        return advertisements.values().stream()
            .filter(ad -> !isStale(ad, now))
            .toList();
    }

    /** Remove stale entries. Called periodically. */
    public void purgeStale() {
        var now = Instant.now();
        advertisements.entrySet().removeIf(e -> isStale(e.getValue(), now));
    }

    private boolean isStale(CapabilityAdvertisement ad, Instant now) {
        return Duration.between(ad.timestamp(), now).toMillis() > STALE_MS;
    }

    /** Number of registered agents. */
    public int size() {
        return advertisements.size();
    }
}
