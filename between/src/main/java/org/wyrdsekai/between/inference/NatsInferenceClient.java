package org.wyrdsekai.between.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Cross-zone inference client over NATS relay. Requests a completion from a
 * remote zone's inference provider; receives the response as a stream of
 * {@link NatsInferenceProtocol.StreamChunk} messages.
 *
 * <p>Thread-safe. Reuses the shared {@link RelaySessionTransport} (one per zone)
 * and maintains per-request stream subscriptions.</p>
 *
 * <p>For streaming use, attach a {@code tokenConsumer} via
 * {@link #requestStreaming(String, NatsInferenceProtocol.Request, Consumer)}.
 * The future completes with the full text once the provider signals {@code done}.</p>
 */
public final class NatsInferenceClient {

    private static final Logger log = LoggerFactory.getLogger(NatsInferenceClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Per-dispatch NATS req/reply timeout. Derived from
     * {@code WYRDSEKAI_INFERENCE_TIMEOUT} (seconds, default 120) so it stays
     * aligned with the rest of the inference stack — notably below the
     * companion watchdog {@code THINKING_TIMEOUT = WYRDSEKAI_INFERENCE_TIMEOUT
     * + 30}. Keeping the NATS timeout strictly under the watchdog guarantees a
     * dead borrowed-9B request fails here (triggering router fallback to local
     * 4B) before the companion gives up on the turn. See task #36. Malformed
     * env falls back to 120. (Static: the env is fixed for the process
     * lifetime; the companion reads the same env the same way.)
     */
    private static final long DEFAULT_TIMEOUT_SEC =
        parseTimeoutSec(System.getenv().get("WYRDSEKAI_INFERENCE_TIMEOUT"));

    /**
     * Parse the {@code WYRDSEKAI_INFERENCE_TIMEOUT} value (seconds). Falls back
     * to 120 when unset, blank, or malformed. Package-private + pure so the
     * derivation is unit-testable without mutating the process environment.
     */
    static long parseTimeoutSec(String raw) {
        if (raw == null || raw.isBlank()) return 120;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 120;
        }
    }

    private final RelaySessionTransport transport;
    private final ScheduledExecutorService timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "nats-inf-timeout");
            t.setDaemon(true);
            return t;
        });

    /** One entry per outstanding request; accumulates tokens and completes the future. */
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();

    public NatsInferenceClient(RelaySessionTransport transport) {
        this.transport = transport;
    }

    /** Non-streaming request. Returns the full response text on done. */
    public CompletableFuture<Completion> request(String targetZone,
                                                  NatsInferenceProtocol.Request req) {
        return requestStreaming(targetZone, req, null);
    }

    /**
     * Streaming request. {@code tokenConsumer} is called for each per-token chunk as it arrives
     * (may be null for accumulation-only mode). The future resolves once the stream terminates.
     */
    public CompletableFuture<Completion> requestStreaming(String targetZone,
                                                           NatsInferenceProtocol.Request req,
                                                           Consumer<String> tokenConsumer) {
        if (transport == null || !transport.isConnected()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("NATS relay transport not connected"));
        }

        // Ensure streamId matches what the provider will publish on.
        var streamId = req.streamId() != null ? req.streamId() : UUID.randomUUID().toString();
        var finalReq = streamId.equals(req.streamId())
            ? req
            : new NatsInferenceProtocol.Request(streamId, req.sourceZone(), req.agentId(),
                req.model(), req.messages(), req.maxTokens(), req.temperature(), req.stream(),
                req.sourceNode());

        var future = new CompletableFuture<Completion>();
        var pendingReq = new PendingRequest(streamId, tokenConsumer, future);
        pending.put(streamId, pendingReq);

        // Subscribe BEFORE publishing to avoid race.
        var streamSubject = NatsInferenceProtocol.streamSubject(streamId);
        var subscription = transport.subscribe(streamSubject, data -> onChunk(streamId, data));
        pendingReq.subscription = subscription;

        // Timeout
        var timeout = timeoutScheduler.schedule(() -> {
            var p = pending.remove(streamId);
            if (p != null) {
                p.cleanup(transport);
                p.future.completeExceptionally(new RuntimeException(
                    "NATS inference request timed out after " + DEFAULT_TIMEOUT_SEC + "s"));
            }
        }, DEFAULT_TIMEOUT_SEC, TimeUnit.SECONDS);
        pendingReq.timeoutHandle = timeout;

        // Publish request — with a pre-check on payload size against the
        // NATS server's advertised max_payload. The 2026-04-27 test-node
        // incident hammered the wire ~10×/sec with a 64MB serialized
        // request that the server was always going to reject (default
        // max_payload = 1 MiB). Pre-checking lets us skip the Jackson
        // serialize → publish → server-reject roundtrip and surface a
        // typed error to the caller (InferenceRouter, which now
        // recognises "payload size exceed" as a permanent error).
        //
        // We do NOT chop or compress the payload — that would be lossy
        // and hide the real upstream issue (a too-large prompt). Instead
        // the caller decides what to do (retrieval, bunshin map-reduce,
        // apologize). The check is best-effort: if maxPayload() returns
        // -1 (unknown), we publish and let the wire enforce as before.
        try {
            var payload = MAPPER.writeValueAsBytes(finalReq);
            var maxPayload = transport.maxPayload();
            if (maxPayload > 0 && payload.length > maxPayload) {
                pending.remove(streamId);
                pendingReq.cleanup(transport);
                future.completeExceptionally(new IllegalArgumentException(
                    "Message payload size exceed server configuration "
                        + payload.length + " vs " + maxPayload));
                return future;
            }
            transport.publish(NatsInferenceProtocol.requestSubject(targetZone), payload);
            log.info("NATS inference request: streamId={} → zone '{}' (model={}, agent={}, bytes={})",
                streamId, targetZone, req.model(), req.agentId(), payload.length);
        } catch (Exception e) {
            pending.remove(streamId);
            pendingReq.cleanup(transport);
            future.completeExceptionally(e);
        }

        return future;
    }

    private void onChunk(String streamId, byte[] data) {
        var pendingReq = pending.get(streamId);
        if (pendingReq == null) return;  // stale/late chunk, ignore

        try {
            var chunk = MAPPER.readValue(data, NatsInferenceProtocol.StreamChunk.class);
            if (chunk.token() != null && !chunk.token().isEmpty()) {
                pendingReq.accumulator.append(chunk.token());
                if (pendingReq.tokenConsumer != null) {
                    try { pendingReq.tokenConsumer.accept(chunk.token()); }
                    catch (Exception ignored) {}
                }
            }
            if (chunk.done()) {
                pending.remove(streamId);
                pendingReq.cleanup(transport);
                if (chunk.error() != null) {
                    pendingReq.future.completeExceptionally(
                        new RuntimeException("Remote inference error: " + chunk.error()));
                } else {
                    var text = chunk.fullContent() != null
                        ? chunk.fullContent()
                        : pendingReq.accumulator.toString();
                    pendingReq.future.complete(new Completion(text,
                        chunk.promptTokens(), chunk.completionTokens(), chunk.finishReason()));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse inference stream chunk for {}: {}",
                streamId, e.getMessage());
        }
    }

    public void close() {
        timeoutScheduler.shutdownNow();
        for (var p : pending.values()) {
            p.cleanup(transport);
            p.future.cancel(true);
        }
        pending.clear();
    }

    /** Completed response. */
    public record Completion(String text, Integer promptTokens, Integer completionTokens,
                             String finishReason) {}

    /** Build a simple request from a system/user prompt pair. */
    public static NatsInferenceProtocol.Request build(
            String sourceZone, String agentId, String model,
            String systemPrompt, String userMessage,
            Integer maxTokens, Double temperature, boolean stream) {
        var messages = new ArrayList<NatsInferenceProtocol.Message>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new NatsInferenceProtocol.Message("system", systemPrompt));
        }
        messages.add(new NatsInferenceProtocol.Message("user", userMessage));
        return new NatsInferenceProtocol.Request(
            UUID.randomUUID().toString(), sourceZone, agentId,
            model, messages, maxTokens, temperature, stream);
    }

    private static final class PendingRequest {
        final String streamId;
        final Consumer<String> tokenConsumer;
        final CompletableFuture<Completion> future;
        final StringBuilder accumulator = new StringBuilder();
        volatile Object subscription;
        volatile ScheduledFuture<?> timeoutHandle;

        PendingRequest(String streamId, Consumer<String> tokenConsumer,
                       CompletableFuture<Completion> future) {
            this.streamId = streamId;
            this.tokenConsumer = tokenConsumer;
            this.future = future;
        }

        void cleanup(RelaySessionTransport transport) {
            if (timeoutHandle != null) timeoutHandle.cancel(false);
            if (subscription != null && transport != null) {
                transport.closeDispatcherObj(subscription);
            }
        }
    }
}
