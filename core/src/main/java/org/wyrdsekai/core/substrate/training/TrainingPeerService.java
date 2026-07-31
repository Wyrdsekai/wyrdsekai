package org.wyrdsekai.core.substrate.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Peer-side handler for {@link PeerDelegatedExecutor}.
 *
 * <p>On nodes that opt-in (env-gated by the steward), construct + start
 * one of these. It subscribes to
 * {@code wyrdsekai.training.peer.<this-node-id>.request}, runs
 * {@link LocalSerialExecutor} when a request lands, replies with the
 * result, and ships the adapter back as chunked NATS messages.</p>
 *
 * <p>The peer always uses its OWN model path resolution + its own
 * inference controller — the request's {@code modelHint} is advisory.
 * The peer is sovereign about its compute even when serving a remote
 * agent.</p>
 *
 * <p>Concurrency: incoming requests are dispatched to a small executor
 * pool so a slow training run doesn't block the NATS callback thread.
 * The pool is sized at {@code maxConcurrentTraining} (default 1) — peer
 * delegation is sequential by default to keep VRAM contention bounded.
 * A future smarter peer could expose its own `canTrainInParallel()`
 * predicate and bump this.</p>
 */
public final class TrainingPeerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrainingPeerService.class);

    private final String localNodeId;
    private final PeerTrainingTransport transport;
    private final DeepSleepTrainer.InferenceController inferenceController;
    private final Path adapterRoot;
    private final ExecutorService trainingPool;
    private final int chunkBytes;
    private volatile PeerTrainingTransport.Subscription subscription;

    /** @param maxConcurrentTraining usually 1; bump only if the peer node
     *                               can do parallel training (≥24GB GPU). */
    public TrainingPeerService(
            String localNodeId,
            PeerTrainingTransport transport,
            DeepSleepTrainer.InferenceController inferenceController,
            Path adapterRoot,
            int maxConcurrentTraining,
            int chunkBytes) {
        this.localNodeId = localNodeId;
        this.transport = transport;
        this.inferenceController = inferenceController;
        this.adapterRoot = adapterRoot;
        this.trainingPool = Executors.newFixedThreadPool(
            Math.max(1, maxConcurrentTraining),
            Thread.ofVirtual().name("peer-training-", 0).factory());
        this.chunkBytes = chunkBytes > 0
            ? chunkBytes
            : PeerTrainingProtocol.DEFAULT_CHUNK_BYTES;
    }

    /** Convenience: defaults to 1 concurrent training, default chunk size. */
    public TrainingPeerService(
            String localNodeId,
            PeerTrainingTransport transport,
            DeepSleepTrainer.InferenceController inferenceController,
            Path adapterRoot) {
        this(localNodeId, transport, inferenceController, adapterRoot,
            1, PeerTrainingProtocol.DEFAULT_CHUNK_BYTES);
    }

    /**
     * Subscribe to incoming requests. Idempotent — calling twice
     * silently reuses the existing subscription.
     */
    public synchronized void start() {
        if (subscription != null) return;
        var subject = PeerTrainingProtocol.requestSubject(localNodeId);
        subscription = transport.subscribe(subject,
            (subj, replyTo, payload) -> onRequest(replyTo, payload));
        log.info("TrainingPeerService listening for requests on {}", subject);
    }

    @Override
    public synchronized void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        trainingPool.shutdown();
    }

    private void onRequest(String replyTo, byte[] payload) {
        // Decode synchronously so we can reply with a parse-error response
        // immediately if the payload is malformed.
        PeerTrainingProtocol.Request request;
        try {
            request = PeerTrainingProtocol.decode(
                payload, PeerTrainingProtocol.Request.class);
        } catch (Exception e) {
            log.warn("Malformed peer training request: {}", e.getMessage());
            return;
        }
        if (replyTo == null || replyTo.isBlank()) {
            log.warn("Peer training request {} has no reply-to subject — dropping",
                request.requestId());
            return;
        }
        log.info("Peer training request from {} for '{}' (requestId={}, corpus={} turns)",
            request.submitterNodeId(), request.agentName(),
            request.requestId(),
            request.corpus() != null ? request.corpus().size() : 0);
        // Heavy lifting on the training pool — frees the NATS callback thread.
        // Outer try/catch so anything that escapes handle() surfaces in logs
        // (Future.get() is never called, so otherwise unhandled Throwables vanish).
        trainingPool.submit(() -> {
            try {
                handle(request, replyTo);
            } catch (Throwable t) {
                log.error("Peer training handle escaped for request {}: {}",
                    request.requestId(), t.getMessage(), t);
            }
        });
    }

    private void handle(PeerTrainingProtocol.Request request, String replyTo) {
        try {
            // Use peer's local strategy: pause-its-own-inference + train.
            // The corpus from the submitter; the model path uses the hint
            // (peer is expected to have the same base model on disk).
            var localStrategy = new TrainingStrategy.LocalSerial(List.of());
            var localCtx = new TrainingExecutor.Context(
                inferenceController, adapterRoot, transport, localNodeId);
            // Honor the submitter's iteration cap so probes / quick smoke tests
            // don't trigger the default 2500-iter run. Null falls back to the
            // VoiceAligner default.
            var executor = new LocalSerialExecutor(localCtx, localStrategy, request.maxIters());

            DeepSleepTrainer.Result result;
            try {
                result = executor.execute(
                    request.agentId(), request.agentName(),
                    request.modelHint(),
                    adapterRoot,
                    request.corpus());
            } catch (Throwable t) {
                log.error("Peer training executor threw for request {}: {}",
                    request.requestId(), t.getMessage(), t);
                respond(replyTo, request, "fail",
                    "executor-threw: " + t.getMessage(), 0, 0L, null);
                return;
            }

            if (result == null) {
                respond(replyTo, request, "fail", "executor-returned-null", 0, 0L, null);
                return;
            }

            // Ship if training completed OR if the adapter was produced but inference
            // resume failed (resume-failed is the peer's local concern; the submitter
            // still needs the adapter bytes).
            var adapterProduced = result.adapterPath() != null;
            if (result.outcome() != DeepSleepTrainer.Outcome.COMPLETED && !adapterProduced) {
                respond(replyTo, request, "fail", result.detail(), 0, 0L, null);
                return;
            }
            if (result.outcome() != DeepSleepTrainer.Outcome.COMPLETED) {
                log.warn("Training outcome={} detail='{}' for request {} — adapter present, shipping anyway",
                    result.outcome(), result.detail(), request.requestId());
            }

            // Read adapter bytes (LocalSerialExecutor wrote a directory; we
            // ship the GGUF specifically since that's what llama-server loads).
            var adapterDir = result.adapterPath();
            var ggufPath = findAdapterGguf(adapterDir);
            if (ggufPath == null) {
                respond(replyTo, request, "fail",
                    "no adapter.gguf in " + adapterDir, 0, 0L, null);
                return;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(ggufPath);
            } catch (IOException e) {
                respond(replyTo, request, "fail",
                    "read adapter: " + e.getMessage(), 0, 0L, null);
                return;
            }

            var sha256 = PeerDelegatedExecutor.sha256Hex(bytes);
            var chunkCount = (int) Math.ceil((double) bytes.length / chunkBytes);

            // Reply FIRST with the chunk plan so the submitter can subscribe.
            respond(replyTo, request, "ok", "training complete",
                chunkCount, (long) bytes.length, sha256);

            // Then publish chunks.
            for (int seq = 0; seq < chunkCount; seq++) {
                var off = seq * chunkBytes;
                var len = Math.min(chunkBytes, bytes.length - off);
                var slice = new byte[len];
                System.arraycopy(bytes, off, slice, 0, len);
                var chunk = new PeerTrainingProtocol.AdapterChunk(
                    request.requestId(), seq, slice);
                var chunkSubject = PeerTrainingProtocol.adapterChunkSubject(
                    localNodeId, request.requestId(), seq);
                transport.publish(chunkSubject, PeerTrainingProtocol.encode(chunk));
            }

            log.info("Peer training: sent {} chunks ({} bytes, sha256 {}...) "
                    + "for request {}",
                chunkCount, bytes.length, sha256.substring(0, 12),
                request.requestId());

        } catch (Throwable e) {
            log.error("Peer training crashed for request {}: {}",
                request.requestId(), e.getMessage(), e);
            respond(replyTo, request, "fail",
                "exception: " + e.getMessage(), 0, 0L, null);
        }
    }

    private void respond(
            String replyTo,
            PeerTrainingProtocol.Request request,
            String status, String detail,
            int chunkCount, long bytes, String sha256) {
        var response = new PeerTrainingProtocol.Response(
            request.requestId(),
            status, detail,
            chunkCount > 0 ? chunkCount : null,
            bytes > 0 ? bytes : null,
            sha256);
        transport.publish(replyTo, PeerTrainingProtocol.encode(response));
    }

    /** Find the GGUF file inside an adapter directory. Returns null if absent. */
    private static Path findAdapterGguf(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(".gguf"))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
