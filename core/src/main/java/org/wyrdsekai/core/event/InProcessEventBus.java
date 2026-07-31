package org.wyrdsekai.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentEvent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Default in-process event bus implementation.
 *
 * <p>Wraps direct pub/sub with filter-based routing. This runs alongside
 * {@link org.wyrdsekai.core.agent.AgentEventStream} — the stream handles
 * agent-specific delivery (rate-limited, bounded queues), while this bus
 * handles plugin delivery (webhooks, external integrations).</p>
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}.</p>
 */
public class InProcessEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private record Subscriber(Predicate<AgentEvent> filter, Consumer<AgentEvent> handler) {}

    private final ConcurrentHashMap<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    /** Global singleton instance. */
    private static volatile InProcessEventBus instance;

    /** Initialize global instance. Called by Main.java at startup. */
    public static InProcessEventBus init() {
        instance = new InProcessEventBus();
        return instance;
    }

    /** Get global instance (null if not initialized). */
    public static InProcessEventBus get() {
        return instance;
    }

    @Override
    public void publish(AgentEvent event) {
        for (var entry : subscribers.entrySet()) {
            var sub = entry.getValue();
            try {
                if (sub.filter() == null || sub.filter().test(event)) {
                    sub.handler().accept(event);
                }
            } catch (Exception e) {
                log.warn("EventBus subscriber '{}' threw on event {}: {}",
                    entry.getKey(), event.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void subscribe(String id, Predicate<AgentEvent> filter, Consumer<AgentEvent> handler) {
        subscribers.put(id, new Subscriber(filter, handler));
        log.debug("EventBus: subscriber '{}' added (total: {})", id, subscribers.size());
    }

    @Override
    public void unsubscribe(String id) {
        if (subscribers.remove(id) != null) {
            log.debug("EventBus: subscriber '{}' removed (total: {})", id, subscribers.size());
        }
    }

    @Override
    public int subscriberCount() {
        return subscribers.size();
    }
}
