package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Phase Q capability catalogue additions in
 * {@link ItemManifestValidator}.
 */
class PhaseQCapabilityCatalogueTest {

    @Test
    void productivity_meta_caps_known_at_correct_tiers() {
        // Reads = Tier 4
        assertEquals(4, ItemManifestValidator.tierFor("calendar.read"));
        assertEquals(4, ItemManifestValidator.tierFor("gdrive.read"));
        assertEquals(4, ItemManifestValidator.tierFor("notion.read"));
        assertEquals(4, ItemManifestValidator.tierFor("linear.read"));
        assertEquals(4, ItemManifestValidator.tierFor("asana.read"));
        assertEquals(4, ItemManifestValidator.tierFor("todoist.read"));
        // Writes = Tier 5
        assertEquals(5, ItemManifestValidator.tierFor("calendar.write"));
        assertEquals(5, ItemManifestValidator.tierFor("gdrive.write"));
        assertEquals(5, ItemManifestValidator.tierFor("notion.write"));
        assertEquals(5, ItemManifestValidator.tierFor("linear.write"));
        assertEquals(5, ItemManifestValidator.tierFor("asana.write"));
        assertEquals(5, ItemManifestValidator.tierFor("todoist.write"));
    }

    @Test
    void knowledge_meta_caps_known_at_tier4() {
        assertEquals(4, ItemManifestValidator.tierFor("arxiv.read"));
        assertEquals(4, ItemManifestValidator.tierFor("scholar.read"));
        assertEquals(4, ItemManifestValidator.tierFor("wikipedia.read"));
        assertEquals(4, ItemManifestValidator.tierFor("stackoverflow.read"));
        assertEquals(4, ItemManifestValidator.tierFor("wolfram.read"));
    }

    @Test
    void scholar_and_stackoverflow_namespaces_known_via_wildcard() {
        // The wildcard fallback handles per-method caps too.
        assertTrue(ItemManifestValidator.isKnownCapability("scholar.search"));
        assertTrue(ItemManifestValidator.isKnownCapability("stackoverflow.top_answer"));
        assertEquals(5, ItemManifestValidator.tierFor("scholar.search"));
        assertEquals(5, ItemManifestValidator.tierFor("stackoverflow.search"));
    }

    @Test
    void all_phaseQ_meta_caps_isKnown() {
        for (var c : new String[]{
            "calendar.read", "calendar.write", "gdrive.read", "gdrive.write",
            "notion.read", "notion.write", "linear.read", "linear.write",
            "asana.read", "asana.write", "todoist.read", "todoist.write",
            "arxiv.read", "scholar.read", "wikipedia.read",
            "stackoverflow.read", "wolfram.read"}) {
            assertTrue(ItemManifestValidator.isKnownCapability(c), "should be known: " + c);
        }
    }
}
