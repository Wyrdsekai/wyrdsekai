package org.wyrdsekai.core.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cleaning sidecars a previous ingest already wrote — without republishing.
 *
 * <h2>Why</h2>
 * {@link DocumentIndexer} now skips {@code metadata.opf} and friends, but the
 * household shelf had already taken in 74,694 of them and published them
 * zone-wide. Re-ingesting would mean re-extracting 74,681 epubs; republishing
 * costs another ~90 minutes. Neither is necessary: a Study document's TITLE is
 * the file name it came from, and a published knowledge chunk's id is derived
 * as {@code pack:studyId} — so one scan can find the sidecars and drop both
 * copies in place.
 */
class SidecarsCanBeSweptOutAfterTheFactTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a chunked file's title still reads as its file name")
    void titlesIdentifySidecars() {
        assertThat(StudyService.isSidecarTitle("metadata.opf")).isTrue();
        assertThat(StudyService.isSidecarTitle("metadata.opf (part 2/7)")).isTrue();
        assertThat(StudyService.isSidecarTitle("cover.jpg")).isTrue();
        assertThat(StudyService.isSidecarTitle("Altered Carbon.epub (part 3/9)")).isFalse();
        assertThat(StudyService.isSidecarTitle("notes.md")).isFalse();
        assertThat(StudyService.isSidecarTitle("")).isFalse();
    }

    @Test
    @DisplayName("the prune drops the sidecar from the shelf AND from the published pack")
    void bothCopiesGo() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("idx"), 4)) {
            var svc = new StudyService(store, null);

            // A shelf as an older ingest left it: real books plus sidecars.
            store.insertStudyItem("doc:steward:books:aaa", "did:person:steward", "document",
                "Altered Carbon.epub (part 1/2)",
                "Takeshi Kovacs woke in a new sleeve on Harlan's World.",
                "books", 1000L, 1, null);
            store.insertStudyItem("doc:steward:books:bbb", "did:person:steward", "document",
                "metadata.opf",
                "<?xml version='1.0'?><package><title>Altered Carbon</title></package>",
                "books", 1001L, 1, null);
            store.insertStudyItem("doc:steward:books:ccc", "did:person:steward", "document",
                "cover.jpg", "binary noise", "books", 1002L, 1, null);
            store.commitAll();

            // Published: knowledge ids derive from the study ids.
            for (var id : new String[]{"aaa", "bbb", "ccc"}) {
                store.insertKnowledgeBulk("study-share-books:doc:steward:books:" + id,
                    "study-share-books", "t", "content " + id, "study-share", null, null, null);
            }
            store.commitAll();

            var result = svc.pruneSidecars("did:person:steward", "books", "study-share-books");

            assertThat(result.scanned()).isEqualTo(3);
            assertThat(result.studyRemoved()).as("the opf and the cover").isEqualTo(2);
            assertThat(result.knowledgeRemoved())
                .as("their published copies go in the same pass — no republish")
                .isEqualTo(2);

            // The book itself is untouched, on both surfaces.
            assertThat(svc.searchAllDocuments("did:person:steward", "Kovacs sleeve", 10))
                .as("the book survives").isNotEmpty();
            assertThat(store.countKnowledgeByPack("study-share-books"))
                .as("one book chunk left in the pack").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a shelf with nothing to prune is left alone")
    void aCleanShelfIsUntouched() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("idx2"), 4)) {
            var svc = new StudyService(store, null);
            store.insertStudyItem("doc:steward:clean:aaa", "did:person:steward", "document",
                "Cryptonomicon.epub", "Waterhouse at Bletchley.", "clean", 1000L, 1, null);
            store.commitAll();

            var result = svc.pruneSidecars("did:person:steward", "clean", "study-share-clean");
            assertThat(result.scanned()).isEqualTo(1);
            assertThat(result.studyRemoved()).isZero();
            assertThat(svc.searchAllDocuments("did:person:steward", "Bletchley", 10)).isNotEmpty();
        }
    }
}
