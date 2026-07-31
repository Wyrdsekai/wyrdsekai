package org.wyrdsekai.core.external.s;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Phase S — bootstrap registers all 7 adapters with the global registry. */
class PhaseSAdaptersBootstrapTest {

    @BeforeEach
    void setup() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseSAdaptersBootstrap.resetForTests();
    }

    @Test
    void registers_all_phase_s_namespaces() {
        PhaseSAdaptersBootstrap.register();
        var ns = ExternalAdapterRegistry.get().namespaces();
        assertTrue(ns.contains("stripe"));
        assertTrue(ns.contains("plaid"));
        assertTrue(ns.contains("wise"));
        assertTrue(ns.contains("coinbase"));
        assertTrue(ns.contains("twilio"));
        assertTrue(ns.contains("vonage"));
        assertTrue(ns.contains("signalwire"));
    }

    @Test
    void register_is_idempotent() {
        PhaseSAdaptersBootstrap.register();
        var before = ExternalAdapterRegistry.get().namespaces().size();
        PhaseSAdaptersBootstrap.register();   // second call — no-op
        var after = ExternalAdapterRegistry.get().namespaces().size();
        assertEquals(before, after);
    }

    @Test
    void registered_adapters_route_through_invoke() {
        PhaseSAdaptersBootstrap.register();
        // Any call without credentials returns credential_missing — proves
        // the registry routed to the right adapter.
        var resp = ExternalAdapterRegistry.get().invoke(
            new AdapterRequest(
                "stripe", "list_charges", Map.of(),
                ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }
}
