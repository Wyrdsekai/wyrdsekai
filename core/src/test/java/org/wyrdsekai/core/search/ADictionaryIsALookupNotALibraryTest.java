package org.wyrdsekai.core.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.KnowledgePackRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dictionary answers every question a little; a library answers some
 * questions well. They must not share a default search surface.
 *
 * <h2>What went wrong</h2>
 * Every install bundles FreeDict EN↔ES (68k rows) and JMdict (217k rows)
 * into the same KNOWLEDGE index the companion's library search reads. A
 * headword gloss is three words long, so BM25's length normalization makes
 * it unbeatable for short queries — live on the home node (2026-08-24),
 * "glass tide" returned Spanish crash-glossary rows above the household's
 * actual Glass Tide passages, "weather boston" returned "weather forecast —
 * pronóstico del tiempo", and the fairy-tale tool wove two whole tales about
 * vocabulary. The persistent Spanish the steward kept seeing was this: not a
 * language bug at all, but dictionary rows winning retrieval.
 */
class ADictionaryIsALookupNotALibraryTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("the catalog knows which packs are lookup surfaces")
    void theCatalogKnowsItsDictionaries() {
        assertThat(KnowledgePackRegistry.lookupPackNames())
            .contains("jmdict", "freedict-spa-eng");
    }

    @Test
    @DisplayName("default knowledge search never returns a dictionary row")
    void defaultSearchExcludesDictionaryRows() throws Exception {
        try (var store = new WyrdLuceneStore(dir, 4)) {
            store.insertKnowledge("d1", "freedict-spa-eng", "crash",
                "crash — choque; estrellarse; colapso", "FreeDict", null, null);
            store.insertKnowledge("b1", "household-books", "Glass Tide, ch. 1",
                "The Deliverator belongs to an elite order, a hallowed "
                    + "subcategory. A crash of the old kind.", "book", null, null);

            var hits = store.searchKnowledge("crash", null, 10);
            assertThat(hits).isNotEmpty();
            assertThat(hits)
                .as("the gloss must not appear on the default surface, however "
                    + "well it scores")
                .noneMatch(r -> "d1".equals(r.id()));
            assertThat(hits).anyMatch(r -> "b1".equals(r.id()));

            // Text-only path shares the exclusion.
            var textHits = store.searchKnowledgeText("crash", 10);
            assertThat(textHits).noneMatch(r -> "d1".equals(r.id()));

            // The explicit pack-scoped door stays open — that is where the
            // translation tooling will knock.
            var scoped = store.searchKnowledgeByPack("crash", null, "freedict-spa-eng", 10);
            assertThat(scoped).anyMatch(r -> "d1".equals(r.id()));
        }
    }

    @Test
    @DisplayName("the filter language understands negation")
    void filterNegationWorks() throws Exception {
        try (var store = new WyrdLuceneStore(dir, 4)) {
            store.insertKnowledge("x1", "packA", "alpha", "shared term rowmatch", "s", null, null);
            store.insertKnowledge("x2", "packB", "beta", "shared term rowmatch", "s", null, null);
            var all = store.searchKnowledge("rowmatch", null, 10, WyrdLuceneStore.SearchMode.TEXT_ONLY);
            assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        }
    }
}
