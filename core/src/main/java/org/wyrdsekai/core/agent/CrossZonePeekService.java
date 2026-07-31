package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * / Phase 2b — cross-zone room snapshot lookup for
 * {@code world.peek("zone.room")}.
 *
 * <p>Sibling of {@link CrossZoneTellService} and
 * {@link org.wyrdsekai.core.familiar.CrossZoneCopyService}. Singleton; wired
 * after {@code RelaySessionTransport} connects so the {@link Caller}
 * functional interface can perform a NATS request/reply against the peer
 * zone. The concrete NATS wiring lives in the {@code between} module — core
 * only sees the functional caller, mirroring the
 * {@link org.wyrdsekai.core.inference.InferenceBackend.NatsRemote} pattern.</p>
 *
 * <p>Failure modes (per spec §8 task §D):
 * <ul>
 *   <li>Service not initialised → {@code null + log warn}</li>
 *   <li>Caller not wired (no relay) → {@code null + log warn}</li>
 *   <li>Same-zone target ("foo" without dot) → returns null; caller routes
 *       to local same-zone path. This service is cross-zone only.</li>
 *   <li>Timeout (&gt;1s) → {@code null + log warn}</li>
 *   <li>Auth/grant denial → {@code null + log warn}</li>
 *   <li>Caller throws → {@code null + log warn}</li>
 * </ul>
 */
public final class CrossZonePeekService {

    private static final Logger log = LoggerFactory.getLogger(CrossZonePeekService.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);

    private static volatile CrossZonePeekService instance;

    private final String localZoneId;
    private volatile Caller caller;

    public CrossZonePeekService(String localZoneId) {
        this.localZoneId = localZoneId;
    }

    public static void init(String localZoneId) {
        instance = new CrossZonePeekService(localZoneId);
    }

    public static CrossZonePeekService get() { return instance; }

    /** Test reset — clears the singleton so a fresh init() can run. */
    public static void resetForTests() { instance = null; }

    public String localZoneId() { return localZoneId; }

    /** Wired by the relay integration layer once NATS req/reply is available. */
    public void setCaller(Caller caller) { this.caller = caller; }

    /**
     * Attempt a cross-zone peek. Returns the rendered snapshot map (per spec §8
     * shape: {@code {name, description, exits[], entities[], items[]}}) or
     * {@code null} on any failure.
     *
     * <p>The {@code targetZone} must NOT equal the local zone — callers should
     * route same-zone peeks via the same-zone path. This is asserted as a fast
     * fail and logged at warn level (the right next step is an architectural
     * fix, not a retry).</p>
     */
    public Map<String, Object> peek(String targetZone, String roomAlias) {
        return peek(targetZone, roomAlias, DEFAULT_TIMEOUT);
    }

    /** Variant with explicit timeout for tests. */
    public Map<String, Object> peek(String targetZone, String roomAlias, Duration timeout) {
        if (targetZone == null || targetZone.isBlank()) {
            log.warn("CrossZonePeekService.peek: empty targetZone");
            return null;
        }
        if (roomAlias == null || roomAlias.isBlank()) {
            log.warn("CrossZonePeekService.peek: empty roomAlias");
            return null;
        }
        if (targetZone.equals(localZoneId)) {
            // Caller has misrouted — same zone should use the local path.
            log.warn("CrossZonePeekService.peek: targetZone '{}' equals local — caller bug",
                targetZone);
            return null;
        }
        var c = caller;
        if (c == null) {
            log.warn("world.peek: unreachable zone \"{}\" — relay caller not wired", targetZone);
            return null;
        }
        try {
            var fut = c.peek(targetZone, localZoneId, roomAlias);
            if (fut == null) {
                log.warn("world.peek: caller returned null future for zone \"{}\"", targetZone);
                return null;
            }
            var snap = fut.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (snap == null) {
                // Caller succeeded but the remote returned null — likely auth denial
                // or unknown room. Log at warn so soak surfaces this.
                log.warn("world.peek: remote returned null for {}@{} — not authorized "
                    + "or no such room", roomAlias, targetZone);
            }
            return snap;
        } catch (TimeoutException te) {
            log.warn("world.peek: timeout (>{} ms) for {}@{}",
                timeout.toMillis(), roomAlias, targetZone);
            return null;
        } catch (ExecutionException ee) {
            var cause = ee.getCause();
            if (cause instanceof SecurityException) {
                log.warn("world.peek: not authorized for room {}@{}", roomAlias, targetZone);
            } else {
                log.warn("world.peek: caller failed for {}@{}: {}",
                    roomAlias, targetZone,
                    cause != null ? cause.getMessage() : ee.getMessage());
            }
            return null;
        } catch (Exception e) {
            log.warn("world.peek: unreachable zone \"{}\" — {}", targetZone, e.getMessage());
            return null;
        }
    }

    /**
     * Functional interface for performing the actual cross-zone request/reply.
     * Implemented in the {@code between} module using NATS request semantics.
     * The returned future completes with the peeked snapshot map (spec §8 shape)
     * or null when the remote rejects.
     */
    @FunctionalInterface
    public interface Caller {
        CompletableFuture<Map<String, Object>> peek(String targetZone,
                                                      String sourceZone,
                                                      String roomAlias);
    }

    /**
     * Convert a {@link RoomSnapshot} to the spec §8 peek shape:
     * {@code {name, description, exits[], entities[], items[]}}.
     *
     * <p>Canonical renderer shared by the local-zone peek path (CompanionActor)
     * and the cross-zone responder (the {@code between} module's peek bridge,
     * added in the definitive re-audit #33-4) so both sides speak an identical
     * wire shape. Returns {@code null} for a null snapshot.</p>
     */
    public static Map<String, Object> renderSnapshot(RoomSnapshot snap) {
        if (snap == null) return null;
        var out = new LinkedHashMap<String, Object>();
        out.put("name", snap.name() != null ? snap.name() : "");
        out.put("description", snap.description() != null ? snap.description() : "");
        var exits = new ArrayList<String>();
        if (snap.exits() != null) {
            for (var e : snap.exits()) {
                if (e != null && e.direction() != null) exits.add(e.direction());
            }
        }
        out.put("exits", exits);
        var entities = new ArrayList<Map<String, Object>>();
        if (snap.entities() != null) {
            for (var e : snap.entities()) {
                if (e == null) continue;
                var em = new LinkedHashMap<String, Object>();
                em.put("alias", e.name() != null ? e.name() : "");
                em.put("kind", e.type() != null ? e.type() : "entity");
                entities.add(em);
            }
        }
        out.put("entities", entities);
        var items = new ArrayList<Map<String, Object>>();
        if (snap.objects() != null) {
            for (var o : snap.objects()) {
                if (o == null) continue;
                var im = new LinkedHashMap<String, Object>();
                im.put("alias", o.name() != null ? o.name() : "");
                im.put("kind", o.takeable() ? "item" : "fixture");
                items.add(im);
            }
        }
        out.put("items", items);
        return out;
    }
}
