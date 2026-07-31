package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PhaseWAdaptersBootstrapTest {

    @BeforeEach
    void setup() {
        ExternalAdapterRegistry.get().clearForTests();
        PhaseWAdaptersBootstrap.resetForTests();
    }

    @Test
    void registers_all_eighteen_adapters() {
        PhaseWAdaptersBootstrap.init();
        var ns = ExternalAdapterRegistry.get().namespaces();
        // §4.44
        assertTrue(ns.contains("deepl"));
        assertTrue(ns.contains("translate"));
        assertTrue(ns.contains("lingua"));
        assertTrue(ns.contains("duolingo"));
        assertTrue(ns.contains("coursa"));
        assertTrue(ns.contains("khan"));
        // §4.45
        assertTrue(ns.contains("unsplash"));
        assertTrue(ns.contains("pixabay"));
        assertTrue(ns.contains("pexels"));
        assertTrue(ns.contains("iconify"));
        assertTrue(ns.contains("fonts"));
        // §4.46
        assertTrue(ns.contains("goodreads"));
        assertTrue(ns.contains("openlib"));
        assertTrue(ns.contains("gbooks"));
        assertTrue(ns.contains("kobo"));
        assertTrue(ns.contains("audible"));
        assertTrue(ns.contains("calibre"));
        assertTrue(ns.contains("mangadex"));
        assertEquals(18, countPhaseWNamespaces(ns));
    }

    @Test
    void init_is_idempotent() {
        PhaseWAdaptersBootstrap.init();
        var firstSize = ExternalAdapterRegistry.get().namespaces().size();
        PhaseWAdaptersBootstrap.init();
        var secondSize = ExternalAdapterRegistry.get().namespaces().size();
        assertEquals(firstSize, secondSize);
        assertTrue(PhaseWAdaptersBootstrap.isInitialised());
    }

    @Test
    void registry_invokes_through_registered_adapter() {
        PhaseWAdaptersBootstrap.init();
        // Use a stub adapter (Goodreads) — its not_yet_wired error path is
        // a clean way to verify the registry routes through it.
        var resp = ExternalAdapterRegistry.get().invoke(
            new AdapterRequest("goodreads", "search",
                Map.of("query", "x"),
                ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    private static int countPhaseWNamespaces(Set<String> ns) {
        var phaseW = Set.of("deepl", "translate", "lingua", "duolingo",
            "coursa", "khan", "unsplash", "pixabay", "pexels", "iconify", "fonts",
            "goodreads", "openlib", "gbooks", "kobo", "audible", "calibre", "mangadex");
        var count = 0;
        for (var n : phaseW) if (ns.contains(n)) count++;
        return count;
    }
}
