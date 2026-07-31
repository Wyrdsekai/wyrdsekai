package org.wyrdsekai.core.external.v;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import static org.junit.jupiter.api.Assertions.*;

class PhaseVAdaptersBootstrapTest {

    @BeforeEach
    void setup() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseVAdaptersBootstrap.resetForTests();
    }

    @AfterEach
    void cleanup() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseVAdaptersBootstrap.resetForTests();
    }

    @Test
    void init_registers_all_fourteen_phase_v_adapters() {
        PhaseVAdaptersBootstrap.init();

        var ns = ExternalAdapterRegistry.get().namespaces();
        // Travel & transport (8)
        assertTrue(ns.contains("amadeus"));
        assertTrue(ns.contains("kayak"));
        assertTrue(ns.contains("google_flights"));
        assertTrue(ns.contains("booking"));
        assertTrue(ns.contains("airbnb"));
        assertTrue(ns.contains("uber"));
        assertTrue(ns.contains("lyft"));
        assertTrue(ns.contains("transit_rt"));
        // Commerce / real-estate / jobs (6)
        assertTrue(ns.contains("shopify"));
        assertTrue(ns.contains("amazon"));
        assertTrue(ns.contains("etsy"));
        assertTrue(ns.contains("zillow"));
        assertTrue(ns.contains("redfin"));
        assertTrue(ns.contains("indeed"));
    }

    @Test
    void init_is_idempotent() {
        PhaseVAdaptersBootstrap.init();
        PhaseVAdaptersBootstrap.init();
        assertTrue(PhaseVAdaptersBootstrap.isInitialised());
        assertTrue(ExternalAdapterRegistry.get().namespaces().contains("amadeus"));
    }

    @Test
    void travel_adapters_count_is_eight() {
        PhaseVAdaptersBootstrap.init();
        var ns = ExternalAdapterRegistry.get().namespaces();
        long travel = ns.stream().filter(n ->
            n.equals("amadeus") || n.equals("kayak") || n.equals("google_flights")
            || n.equals("booking") || n.equals("airbnb") || n.equals("uber")
            || n.equals("lyft") || n.equals("transit_rt")
        ).count();
        assertEquals(8, travel);
    }

    @Test
    void commerce_adapters_count_is_six() {
        PhaseVAdaptersBootstrap.init();
        var ns = ExternalAdapterRegistry.get().namespaces();
        long commerce = ns.stream().filter(n ->
            n.equals("shopify") || n.equals("amazon") || n.equals("etsy")
            || n.equals("zillow") || n.equals("redfin") || n.equals("indeed")
        ).count();
        assertEquals(6, commerce);
    }

    @Test
    void each_adapter_declares_credential_slot() {
        PhaseVAdaptersBootstrap.init();
        var ns = ExternalAdapterRegistry.get().namespaces();
        for (var n : ns) {
            var adapter = ExternalAdapterRegistry.get().lookup(n).orElseThrow();
            var slot = adapter.credentialSlot();
            assertNotNull(slot, "adapter " + n + " must declare a credential slot");
            assertFalse(slot.isBlank(), "adapter " + n + " credential slot is blank");
        }
    }
}
