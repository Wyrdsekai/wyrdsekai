package org.wyrdsekai.core.substrate.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ship corpus to a peer node, train there, ship the adapter back.
 *
 * <p>Closes #396. The peer-side counterpart is
 * {@link TrainingPeerService} — it subscribes to the request subject,
 * runs {@link LocalSerialExecutor} locally, and responds + ships
 * adapter chunks per {@link PeerTrainingProtocol}.</p>
 *
 * <p>Failure modes that return {@code SKIPPED_NO_BACKEND}:
 * <ul>
 *   <li>No transport configured (NATS not wired into this node).</li>
 *   <li>Peer didn't respond within {@link #REQUEST_TIMEOUT}.</li>
 * </ul></p>
 *
 * <p>Failure modes that return {@code FAILED}:
 * <ul>
 *   <li>Peer responded but with status="fail".</li>
 *   <li>Adapter chunks didn't all arrive within {@link #ADAPTER_TIMEOUT}.</li>
 *   <li>Reassembled SHA-256 didn't match what the peer reported.</li>
 *   <li>Could not write the adapter to local disk.</li>
 * </ul></p>
 *
 * <p>The submitter never pauses local inference for peer-delegated
 * training — that's the whole point. Local inference stays online
 * during the peer's training window.</p>
 */
final class PeerDelegatedExecutor implements TrainingExecutor {

    private static final Logger log = LoggerFactory.getLogger(PeerDelegatedExecutor.class);

    /** How long the submitter waits for the peer's initial Response. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** How long to wait for ALL adapter chunks once we know the count. */
    private static final Duration ADAPTER_TIMEOUT = Duration.ofMinutes(15);

    private final Context ctx;
    private final TrainingStrategy.PeerDelegated strategy;

    PeerDelegatedExecutor(Context ctx, TrainingStrategy.PeerDelegated strategy) {
        this.ctx = ctx;
        this.strategy = strategy;
    }

    @Override
    public DeepSleepTrainer.Result execute(
            String agentId, String agentName,
            String baseModelPath, Path workDir,
            List<Map<String, String>> corpus) {

        var transport = ctx.peerTransport();
        if (transport == null) {
            log.warn("PeerDelegated: no PeerTrainingTransport in Context — "
                    + "peer training disabled on this node");
            return new DeepSleepTrainer.Result(
                DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND,
                "peer-delegated: no transport", null);
        }

        var requestId = UUID.randomUUID().toString();
        var subject = PeerTrainingProtocol.requestSubject(strategy.peerNodeId());
        var request = new PeerTrainingProtocol.Request(
            requestId,
            ctx.localNodeId() != null ? ctx.localNodeId() : "unknown",
            agentId, agentName,
            baseModelPath,
            corpus,
            null /* maxIters — let peer use its default */);

        log.info("PeerDelegated: requesting training for '{}' on peer {} "
                + "(corpus={} turns, requestId={})",
            agentName, strategy.peerNodeId(), corpus.size(), requestId);

        // CRITICAL: subscribe to adapter chunks BEFORE sending the request.
        // The peer may publish chunks immediately after sending its Response —
        // if we subscribed only after receiving the response, we'd race the
        // peer and miss early chunks. Subscribing first guarantees no chunk
        // is dropped (the wildcard is requestId-scoped so we don't see
        // unrelated traffic).
        var chunks = new HashMap<Integer, byte[]>();
        var done = new CountDownLatch(1);
        var expectedChunks = new int[]{-1}; // mutable holder; set after Response
        var wildcard = PeerTrainingProtocol.adapterChunkWildcard(
            strategy.peerNodeId(), requestId);

        try (var sub = transport.subscribe(wildcard, (subj, replyTo, payload) -> {
            try {
                var chunk = PeerTrainingProtocol.decode(
                    payload, PeerTrainingProtocol.AdapterChunk.class);
                chunks.put(chunk.seq(), chunk.data());
                if (expectedChunks[0] > 0 && chunks.size() >= expectedChunks[0]) {
                    done.countDown();
                }
            } catch (Exception e) {
                log.warn("PeerDelegated: failed to decode chunk: {}", e.getMessage());
            }
        })) {
            var responseBytes = transport.requestReply(
                subject, PeerTrainingProtocol.encode(request), REQUEST_TIMEOUT);
            if (responseBytes.isEmpty()) {
                log.warn("PeerDelegated: peer {} did not respond within {}s",
                    strategy.peerNodeId(), REQUEST_TIMEOUT.toSeconds());
                return new DeepSleepTrainer.Result(
                    DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND,
                    "peer-delegated: no response from " + strategy.peerNodeId(), null);
            }

            var response = PeerTrainingProtocol.decode(
                responseBytes.get(), PeerTrainingProtocol.Response.class);

            if (!response.ok()) {
                log.warn("PeerDelegated: peer {} returned status={} detail={}",
                    strategy.peerNodeId(), response.status(), response.detail());
                var outcome = "skip".equals(response.status())
                    ? DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND
                    : DeepSleepTrainer.Outcome.FAILED;
                return new DeepSleepTrainer.Result(
                    outcome, "peer-" + response.status() + ": " + response.detail(), null);
            }

            var chunkCount = response.adapterChunkCount() != null ? response.adapterChunkCount() : 0;
            if (chunkCount <= 0) {
                log.warn("PeerDelegated: peer {} responded ok but with chunkCount={}",
                    strategy.peerNodeId(), chunkCount);
                return new DeepSleepTrainer.Result(
                    DeepSleepTrainer.Outcome.FAILED,
                    "peer-delegated: zero chunks reported", null);
            }
            expectedChunks[0] = chunkCount;

            // Chunks may have already arrived (or be arriving). If enough
            // are already in, trip done immediately.
            if (chunks.size() >= chunkCount) {
                done.countDown();
            }

            var arrived = done.await(
                ADAPTER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!arrived) {
                log.warn("PeerDelegated: adapter chunks incomplete after {} "
                        + "(received {}/{} from peer {})",
                    ADAPTER_TIMEOUT, chunks.size(), chunkCount, strategy.peerNodeId());
                return new DeepSleepTrainer.Result(
                    DeepSleepTrainer.Outcome.FAILED,
                    "peer-delegated: adapter chunks timed out ("
                        + chunks.size() + "/" + chunkCount + ")", null);
            }

            return finalizeAdapter(agentId, agentName, requestId, chunks,
                chunkCount, response, strategy.peerNodeId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeepSleepTrainer.Result(
                DeepSleepTrainer.Outcome.FAILED,
                "peer-delegated: interrupted", null);
        }
    }

    /** Reassemble + verify + write the adapter to disk. Extracted for clarity. */
    private DeepSleepTrainer.Result finalizeAdapter(
            String agentId, String agentName, String requestId,
            HashMap<Integer, byte[]> chunks, int chunkCount,
            PeerTrainingProtocol.Response response, String peerNodeId) {

        // Reassemble in seq order.
        var totalSize = 0;
        for (int i = 0; i < chunkCount; i++) {
            var c = chunks.get(i);
            if (c == null) {
                return new DeepSleepTrainer.Result(
                    DeepSleepTrainer.Outcome.FAILED,
                    "peer-delegated: missing chunk seq=" + i, null);
            }
            totalSize += c.length;
        }
        var assembled = new byte[totalSize];
        var off = 0;
        for (int i = 0; i < chunkCount; i++) {
            var c = chunks.get(i);
            System.arraycopy(c, 0, assembled, off, c.length);
            off += c.length;
        }

        // Verify SHA-256 if peer reported one.
        if (response.adapterSha256() != null && !response.adapterSha256().isBlank()) {
            var actual = sha256Hex(assembled);
            if (!actual.equalsIgnoreCase(response.adapterSha256())) {
                var expected = response.adapterSha256();
                var preview = expected.length() > 12 ? expected.substring(0, 12) + "..." : expected;
                return new DeepSleepTrainer.Result(
                    DeepSleepTrainer.Outcome.FAILED,
                    "peer-delegated: SHA-256 mismatch (expected " + preview + ")", null);
            }
        }

        // Write to local adapter root: <root>/<safe-agentId>/adapter.gguf
        var safeId = agentId.replaceAll("[^a-zA-Z0-9_-]", "_");
        var adapterDir = ctx.adapterRoot().resolve(safeId);
        var adapterPath = adapterDir.resolve("adapter.gguf");
        try {
            Files.createDirectories(adapterDir);
            Files.write(adapterPath, assembled);
        } catch (IOException e) {
            log.error("PeerDelegated: failed to write adapter to {}: {}",
                adapterPath, e.getMessage());
            return new DeepSleepTrainer.Result(
                DeepSleepTrainer.Outcome.FAILED,
                "peer-delegated: write failed: " + e.getMessage(), null);
        }

        var shaPreview = response.adapterSha256() == null ? "none"
            : response.adapterSha256().length() > 12
                ? response.adapterSha256().substring(0, 12) + "..."
                : response.adapterSha256();
        log.info("PeerDelegated: complete for '{}' — {} bytes from peer {} "
                + "→ {} (chunks={}, sha256={})",
            agentName, totalSize, peerNodeId, adapterPath, chunkCount, shaPreview);

        return new DeepSleepTrainer.Result(
            DeepSleepTrainer.Outcome.COMPLETED, "peer-delegated:ok", adapterPath);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var digest = md.digest(bytes);
            var sb = new StringBuilder(digest.length * 2);
            for (var b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
