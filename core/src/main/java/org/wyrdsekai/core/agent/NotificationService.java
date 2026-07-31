package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton service for delivering push notifications from agents to players.
 *
 * <p>Notifications flow through the existing WebSocket connection via
 * {@link S2CMessage.Notification}. The delivery callback is set by the server
 * layer (WyrdWebSocket) at startup. If no callback is set, notifications are
 * silently dropped (safe for testing and headless modes).</p>
 *
 * <p>Follows the same singleton pattern as {@link AgentEventStream}:
 * initialized by Main.java at startup, accessed via {@link #get()}.</p>
 *
 * @see org.wyrdsekai.common.protocol.S2CMessage.Notification
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Global instance -- initialized by Main.java. */
    private static volatile NotificationService instance;

    /** Callback to deliver notifications to player sessions: (targetDid, notification). */
    private volatile DeliveryCallback deliveryCallback;

    /**
     * Delivery callback wired by the server layer. Returns {@code true} only when
     * the notification actually reached at least one live session.
     *
     * <p>#33 re-audit (honest-over-silent): this used to be a {@link BiConsumer}
     * that discarded the deliverer's boolean, so {@link #notify} logged
     * "Notification sent" even when the target had no live session (e.g. an
     * SSH-only or disconnected player) and the message vanished. Mirrors the
     * honest-boolean contract of {@code CrossZoneTellService.PlayerDeliverer} /
     * {@code WyrdWebSocket.deliverToPlayer}.</p>
     */
    @FunctionalInterface
    public interface DeliveryCallback {
        /** @return true when at least one live session received the notification. */
        boolean deliver(String targetDid, S2CMessage.Notification notification);
    }

    /**
     * Callback to forward a notification to a traveling player in a remote zone:
     * (targetDid, destinationZoneId, notification). Returns true if forwarded via relay.
     * When null, notifications for traveling players fall through to deliveryCallback.
     */
    private volatile RemoteForwarder remoteForwarder;

    @FunctionalInterface
    public interface RemoteForwarder {
        boolean forward(String targetDid, String destinationZoneId, S2CMessage.Notification notification);
    }

    /** Recent delivery log for agent context building (bounded). */
    private final ConcurrentLinkedDeque<DeliveryRecord> recentDeliveries = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 50;

    /** Buffered notifications for traveling players whose remote delivery failed. */
    private final ConcurrentHashMap<String, List<S2CMessage.Notification>>
        buffered = new ConcurrentHashMap<>();
    private static final int MAX_BUFFERED_PER_PLAYER = 50;

    /** Record of a notification delivery for context/tracking. */
    public record DeliveryRecord(
        String targetDid,
        String fromAgentId,
        String message,
        String priority,
        Instant sentAt
    ) {}

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() {
        instance = new NotificationService();
    }

    /** Get the global instance (null if not initialized). */
    public static NotificationService get() {
        return instance;
    }

    /**
     * Set the delivery callback used to push notifications to player sessions.
     * Called by the server layer (WyrdWebSocket) once the WebSocket handler is ready.
     *
     * @param callback accepts (targetDid, notification) — targetDid may be "all";
     *                 returns true when a live session received it
     */
    public void setDeliveryCallback(DeliveryCallback callback) {
        this.deliveryCallback = callback;
        log.info("Notification delivery callback registered");
    }

    /**
     * Set the remote forwarder used to deliver notifications to traveling players.
     * If not set, notifications for TRAVELING targets fall through to local delivery
     * (which won't reach them, but is safe).
     */
    public void setRemoteForwarder(RemoteForwarder forwarder) {
        this.remoteForwarder = forwarder;
        log.info("Notification remote forwarder registered");
    }


    /**
     * Send a notification to a specific player.
     *
     * @param targetDid target player DID, or "steward" (resolved by caller), or "all"
     * @param message   the notification message text
     * @param priority  "ambient", "normal", or "critical"
     * @param fromAgentId the agent sending the notification
     */
    public void notify(String targetDid, String message, String priority, String fromAgentId) {
        if (message == null || message.isBlank()) {
            log.warn("Ignoring blank notification from agent '{}'", fromAgentId);
            return;
        }

        var validPriority = normalizePriority(priority);
        var notification = new S2CMessage.Notification(0, validPriority, fromAgentId, message);

        recordDelivery(targetDid, fromAgentId, message, validPriority);

        // Check if target's home zone differs from ours — forward to their home zone if so
        // Case 1: Home zone notifying a player who is traveling (TRAVELING state)
        // Case 2: Foreign zone notifying a visitor (home zone != local)
        var registry = EntityRegistry.get();
        if (registry != null && remoteForwarder != null && !"all".equals(targetDid)) {
            var localZoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");

            // Case 1: Local player is traveling elsewhere
            var presence = registry.presenceOf(targetDid);
            if (presence == EntityRegistry.PresenceState.TRAVELING) {
                var destZone = registry.travelDestinationOf(targetDid).orElse(null);
                if (destZone != null) {
                    var forwarded = remoteForwarder.forward(targetDid, destZone, notification);
                    if (forwarded) {
                        log.info("Notification forwarded to traveling {} at zone '{}': {}",
                            targetDid, destZone, truncate(message, 80));
                        return;
                    }
                    // Forwarding failed — buffer for delivery on return
                    bufferForPlayer(targetDid, notification);
                    log.info("Notification buffered for traveling {} (delivery failed): {}",
                        targetDid, truncate(message, 80));
                    return;
                }
            }

            // Case 2: Target is a visitor from another zone — forward to their home
            var homeZone = registry.homeZoneOf(targetDid).orElse(null);
            if (homeZone != null && !homeZone.equals(localZoneId)) {
                var forwarded = remoteForwarder.forward(targetDid, homeZone, notification);
                if (forwarded) {
                    log.info("Notification forwarded to visitor {} via home zone '{}': {}",
                        targetDid, homeZone, truncate(message, 80));
                    return;
                }
            }
        }

        // In-world delivery via WebSocket
        if (deliveryCallback != null) {
            var delivered = deliveryCallback.deliver(targetDid, notification);
            if (delivered) {
                log.info("Notification sent: from='{}' to='{}' priority={} message='{}'",
                    fromAgentId, targetDid, validPriority, truncate(message, 80));
            } else if ("all".equals(targetDid)) {
                // Broadcast reached nobody — no per-player mailbox to persist to.
                log.warn("Notification broadcast reached no live sessions: from='{}' message='{}'",
                    fromAgentId, truncate(message, 80));
            } else {
                // #33 re-audit (honest-over-silent): the target has no live session, so
                // buffer for later delivery (flushed on their next connect via
                // flushBuffered) and NEVER claim "sent" for a message that did not land.
                bufferForPlayer(targetDid, notification);
                log.warn("Notification not delivered to live session for {}, persisted for later: "
                    + "from='{}' priority={} message='{}'",
                    targetDid, fromAgentId, validPriority, truncate(message, 80));
            }
        } else {
            log.debug("No delivery callback set; in-world notification dropped: {}", truncate(message, 80));
        }

        // External delivery is handled by each CompanionActor's own channels.
        // See CompanionActor.fanOutExternal().
    }

    /**
     * Send a notification to all connected players.
     *
     * @param message     the notification message text
     * @param priority    "ambient", "normal", or "critical"
     * @param fromAgentId the agent sending the notification
     */
    public void notifyAll(String message, String priority, String fromAgentId) {
        notify("all", message, priority, fromAgentId);
    }

    /**
     * Get recent delivery records for an agent (for prompt context building).
     *
     * @param agentId the agent to query
     * @param limit   max number of records
     * @return recent deliveries from this agent, most recent first
     */
    public List<DeliveryRecord> recentForAgent(String agentId, int limit) {
        var result = new ArrayList<DeliveryRecord>();
        for (var record : recentDeliveries) {
            if (agentId.equals(record.fromAgentId())) {
                result.add(record);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    /** Buffer a notification for a traveling player (delivery failed). Bounded. */
    private void bufferForPlayer(String playerId, S2CMessage.Notification notification) {
        buffered.compute(playerId, (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            if (list.size() >= MAX_BUFFERED_PER_PLAYER) {
                list.remove(0);  // drop oldest
            }
            list.add(notification);
            return list;
        });
    }

    /**
     * Flush buffered notifications for a returning player.
     * Called when player's presence returns to PRESENT.
     * @return the flushed notifications (may be empty)
     */
    public List<S2CMessage.Notification> flushBuffered(String playerId) {
        var list = buffered.remove(playerId);
        if (list == null || list.isEmpty()) return List.of();

        // Deliver each via local callback
        if (deliveryCallback != null) {
            for (var n : list) {
                deliveryCallback.deliver(playerId, n);
            }
            log.info("Flushed {} buffered notifications to {}", list.size(), playerId);
        }
        return List.copyOf(list);
    }

    /** Count of currently buffered notifications for a player. */
    public int bufferedCountFor(String playerId) {
        var list = buffered.get(playerId);
        return list == null ? 0 : list.size();
    }

    private void recordDelivery(String targetDid, String fromAgentId,
                                 String message, String priority) {
        recentDeliveries.addFirst(new DeliveryRecord(
            targetDid, fromAgentId, message, priority, Instant.now()));
        while (recentDeliveries.size() > MAX_RECENT) {
            recentDeliveries.removeLast();
        }
    }

    private static String normalizePriority(String priority) {
        if (priority == null) return "normal";
        return switch (priority.toLowerCase()) {
            case "ambient", "normal", "critical" -> priority.toLowerCase();
            default -> "normal";
        };
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
