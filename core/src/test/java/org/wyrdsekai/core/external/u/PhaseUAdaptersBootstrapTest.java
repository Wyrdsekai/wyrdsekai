package org.wyrdsekai.core.external.u;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link PhaseUAdaptersBootstrap} registers every Phase U
 * adapter and that the registry can route into each namespace cleanly
 * (independent of credential state).
 */
class PhaseUAdaptersBootstrapTest {

    @BeforeEach
    void setup() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseUAdaptersBootstrap.resetForTests();
    }

    @AfterEach
    void teardown() {
        PhaseUAdaptersBootstrap.resetForTests();
        ExternalAdapterRegistry.get().clearForTests();
    }

    @Test
    void init_registers_all_phase_u_namespaces() {
        PhaseUAdaptersBootstrap.init();
        var namespaces = ExternalAdapterRegistry.get().namespaces();

        // §4.38 health
        assertTrue(namespaces.contains("oura"));
        assertTrue(namespaces.contains("fitbit"));
        assertTrue(namespaces.contains("apple_health"));
        assertTrue(namespaces.contains("whoop"));
        assertTrue(namespaces.contains("garmin"));
        assertTrue(namespaces.contains("google_fit"));
        // §4.39 gov
        assertTrue(namespaces.contains("usajobs"));
        assertTrue(namespaces.contains("datagov"));
        assertTrue(namespaces.contains("congress"));
        assertTrue(namespaces.contains("irs"));
        // §4.40 maps
        assertTrue(namespaces.contains("maps"));
        assertTrue(namespaces.contains("nominatim"));
        assertTrue(namespaces.contains("mapbox"));
        assertTrue(namespaces.contains("timezone"));
        // §4.41 weather
        assertTrue(namespaces.contains("openweather"));
        assertTrue(namespaces.contains("weatherapi"));
        assertTrue(namespaces.contains("visualcrossing"));
    }

    @Test
    void init_is_idempotent() {
        PhaseUAdaptersBootstrap.init();
        PhaseUAdaptersBootstrap.init();
        // 17 adapters total
        assertEquals(17, PhaseUAdaptersBootstrap.phaseUAdapters().size());
        // every adapter present exactly once
        var names = ExternalAdapterRegistry.get().namespaces();
        assertEquals(17, names.size());
    }

    @Test
    void each_adapter_declares_at_least_one_method() {
        for (var adapter : PhaseUAdaptersBootstrap.phaseUAdapters()) {
            Set<String> caps = adapter.capabilities();
            assertNotNull(caps, "null caps for " + adapter.namespace());
            assertFalse(caps.isEmpty(),
                "no methods declared for " + adapter.namespace());
        }
    }

    @Test
    void each_adapter_declares_credential_slot() {
        for (var adapter : PhaseUAdaptersBootstrap.phaseUAdapters()) {
            // empty string is the auth-free signal (Nominatim, datagov)
            assertNotNull(adapter.credentialSlot(),
                "null slot for " + adapter.namespace());
        }
    }

    @Test
    void unknown_method_returns_unknown_method_envelope() {
        PhaseUAdaptersBootstrap.init();
        var resp = ExternalAdapterRegistry.get().invoke(new AdapterRequest(
            "oura", "definitely_not_a_method", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void resetForTests_unregisters_all_phase_u_namespaces() {
        PhaseUAdaptersBootstrap.init();
        assertFalse(ExternalAdapterRegistry.get().namespaces().isEmpty());
        PhaseUAdaptersBootstrap.resetForTests();
        var remaining = ExternalAdapterRegistry.get().namespaces();
        for (var a : PhaseUAdaptersBootstrap.phaseUAdapters()) {
            assertFalse(remaining.contains(a.namespace()),
                "namespace " + a.namespace() + " was not unregistered");
        }
    }
}
