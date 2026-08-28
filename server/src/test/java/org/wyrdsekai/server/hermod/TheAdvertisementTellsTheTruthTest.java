package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.GossipTransport;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 5b/c: ads carry real load (0..1, from the OS) and deployment-declared domains. */
class TheAdvertisementTellsTheTruthTest {

    @Test
    void loadIsRealAndDomainsAreDeclared() throws Exception {
        var seen = new AtomicReference<Capability>();
        GossipTransport loop = new GossipTransport() {
            Consumer<Capability> sink;
            public void publish(Capability c) { seen.set(c); if (sink != null) sink.accept(c); }
            public void subscribe(Consumer<Capability> s) { this.sink = s; }
        };
        try (var svc = new HermodService(loop, "hh1", "node-1",
                "llm.local-gpu", List.of("m"), null, Clock.systemUTC())) {
            svc.residentDomains(List.of("journals", "library"));
            svc.start();
            Thread.sleep(300);
            var cap = seen.get();
            assertNotNull(cap);
            assertTrue(cap.loadFactor() >= 0.0 && cap.loadFactor() <= 1.0,
                "load normalized to 0..1, was " + cap.loadFactor());
            assertEquals(List.of("journals", "library"), cap.residentDataDomains());
            assertTrue(cap.charging(), "a mains-powered node is always charging");
        }
    }
}
