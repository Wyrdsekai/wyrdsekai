package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservationBufferTest {

    @Test
    void observe_adds_to_buffer() {
        var buf = new ObservationBuffer();
        buf.observe("room", "Someone entered", 0.8);
        assertEquals(1, buf.size());
    }

    @Test
    void buffer_evicts_oldest_at_capacity() {
        var buf = new ObservationBuffer();
        for (int i = 0; i < 55; i++) {
            buf.observe("room", "event " + i, 0.5);
        }
        assertEquals(50, buf.size()); // MAX_SIZE
    }

    @Test
    void relevant_filters_by_threshold() {
        var buf = new ObservationBuffer();
        buf.observe("tell", "Important message", 0.9);
        buf.observe("room", "Background noise", 0.1);

        var relevant = buf.relevant(0.5);
        assertEquals(1, relevant.size());
        assertEquals("tell", relevant.getFirst().source());
    }

    @Test
    void top_returns_highest_relevance_first() {
        var buf = new ObservationBuffer();
        buf.observe("room", "low", 0.2);
        buf.observe("tell", "high", 0.9);
        buf.observe("event", "medium", 0.5);

        var top = buf.top(3);
        assertEquals(3, top.size());
        assertEquals("tell", top.get(0).source());
    }

    @Test
    void buildContext_produces_formatted_output() {
        var buf = new ObservationBuffer();
        buf.observe("tell", "Hello from Claude", 0.9);
        buf.observe("oracle", "Rain predicted", 0.7);

        var context = buf.buildContext(5);
        assertNotNull(context);
        assertTrue(context.contains("Recent Observations"));
        assertTrue(context.contains("tell"));
        assertTrue(context.contains("Hello from Claude"));
    }

    @Test
    void buildContext_returns_null_when_empty() {
        var buf = new ObservationBuffer();
        assertNull(buf.buildContext(5));
    }

    @Test
    void clear_empties_buffer() {
        var buf = new ObservationBuffer();
        buf.observe("room", "test", 0.5);
        buf.clear();
        assertEquals(0, buf.size());
    }
}
