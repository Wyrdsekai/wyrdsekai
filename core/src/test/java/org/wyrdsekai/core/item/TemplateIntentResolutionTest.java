package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template name the model invented must map to what she MEANT, or to nothing — never to
 * whatever template happens to share a word with her sentence.
 *
 * <p>Household node, 2026-09-12 10:41: {@code craft_from_template} with template
 * {@code backup_vault} and the name "The Counting House vault chest - a key for storing dated
 * snapshots of the household's current state; use `create` to pack new backups, and restore
 * …". No such template. The substring search matched the word {@code create} against the
 * workbench hammer's "Create Level 1 template items…", the token scorer counted {@code the},
 * {@code for} and {@code and} as evidence, and she crafted a hammer she believed was the
 * household's vault key. It sat in her inventory under that name.</p>
 */
class TemplateIntentResolutionTest {

    private static StandardItemLibrary library;

    @BeforeAll
    static void setUp() {
        var scriptsPath = Path.of("scripts");
        if (!scriptsPath.resolve("std/book.js").toFile().exists()) {
            scriptsPath = Path.of("../scripts");
        }
        library = new StandardItemLibrary(scriptsPath);
    }

    @Test
    @DisplayName("backup_vault + the vault-key item name resolves to NO template — not the hammer")
    void theVaultKeyIsNotAHammer() {
        var r = library.resolveIntent("backup_vault",
            "The Counting House vault chest - a key for storing dated snapshots of the household's "
                + "current state; use `create` to pack new backups, and restore <id> to stage one");
        assertFalse(r.matched(), "no template names a backup vault; got " + r);
        assertEquals(StandardItemLibrary.How.NONE, r.how());
        for (var c : r.candidates()) {
            assertNotNull(c);
        }
    }

    @Test
    @DisplayName("every alias in the table resolves to its own template — no two templates share one")
    void everyAliasIsUnambiguous() {
        for (var e : StandardItemLibrary.ALIASES.entrySet()) {
            var template = library.templates().get(e.getKey());
            assertNotNull(template, "alias table names a template that does not exist: " + e.getKey());
            for (var alias : e.getValue()) {
                var hit = library.get(alias);
                assertNotNull(hit, "'" + alias + "' resolves to nothing — claimed by two templates?");
                assertEquals(e.getKey(), hit.name(), "'" + alias + "' resolves to the wrong template");
            }
        }
    }

    @Test
    @DisplayName("the id enum the tool schema offers and the alias table cover the same templates")
    void aliasTableCoversEveryTemplate() {
        for (var name : StandardItemLibrary.TEMPLATE_NAMES) {
            assertTrue(StandardItemLibrary.ALIASES.containsKey(name),
                "template '" + name + "' has no aliases — the words a model reaches for are unmapped");
        }
        assertEquals(StandardItemLibrary.TEMPLATE_NAMES.size(), StandardItemLibrary.ALIASES.size());
    }

    @Test
    @DisplayName("plain words map: notebook → simple-book, key → room-key, browser → web-window")
    void plainWordsMap() {
        assertEquals("simple-book", library.resolveIntent("notebook", null).template().name());
        assertEquals("room-key", library.resolveIntent("key", "a key to the study").template().name());
        assertEquals("web-window", library.resolveIntent("browser", null).template().name());
        assertEquals("email-quill", library.resolveIntent("email", "letters to the steward").template().name());
        assertEquals("code-terminal", library.resolveIntent("repl", null).template().name());
        assertEquals(StandardItemLibrary.How.NAME, library.resolveIntent("Scrying_Crystal", null).how());
    }

    @Test
    @DisplayName("a compound the model assembled from real words still lands: web_search_lens → web-window")
    void compoundNamesLandOnThePhraseTheyContain() {
        var r = library.resolveIntent("web_search_lens", "a lens that searches the web");
        assertTrue(r.matched(), "expected web-window, got " + r);
        assertEquals("web-window", r.template().name());
        assertEquals(StandardItemLibrary.How.WORDS, r.how());
    }

    @Test
    @DisplayName("the item name alone can carry the intent when the template name says nothing")
    void itemNameCarriesIntent() {
        var r = library.resolveIntent("tool", "a mailbox for letters left while I am away");
        assertTrue(r.matched(), "expected mailbox, got " + r);
        assertEquals("mailbox", r.template().name());
    }

    @Test
    @DisplayName("a tie between two templates is declined, with both as candidates")
    void tiesAreDeclined() {
        // "lens" names the oracle lens; "globe" names the weather globe — one word each, equal weight.
        var r = library.resolveIntent("lens_globe", null);
        assertFalse(r.matched(), "a two-way tie must not pick a side: " + r);
        assertEquals(2, r.candidates().size());
    }

    @Test
    @DisplayName("stopwords and fragments are not evidence")
    void stopwordsAndFragmentsAreNotEvidence() {
        assertFalse(library.resolveIntent("the_thing_for_and_with", null).matched());
        // "temperature_converter" shares no whole word with any template.
        assertFalse(library.resolveIntent("temperature_converter", "temperature converter").matched());
        assertNull(library.resolveIntent(null, null).template());
    }
}
