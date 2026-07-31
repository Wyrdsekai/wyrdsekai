package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PhaseQAdaptersBootstrapTest {

    private ExternalAdapterRegistry reg;

    @BeforeEach
    void setUp() {
        reg = ExternalAdapterRegistry.get();
        reg.clearForTests();
        PhaseQAdaptersBootstrap.resetForTests();
    }

    @AfterEach
    void tearDown() {
        reg.clearForTests();
        PhaseQAdaptersBootstrap.resetForTests();
    }

    @Test
    void registers_all_phaseQ_namespaces() {
        PhaseQAdaptersBootstrap.init();
        var ns = reg.namespaces();
        // §4.27 productivity
        assertTrue(ns.contains("calendar"));
        assertTrue(ns.contains("gdrive"));
        assertTrue(ns.contains("notion"));
        assertTrue(ns.contains("linear"));
        assertTrue(ns.contains("asana"));
        assertTrue(ns.contains("todoist"));
        // §4.28 knowledge
        assertTrue(ns.contains("arxiv"));
        assertTrue(ns.contains("scholar"));
        assertTrue(ns.contains("wikipedia"));
        assertTrue(ns.contains("stackoverflow"));
        assertTrue(ns.contains("wolfram"));
    }

    @Test
    void registers_correct_classes() {
        PhaseQAdaptersBootstrap.init();
        assertInstanceOf(GoogleCalendarAdapter.class, reg.lookup("calendar").orElseThrow());
        assertInstanceOf(WikipediaAdapter.class, reg.lookup("wikipedia").orElseThrow());
        assertInstanceOf(WolframAdapter.class, reg.lookup("wolfram").orElseThrow());
    }

    @Test
    void second_init_is_idempotent() {
        PhaseQAdaptersBootstrap.init();
        var sizeAfterFirst = reg.namespaces().size();
        PhaseQAdaptersBootstrap.init();
        assertEquals(sizeAfterFirst, reg.namespaces().size(),
            "second init must not duplicate or replace");
    }

    @Test
    void all_eleven_adapters_registered() {
        PhaseQAdaptersBootstrap.init();
        // Filter to just Phase Q namespaces in case other adapters got registered.
        var phaseQ = Set.of("calendar", "gdrive", "notion", "linear",
            "asana", "todoist", "arxiv", "scholar", "wikipedia", "stackoverflow", "wolfram");
        var found = reg.namespaces().stream().filter(phaseQ::contains).count();
        assertEquals(11, found);
    }
}
