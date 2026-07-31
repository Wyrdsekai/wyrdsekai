package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.CrossZonePeekService;
import org.wyrdsekai.core.naming.FederationSubjects;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Both sides of cross-zone {@code world.peek("zone.room")}
 * over the same relay pub/sub transport the cross-zone tell path uses.
 *
 * <p>Definitive re-audit fix (#33-4): {@link CrossZonePeekService} shipped with
 * only a test-wired {@code setCaller} and no responder at all — a two-sided gap
 * where {@code world.peek} across zones could never resolve in production. This
 * bridge closes both sides:</p>
 * <ul>
 *   <li><b>Responder</b> ({@link #startResponder}) subscribes
 *       {@code federation.{myZone}.peek}, resolves the room snapshot from the
 *       local {@link RoomRegistry}, renders it with
 *       {@link CrossZonePeekService#renderSnapshot}, and publishes the reply on
 *       the requester's {@code federation.{sourceZone}.peek_reply}.</li>
 *   <li><b>Caller</b> (this class implements {@link CrossZonePeekService.Caller})
 *       subscribes its own {@code federation.{myZone}.peek_reply} once,
 *       publishes a request tagged with a UUID {@code requestId}, and completes
 *       the pending future when the matching reply lands.</li>
 * </ul>
 *
 * <p>Request/reply is correlated by {@code requestId} over two fixed subjects
 * (one per direction, per zone) rather than a per-request inbox, so exactly two
 * subscriptions are needed regardless of peek volume. Pending futures are
 * swept after {@link #REQUEST_TTL} so a lost reply can never leak a future —
 * {@link CrossZonePeekService#peek} already treats a null/timeout as
 * "unreachable".</p>
 */
public final class CrossZonePeekBridge implements CrossZonePeekService.Caller {

    private static final Logger log = LoggerFactory.getLogger(CrossZonePeekBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration ROOM_ASK_TIMEOUT = Duration.ofMillis(900);
    /** Slightly longer than CrossZonePeekService's 1s peek timeout. */
    private static final Duration REQUEST_TTL = Duration.ofMillis(1500);

    private final String localZoneId;
    private final BiConsumer<String, byte[]> publish;
    private final BiConsumer<String, Consumer<byte[]>> subscribe;

    /** requestId → pending caller future. */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pending
        = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cross-zone-peek-sweeper");
            t.setDaemon(true);
            return t;
        });

    public CrossZonePeekBridge(String localZoneId,
                               BiConsumer<String, byte[]> publish,
                               BiConsumer<String, Consumer<byte[]>> subscribe) {
        this.localZoneId = localZoneId;
        this.publish = publish;
        this.subscribe = subscribe;
    }

    /** Subscribe the responder side — call once during federation wiring. */
    public void startResponder() {
        subscribe.accept(FederationSubjects.peekRequest(localZoneId), this::onPeekRequest);
        log.info("CrossZonePeekBridge: responder listening on {}",
            FederationSubjects.peekRequest(localZoneId));
    }

    /** Subscribe the caller's reply inbox — call once during federation wiring. */
    public void startCaller() {
        subscribe.accept(FederationSubjects.peekReply(localZoneId), this::onPeekReply);
        log.info("CrossZonePeekBridge: caller reply inbox on {}",
            FederationSubjects.peekReply(localZoneId));
    }

    // ── Caller side (CrossZonePeekService.Caller) ─────────────────────

    @Override
    public CompletableFuture<Map<String, Object>> peek(String targetZone,
                                                        String sourceZone,
                                                        String roomAlias) {
        var requestId = UUID.randomUUID().toString();
        var future = new CompletableFuture<Map<String, Object>>();
        pending.put(requestId, future);
        // Guarantee the future never leaks: sweep after the TTL.
        sweeper.schedule(() -> {
            var stale = pending.remove(requestId);
            if (stale != null && !stale.isDone()) {
                stale.completeExceptionally(
                    new TimeoutException("cross-zone peek reply timed out"));
            }
        }, REQUEST_TTL.toMillis(), TimeUnit.MILLISECONDS);

        try {
            var req = MAPPER.createObjectNode();
            req.put("requestId", requestId);
            req.put("sourceZone", sourceZone != null ? sourceZone : localZoneId);
            req.put("roomAlias", roomAlias);
            publish.accept(FederationSubjects.peekRequest(targetZone),
                MAPPER.writeValueAsBytes(req));
        } catch (Exception e) {
            pending.remove(requestId);
            future.completeExceptionally(e);
        }
        return future;
    }

    private void onPeekReply(byte[] data) {
        try {
            JsonNode node = MAPPER.readTree(data);
            var requestId = node.path("requestId").asText(null);
            if (requestId == null) return;
            var future = pending.remove(requestId);
            if (future == null) return;  // already swept / duplicate
            var snapNode = node.get("snapshot");
            if (snapNode == null || snapNode.isNull()) {
                future.complete(null);   // denied / no such room
                return;
            }
            @SuppressWarnings("unchecked")
            var snapshot = (Map<String, Object>) MAPPER.convertValue(snapNode, Map.class);
            future.complete(snapshot);
        } catch (Exception e) {
            log.debug("CrossZonePeekBridge: dropped unparseable peek reply: {}", e.getMessage());
        }
    }

    // ── Responder side ────────────────────────────────────────────────

    private void onPeekRequest(byte[] data) {
        String requestId = null;
        String sourceZone = null;
        try {
            JsonNode node = MAPPER.readTree(data);
            requestId = node.path("requestId").asText(null);
            sourceZone = node.path("sourceZone").asText(null);
            var roomAlias = node.path("roomAlias").asText(null);
            if (requestId == null || sourceZone == null || roomAlias == null) {
                log.debug("CrossZonePeekBridge: dropping malformed peek request");
                return;
            }
            var snapshot = resolveSnapshot(roomAlias);
            reply(sourceZone, requestId, snapshot);
        } catch (Exception e) {
            log.debug("CrossZonePeekBridge: peek request handling failed: {}", e.getMessage());
            if (requestId != null && sourceZone != null) {
                reply(sourceZone, requestId, null);  // honest null, never a hang
            }
        }
    }

    /** Resolve a local room alias → rendered §8 snapshot map, or null. */
    private Map<String, Object> resolveSnapshot(String roomAlias) {
        var registry = RoomRegistry.get();
        if (registry == null) {
            log.warn("CrossZonePeekBridge: RoomRegistry not initialised — cannot serve peek");
            return null;
        }
        var roomId = registry.resolveRoomId(roomAlias);
        if (roomId == null) {
            log.info("CrossZonePeekBridge: no such room '{}' for cross-zone peek", roomAlias);
            return null;
        }
        try {
            RoomSnapshot snap = registry.<RoomSnapshot>askRoom(
                    roomId, RoomCommand.GetSnapshot::new, ROOM_ASK_TIMEOUT)
                .toCompletableFuture()
                .get(ROOM_ASK_TIMEOUT.toMillis() + 100, TimeUnit.MILLISECONDS);
            return CrossZonePeekService.renderSnapshot(snap);
        } catch (Exception e) {
            log.info("CrossZonePeekBridge: snapshot fetch for '{}' failed: {}",
                roomAlias, e.getMessage());
            return null;
        }
    }

    private void reply(String sourceZone, String requestId, Map<String, Object> snapshot) {
        try {
            ObjectNode reply = MAPPER.createObjectNode();
            reply.put("requestId", requestId);
            if (snapshot == null) {
                reply.putNull("snapshot");
            } else {
                reply.set("snapshot", MAPPER.valueToTree(snapshot));
            }
            publish.accept(FederationSubjects.peekReply(sourceZone),
                MAPPER.writeValueAsBytes(reply));
        } catch (Exception e) {
            log.debug("CrossZonePeekBridge: failed to publish peek reply: {}", e.getMessage());
        }
    }

    /** Release the sweeper thread. */
    public void close() {
        sweeper.shutdownNow();
    }
}
