package org.wyrdsekai.core.event;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.LocationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: verifies EventBus → plugin delivery and
 * AgentEventStream → InProcessEventBus forwarding path.
 */
class EventBusIntegrationTest {

    @Test
    void publish_reaches_all_filtered_subscribers() {
        var bus = new InProcessEventBus();

        var systemEvents = new ArrayList<AgentEvent>();
        var oracleEvents = new ArrayList<AgentEvent>();
        var allEvents = new ArrayList<AgentEvent>();

        bus.subscribe("system-only",
            e -> e instanceof AgentEvent.SystemEvent,
            systemEvents::add);
        bus.subscribe("oracle-only",
            e -> e instanceof AgentEvent.OraclePredictionsArrived,
            oracleEvents::add);
        bus.subscribe("all", null, allEvents::add);

        // Publish system event
        bus.publish(new AgentEvent.SystemEvent(
            AgentEvent.SystemEventType.NODE_JOINED, "node1", "test", Instant.now()));
        // Publish oracle event
        bus.publish(new AgentEvent.OraclePredictionsArrived(
            "user1", 3, 0.9, true, Instant.now()));

        assertEquals(1, systemEvents.size());
        assertEquals(1, oracleEvents.size());
        assertEquals(2, allEvents.size());
    }

    @Test
    void webhook_plugin_initializes_and_subscribes() {
        var bus = new InProcessEventBus();
        var webhook = new WebhookEventBus(
            "http://localhost:99999/hook",
            Set.of("system", "oracle_predictions"), null);
        webhook.initialize(bus);

        assertEquals(1, bus.subscriberCount());
    }

    @Test
    void webhook_event_type_names_cover_all_event_types() {
        // Ensure all AgentEvent subtypes have a mapping
        assertNotNull(WebhookEventBus.eventTypeName(
            new AgentEvent.ZoneBroadcast("ns", "r1", null, Instant.now())));
        assertNotNull(WebhookEventBus.eventTypeName(
            new AgentEvent.SystemEvent(AgentEvent.SystemEventType.NODE_JOINED,
                "s", "d", Instant.now())));
        assertNotNull(WebhookEventBus.eventTypeName(
            new AgentEvent.AdjacentActivity("r1", "Room",
                AgentEvent.ActivityType.SPEECH, 1, Instant.now())));
        assertNotNull(WebhookEventBus.eventTypeName(
            new AgentEvent.AgentMessage("a1", "A", "a2", "hi", Instant.now())));
        assertNotNull(WebhookEventBus.eventTypeName(
            new AgentEvent.LocationUpdate(35.0, 139.0, "Tokyo",
                LocationContext.LocationState.HOME, Instant.now())));
        assertNotNull(WebhookEventBus.eventTypeName(
            new AgentEvent.OraclePredictionsArrived("u1", 1, 0.5, false, Instant.now())));
    }

    @Test
    void plugin_loader_shutdown_is_safe() {
        var bus = new InProcessEventBus();
        var webhook = new WebhookEventBus("http://localhost:99999", null, null);
        webhook.initialize(bus);
        assertDoesNotThrow(() ->
            EventBusPluginLoader.shutdownAll(List.of(webhook)));
    }
}
