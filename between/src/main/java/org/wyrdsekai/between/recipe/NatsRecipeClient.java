package org.wyrdsekai.between.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cross-zone recipe-borrow client over the NATS relay (, option b).
 * Asks a remote zone to run a heavy recipe and resolves with its
 * {@link NatsRecipeProtocol.Response}.
 *
 * <p>Thread-safe; reuses the shared {@link RelaySessionTransport} and tracks one
 * pending result subscription per outstanding request. Mirrors
 * {@link org.wyrdsekai.between.inference.NatsInferenceClient} (subscribe before
 * publish; single-shot result; per-request timeout).</p>
 */
public final class NatsRecipeClient {

    private static final Logger log = LoggerFactory.getLogger(NatsRecipeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Generous default: heavy recipes can run for hours. The borrower may override. */
    private static final long DEFAULT_TIMEOUT_SEC = 24 * 3600;

    private final RelaySessionTransport transport;
    private final long timeoutSec;
    private final ScheduledExecutorService timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "nats-recipe-timeout");
            t.setDaemon(true);
            return t;
        });

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public NatsRecipeClient(RelaySessionTransport transport) {
        this(transport, DEFAULT_TIMEOUT_SEC);
    }

    public NatsRecipeClient(RelaySessionTransport transport, long timeoutSec) {
        this.transport = transport;
        this.timeoutSec = timeoutSec;
    }

    /** Build a borrow request for {@code recipeName}. */
    public static NatsRecipeProtocol.Request build(String sourceZone, String agentDid,
                                                   String recipeName, Map<String, Object> params,
                                                   String requisitesNote) {
        return new NatsRecipeProtocol.Request(
            UUID.randomUUID().toString(), sourceZone, agentDid,
            recipeName, params == null ? Map.of() : params, requisitesNote);
    }

    /** Ask {@code targetZone} to run the recipe. Resolves with the lender's response. */
    public CompletableFuture<NatsRecipeProtocol.Response> borrow(String targetZone,
                                                                NatsRecipeProtocol.Request req) {
        if (transport == null || !transport.isConnected()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("NATS relay transport not connected"));
        }
        var requestId = req.requestId() != null ? req.requestId() : UUID.randomUUID().toString();
        var finalReq = requestId.equals(req.requestId())
            ? req
            : new NatsRecipeProtocol.Request(requestId, req.sourceZone(), req.agentDid(),
                req.recipeName(), req.params(), req.requisitesNote());

        var future = new CompletableFuture<NatsRecipeProtocol.Response>();
        var p = new Pending(requestId, future);
        pending.put(requestId, p);

        // Subscribe BEFORE publishing to avoid losing a fast lender's reply.
        var resultSubject = NatsRecipeProtocol.resultSubject(requestId);
        p.subscription = transport.subscribe(resultSubject, data -> onResult(requestId, data));

        p.timeoutHandle = timeoutScheduler.schedule(() -> {
            var q = pending.remove(requestId);
            if (q != null) {
                q.cleanup(transport);
                q.future.completeExceptionally(new RuntimeException(
                    "Cross-zone recipe borrow timed out after " + timeoutSec + "s"));
            }
        }, timeoutSec, TimeUnit.SECONDS);

        try {
            var payload = MAPPER.writeValueAsBytes(finalReq);
            transport.publish(NatsRecipeProtocol.runSubject(targetZone), payload);
            log.info("Cross-zone recipe borrow: requestId={} '{}' → zone '{}' (agent={})",
                requestId, req.recipeName(), targetZone, req.agentDid());
        } catch (Exception e) {
            pending.remove(requestId);
            p.cleanup(transport);
            future.completeExceptionally(e);
        }
        return future;
    }

    private void onResult(String requestId, byte[] data) {
        var p = pending.remove(requestId);
        if (p == null) return; // stale/duplicate
        p.cleanup(transport);
        try {
            var resp = MAPPER.readValue(data, NatsRecipeProtocol.Response.class);
            p.future.complete(resp);
        } catch (Exception e) {
            p.future.completeExceptionally(e);
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

    private static final class Pending {
        final String requestId;
        final CompletableFuture<NatsRecipeProtocol.Response> future;
        volatile Object subscription;
        volatile ScheduledFuture<?> timeoutHandle;

        Pending(String requestId, CompletableFuture<NatsRecipeProtocol.Response> future) {
            this.requestId = requestId;
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
