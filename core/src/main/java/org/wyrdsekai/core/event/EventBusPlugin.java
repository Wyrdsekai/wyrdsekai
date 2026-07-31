package org.wyrdsekai.core.event;

/**
 * Plugin interface for external event bus integrations.
 *
 * <p>Plugins are loaded from {@code ~/.wyrdsekai/eventbus.json} at startup.
 * Each plugin receives the event bus and can subscribe to events and/or
 * publish events from external sources.</p>
 */
public interface EventBusPlugin {

    /** Plugin display name (e.g. "webhook", "matrix", "mqtt"). */
    String name();

    /**
     * Initialize the plugin with access to the event bus.
     * Subscribe to events, start background threads, etc.
     *
     * @param bus the event bus to subscribe to / publish to
     */
    void initialize(EventBus bus);

    /**
     * Shut down the plugin. Release resources, stop threads.
     */
    void shutdown();
}
