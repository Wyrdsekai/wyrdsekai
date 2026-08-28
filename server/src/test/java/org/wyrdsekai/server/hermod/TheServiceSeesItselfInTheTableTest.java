package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.GossipTransport;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Optional;
import org.wyrdsekai.hermod.TaskEnvelope;

/** Pins the service wiring: heartbeat advertises, self appears in table, mesh answers honestly pre-executor. */
class TheServiceSeesItselfInTheTableTest {

    static final class Loopback implements GossipTransport {
        Consumer<Capability> sink;
        public void publish(Capability c) { if (sink != null) sink.accept(c); }
        public void subscribe(Consumer<Capability> s) { this.sink = s; }
    }

    @Test
    void heartbeatPutsThisDeviceInItsOwnView() throws Exception {
        try (var svc = new HermodService(new Loopback(), "hh1", "node-1",
                "llm.dense-9b", List.of("m"), null, Clock.systemUTC())) {
            svc.start();
            Thread.sleep(200);
            var snap = svc.table().snapshot(Instant.now());
            assertEquals(1, snap.size());
            assertEquals("node-1", snap.get(0).deviceId());
        }
    }

    @Test
    void preExecutorMeshRefusesHonestly() throws Exception {
        try (var svc = new HermodService(new Loopback(), "hh1", "node-1",
                "llm.dense-9b", List.of("m"), null, Clock.systemUTC())) {
            svc.start();
            Thread.sleep(200);
            var e = new TaskEnvelope("e1", "hh1", "node-1",
                "inference.chat", "none", "llm.dense-9b", Map.of(), 100,
                Instant.now(), Instant.now().plusSeconds(30),
                Optional.empty(), new byte[]{1});
            var r = svc.mesh().submit(e);
            assertFalse(r.ok());
            assertTrue(r.error().contains("no local executor configured"), r.error());
        }
    }
}
