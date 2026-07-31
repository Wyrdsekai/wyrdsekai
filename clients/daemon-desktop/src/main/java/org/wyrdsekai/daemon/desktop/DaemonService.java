package org.wyrdsekai.daemon.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.daemon.common.*;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Orchestrates the desktop daemon lifecycle:
 * 1. Detect/start inference backend (Ollama or llama-server)
 * 2. Connect to NATS
 * 3. Start gossip announcements
 * 4. Subscribe to inference requests
 *
 * The service runs on background threads; the main thread blocks.
 */
public final class DaemonService {

    private static final Logger log = LoggerFactory.getLogger(DaemonService.class);

    private final DaemonConfig config;
    private final String nodeId;
    private final DaemonStats stats = new DaemonStats();

    private InferenceProcess inferenceProcess;
    private DaemonNatsClient nats;
    private DaemonGossipClient gossip;
    private volatile boolean running;

    public DaemonService(DaemonConfig config) {
        this.config = config;
        this.nodeId = config.nodeName() + "-daemon-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Start the daemon. Blocks until NATS is connected and inference is healthy.
     */
    public void start() {
        if (running) return;
        running = true;

        log.info("Starting daemon service (nodeId={})", nodeId);

        // 1. Start inference backend
        inferenceProcess = new InferenceProcess(config);
        try {
            inferenceProcess.start();
            log.info("Inference backend started: {}", inferenceProcess.backendName());
        } catch (Exception e) {
            log.error("Failed to start inference backend: {}", e.getMessage());
            running = false;
            return;
        }

        // 2. Wait for inference health
        if (!inferenceProcess.waitForHealth(120)) {
            log.error("Inference backend not healthy after 120s");
            inferenceProcess.stop();
            running = false;
            return;
        }

        // 3. Connect to NATS
        nats = new DaemonNatsClient(config.natsUrl(), nodeId);
        try {
            nats.connect();
        } catch (Exception e) {
            log.error("Failed to connect to NATS at {}: {}", config.natsUrl(), e.getMessage());
            inferenceProcess.stop();
            running = false;
            return;
        }

        // 4. Start gossip
        gossip = new DaemonGossipClient(nats, nodeId);
        gossip.subscribePeers(cap ->
            log.debug("Peer update: {} ({} models, queue={})",
                cap.nodeId(), cap.models().size(), cap.queueDepth()));
        gossip.startAnnouncing(this::buildCapability);

        // 5. Subscribe to inference requests
        var requestSubject = "wyrd.inference.request." + nodeId;
        nats.subscribeRequestReply(requestSubject, (data, reply) -> {
            handleInferenceRequest(data, reply);
        });

        log.info("Daemon service running — {} on port {}",
            inferenceProcess.backendName(), config.inferencePort());
    }

    /**
     * Stop the daemon gracefully.
     */
    public void stop() {
        if (!running) return;
        running = false;
        log.info("Stopping daemon service");

        if (gossip != null) {
            gossip.close();
        }
        if (nats != null) {
            nats.close();
        }
        if (inferenceProcess != null) {
            inferenceProcess.stop();
        }
        log.info("Daemon service stopped");
    }

    public boolean isRunning() { return running; }
    public DaemonStats stats() { return stats; }
    public String nodeId() { return nodeId; }

    public DaemonGossipClient gossip() { return gossip; }

    // --- Internal ---

    private DaemonCapability buildCapability() {
        var endpoint = "http://" + getLocalAddress() + ":" + config.inferencePort();
        var modelId = config.modelId().isEmpty() ? "unknown" : config.modelId();
        var tier = inferTier(modelId);

        var model = new DaemonModel(
            modelId, tier, endpoint,
            1, // phones/desktops: 1 concurrent
            stats.activeRequests()
        );

        return DaemonCapability.now(
            nodeId, List.of(model),
            config.gpuLayers() > 0 ? 1 : 0,
            0, // free VRAM not easily detectable without nvidia-smi parsing
            stats.activeRequests() < 1 ? 1 : 0,
            stats.queueDepth(),
            stats.avgLatencyMs()
        );
    }

    private void handleInferenceRequest(byte[] data, Consumer<byte[]> reply) {
        var mapper = new ObjectMapper();
        try {
            var request = mapper.readValue(data, InferenceRequest.class);
            stats.recordRequestStart();

            var startTime = System.currentTimeMillis();

            // Forward to local inference backend via HTTP
            var response = inferenceProcess.forwardRequest(request);
            var latencyMs = System.currentTimeMillis() - startTime;

            if (response.hasError()) {
                stats.recordFailure();
            } else {
                stats.recordCompletion(latencyMs, response.completionTokens());
            }

            reply.accept(mapper.writeValueAsBytes(response));

        } catch (Exception e) {
            log.error("Failed to handle inference request: {}", e.getMessage());
            stats.recordFailure();
            try {
                reply.accept(mapper.writeValueAsBytes(
                    InferenceResponse.error("unknown", e.getMessage())));
            } catch (Exception ex) {
                log.error("Failed to send error reply: {}", ex.getMessage());
            }
        }
    }

    private static String inferTier(String modelId) {
        var lower = modelId.toLowerCase();
        if (lower.contains("0.5b") || lower.contains("0.6b") || lower.contains("1b")) return "tiny";
        if (lower.contains("1.5b") || lower.contains("1.7b") || lower.contains("3b") || lower.contains("4b")) return "medium";
        if (lower.contains("7b") || lower.contains("8b")) return "large";
        if (lower.contains("14b") || lower.contains("30b") || lower.contains("70b")) return "large";
        return "medium"; // default
    }

    private static String getLocalAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
