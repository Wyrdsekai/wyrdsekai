package org.wyrdsekai.rendezvous;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";
    private static final String DID_C =
        "did:wyrd:z6MkNnewnewnewnewnewnewnewnewnewnewnewnewnewn";

    private KeywordIndex idx;

    @BeforeEach void setUp() throws IOException {
        idx = new KeywordIndex();
    }

    @AfterEach void tearDown() throws IOException {
        if (idx != null) idx.close();
    }

    private static ZoneManifestV1 manifest(String did, String label, String display,
                                            String tagline, String description,
                                            List<String> tags,
                                            ZoneManifestV1.Capabilities caps) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, display, null,
            tagline, description, tags, caps, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
    }

    @Test void index_addsDocument() {
        idx.index(manifest(DID_A, "kitchen", "Alice's Kitchen",
            "tea and crafts", "a warm social space", List.of(), null));
        assertEquals(1, idx.documentCount());
    }

    @Test void search_matchesOnTagline() {
        idx.index(manifest(DID_A, "kitchen", "Alice's Kitchen",
            "afternoon tea and crafts",
            "cozy space for cooking", List.of(), null));
        idx.index(manifest(DID_B, "garage", "Bob's Garage",
            "woodworking tools", "workshop", List.of(), null));

        var hits = idx.search("crafts", 10);
        assertEquals(1, hits.size());
        assertEquals(DID_A, hits.get(0).did());
    }

    @Test void search_tokenizesStandardEnglish() {
        // Standard analyzer stems + lowercases — "crafts" and "Crafts"
        // should both match "crafts" in the index.
        idx.index(manifest(DID_A, "kitchen", "Alice's Kitchen",
            "Crafts Afternoon", "social", List.of(), null));
        assertEquals(1, idx.search("crafts", 10).size());
        assertEquals(1, idx.search("Crafts", 10).size());
        assertEquals(1, idx.search("CRAFTS", 10).size());
    }

    @Test void search_labelBoostsOverDescription() {
        // DID_A has "music" in the label; DID_B has "music" only in description.
        // Label has a higher boost, so DID_A should rank first.
        idx.index(manifest(DID_A, "music", "Music Room",
            "a quiet place", "cozy", List.of(), null));
        idx.index(manifest(DID_B, "garage", "Garage",
            "working", "sometimes music plays here", List.of(), null));

        var hits = idx.search("music", 10);
        assertEquals(2, hits.size());
        assertEquals(DID_A, hits.get(0).did(),
            "label match ranks above description match");
    }

    @Test void search_matchesOnTags() {
        idx.index(manifest(DID_A, "a", "A", "a", "a",
            List.of("social", "crafts"), null));
        idx.index(manifest(DID_B, "b", "B", "b", "b",
            List.of("work"), null));

        assertEquals(DID_A, idx.search("crafts", 10).get(0).did());
        assertEquals(DID_B, idx.search("work", 10).get(0).did());
    }

    @Test void search_matchesOnCapabilitiesRoomsAndAgentSkills() {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(new ZoneManifestV1.PublicRoom("library", "The Library", "Books.")),
            List.of(new ZoneManifestV1.PublicAgent("kettle", "Kettle", "companion",
                "Helpful.", List.of("recipe-lookup", "meal-planning"))),
            Map.of(), Map.of());
        idx.index(manifest(DID_A, "kitchen", "K", "t", "d", List.of(), caps));

        assertEquals(1, idx.search("library", 10).size());
        assertEquals(1, idx.search("kettle", 10).size());
        assertEquals(1, idx.search("recipe-lookup", 10).size(),
            "skills must be indexed for capability search");
        assertEquals(1, idx.search("meal-planning", 10).size());
    }

    @Test void index_updatesInPlace() {
        // Use tokenizer-safe distinct words (no shared subword on hyphen split).
        idx.index(manifest(DID_A, "kitchen", "Alice's Kitchen",
            "tea", "social", List.of("forestry"), null));
        idx.index(manifest(DID_A, "kitchen", "Alice's Kitchen",
            "tea", "social", List.of("oceanography"), null));

        assertEquals(1, idx.documentCount(), "update-in-place, not dup");
        assertTrue(idx.search("forestry", 10).isEmpty(),
            "old tag dropped on reindex");
        assertEquals(1, idx.search("oceanography", 10).size());
    }

    @Test void remove_dropsDocument() {
        idx.index(manifest(DID_A, "kitchen", "K", "tea", "social",
            List.of(), null));
        idx.remove(DID_A);

        assertEquals(0, idx.documentCount());
        assertTrue(idx.search("tea", 10).isEmpty());
    }

    @Test void search_respectsLimit() {
        idx.index(manifest(DID_A, "kitchen", "K", "social", "d", List.of(), null));
        idx.index(manifest(DID_B, "garage", "G", "social", "d", List.of(), null));
        idx.index(manifest(DID_C, "office", "O", "social", "d", List.of(), null));

        assertEquals(2, idx.search("social", 2).size());
    }

    @Test void search_emptyQueryReturnsEmpty() {
        idx.index(manifest(DID_A, "kitchen", "K", "t", "d", List.of(), null));
        assertTrue(idx.search("", 10).isEmpty());
        assertTrue(idx.search(null, 10).isEmpty());
    }

    @Test void search_operatorChars_dontCrashParser() {
        idx.index(manifest(DID_A, "kitchen", "Alice's Kitchen",
            "afternoon tea", "social", List.of(), null));
        // Apostrophes, brackets, other operator-like chars must not
        // break the query parser. The cleanForParser step strips them.
        assertDoesNotThrow(() -> idx.search("alice's kitchen", 10));
        assertDoesNotThrow(() -> idx.search("(tea)", 10));
        assertDoesNotThrow(() -> idx.search("search-me!", 10));
    }

    @Test void search_scoresSortDescending() {
        idx.index(manifest(DID_A, "kitchen", "K",
            "tea tea tea", "tea", List.of(), null));
        idx.index(manifest(DID_B, "garage", "G",
            "tea", "a", List.of(), null));

        var hits = idx.search("tea", 10);
        assertTrue(hits.size() >= 2);
        assertTrue(hits.get(0).score() >= hits.get(1).score(),
            "scores must sort descending");
    }
}
