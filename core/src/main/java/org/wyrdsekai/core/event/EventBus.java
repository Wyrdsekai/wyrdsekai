package org.wyrdsekai.core.event;

import org.wyrdsekai.core.agent.AgentEvent;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Pluggable event bus interface for agent event distribution.
 *
 * <p>The default implementation wraps {@link org.wyrdsekai.core.agent.AgentEventStream}
 * (in-process pub/sub). External integrations (webhooks, Matrix, MQTT, Discord)
 * implement this interface to receive events and optionally inject events back.</p>
 *
 * <p>Thread-safe: implementations must be safe for concurrent publish/subscribe.</p>
 */
public interface EventBus {

    /**
     * Publish an event to all subscribers matching the event type.
     *
     * @param event the agent event to publish
     */
    void publish(AgentEvent event);

    /**
     * Subscribe to events matching a filter.
     *
     * @param id      unique subscriber ID (for unsubscribe)
     * @param filter  predicate to filter events (null = all events)
     * @param handler callback for matching events
     */
    void subscribe(String id, Predicate<AgentEvent> filter, Consumer<AgentEvent> handler);

    /**
     * Unsubscribe a previously registered handler.
     *
     * @param id the subscriber ID
     */
    void unsubscribe(String id);

    /**
     * Number of active subscribers.
     */
    int subscriberCount();
}
