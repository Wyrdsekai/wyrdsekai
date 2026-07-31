package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capability discovery through Between federation (section 91.2).
 *
 * Each node periodically broadcasts which MCP services it can provide.
 * Other nodes collect these announcements to build a local capability map.
 * When a room script or agent needs a service, the gossip layer can
 * answer "who offers this?" without network round-trips.
 *
 * NATS subject: wyrd.discovery.capabilities
 *
 * Announcements expire after {@link #EXPIRY_SECONDS} (180s = 3x the
 * recommended 60s broadcast interval). If a node stops broadcasting,
 * its capabilities are pruned.
 *
 * Like the rest of the Between, this is volatile (RAM, not disk).
 * Rebuilt from live gossip on every boot.
 */
public class PeerCapabilityGossip {

    private static final Logger log = LoggerFactory.getLogger(PeerCapabilityGossip.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** Announcements older than this are considered stale and pruned. */
    static final int EXPIRY_SECONDS = 180;

    private static final String SUBJECT = "wyrd.discovery.capabilities";

    /** Wire format for a capability announcement. */
    public record CapabilityAnnouncement(
        String nodeId,
        Set<String> serviceIds,
        long timestamp
    ) {}

    /** Listener for incoming capability announcements. */
    @FunctionalInterface
    public interface CapabilityListener {
        void onAnnouncement(CapabilityAnnouncement announcement);
    }

    private final BetweenLockerBridge.MessageTransport transport;
    private final String localNodeId;

    // nodeId -> latest announcement
    private final Map<String, CapabilityAnnouncement> announcements = new ConcurrentHashMap<>();

    // serviceId -> set of nodeIds that offer it (derived view, rebuilt on query)
    // We don't maintain this separately since announcements map is the source of truth.

    public PeerCapabilityGossip(BetweenLockerBridge.MessageTransport transport,
                                 String localNodeId) {
        this.transport = transport;
        this.localNodeId = localNodeId;
    }

    /**
     * Broadcast this node's available MCP services.
     * Should be called periodically (recommended: every 60s).
     */
    public void announceCapabilities(Set<String> serviceIds) {
        var announcement = new CapabilityAnnouncement(
            localNodeId, Set.copyOf(serviceIds), Instant.now().getEpochSecond());

        // Store locally too
        announcements.put(localNodeId, announcement);

        try {
            String json = MAPPER.writeValueAsString(announcement);
            transport.publish(SUBJECT, json);
            log.debug("Announced {} capabilities from node {}", serviceIds.size(), localNodeId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize capability announcement: {}", e.getMessage());
        }
    }

    /**
     * Subscribe to capability announcements from other nodes.
     * Optionally provide a listener for custom processing.
     */
    public void subscribeCapabilities(CapabilityListener listener) {
        transport.subscribe(SUBJECT, json -> {
            try {
                var announcement = MAPPER.readValue(json, CapabilityAnnouncement.class);
                var existing = announcements.get(announcement.nodeId());
                // Only accept if newer or same time (same-second re-announcement)
                if (existing == null || announcement.timestamp() >= existing.timestamp()) {
                    announcements.put(announcement.nodeId(), announcement);
                    if (listener != null) {
                        listener.onAnnouncement(announcement);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to deserialize capability announcement: {}", e.getMessage());
            }
        });
        log.debug("Subscribed to capability announcements");
    }

    /**
     * Subscribe to announcements without a custom listener.
     * Announcements are still stored in the local map for queries.
     */
    public void subscribeCapabilities() {
        subscribeCapabilities(null);
    }

    /**
     * Returns the full capability map: serviceId to set of nodeIds that offer it.
     * Prunes stale announcements before building the map.
     */
    public Map<String, Set<String>> knownCapabilities() {
        pruneStale();
        var result = new HashMap<String, Set<String>>();
        for (var entry : announcements.entrySet()) {
            for (var serviceId : entry.getValue().serviceIds()) {
                result.computeIfAbsent(serviceId, _ -> new HashSet<>())
                    .add(entry.getKey());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Find nodes that offer a specific service.
     * Returns an empty set if no nodes offer it.
     */
    public Set<String> findService(String serviceId) {
        pruneStale();
        var nodes = new HashSet<String>();
        for (var entry : announcements.entrySet()) {
            if (entry.getValue().serviceIds().contains(serviceId)) {
                nodes.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(nodes);
    }

    /**
     * Number of known peer nodes (including self).
     */
    public int knownNodeCount() {
        pruneStale();
        return announcements.size();
    }

    /**
     * All known service IDs across all nodes.
     */
    public Set<String> allServiceIds() {
        pruneStale();
        var services = new HashSet<String>();
        for (var announcement : announcements.values()) {
            services.addAll(announcement.serviceIds());
        }
        return Collections.unmodifiableSet(services);
    }

    /**
     * Remove stale announcements (older than EXPIRY_SECONDS).
     */
    private void pruneStale() {
        long cutoff = Instant.now().getEpochSecond() - EXPIRY_SECONDS;
        announcements.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
    }
}
