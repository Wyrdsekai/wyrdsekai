package org.wyrdsekai.core.library;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DocumentExtractor (text extraction + chunking) and DocumentIndexer.
 */
class DocumentExtractorTest {

    private static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("doc-extract-test-");
    }

    @Test
    void extract_plain_text() throws Exception {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "This is a plain text document.\n\nIt has two paragraphs.");

        var result = DocumentExtractor.extract(file);
        assertTrue(result.success());
        assertFalse(result.chunks().isEmpty());
        assertTrue(result.chunks().getFirst().content().contains("plain text"));
    }

    @Test
    void extract_markdown() throws Exception {
        var file = tempDir.resolve("readme.md");
        Files.writeString(file, """
            # Project Title

            This is the introduction paragraph with enough words to be meaningful.

            ## Section Two

            More content in the second section about various topics.
            """);

        var result = DocumentExtractor.extract(file);
        assertTrue(result.success());
        assertFalse(result.chunks().isEmpty());
        assertTrue(result.chunks().getFirst().content().contains("Project Title"));
    }

    @Test
    void extract_csv() throws Exception {
        var file = tempDir.resolve("data.csv");
        Files.writeString(file, "name,age,city\nAlice,30,Tokyo\nBob,25,Osaka\n");

        var result = DocumentExtractor.extract(file);
        assertTrue(result.success());
        assertTrue(result.chunks().getFirst().content().contains("Alice"));
    }

    @Test
    void chunk_long_text() {
        var sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("This is paragraph number ").append(i).append(" with some filler content to make it longer. ");
            sb.append("Each paragraph needs enough words to eventually trigger chunking at the 500 word boundary.\n\n");
        }

        var chunks = DocumentExtractor.chunkText("long-doc.txt", sb.toString());
        assertTrue(chunks.size() > 1, "Long document should produce multiple chunks");
        assertEquals(chunks.size(), chunks.getFirst().totalChunks());
    }

    @Test
    void empty_file_returns_error() throws Exception {
        var file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        var result = DocumentExtractor.extract(file);
        assertFalse(result.success());
    }

    @Test
    void extract_directory() throws Exception {
        var subdir = tempDir.resolve("docs");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("a.txt"), "Document A content about gardening.");
        Files.writeString(subdir.resolve("b.md"), "# Document B\n\nContent about cooking.");
        Files.writeString(subdir.resolve("c.csv"), "item,price\napples,2.50\nbread,3.00");

        var results = DocumentExtractor.extractDirectory(subdir);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(DocumentExtractor.ExtractionResult::success));
    }

    @Test
    void document_indexer_indexes_directory() throws Exception {
        var indexDir = Files.createTempDirectory("doc-index-test-");
        var store = new WyrdLuceneStore(indexDir.resolve("search"), 384);
        store.ensureAllCollections();
        var study = new StudyService(store);
        var indexer = new DocumentIndexer(study);

        var docsDir = tempDir.resolve("indexable");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("tax-form.txt"),
            "Tax deduction for home office expenses. Section 179 depreciation applies to business equipment.");
        Files.writeString(docsDir.resolve("recipe.md"),
            "# Sourdough Bread\n\nMix flour, water, salt, and starter. Let rise for 12 hours.");

        var result = indexer.indexDirectory("did:key:test", "test-docs", docsDir, null);
        assertTrue(result.success());
        assertEquals(2, result.filesProcessed());
        assertTrue(result.chunksIndexed() >= 2);

        // Search should find the tax document
        var searchResults = study.searchDocuments("did:key:test", "test-docs", "home office deduction", 5);
        assertFalse(searchResults.isEmpty(), "Should find tax document via search");

        store.close();
    }
}
