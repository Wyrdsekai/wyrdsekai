package org.wyrdsekai.daemon.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * InferenceGossip client for daemons.
 *
 * Periodically broadcasts this daemon's inference capabilities on the same
 * NATS subject ({@code wyrd.inference.capabilities}) used by the server's
 * {@code InferenceGossip}. Also subscribes to peer announcements to build
 * a local mesh view.
 *
 * The daemon announces with a {@code daemon: true} capability tag via
 * the node name convention (suffix "-daemon") so the server's InferenceRouter
 * can prefer daemons for deferred work.
 */
public final class DaemonGossipClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DaemonGossipClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Must match {@code InferenceGossip.SUBJECT} on the server. */
    static final String SUBJECT = "wyrd.inference.capabilities";

    /** Must match {@code InferenceGossip.EXPIRY_SECONDS} on the server. */
    static final int EXPIRY_SECONDS = 90;

    /** Announce interval — 3x before expiry. */
    static final int ANNOUNCE_INTERVAL_SECONDS = 30;

    private final DaemonNatsClient nats;
    private final String nodeId;

    // Peer capability map (same structure as server's InferenceGossip)
    private final Map<String, DaemonCapability> peers = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> announceTask;

    public DaemonGossipClient(DaemonNatsClient nats, String nodeId) {
        this.nats = nats;
        this.nodeId = nodeId;
    }

    /**
     * Start periodic capability announcements.
     *
     * @param capabilityProvider supplies the current capability snapshot each cycle
     */
    public void startAnnouncing(Supplier<DaemonCapability> capabilityProvider) {
        if (scheduler != null) {
            throw new IllegalStateException("Already announcing");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "daemon-gossip");
            t.setDaemon(true);
            return t;
        });

        announceTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                var cap = capabilityProvider.get();
                announce(cap);
            } catch (Exception e) {
                log.error("Gossip announce failed: {}", e.getMessage());
            }
        }, 0, ANNOUNCE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.info("Started gossip announcements every {}s", ANNOUNCE_INTERVAL_SECONDS);
    }

    /**
     * Announce capabilities immediately (outside the timer).
     */
    public void announce(DaemonCapability capability) {
        try {
            var json = MAPPER.writeValueAsString(capability);
            nats.publish(SUBJECT, json);
            log.debug("Announced: {} models, {} slots, queue={}",
                capability.models().size(), capability.availableSlots(), capability.queueDepth());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize capability: {}", e.getMessage());
        }
    }

    /**
     * Subscribe to peer capability announcements.
     *
     * @param listener optional callback for each update (may be null)
     */
    public void subscribePeers(Consumer<DaemonCapability> listener) {
        nats.subscribe(SUBJECT, json -> {
            try {
                var cap = MAPPER.readValue(json, DaemonCapability.class);
                // Skip own announcements
                if (nodeId.equals(cap.nodeId())) return;

                var existing = peers.get(cap.nodeId());
                if (existing == null || cap.timestamp() >= existing.timestamp()) {
                    peers.put(cap.nodeId(), cap);
                    if (listener != null) {
                        listener.accept(cap);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse peer capability: {}", e.getMessage());
            }
        });
        log.info("Subscribed to peer capability announcements");
    }

    /**
     * Subscribe without a listener.
     */
    public void subscribePeers() {
        subscribePeers(null);
    }

    /**
     * Get known peer capabilities (pruned of stale entries).
     */
    public Map<String, DaemonCapability> knownPeers() {
        pruneStale();
        return Collections.unmodifiableMap(new HashMap<>(peers));
    }

    /**
     * Number of known peers (excluding self).
     */
    public int peerCount() {
        pruneStale();
        return peers.size();
    }

    /**
     * Stop gossip announcements and cleanup.
     */
    @Override
    public void close() {
        if (announceTask != null) {
            announceTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        peers.clear();
        log.info("Gossip client closed");
    }

    private void pruneStale() {
        long cutoff = Instant.now().getEpochSecond() - EXPIRY_SECONDS;
        peers.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
    }
}
