package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rita campaign 2026-07-11 (#26) — template-catalog binding on the player
 * provider. The Workshop's "template catalog" furnishing reads
 * {@code world.catalog.*}; the player-side provider (VisitorItemProvider /
 * HomeOwnerItemProvider via WyrdWebSocket.buildPlayerProvider) never bound
 * the StandardItemLibrary, so every catalog read fell through the interface
 * defaults and the catalog answered "the item library isn't bound on this
 * surface".
 */
class VisitorItemProviderCatalogTest {

    private static StandardItemLibrary library;

    @BeforeAll
    static void setUp() {
        // Gradle runs tests from the subproject dir (core/), but scripts/ is at project root
        var scriptsPath = Path.of("scripts");
        if (!scriptsPath.resolve("std/book.js").toFile().exists()) {
            scriptsPath = Path.of("../scripts");
        }
        library = new StandardItemLibrary(scriptsPath);
    }

    @Test
    void unboundProviderKeepsSafeEmptyDefaults() {
        var provider = new VisitorItemProvider("alpha", "alpha");
        assertTrue(provider.catalogSearch("web").isEmpty());
        assertTrue(provider.catalogByCategory("knowledge").isEmpty());
        assertNull(provider.catalogTemplateInfo("web-window"));
    }

    @Test
    void boundProviderServesCatalogSearch() {
        var provider = new VisitorItemProvider("alpha", "alpha").withCatalog(library);
        var all = provider.catalogSearch("");
        assertFalse(all.isEmpty(), "bound catalog must list templates");
        var first = all.get(0);
        assertTrue(first.containsKey("name"));
        assertTrue(first.containsKey("displayName"));
        assertTrue(first.containsKey("category"));
    }

    @Test
    void boundProviderServesTemplateInfo() {
        var provider = new VisitorItemProvider("alpha", "alpha").withCatalog(library);
        // Use whatever the library actually indexes — pick the first search hit
        var all = provider.catalogSearch("");
        assertFalse(all.isEmpty());
        var name = String.valueOf(all.get(0).get("name"));
        var info = provider.catalogTemplateInfo(name);
        assertNotNull(info, "templateInfo must resolve for an indexed template");
        assertEquals(name, info.get("name"));
        assertTrue(info.containsKey("description"));
    }

    @Test
    void byCategoryMirrorsFullProviderShape() {
        var provider = new VisitorItemProvider("alpha", "alpha").withCatalog(library);
        var all = provider.catalogSearch("");
        var category = String.valueOf(all.get(0).get("category"));
        var inCat = provider.catalogByCategory(category);
        assertFalse(inCat.isEmpty(), "byCategory must find templates in an existing category");
        for (var t : inCat) {
            assertEquals(category, String.valueOf(t.get("category")));
        }
    }
}
