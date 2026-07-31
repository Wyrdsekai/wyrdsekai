package org.wyrdsekai.core.library;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 75k-ebook hardening pass:
 *  - deterministic chunk ids (the old millisecond-timestamp ids collided
 *    within a single book and silently overwrote sibling chunks)
 *  - idempotent re-runs (re-ingesting upserts instead of duplicating)
 *  - IngestLedger resume (second run skips already-indexed files)
 *  - Calibre metadata.db catalog mode (card catalog without book text)
 */
class BulkIngestHardeningTest {

    @TempDir
    Path tmp;

    private WyrdLuceneStore store;
    private StudyService study;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp.resolve("search"), 384);
        study = new StudyService(store, null);
    }

    @Test
    void chunk_ids_are_deterministic_and_distinct_per_chunk() {
        var a0 = StudyService.documentChunkId("u", "books", "/lib/a.epub#0");
        var a0again = StudyService.documentChunkId("u", "books", "/lib/a.epub#0");
        var a1 = StudyService.documentChunkId("u", "books", "/lib/a.epub#1");
        var b0 = StudyService.documentChunkId("u", "books", "/lib/b.epub#0");
        assertEquals(a0, a0again, "same (file, chunk) must produce the same id");
        assertNotEquals(a0, a1, "sibling chunks must not collide");
        assertNotEquals(a0, b0, "different files must not collide");
    }

    @Test
    void sibling_chunks_indexed_in_same_millisecond_all_survive() {
        // The old timestamp-id scheme lost these.
        for (int i = 0; i < 50; i++) {
            study.indexDocumentChunk("u", "books", "Book (part " + i + ")",
                "chunk content " + i, "/lib/book.epub", i);
        }
        study.commitDocuments();
        for (int i = 0; i < 50; i++) {
            assertTrue(study.hasDocumentChunk("u", "books", "/lib/book.epub", i),
                "chunk " + i + " must be present");
        }
    }

    @Test
    void reingest_upserts_instead_of_duplicating() throws Exception {
        var dir = tmp.resolve("docs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("note.txt"), "The quiet hum of the archive.");

        var indexer = new DocumentIndexer(study);
        indexer.indexFile("u", "docs", dir.resolve("note.txt"));
        indexer.indexFile("u", "docs", dir.resolve("note.txt"));
        study.commitDocuments();

        var hits = study.searchDocuments("u", "docs", "archive", 10);
        assertEquals(1, hits.size(), "re-indexing the same file must not duplicate");
    }

    @Test
    void ledger_resumes_skipping_done_files_and_reindexes_changed_ones() throws Exception {
        var ledgerFile = tmp.resolve("test.ledger");
        var docs = tmp.resolve("books");
        Files.createDirectories(docs);
        var a = Files.writeString(docs.resolve("a.txt"), "alpha");
        var b = Files.writeString(docs.resolve("b.txt"), "beta");

        try (var ledger = IngestLedger.openAt(ledgerFile)) {
            assertFalse(ledger.isDone(a));
            ledger.markDone(a);
            ledger.markDone(b);
            assertTrue(ledger.isDone(a));
            ledger.flush();
        }

        // Reopen (simulating a crashed run restarting) — both still done.
        try (var ledger = IngestLedger.openAt(ledgerFile)) {
            assertEquals(2, ledger.doneCount());
            assertTrue(ledger.isDone(a));
            assertTrue(ledger.isDone(b));

            // A modified file (different mtime) must be re-indexed.
            Files.writeString(b, "beta v2");
            Files.setLastModifiedTime(b,
                FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            assertFalse(ledger.isDone(b), "changed file must not count as done");
        }
    }

    @Test
    void calibre_catalog_mode_indexes_metadata_without_book_text() throws Exception {
        var lib = tmp.resolve("calibre");
        Files.createDirectories(lib);
        buildMetadataDb(lib.resolve("metadata.db"));

        assertTrue(CalibreCatalogIndexer.isCalibreLibrary(lib));
        assertFalse(CalibreCatalogIndexer.isCalibreLibrary(tmp.resolve("nope")));

        var messages = new ArrayList<String>();
        var result = new CalibreCatalogIndexer(study)
            .indexCatalog("u", "shelf", lib, messages::add);
        assertEquals(2, result.books());
        assertEquals(0, result.errors());

        var hits = study.searchDocuments("u", "shelf", "hobbit", 10);
        assertEquals(1, hits.size());
        assertNotNull(hits.getFirst().content());
        assertTrue(hits.getFirst().content().contains("Tolkien"));
        assertTrue(hits.getFirst().content().contains("unexpected journey"),
            "description (HTML-stripped) should be searchable");
        assertFalse(hits.getFirst().content().contains("<p>"), "markup must be stripped");

        // Re-running the catalog upserts — no duplicates.
        new CalibreCatalogIndexer(study).indexCatalog("u", "shelf", lib, null);
        assertEquals(1, study.searchDocuments("u", "shelf", "hobbit", 10).size());
    }

    /** Minimal Calibre-shaped metadata.db: 2 books, authors, tags, comments. */
    private static void buildMetadataDb(Path db) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, "
                + "path TEXT, pubdate TEXT, series_index REAL)");
            st.executeUpdate("CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT)");
            st.executeUpdate("CREATE TABLE books_authors_link (book INTEGER, author INTEGER)");
            st.executeUpdate("CREATE TABLE series (id INTEGER PRIMARY KEY, name TEXT)");
            st.executeUpdate("CREATE TABLE books_series_link (book INTEGER, series INTEGER)");
            st.executeUpdate("CREATE TABLE comments (book INTEGER, text TEXT)");
            st.executeUpdate("CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT)");
            st.executeUpdate("CREATE TABLE books_tags_link (book INTEGER, tag INTEGER)");
            st.executeUpdate("CREATE TABLE data (book INTEGER, format TEXT)");

            st.executeUpdate("INSERT INTO books VALUES (1, 'The Hobbit', "
                + "'J. R. R. Tolkien/The Hobbit (1)', '1937-09-21', 1.0)");
            st.executeUpdate("INSERT INTO books VALUES (2, 'Dune', "
                + "'Frank Herbert/Dune (2)', '1965-08-01', 1.0)");
            st.executeUpdate("INSERT INTO authors VALUES (1, 'J. R. R. Tolkien')");
            st.executeUpdate("INSERT INTO authors VALUES (2, 'Frank Herbert')");
            st.executeUpdate("INSERT INTO books_authors_link VALUES (1, 1)");
            st.executeUpdate("INSERT INTO books_authors_link VALUES (2, 2)");
            st.executeUpdate("INSERT INTO comments VALUES (1, "
                + "'<p>Bilbo Baggins sets out on an <i>unexpected journey</i>.</p>')");
            st.executeUpdate("INSERT INTO tags VALUES (1, 'Fantasy')");
            st.executeUpdate("INSERT INTO books_tags_link VALUES (1, 1)");
            st.executeUpdate("INSERT INTO data VALUES (1, 'EPUB')");
            st.executeUpdate("INSERT INTO data VALUES (2, 'EPUB')");
        }
    }
}
