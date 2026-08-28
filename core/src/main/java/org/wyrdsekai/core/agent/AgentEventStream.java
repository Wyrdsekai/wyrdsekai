package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.event.InProcessEventBus;
import org.wyrdsekai.core.resilience.ResilienceConfig;
import org.wyrdsekai.core.resilience.TokenBucketRateLimiter;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Singleton publish/subscribe hub for agent event perception.
 *
 * Agents subscribe to receive {@link AgentEvent}s from outside their current
 * room -- zone broadcasts, system events, and adjacent room activity.
 * This gives agents awareness of the wider world without being in every room.
 *
 * <p>Each subscriber is wrapped in a {@link RateLimitedSubscriber} that uses a
 * bounded queue and rate limiter. Critical events (HEALTH_ALERT, AgentMessage)
 * bypass rate limiting. Ambient events are dropped when the rate limit is
 * exceeded or the queue is full.</p>
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}. Initialized by Main.java
 * at startup. {@link #get()} returns null before {@link #init()} is called.</p>
 */
public class AgentEventStream {

    private static final Logger log = LoggerFactory.getLogger(AgentEventStream.class);

    /** Fallback rate limit if ResilienceConfig is not set. See {@link ResilienceConfig#eventsPerSecondPerAgent()}. */
    private static final double DEFAULT_EVENTS_PER_SECOND = 10.0;
    /** Fallback queue capacity if ResilienceConfig is not set. See {@link ResilienceConfig#eventQueueCapacity()}. */
    private static final int DEFAULT_QUEUE_CAPACITY = 100;

    /** Global instance -- initialized by Main.java. */
    private static volatile AgentEventStream instance;

    /** agentId -> rate-limited subscriber */
    private final ConcurrentHashMap<String, RateLimitedSubscriber> subscribers = new ConcurrentHashMap<>();

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() {
        instance = new AgentEventStream();
    }

    /** Get the global instance (null if not initialized). */
    public static AgentEventStream get() {
        return instance;
    }

    /**
     * Subscribe an agent to receive events.
     *
     * @param agentId  the agent's entity ID
     * @param listener callback invoked for each event
     */
    public void subscribe(String agentId, Consumer<AgentEvent> listener) {
        var rc = ResilienceConfig.get();
        var prev = subscribers.put(agentId,
            new RateLimitedSubscriber(agentId, listener,
                rc.eventsPerSecondPerAgent(), rc.eventQueueCapacity()));
        if (prev != null) {
            prev.shutdown();
        }
    }

    /**
     * Unsubscribe an agent.
     *
     * @param agentId the agent's entity ID
     */
    public void unsubscribe(String agentId) {
        var sub = subscribers.remove(agentId);
        if (sub != null) {
            sub.shutdown();
        }
    }

    /**
     * Publish a zone service broadcast to all subscribers.
     *
     * @param namespace the zone service namespace (e.g. "codezaiku", "iot")
     * @param roomId    the originating room
     * @param message   the S2C message payload
     */
    public void publishZoneBroadcast(String namespace, String roomId, S2CMessage message) {
        var event = new AgentEvent.ZoneBroadcast(namespace, roomId, message, Instant.now());
        deliver(event);
    }

    /**
     * Publish a system-level event to all subscribers.
     *
     * @param type   the system event type
     * @param source identifier for the source (e.g. node ID, backend name)
     * @param detail human-readable detail
     */
    public void publishSystemEvent(AgentEvent.SystemEventType type, String source, String detail) {
        var event = new AgentEvent.SystemEvent(type, source, detail, Instant.now());
        deliver(event);
    }

    /**
     * Publish adjacent room activity to all subscribers.
     *
     * @param sourceRoomId   the neighboring room's ID
     * @param sourceRoomName the neighboring room's display name
     * @param type           what kind of activity
     * @param entityCount    how many entities involved
     */
    public void publishAdjacentActivity(String sourceRoomId, String sourceRoomName,
                                        AgentEvent.ActivityType type, int entityCount) {
        var event = new AgentEvent.AdjacentActivity(sourceRoomId, sourceRoomName,
                type, entityCount, Instant.now());
        deliver(event);
    }

    /**
     * Publish a direct message from one agent to another.
     * Unlike zone/system/adjacent events, this is targeted delivery —
     * only the specified recipient receives the message.
     *
     * @param fromId   the sender agent's entity ID
     * @param fromName the sender agent's display name
     * @param toId     the target agent's entity ID
     * @param message  the message text
     * @return true if the target agent was found and delivery was attempted
     */
    public boolean publishAgentMessage(String fromId, String fromName,
                                       String toId, String message) {
        return publishAgentMessage(fromId, fromName, toId, message, null);
    }

    /**
     * Locale-aware overload — carries the sender's UI locale through to the
     * companion so the synthesized Said event is correctly tagged for the
     * translate-route-translate hop. Pass null when unknown.
     */
    public boolean publishAgentMessage(String fromId, String fromName,
                                       String toId, String message,
                                       String senderLocale) {
        var sub = subscribers.get(toId);
        if (sub == null) {
            log.debug("AgentEventStream: no subscriber for target agent '{}'", toId);
            return false;
        }
        var event = new AgentEvent.AgentMessage(
            fromId, fromName, toId, message, senderLocale, Instant.now());
        // #32 item 4 (NEVER-SILENT): report the REAL enqueue result. deliver()
        // used to be void, so a queue-full drop still returned true here and
        // the sender's client printed "[to X] …" for a message no one would
        // ever see. Mirrors the honest-boolean contract the player deliverer
        // already has (see CrossZoneTellService javadoc).
        boolean queued = sub.deliver(event);
        if (!queued) {
            log.warn("AgentEventStream: tell from '{}' to '{}' DROPPED at the subscriber "
                + "queue — message: {}", fromName, toId,
                message == null ? "" : message.substring(0, Math.min(80, message.length())));
        }
        return queued;
    }

    /**
     * Publish Oracle predictions arrival to all subscribers.
     * Agents use this to spike Alertness drive for proactive behavior.
     */
    public void publishOraclePredictions(String userId, int count,
                                          double maxConfidence, boolean hasActionable) {
        deliver(new AgentEvent.OraclePredictionsArrived(
            userId, count, maxConfidence, hasActionable, Instant.now()));
    }

    /**
     * Publish an abort signal to all agents in a room.
     * Used when a human issues "abort"/"stop"/"cancel" command.
     *
     * @param fromId   the human player's entity ID
     * @param fromName the human player's display name
     * @param roomId   the room where the abort was issued
     */
    public void publishAbort(String fromId, String fromName, String roomId) {
        var event = new AgentEvent.AbortSignal(fromId, fromName, roomId, Instant.now());
        deliver(event);
    }

    /** Number of current subscribers. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /** Get dropped event count for a specific subscriber (for metrics). */
    public long droppedCount(String agentId) {
        var sub = subscribers.get(agentId);
        return sub != null ? sub.getDroppedCount() : 0;
    }

    /** Total dropped events across all subscribers. */
    public long totalDroppedCount() {
        return subscribers.values().stream()
            .mapToLong(RateLimitedSubscriber::getDroppedCount)
            .sum();
    }

    /** Deliver an event to all subscribers via their rate-limited wrappers. */
    private void deliver(AgentEvent event) {
        for (var sub : subscribers.values()) {
            sub.deliver(event);
        }
        // Also forward to plugin event bus (webhooks, external integrations)
        var pluginBus = InProcessEventBus.get();
        if (pluginBus != null) {
            pluginBus.publish(event);
        }
    }

    /**
     * Rate-limited, bounded-queue subscriber wrapper.
     * Critical events bypass the rate limiter. When the queue is full,
     * the oldest event is dropped to make room.
     */
    static class RateLimitedSubscriber {
        private final String agentId;
        private final Consumer<AgentEvent> delegate;
        private final TokenBucketRateLimiter limiter;
        private final ArrayBlockingQueue<AgentEvent> queue;
        private final Thread drainThread;
        private final AtomicLong droppedCount = new AtomicLong(0);
        private volatile boolean running = true;

        RateLimitedSubscriber(String agentId, Consumer<AgentEvent> delegate,
                              double eventsPerSecond, int queueCapacity) {
            this.agentId = agentId;
            this.delegate = delegate;
            this.limiter = new TokenBucketRateLimiter(eventsPerSecond, eventsPerSecond * 2);
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.drainThread = new Thread(this::drainLoop, "event-drain-" + agentId);
            this.drainThread.setDaemon(true);
            this.drainThread.start();
        }

        /**
         * Enqueue an event for the drain loop. Returns whether THIS event was
         * accepted. #32 item 4: drops used to be counter-only — a direct tell
         * could vanish with zero logging while the sender was told it landed.
         * Every drop is now logged (WARN for critical events, DEBUG for
         * ambient), and callers get an honest boolean.
         */
        boolean deliver(AgentEvent event) {
            // Critical events bypass rate limiter
            if (isCritical(event) || limiter.tryAcquire()) {
                if (!queue.offer(event)) {
                    // Queue full — drop oldest to make room
                    var evicted = queue.poll();
                    if (evicted != null) {
                        droppedCount.incrementAndGet();
                        if (isCritical(evicted)) {
                            log.warn("AgentEventStream: subscriber '{}' queue full — evicted a "
                                + "CRITICAL event ({}) to admit a newer one", agentId,
                                evicted.getClass().getSimpleName());
                        } else {
                            log.debug("AgentEventStream: subscriber '{}' queue full — evicted "
                                + "oldest {}", agentId, evicted.getClass().getSimpleName());
                        }
                    }
                    if (!queue.offer(event)) {
                        droppedCount.incrementAndGet();
                        if (isCritical(event)) {
                            log.warn("AgentEventStream: subscriber '{}' queue still full — "
                                + "DROPPED critical {}", agentId,
                                event.getClass().getSimpleName());
                        }
                        return false;
                    }
                }
                return true;
            }
            // Rate limited — drop (ambient only; critical events bypass the limiter)
            droppedCount.incrementAndGet();
            log.debug("AgentEventStream: subscriber '{}' rate-limited — dropped {}",
                agentId, event.getClass().getSimpleName());
            return false;
        }

        long getDroppedCount() {
            return droppedCount.get();
        }

        void shutdown() {
            running = false;
            drainThread.interrupt();
        }

        private void drainLoop() {
            while (running) {
                try {
                    var event = queue.take();
                    delegate.accept(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("AgentEventStream: listener for {} threw: {}",
                        agentId, e.getMessage());
                }
            }
            // Drain remaining
            AgentEvent remaining;
            while ((remaining = queue.poll()) != null) {
                try {
                    delegate.accept(remaining);
                } catch (Exception e) {
                    // best effort
                }
            }
        }

        private static boolean isCritical(AgentEvent event) {
            return switch (event) {
                case AgentEvent.SystemEvent se ->
                    se.type() == AgentEvent.SystemEventType.HEALTH_ALERT;
                case AgentEvent.AgentMessage _ -> true; // direct messages are always critical
                // #33 re-audit: a human abort/stop/cancel is a control event — it must
                // NEVER be silently dropped at DEBUG under the token-bucket alongside
                // ambient chatter. Bypass the rate limiter so "stop" always lands; the
                // only remaining drop path is queue-full, which logs WARN below.
                case AgentEvent.AbortSignal _ -> true;
                default -> false;
            };
        }
    }
}
