package org.wyrdsekai.hermod;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.util.function.Consumer;

/** Pins P1 merge semantics: LWW per device, order-independent convergence, TTL expiry. */
class AStaleAdvertisementNeverRegressesTheTableTest {

    private Capability ad(String device, Instant at, boolean charging) {
        return new Capability(device, "hh1", "llm.a1b", List.of("m"),
            List.of(), charging, true, 0.2, at);
    }

    @Test
    void freshBeatsStaleRegardlessOfArrivalOrder() {
        var t = new CapabilityTable(Duration.ofMinutes(5));
        var now = Instant.now();
        var fresh = ad("phone-1", now, true);
        var stale = ad("phone-1", now.minusSeconds(30), false);
        assertTrue(t.merge(fresh));
        assertFalse(t.merge(stale), "stale ad must not win");
        assertTrue(t.snapshot(now).get(0).charging(), "table kept the fresh view");
    }

    @Test
    void silenceExpiresACapability() {
        var t = new CapabilityTable(Duration.ofSeconds(10));
        var old = Instant.now().minusSeconds(60);
        t.merge(ad("node-1", old, true));
        assertTrue(t.snapshot(Instant.now()).isEmpty(), "absence of advertisement is absence of capability");
    }

    @Test
    void loopbackTransportConverges() {
        var t = new CapabilityTable(Duration.ofMinutes(5));
        GossipTransport loop = new GossipTransport() {
            Consumer<Capability> sink;
            public void publish(Capability c) { if (sink != null) sink.accept(c); }
            public void subscribe(Consumer<Capability> s) { this.sink = s; }
        };
        t.attach(loop);
        loop.publish(ad("phone-1", Instant.now(), true));
        assertEquals(1, t.snapshot(Instant.now()).size());
    }
}
