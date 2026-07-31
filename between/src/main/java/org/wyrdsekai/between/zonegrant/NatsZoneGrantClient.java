package org.wyrdsekai.between.zonegrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * #1184 — JOINER side of the multi-node zone-secret grant. A node that joins an existing
 * zone but holds no master asks the zone for it: publishes its X25519 grant <b>public</b> key and
 * resolves with the holder's {@link NatsZoneGrantProtocol.Response} (an opaque ECIES blob it can
 * unwrap with its X25519 <b>private</b> key). Mirrors {@code NatsRecipeClient} (subscribe before
 * publish; single-shot result; per-request timeout; reuses the shared {@link RelaySessionTransport}).
 */
public final class NatsZoneGrantClient {

    private static final Logger log = LoggerFactory.getLogger(NatsZoneGrantClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Grants are interactive (a holder is online) — keep this short; the joiner can retry. */
    private static final long DEFAULT_TIMEOUT_SEC = 30;

    private final RelaySessionTransport transport;
    private final long timeoutSec;
    private final ScheduledExecutorService timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "nats-zonegrant-timeout");
            t.setDaemon(true);
            return t;
        });

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public NatsZoneGrantClient(RelaySessionTransport transport) {
        this(transport, DEFAULT_TIMEOUT_SEC);
    }

    public NatsZoneGrantClient(RelaySessionTransport transport, long timeoutSec) {
        this.transport = transport;
        this.timeoutSec = timeoutSec;
    }

    /**
     * Ask the zone for its master. {@code myX25519Spki} is this node's X25519 grant public key
     * (X.509 SPKI, from {@code NodeIdentity.x25519PublicKeyBytes()}); the holder wraps the master to
     * it so only this node — holding the matching private key — can unwrap.
     */
    public CompletableFuture<NatsZoneGrantProtocol.Response> requestGrant(String zoneId,
                                                                         String myNodeId,
                                                                         byte[] myX25519Spki) {
        if (transport == null || !transport.isConnected()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("NATS relay transport not connected"));
        }
        var requestId = UUID.randomUUID().toString();
        var req = new NatsZoneGrantProtocol.Request(requestId, zoneId, myNodeId,
            Base64.getEncoder().encodeToString(myX25519Spki));

        var future = new CompletableFuture<NatsZoneGrantProtocol.Response>();
        var p = new Pending(requestId, future);
        pending.put(requestId, p);

        // Subscribe BEFORE publishing so we don't lose a fast holder's reply.
        p.subscription = transport.subscribe(
            NatsZoneGrantProtocol.resultSubject(requestId), data -> onResult(requestId, data));

        p.timeoutHandle = timeoutScheduler.schedule(() -> {
            var q = pending.remove(requestId);
            if (q != null) {
                q.cleanup(transport);
                q.future.completeExceptionally(new RuntimeException(
                    "Zone-secret grant request timed out after " + timeoutSec + "s "
                        + "(no holder of zone '" + zoneId + "' responded)"));
            }
        }, timeoutSec, TimeUnit.SECONDS);

        try {
            transport.publish(NatsZoneGrantProtocol.requestSubject(zoneId),
                MAPPER.writeValueAsBytes(req));
            log.info("Zone-secret grant request: requestId={} zone='{}' node={}",
                requestId, zoneId, myNodeId);
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
            p.future.complete(MAPPER.readValue(data, NatsZoneGrantProtocol.Response.class));
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
        final CompletableFuture<NatsZoneGrantProtocol.Response> future;
        volatile Object subscription;
        volatile ScheduledFuture<?> timeoutHandle;

        Pending(String requestId, CompletableFuture<NatsZoneGrantProtocol.Response> future) {
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
