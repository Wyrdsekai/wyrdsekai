package org.wyrdsekai.core.event;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InProcessEventBusTest {

    private static AgentEvent systemEvent(String detail) {
        return new AgentEvent.SystemEvent(
            AgentEvent.SystemEventType.NODE_JOINED, "test", detail, Instant.now());
    }

    @Test
    void publish_delivers_to_subscriber() {
        var bus = new InProcessEventBus();
        var received = new ArrayList<AgentEvent>();
        bus.subscribe("test-sub", null, received::add);

        bus.publish(systemEvent("hello"));

        assertEquals(1, received.size());
    }

    @Test
    void filter_restricts_delivery() {
        var bus = new InProcessEventBus();
        var received = new ArrayList<AgentEvent>();
        // Only accept OraclePredictions
        bus.subscribe("test-sub",
            e -> e instanceof AgentEvent.OraclePredictionsArrived,
            received::add);

        bus.publish(systemEvent("should be filtered"));
        bus.publish(new AgentEvent.OraclePredictionsArrived(
            "user1", 3, 0.9, true, Instant.now()));

        assertEquals(1, received.size());
        assertInstanceOf(AgentEvent.OraclePredictionsArrived.class, received.getFirst());
    }

    @Test
    void unsubscribe_stops_delivery() {
        var bus = new InProcessEventBus();
        var count = new AtomicInteger(0);
        bus.subscribe("test-sub", null, e -> count.incrementAndGet());

        bus.publish(systemEvent("1"));
        bus.unsubscribe("test-sub");
        bus.publish(systemEvent("2"));

        assertEquals(1, count.get());
    }

    @Test
    void multiple_subscribers_all_receive() {
        var bus = new InProcessEventBus();
        var received1 = new ArrayList<AgentEvent>();
        var received2 = new ArrayList<AgentEvent>();
        bus.subscribe("sub1", null, received1::add);
        bus.subscribe("sub2", null, received2::add);

        bus.publish(systemEvent("broadcast"));

        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
    }

    @Test
    void subscriber_count_tracks_correctly() {
        var bus = new InProcessEventBus();
        assertEquals(0, bus.subscriberCount());

        bus.subscribe("a", null, e -> {});
        bus.subscribe("b", null, e -> {});
        assertEquals(2, bus.subscriberCount());

        bus.unsubscribe("a");
        assertEquals(1, bus.subscriberCount());
    }

    @Test
    void exception_in_subscriber_doesnt_break_others() {
        var bus = new InProcessEventBus();
        var received = new ArrayList<AgentEvent>();
        bus.subscribe("bad", null, e -> { throw new RuntimeException("boom"); });
        bus.subscribe("good", null, received::add);

        bus.publish(systemEvent("test"));

        assertEquals(1, received.size());
    }
}
