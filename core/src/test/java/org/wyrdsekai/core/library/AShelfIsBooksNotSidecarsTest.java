package org.wyrdsekai.core.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shelf ingest indexes books, not the files that sit beside them.
 *
 * <h2>What went wrong</h2>
 * The household shelf, 2026-08-25. A full-text ingest of a Calibre library
 * walked every regular file, so 74,681 epubs arrived alongside
 * <strong>74,694 {@code metadata.opf}</strong> files and 72,606 cover images.
 * The images cost only wasted work — the extractor cannot read them. The .opf
 * files were worse: each is pure title/author/description, so BM25 ranks them
 * ABOVE the prose of the book they describe. Asking the published shelf about
 * "Takeshi Kovacs" returned two hits of raw {@code <?xml version='1.0'?>}
 * before any actual writing. The Calibre catalog path already renders that
 * metadata as clean prose, so the sidecar adds nothing but noise.
 */
class AShelfIsBooksNotSidecarsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Calibre sidecars are not documents")
    void sidecarsAreSkipped() {
        assertThat(DocumentIndexer.isSidecar(dir.resolve("metadata.opf"))).isTrue();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("cover.jpg"))).isTrue();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("metadata.db"))).isTrue();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("toc.ncx"))).isTrue();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("SHELF.ZIP"))).isTrue();
    }

    @Test
    @DisplayName("books and papers still are")
    void realDocumentsSurvive() {
        assertThat(DocumentIndexer.isSidecar(dir.resolve("Altered Carbon.epub"))).isFalse();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("notes.md"))).isFalse();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("paper.pdf"))).isFalse();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("config.xml"))).isFalse();
        assertThat(DocumentIndexer.isSidecar(dir.resolve("README"))).isFalse();
    }

    @Test
    @DisplayName("a book folder ingests the book and leaves the sidecars")
    void aBookFolderYieldsOnlyTheBook() throws Exception {
        var shelf = dir.resolve("shelf");
        var book = shelf.resolve("Altered Carbon (42)");
        Files.createDirectories(book);
        Files.writeString(book.resolve("Altered Carbon.txt"),
            "Takeshi Kovacs woke in a new sleeve on Harlan's World. " + "prose ".repeat(200));
        Files.writeString(book.resolve("metadata.opf"),
            "<?xml version='1.0'?><package><title>Altered Carbon</title></package>");
        Files.write(book.resolve("cover.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        try (var store = new WyrdLuceneStore(dir.resolve("idx"), 4)) {
            var svc = new StudyService(store, null);
            var result = new DocumentIndexer(svc).indexDirectory(
                "did:person:steward", "books", shelf, null);

            assertThat(result.filesProcessed())
                .as("one book — not the opf, not the cover")
                .isEqualTo(1);

            // The book's prose is there...
            assertThat(svc.searchAllDocuments("did:person:steward", "Kovacs sleeve", 10))
                .as("the book is findable").isNotEmpty();
            // ...and no XML came with it.
            assertThat(svc.searchAllDocuments("did:person:steward", "package title", 10))
                .as("the metadata.opf never became a document")
                .noneMatch(r -> r.content().contains("<?xml"));
        }
    }
}
