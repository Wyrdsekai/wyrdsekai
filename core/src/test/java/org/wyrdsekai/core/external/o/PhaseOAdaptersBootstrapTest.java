package org.wyrdsekai.core.external.o;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import static org.junit.jupiter.api.Assertions.*;

class PhaseOAdaptersBootstrapTest {

    @BeforeEach
    void setup() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseOAdaptersBootstrap.resetForTests();
    }

    @AfterEach
    void teardown() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseOAdaptersBootstrap.resetForTests();
    }

    @Test
    void registers_all_seven_namespaces() {
        PhaseOAdaptersBootstrap.init();
        var ns = ExternalAdapterRegistry.get().namespaces();
        assertTrue(ns.contains("email"));
        assertTrue(ns.contains("slack"));
        assertTrue(ns.contains("discord"));
        assertTrue(ns.contains("telegram"));
        assertTrue(ns.contains("signal"));
        assertTrue(ns.contains("matrix"));
        assertTrue(ns.contains("whatsapp"));
    }

    @Test
    void idempotent_init() {
        PhaseOAdaptersBootstrap.init();
        var first = ExternalAdapterRegistry.get().namespaces().size();
        PhaseOAdaptersBootstrap.init(); // second call no-ops
        assertEquals(first, ExternalAdapterRegistry.get().namespaces().size());
    }

    @Test
    void each_adapter_declares_credential_slot() {
        PhaseOAdaptersBootstrap.init();
        var registry = ExternalAdapterRegistry.get();
        for (var ns : new String[]{
                "email", "slack", "discord", "telegram", "signal", "matrix", "whatsapp"}) {
            var a = registry.lookup(ns).orElseThrow();
            assertNotNull(a.credentialSlot(), ns + " must declare credentialSlot");
            assertFalse(a.credentialSlot().isBlank(),
                ns + " credentialSlot must not be blank");
        }
    }
}
