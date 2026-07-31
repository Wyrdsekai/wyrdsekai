package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Service checkpointing and state continuity (Wave 6: State Continuity).
 *
 * Periodically checkpoints service state to the Between mesh so any node
 * can resume a service after failover. Supports both graceful migration
 * (full state export) and ungraceful failover (load from last checkpoint).
 *
 * Checkpoint format uses delta compression — only changed fields since last
 * checkpoint are transmitted. Full snapshots sent on first sync or when
 * delta exceeds 50% of full size.
 */
public final class ServiceCheckpointer {

    private static final Logger log = LoggerFactory.getLogger(ServiceCheckpointer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * A checkpoint for a service's state.
     */
    public record Checkpoint(
        @JsonProperty("serviceId") String serviceId,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("sequence") long sequence,
        @JsonProperty("isFull") boolean isFull,        // full snapshot vs delta
        @JsonProperty("stateJson") String stateJson,    // serialized state
        @JsonProperty("stateSize") int stateSize,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public Checkpoint {}
    }

    /**
     * Provider interface — services implement this to participate in checkpointing.
     */
    public interface CheckpointProvider {
        /** Service identifier (e.g., "oracle", "forge", "companion-wyrd"). */
        String serviceId();

        /** Export current state as JSON string. */
        String exportState();

        /** Import state from a checkpoint (on failover or migration). */
        void importState(String stateJson);

        /** Export only changes since the given sequence number. Null = no delta support. */
        default String exportDelta(long sinceSequence) { return null; }
    }

    // ── State ──

    private final NatsBridge nats;
    private final String localNodeId;

    /** Registered checkpoint providers: serviceId → provider. */
    private final ConcurrentHashMap<String, CheckpointProvider> providers = new ConcurrentHashMap<>();

    /** Last known checkpoint per service (from NATS). */
    private final ConcurrentHashMap<String, Checkpoint> lastCheckpoints = new ConcurrentHashMap<>();

    /** Current sequence number per service. */
    private final ConcurrentHashMap<String, Long> sequences = new ConcurrentHashMap<>();

    /** Scheduled checkpoint futures. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledCheckpoints = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { var t = new Thread(r, "service-checkpointer"); t.setDaemon(true); return t; });

    public ServiceCheckpointer(NatsBridge nats, String localNodeId) {
        this.nats = nats;
        this.localNodeId = localNodeId;
    }

    /**
     * Register a service for periodic checkpointing.
     * @param provider the service's checkpoint provider
     * @param interval how often to checkpoint (default: 60s)
     */
    public void register(CheckpointProvider provider, Duration interval) {
        providers.put(provider.serviceId(), provider);
        sequences.putIfAbsent(provider.serviceId(), 0L);

        // Schedule periodic checkpoints
        var future = scheduler.scheduleAtFixedRate(() -> {
            try {
                checkpoint(provider.serviceId());
            } catch (Exception e) {
                log.warn("Checkpoint failed for {}: {}", provider.serviceId(), e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        scheduledCheckpoints.put(provider.serviceId(), future);

        log.info("Registered checkpoint provider: {} (interval={}s)",
            provider.serviceId(), interval.toSeconds());
    }

    /**
     * Perform a checkpoint for a service.
     */
    public void checkpoint(String serviceId) {
        var provider = providers.get(serviceId);
        if (provider == null) return;

        var seq = sequences.merge(serviceId, 1L, Long::sum);
        var lastCheckpoint = lastCheckpoints.get(serviceId);

        // Try delta first
        String stateJson;
        boolean isFull;
        if (lastCheckpoint != null && seq > 1) {
            var delta = provider.exportDelta(lastCheckpoint.sequence());
            if (delta != null && delta.length() < provider.exportState().length() / 2) {
                stateJson = delta;
                isFull = false;
            } else {
                stateJson = provider.exportState();
                isFull = true;
            }
        } else {
            stateJson = provider.exportState();
            isFull = true;
        }

        var checkpoint = new Checkpoint(
            serviceId, localNodeId, seq, isFull,
            stateJson, stateJson.length(), Instant.now());

        lastCheckpoints.put(serviceId, checkpoint);
        nats.broadcast("service.checkpoint", serviceId, MAPPER.valueToTree(checkpoint));

        log.debug("Checkpoint {} seq={} {} ({}B)",
            serviceId, seq, isFull ? "FULL" : "DELTA", stateJson.length());
    }

    /**
     * Force a full checkpoint (before graceful migration).
     */
    public Checkpoint fullCheckpoint(String serviceId) {
        var provider = providers.get(serviceId);
        if (provider == null) return null;

        var seq = sequences.merge(serviceId, 1L, Long::sum);
        var stateJson = provider.exportState();
        var checkpoint = new Checkpoint(
            serviceId, localNodeId, seq, true,
            stateJson, stateJson.length(), Instant.now());
        lastCheckpoints.put(serviceId, checkpoint);
        nats.broadcast("service.checkpoint", serviceId, MAPPER.valueToTree(checkpoint));
        return checkpoint;
    }

    /**
     * Load the last checkpoint for a service (for failover).
     * @return the checkpoint if available
     */
    public Optional<Checkpoint> getLastCheckpoint(String serviceId) {
        return Optional.ofNullable(lastCheckpoints.get(serviceId));
    }

    /**
     * Restore a service from its last checkpoint.
     * @return true if restoration was successful
     */
    public boolean restore(String serviceId) {
        var checkpoint = lastCheckpoints.get(serviceId);
        var provider = providers.get(serviceId);
        if (checkpoint == null || provider == null) return false;

        try {
            provider.importState(checkpoint.stateJson());
            sequences.put(serviceId, checkpoint.sequence());
            log.info("Restored {} from checkpoint seq={} ({}B, {}s old)",
                serviceId, checkpoint.sequence(), checkpoint.stateSize(),
                Duration.between(checkpoint.timestamp(), Instant.now()).toSeconds());
            return true;
        } catch (Exception e) {
            log.error("Failed to restore {} from checkpoint: {}", serviceId, e.getMessage());
            return false;
        }
    }

    /**
     * Start subscribing to checkpoint updates from other nodes.
     */
    public void startReplication() {
        nats.subscribeBroadcast("service.checkpoint", ">", env -> {
            try {
                var cp = MAPPER.convertValue(env.payload(), Checkpoint.class);
                // Store the latest checkpoint from any node
                var existing = lastCheckpoints.get(cp.serviceId());
                if (existing == null || cp.sequence() > existing.sequence()) {
                    lastCheckpoints.put(cp.serviceId(), cp);
                    log.debug("Replicated checkpoint: {} seq={} from node {}",
                        cp.serviceId(), cp.sequence(), cp.nodeId());
                }
            } catch (Exception e) {
                log.warn("Failed to parse checkpoint: {}", e.getMessage());
            }
        });
        log.info("ServiceCheckpointer: replication started");
    }

    /** Get all tracked service checkpoints. */
    public Map<String, Checkpoint> getAllCheckpoints() {
        return Map.copyOf(lastCheckpoints);
    }

    public void shutdown() {
        scheduledCheckpoints.values().forEach(f -> f.cancel(false));
        scheduledCheckpoints.clear();
        scheduler.shutdownNow();
    }
}
