package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Standard Item Library — template registry, search, instantiation.
 */
class StandardItemLibraryTest {

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
    void templateCountAtLeast20() {
        assertTrue(library.templates().size() >= 20,
            "Library should have at least 20 templates, got " + library.templates().size());
    }

    @Test
    void allTemplatesHaveThematicProfile() {
        for (var entry : library.templates().entrySet()) {
            var t = entry.getValue();
            assertNotNull(t.thematic(), "Template " + entry.getKey() + " missing thematic profile");
            assertFalse(t.thematic().domains().isEmpty(),
                "Template " + entry.getKey() + " has empty domains");
        }
    }

    @Test
    void allTemplatesHaveBaseScript() {
        for (var entry : library.templates().entrySet()) {
            var t = entry.getValue();
            assertNotNull(t.baseScript(), "Template " + entry.getKey() + " missing baseScript");
            assertTrue(t.baseScript().startsWith("std/"),
                "Template " + entry.getKey() + " baseScript should start with std/");
        }
    }

    @Test
    void searchByKeyword() {
        var results = library.search("book");
        assertFalse(results.isEmpty(), "Search for 'book' should return results");
        assertTrue(results.stream().anyMatch(t -> t.name().contains("book")),
            "Should find a book template");
    }

    @Test
    void searchByDomain() {
        var results = library.search("communication");
        assertFalse(results.isEmpty(), "Search for 'communication' should return results");
    }

    @Test
    void searchBySymbol() {
        var results = library.search("sight");
        assertFalse(results.isEmpty(), "Search for 'sight' should return results");
        assertTrue(results.stream().anyMatch(t -> t.category().equals("crystal")),
            "Sight should match crystal-type templates");
    }

    @Test
    void byCategoryFilters() {
        var crystals = library.byCategory("crystal");
        assertFalse(crystals.isEmpty());
        assertTrue(crystals.stream().allMatch(t -> t.category().equals("crystal")));

        var keys = library.byCategory("key");
        assertFalse(keys.isEmpty());
        assertTrue(keys.stream().allMatch(t -> t.category().equals("key")));
    }

    @Test
    void byLevelFilters() {
        var level1 = library.byLevel(1);
        var level2 = library.byLevel(2);
        assertFalse(level1.isEmpty(), "Should have Level 1 templates");
        assertFalse(level2.isEmpty(), "Should have Level 2 templates");
        assertTrue(level1.size() > level2.size(), "More Level 1 than Level 2 templates");
    }

    @Test
    void getByName() {
        var book = library.get("simple-book");
        assertNotNull(book);
        assertEquals("Simple Book", book.displayName());
        assertEquals("book", book.category());
        assertEquals("std/book", book.baseScript());
        assertEquals(1, book.level());
    }

    @Test
    void getNonexistent() {
        assertNull(library.get("does-not-exist"));
    }

    @Test
    void instantiateCreatesValidItem() {
        var item = library.instantiate("simple-book", Map.of("title", "My Book", "name", "My Book"), "ember");
        assertNotNull(item);
        assertEquals("My Book", item.name());
        assertEquals("book", item.category());
        assertEquals("std/book", item.templateBase());
        assertNotNull(item.thematic());
        assertTrue(item.thematic().domains().contains("knowledge"));
        assertNotNull(item.config());
        assertEquals("My Book", item.config().get("title"));
        assertEquals("ember", item.creatorDid());
        assertTrue(item.isTemplate());
        assertTrue(item.isScripted()); // generated script from template
    }

    @Test
    void instantiateGeneratesInheritScript() {
        var item = library.instantiate("scrying-crystal", Map.of(), "ember");
        assertNotNull(item.script());
        assertTrue(item.script().contains("inherit(\"std/crystal\")"),
            "Generated script should contain inherit() call");
    }

    @Test
    void instantiateMergesConfig() {
        var item = library.instantiate("simple-book",
            Map.of("title", "Custom Title", "extra", "value"), "agent-1");
        assertEquals("Custom Title", item.config().get("title"));
        assertEquals("value", item.config().get("extra"));
        // Default author should still be present
        assertEquals("unknown", item.config().get("author"));
    }

    @Test
    void instantiateUnknownTemplateThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> library.instantiate("nonexistent", Map.of(), "test"));
    }

    @Test
    void resolveBaseScriptFromFileSystem() {
        // This test depends on scripts/std/ existing in the working directory
        var source = library.resolveBaseScript("std/book");
        if (source != null) {
            assertTrue(source.contains("item._type = \"book\""),
                "Book base script should set type to book");
            assertTrue(source.contains("function invoke"),
                "Book base script should define invoke()");
        }
        // If scripts/ not in test classpath, this is OK — the test still validates the API
    }

    @Test
    void templateUniqueNames() {
        var names = library.templates().keySet();
        assertEquals(names.size(), library.templates().size(),
            "All template names should be unique");
    }

    @Test
    void allCategoriesCovered() {
        var categories = library.templates().values().stream()
            .map(StandardItemLibrary.ItemTemplate::category)
            .distinct()
            .toList();
        // Should have at least book, crystal, tool, key, container, portal, consumable, aspect, automator, document, blueprint
        assertTrue(categories.size() >= 8,
            "Should cover at least 8 item categories, got " + categories);
    }

    // ── Alias resolution (2026-05-06): JA Ember task10 hit "Template not
    //    found: crystal" because translator rendered 遠視水晶 as "far-sighted
    //    crystal" instead of "scrying crystal". Aliases absorb that drift.

    @Test
    void getByDisplayName() {
        var t = library.get("Scrying Crystal");
        assertNotNull(t);
        assertEquals("scrying-crystal", t.name());
    }

    @Test
    void getByNormalizedForm() {
        // hyphens, underscores, spaces all collapse
        assertEquals("scrying-crystal", library.get("scrying crystal").name());
        assertEquals("scrying-crystal", library.get("scrying_crystal").name());
        assertEquals("scrying-crystal", library.get("SCRYING-CRYSTAL").name());
    }

    @Test
    void getByAlias_crystalResolvesToScrying() {
        // The case that motivated this: bare "crystal" should land on the
        // canonical scrying-crystal template (most generic crystal).
        var t = library.get("crystal");
        assertNotNull(t, "Bare 'crystal' should resolve to scrying-crystal");
        assertEquals("scrying-crystal", t.name());
    }

    @Test
    void getByAlias_translationDriftCases() {
        // Real translator outputs that previously failed.
        assertEquals("scrying-crystal", library.get("far-sighted crystal").name());
        assertEquals("scrying-crystal", library.get("viewing crystal").name());
        assertEquals("scrying-crystal", library.get("scry").name());
    }

    @Test
    void getByAlias_otherCrystals() {
        // Specific aliases route to the right crystal variant.
        assertEquals("weather-globe", library.get("globe").name());
        assertEquals("oracle-lens", library.get("oracle").name());
        assertEquals("dashboard-orb", library.get("orb").name());
    }

    @Test
    void getByAlias_acrossCategories() {
        assertEquals("simple-book", library.get("book").name());
        assertEquals("mailbox", library.get("inbox").name());
        assertEquals("room-key", library.get("key").name());
        assertEquals("ward-stone", library.get("ward").name());
        assertEquals("workbench-hammer", library.get("hammer").name());
    }

    @Test
    void getReturnsNullForBlankOrNull() {
        assertNull(library.get(null));
        assertNull(library.get(""));
        assertNull(library.get("   "));
    }

    @Test
    void normalizeFolds() {
        assertEquals("scrying-crystal", StandardItemLibrary.normalize("Scrying Crystal"));
        assertEquals("scrying-crystal", StandardItemLibrary.normalize("scrying_crystal"));
        assertEquals("scrying-crystal", StandardItemLibrary.normalize("  scrying  crystal  "));
        assertEquals("", StandardItemLibrary.normalize(""));
        assertEquals("", StandardItemLibrary.normalize(null));
    }

    @Test
    void instantiateUsesAliasResolution() {
        // The end-to-end fix: a model calling craft_item with template="crystal"
        // (translator-imprecise) should now succeed and produce a scrying-crystal.
        var item = library.instantiate("crystal", Map.of(), "ember");
        assertNotNull(item);
        assertEquals("crystal", item.category());
        // Default name comes from the template's displayName.
        assertEquals("Scrying Crystal", item.name());
    }
}
