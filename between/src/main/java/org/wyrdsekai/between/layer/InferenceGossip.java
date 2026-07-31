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
 * Inference capability discovery through Between federation (§G8).
 *
 * Each node periodically broadcasts its inference state — available models,
 * GPU count, free VRAM, queue depth, latency. Other nodes collect these to
 * build a local capability map for remote inference routing.
 *
 * NATS subject: wyrd.inference.capabilities
 *
 * Announcements expire after {@link #EXPIRY_SECONDS} (90s = 3x the
 * recommended 30s broadcast interval).
 */
public class InferenceGossip {

    private static final Logger log = LoggerFactory.getLogger(InferenceGossip.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** Announcements older than this are considered stale and pruned. */
    static final int EXPIRY_SECONDS = 90;

    private static final String SUBJECT = "wyrd.inference.capabilities";

    /** Wire format for an inference capability announcement. */
    public record InferenceCapability(
        String nodeId,
        List<AvailableModel> models,
        int totalGpuCount,
        long totalFreeVramMB,
        int availableSlots,
        int queueDepth,
        double avgLatencyMs,
        long timestamp
    ) {}

    /** A model available on a node. */
    public record AvailableModel(
        String modelId,
        String tier,
        String endpoint,
        int maxConcurrent,
        int activeLeases
    ) {}

    /** Listener for incoming inference capability announcements. */
    @FunctionalInterface
    public interface InferenceCapabilityListener {
        void onCapability(InferenceCapability capability);
    }

    private final BetweenLockerBridge.MessageTransport transport;
    private final String localNodeId;

    // nodeId -> latest capability announcement
    private final Map<String, InferenceCapability> capabilities = new ConcurrentHashMap<>();

    public InferenceGossip(BetweenLockerBridge.MessageTransport transport, String localNodeId) {
        this.transport = transport;
        this.localNodeId = localNodeId;
    }

    /**
     * Broadcast this node's inference capabilities.
     * Should be called periodically (recommended: every 30s).
     */
    public void announceCapabilities(InferenceCapability capability) {
        capabilities.put(localNodeId, capability);
        try {
            String json = MAPPER.writeValueAsString(capability);
            transport.publish(SUBJECT, json);
            log.debug("Announced inference capabilities: {} models, {} GPUs, queue={}",
                capability.models().size(), capability.totalGpuCount(), capability.queueDepth());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize inference capability: {}", e.getMessage());
        }
    }

    /**
     * Subscribe to inference capability announcements from other nodes.
     */
    public void subscribeCapabilities(InferenceCapabilityListener listener) {
        transport.subscribe(SUBJECT, json -> {
            try {
                var capability = MAPPER.readValue(json, InferenceCapability.class);
                var existing = capabilities.get(capability.nodeId());
                if (existing == null || capability.timestamp() >= existing.timestamp()) {
                    capabilities.put(capability.nodeId(), capability);
                    if (listener != null) {
                        listener.onCapability(capability);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to deserialize inference capability: {}", e.getMessage());
            }
        });
        log.debug("Subscribed to inference capability announcements");
    }

    /**
     * Subscribe without a custom listener.
     */
    public void subscribeCapabilities() {
        subscribeCapabilities(null);
    }

    /**
     * Get capabilities for a specific node.
     */
    public Optional<InferenceCapability> getCapability(String nodeId) {
        pruneStale();
        return Optional.ofNullable(capabilities.get(nodeId));
    }

    /**
     * Find nodes that have a model of the given tier with available slots.
     * Returns entries sorted by queue depth (lowest first), preferring
     * nodes with lower latency.
     */
    public List<InferenceCapability> findNodesWithTier(String tier) {
        pruneStale();
        return capabilities.values().stream()
            .filter(c -> !c.nodeId().equals(localNodeId)) // exclude self
            .filter(c -> c.models().stream().anyMatch(m ->
                m.tier().equals(tier) && m.activeLeases() < m.maxConcurrent()))
            .sorted(Comparator
                .comparingInt(InferenceCapability::queueDepth)
                .thenComparingDouble(InferenceCapability::avgLatencyMs))
            .toList();
    }

    /**
     * Find the best remote endpoint for a given tier.
     * Returns the model from the node with lowest queue depth and latency.
     */
    public Optional<RemoteEndpoint> findBestRemote(String tier) {
        var candidates = findNodesWithTier(tier);
        for (var cap : candidates) {
            for (var model : cap.models()) {
                if (model.tier().equals(tier) && model.activeLeases() < model.maxConcurrent()) {
                    return Optional.of(new RemoteEndpoint(
                        cap.nodeId(), model.modelId(), model.endpoint(),
                        model.tier(), model.maxConcurrent()));
                }
            }
        }
        return Optional.empty();
    }

    /** A resolved remote inference endpoint. */
    public record RemoteEndpoint(
        String nodeId, String modelId, String endpoint, String tier, int maxConcurrent
    ) {}

    /**
     * Number of known peer nodes with inference capability (including self).
     */
    public int knownNodeCount() {
        pruneStale();
        return capabilities.size();
    }

    /**
     * All known inference-capable node IDs.
     */
    public Set<String> knownNodeIds() {
        pruneStale();
        return Collections.unmodifiableSet(new HashSet<>(capabilities.keySet()));
    }

    /**
     * Handle a node departure — remove its capabilities.
     */
    public void nodeDisconnected(String nodeId) {
        capabilities.remove(nodeId);
        log.debug("Removed inference capabilities for departed node '{}'", nodeId);
    }

    /**
     * Remove stale announcements (older than EXPIRY_SECONDS).
     */
    private void pruneStale() {
        long cutoff = Instant.now().getEpochSecond() - EXPIRY_SECONDS;
        capabilities.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
    }
}
