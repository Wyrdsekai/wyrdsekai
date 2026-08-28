package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.Capability;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the gossip wire format: a Capability round-trips JSON losslessly. */
class TheAdvertisementSurvivesTheWireTest {

    @Test
    void jsonRoundTrip() throws Exception {
        var out = new Capability("phone-1", "hh1", "llm.a1b", List.of("lfm2-8b-a1b"),
            List.of("photos"), true, false, 0.42, Instant.parse("2026-08-13T12:00:00Z"));
        var back = NatsGossip.decode(NatsGossip.encode(out));
        assertEquals(out, back);
    }

    @Test
    void subjectIsHouseholdScoped() {
        assertEquals("hh.hh1.hermod.capability", NatsGossip.subject("hh1"));
    }
}
