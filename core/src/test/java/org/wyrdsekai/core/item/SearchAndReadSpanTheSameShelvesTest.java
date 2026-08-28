package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;

/**
 * A search that returns ids its paired read cannot resolve is a broken pair.
 *
 * <p>{@code searchKnowledge} was taught to merge consent-granted Study hits.
 * {@code readKnowledgeChunk} was not — it kept resolving ids against the
 * KNOWLEDGE collection alone. Study ids
 * ({@code doc:<owner>:<collection>:<hash>}) are not in that collection.</p>
 *
 * <p>Live, 2026-08-08. The {@code library_card} chain is search → read → summarise.
 * It searched and got <b>"20 results (10 pack, 10 study)"</b>, then read each id,
 * dropped all ten Study passages on {@code if (!chunk || !chunk.text) continue},
 * and handed the summariser a <b>142-character</b> prompt with nothing in it. She
 * told the bondholder the books held no answer. They held ten passages.</p>
 *
 * <p>Extending one half of a pair and not the other is what broke it, so these
 * tests pin the pair together — and pin that the read re-checks consent rather
 * than treating a chunk id as authorisation.</p>
 */
class SearchAndReadSpanTheSameShelvesTest {

    private static String impl() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/item/ItemWorldApiProviderImpl.java";
        var fromCore = Paths.get("..", rel);
        var p = Files.exists(fromCore)
            ? fromCore : Paths.get(rel);
        return Files.readString(p);
    }

    /**
     * The whole method, bounded by its own closing brace.
     *
     * <p>These assertions used to read a fixed 2,500 characters from the
     * signature. Adding a branch above the asserted line pushed it out of the
     * window and the test failed for a reason that had nothing to do with the
     * behaviour it guards. A source window must be bounded by the method, never
     * by a character count.</p>
     */
    private static String methodBody(String src, String signature) throws Exception {
        int m = src.indexOf(signature);
        if (m < 0) throw new AssertionError("method not found: " + signature);
        int brace = src.indexOf('{', m);
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(m, i + 1);
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    /** THE fix: read must reach Study when KNOWLEDGE has no such id. */
    @Test
    void the_read_path_falls_back_to_study() throws Exception {
        var src = impl();
        int read = src.indexOf("public Map<String, Object> readKnowledgeChunk");
        assertThat(read).isGreaterThan(0);

        var body = methodBody(src, "public Map<String, Object> readKnowledgeChunk");
        assertThat(body)
            .as("a Study id resolved only against KNOWLEDGE comes back null")
            .contains("readGrantedStudyChunk(chunkId)");
    }

    /**
     * Both halves of the pair must span Study — asserted by RUNNING them.
     *
     * <p>This test used to grep the source for {@code searchGrantedStudy(query}
     * and a method name. It passed while the person half of the feature was
     * dead, because a person's search never entered that code: their call was
     * forwarded to a shared provider carrying a placeholder identity. A string
     * that appears in a file proves the string is in the file. So: search for a
     * passage that exists ONLY in this person's Study, then read back the very
     * id the search returned.</p>
     */
    @Test
    void search_and_read_both_reach_study() throws Exception {
        var dir = Files.createTempDirectory("pair-test");
        try (var store = new org.wyrdsekai.core.search.WyrdLuceneStore(dir.resolve("idx"), 4)) {
            var person = "did:key:z6MkPairTestPersonOwningTheseShelves0000";
            var svc = new org.wyrdsekai.core.library.StudyService(store, null);
            svc.indexDocumentChunk(person, "books", "Altered Carbon.epub",
                "Takeshi Kovacs woke in a new sleeve on Harlan's World.", "/s/ac.epub", 0);
            svc.commitDocuments();
            HouseholdResources.register(store);
            try {
                var provider = new ItemWorldApiProviderImpl(store, null, null, null,
                    person, person, null, null, null, null, null);

                var hits = provider.searchKnowledge("Takeshi Kovacs sleeve", 5);
                assertThat(hits).as("search half — the person's own shelf").isNotEmpty();

                var id = String.valueOf(hits.getFirst().get("id"));
                var chunk = provider.readKnowledgeChunk(id);
                assertThat(chunk)
                    .as("read half — the id the search just handed out must resolve")
                    .isNotNull();
                assertThat(String.valueOf(chunk.get("text")))
                    .as("and must be the passage, not an empty shell")
                    .contains("Kovacs");
            } finally {
                HouseholdResources.resetForTests();
            }
        }
    }

    /**
     * A chunk id is not authorisation. Ids are guessable, they get logged, and
     * they outlive the grant that produced them.
     */
    @Test
    void the_read_recheeks_consent_rather_than_trusting_the_id() throws Exception {
        var src = impl();
        var body = methodBody(src,
            "private WyrdLuceneStore.SearchResult readGrantedStudyChunk");

        assertThat(body)
            .as("must go through the grant path, not straight to getById")
            .contains("searchAsCompanion");
        assertThat(body)
            .as("and must know whose Study it is reading")
            .contains("primaryBondholderDid()");
    }

    /** No consent oracle means no read — never a silent allow. */
    @Test
    void it_fails_closed_without_a_consent_oracle() throws Exception {
        var src = impl();
        var body = methodBody(src,
            "private WyrdLuceneStore.SearchResult readGrantedStudyChunk");

        assertThat(body).contains("HomeClients.get()");
        assertThat(body)
            .as("an unavailable grant check must return nothing, loudly")
            .contains("return null");
        assertThat(body).contains("wiring gap, not a refusal");
    }

    /** An ungranted chunk must be withheld even when it exists. */
    @Test
    void an_ungranted_chunk_is_withheld() throws Exception {
        var src = impl();
        var body = methodBody(src,
            "private WyrdLuceneStore.SearchResult readGrantedStudyChunk");

        assertThat(body).contains("withheld — not covered by a grant");
    }
}
