package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam: a person holds an item, and the item reaches that person's shelves.
 *
 * <h2>What went wrong, and what the old test missed</h2>
 * Home node, 2026-08-25. The steward asked his own fairy-tale tool about Takeshi
 * Kovacs. His 74,681 books were in his Study, his credential row was correctly
 * linked to his person DID — and the journal said <em>"Study leg skipped: caller
 * is not a person and no bondholder resolves to one"</em>. A person holding an
 * item had their {@code library.search} forwarded to ONE SHARED provider that
 * {@code Main} builds with the placeholder identity {@code "household"}, so the
 * question "whose private shelves may I read?" was asked of a placeholder.
 *
 * <p>A test already claimed to cover this. It asserted the ORDER of checks in
 * the provider's source — self-check before bondholder-check — and it passed
 * happily while the feature was dead, because the person's search never entered
 * that code at all. A shape assertion downstream of the break proves nothing;
 * this one runs the path a person actually takes.</p>
 */
class APersonHoldingAnItemReadsTheirOwnShelvesTest {

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        HouseholdResources.resetForTests();
        HouseholdItemContent.resetForTests();
    }

    private static final String STEWARD = "did:key:z6MkStewardOfThisHousehold000000000000000";

    /** A shelf whose contents exist ONLY in the person's Study — never in a pack. */
    private WyrdLuceneStore shelfWithPrivateBooks() throws Exception {
        var store = new WyrdLuceneStore(dir.resolve("idx"), 4);
        var svc = new StudyService(store, null);
        svc.indexDocumentChunk(STEWARD, "books", "Altered Carbon.epub",
            "Takeshi Kovacs woke in a new sleeve on Harlan's World.", "/shelf/ac.epub", 0);
        svc.indexDocumentChunk(STEWARD, "books", "Cryptonomicon.epub",
            "Waterhouse studied the intercepts at Bletchley Park.", "/shelf/cn.epub", 0);
        svc.commitDocuments();
        // A pack that does NOT contain the books, so any hit must come from the
        // Study leg and cannot be an accident of publication.
        store.insertKnowledgeBulk("pack:1", "generic-pack", "Gardening",
            "Mulch the beds before the first frost.", "pack", null, null, null);
        store.commitAll();
        return store;
    }

    @Test
    @DisplayName("a named caller reaches their own Study through a carried item")
    void thePersonsOwnBooksAnswer() throws Exception {
        try (var store = shelfWithPrivateBooks()) {
            HouseholdResources.register(store);

            registerPerCallerContent(store);
            var held = new VisitorItemProvider("home", "home").withCaller(STEWARD);

            var hits = held.searchKnowledge("Takeshi Kovacs sleeve", 5);
            assertThat(titles(hits))
                .as("his own book, from his own hands")
                .anyMatch(t -> t.contains("Altered Carbon"));
        }
    }

    @Test
    @DisplayName("an unnamed caller gets packs only — it does not borrow an identity")
    void anAnonymousSurfaceReachesNoPrivateShelf() throws Exception {
        try (var store = shelfWithPrivateBooks()) {
            HouseholdResources.register(store);

            registerPerCallerContent(store);
            var anonymous = new VisitorItemProvider("home", "home");

            var hits = anonymous.searchKnowledge("Takeshi Kovacs sleeve", 5);
            assertThat(titles(hits))
                .as("no caller means no private reach — never a placeholder's")
                .noneMatch(t -> t.contains("Altered Carbon"));
        }
    }

    @Test
    @DisplayName("the shared household provider has no reach of its own")
    void theSharedInstanceReachesNothing() {
        // The object Main registers is built with agentId "household". Whatever
        // else it can do, it must not answer an authorisation question, because
        // it cannot know who is asking.
        assertThat(StudyReach.NONE.search("Takeshi Kovacs", 5)).isEmpty();
    }

    @Test
    @DisplayName("an identity that resolves to nobody reaches nothing")
    void anUnresolvableCallerIsNotAPerson() throws Exception {
        try (var store = shelfWithPrivateBooks()) {
            HouseholdResources.register(store);
            var reach = PersonStudyReach.forPerson("not-a-person-at-all");
            assertThat(reach.search("Takeshi Kovacs", 5))
                .as("fails closed, quietly, with no borrowed identity")
                .isEmpty();
        }
    }

    /** Exactly what {@code Main} wires: one provider per acting caller. */
    private static void registerPerCallerContent(WyrdLuceneStore store) {
        HouseholdItemContent.registerFactory(callerDid -> {
            var who = callerDid != null && !callerDid.isBlank() ? callerDid : "household";
            return new ItemWorldApiProviderImpl(store, null, null, null, who, who,
                null, null, null, null, null);
        });
    }

    private static List<String> titles(List<Map<String, Object>> hits) {
        return hits.stream().map(h -> String.valueOf(h.getOrDefault("title", ""))).toList();
    }
}
