package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import static org.junit.jupiter.api.Assertions.*;

class PhaseRAdaptersBootstrapTest {

    @AfterEach
    void tearDown() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseRAdaptersBootstrap.resetForTests();
    }

    @Test
    void bootstrap_registers_all_phase_r_adapters() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseRAdaptersBootstrap.resetForTests();
        PhaseRAdaptersBootstrap.init();
        var ns = ExternalAdapterRegistry.get().namespaces();
        assertTrue(ns.contains("anthropic"));
        assertTrue(ns.contains("openai"));
        assertTrue(ns.contains("gemini"));
        assertTrue(ns.contains("hf"));
        assertTrue(ns.contains("replicate"));
        assertTrue(ns.contains("elevenlabs"));
        assertTrue(ns.contains("whisper"));
        assertTrue(ns.contains("hass"));
        assertTrue(ns.contains("hue"));
        assertTrue(ns.contains("apple_home"));
        assertTrue(ns.contains("sonos"));
        assertTrue(ns.contains("spotify"));
        assertTrue(ns.contains("youtube"));
        assertTrue(ns.contains("apple_music"));
    }

    @Test
    void bootstrap_is_idempotent() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseRAdaptersBootstrap.resetForTests();
        PhaseRAdaptersBootstrap.init();
        var firstCount = ExternalAdapterRegistry.get().namespaces().size();
        PhaseRAdaptersBootstrap.init();
        assertEquals(firstCount, ExternalAdapterRegistry.get().namespaces().size());
    }
}
