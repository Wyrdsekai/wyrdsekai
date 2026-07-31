package org.wyrdsekai.core.event;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentEvent;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WebhookEventBusTest {

    @Test
    void event_type_names_are_correct() {
        assertEquals("zone_broadcast", WebhookEventBus.eventTypeName(
            new AgentEvent.ZoneBroadcast("ns", "room1", null, Instant.now())));
        assertEquals("system", WebhookEventBus.eventTypeName(
            new AgentEvent.SystemEvent(AgentEvent.SystemEventType.NODE_JOINED,
                "src", "detail", Instant.now())));
        assertEquals("adjacent_activity", WebhookEventBus.eventTypeName(
            new AgentEvent.AdjacentActivity("r1", "Room 1",
                AgentEvent.ActivityType.SPEECH, 2, Instant.now())));
        assertEquals("agent_message", WebhookEventBus.eventTypeName(
            new AgentEvent.AgentMessage("a1", "Agent1", "a2", "hello", Instant.now())));
        assertEquals("oracle_predictions", WebhookEventBus.eventTypeName(
            new AgentEvent.OraclePredictionsArrived("u1", 3, 0.9, true, Instant.now())));
    }

    @Test
    void plugin_name_is_webhook() {
        var plugin = new WebhookEventBus("http://example.com", null, null);
        assertEquals("webhook", plugin.name());
    }

    @Test
    void initialize_adds_subscriber_to_bus() {
        var bus = new InProcessEventBus();
        var plugin = new WebhookEventBus("http://example.com/hook", null, null);

        assertEquals(0, bus.subscriberCount());
        plugin.initialize(bus);
        assertEquals(1, bus.subscriberCount());
    }
}
