package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A note she adds is a note she can list.
 *
 * <p>{@code world.notes.list} enumerated with a TEXT_ONLY {@code "*"} query, which matches
 * the literal token and nothing else — so every item that asked for its notes got an empty
 * list, in production, since the API existed. Found 2026-09-03 when her quiet place was
 * rewritten to keep what she brings and then could not show it. The store's own comment on
 * {@code listAllStudy} already said "*" can't be used for enumeration; this pins the
 * match-all path for the notes listing and the findings ledger.
 */
class StudyNotesCanBeListedTest {

    @TempDir Path dir;
    private WyrdLuceneStore store;

    @BeforeEach void setUp() { store = new WyrdLuceneStore(dir, 384); store.ensureAllCollections(); }
    @AfterEach void tearDown() throws Exception { store.close(); }

    @Test
    void a_wildcard_text_query_is_not_an_enumeration_but_the_recent_listing_is() {
        var study = new StudyService(store);
        study.addNote("did:key:z6MkHer", "[quiet place] that I miss him more in the mornings");
        study.addNote("did:key:z6MkHer", "[quiet place] the reading corner needs a lamp");

        assertEquals(0, store.searchStudyByType("did:key:z6MkHer", "note", "*", 100).size(),
            "the literal-token query is the bug this test exists for");
        var listed = store.listStudyByTypeRecent("did:key:z6MkHer", "note", 100);
        assertEquals(2, listed.size());
        assertTrue(listed.stream().allMatch(r -> r.content().startsWith("[quiet place] ")));
        assertEquals(0, store.listStudyByTypeRecent("did:key:z6MkSomeoneElse", "note", 100).size());
    }

    @Test
    void the_item_api_lists_notes_through_the_match_all_path() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/item/ItemWorldApiProviderImpl.java"));
        int at = src.indexOf("public List<Map<String, Object>> notesList(");
        assertTrue(at > 0);
        var body = src.substring(at, at + 900);
        assertTrue(body.contains("listStudyByTypeRecent(agentId, \"note\""), "notesList must enumerate, not text-search");
        assertFalse(body.contains("searchStudyByType(agentId, \"note\", \"*\""), "no wildcard text query in notesList");
    }
}
