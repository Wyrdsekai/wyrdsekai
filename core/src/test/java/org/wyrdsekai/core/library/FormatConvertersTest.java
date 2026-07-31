package org.wyrdsekai.core.library;

import org.junit.jupiter.api.*;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for format converters: EPUB, StackExchange XML, HTML stripping.
 * Parquet test is separate (requires parquet file fixture).
 */
class FormatConvertersTest {

    private static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("converter-test-");
    }

    // --- HTML stripping ---

    @Test
    void strip_html_removes_tags() {
        var result = FormatConverters.stripHtml("<p>Hello <b>world</b></p>");
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("world"));
        assertFalse(result.contains("<p>"));
        assertFalse(result.contains("<b>"));
    }

    @Test
    void strip_html_decodes_entities() {
        var result = FormatConverters.stripHtml("&amp; &lt; &gt; &quot; &#39;");
        assertTrue(result.contains("&"));
        assertTrue(result.contains("<"));
        assertTrue(result.contains(">"));
    }

    @Test
    void strip_html_removes_scripts_and_styles() {
        var result = FormatConverters.stripHtml(
            "<p>Text</p><script>alert('xss')</script><style>.x{}</style><p>More</p>");
        assertTrue(result.contains("Text"));
        assertTrue(result.contains("More"));
        assertFalse(result.contains("alert"));
        assertFalse(result.contains(".x"));
    }

    @Test
    void strip_html_handles_null() {
        assertEquals("", FormatConverters.stripHtml(null));
    }

    // --- EPUB ---

    @Test
    void convert_epub_extracts_content() throws Exception {
        // Create a minimal EPUB (it's just a ZIP with XHTML)
        var epubFile = tempDir.resolve("test.epub");
        var outputFile = tempDir.resolve("test-epub.jsonl");

        try (var zos = new ZipOutputStream(
                new FileOutputStream(epubFile.toFile()))) {

            // Add mimetype
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes());
            zos.closeEntry();

            // Add a content file
            zos.putNextEntry(new ZipEntry("OEBPS/chapter1.xhtml"));
            zos.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <html><body>
                <h1>Chapter One</h1>
                <p>It was the best of times, it was the worst of times, it was the age of wisdom,
                it was the age of foolishness. This is a test paragraph with enough content to be
                meaningful for chunking purposes. The story continues with more text about various
                topics and themes that are relevant to the narrative.</p>
                </body></html>
                """.getBytes());
            zos.closeEntry();

            // Add a second chapter
            zos.putNextEntry(new ZipEntry("OEBPS/chapter2.xhtml"));
            zos.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <html><body>
                <h1>Chapter Two</h1>
                <p>The second chapter explores different themes entirely. Here we find characters
                dealing with complex situations that require careful thought and consideration.</p>
                </body></html>
                """.getBytes());
            zos.closeEntry();
        }

        int chunks = FormatConverters.convertEpub(epubFile, outputFile, "test-book", null);

        assertTrue(chunks > 0, "Should produce at least one chunk");
        assertTrue(Files.exists(outputFile));
        var content = Files.readString(outputFile);
        assertTrue(content.contains("best of times"), "Should contain chapter content");
        assertTrue(content.contains("test-book:"), "Chunk IDs should use pack name");
    }

    @Test
    void convert_epub_skips_toc() throws Exception {
        var epubFile = tempDir.resolve("toc-test.epub");
        var outputFile = tempDir.resolve("toc-test.jsonl");

        try (var zos = new ZipOutputStream(
                new FileOutputStream(epubFile.toFile()))) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes());
            zos.closeEntry();

            // TOC file — should be skipped
            zos.putNextEntry(new ZipEntry("OEBPS/toc.xhtml"));
            zos.write("<html><body><nav>Table of Contents</nav></body></html>".getBytes());
            zos.closeEntry();

            // Real content
            zos.putNextEntry(new ZipEntry("OEBPS/content.xhtml"));
            zos.write("<html><body><p>This is real content that should be indexed and is long enough.</p></body></html>".getBytes());
            zos.closeEntry();
        }

        int chunks = FormatConverters.convertEpub(epubFile, outputFile, "toc-test", null);
        var content = Files.readString(outputFile);
        assertFalse(content.contains("Table of Contents"), "TOC should be skipped");
    }

    // --- StackExchange XML ---

    @Test
    void convert_stackexchange_xml() throws Exception {
        var xmlFile = tempDir.resolve("Posts.xml");
        var outputFile = tempDir.resolve("se-test.jsonl");

        Files.writeString(xmlFile, """
            <?xml version="1.0" encoding="utf-8"?>
            <posts>
              <row Id="1" PostTypeId="1" Title="How do I make sourdough bread?" Body="&lt;p&gt;I want to make sourdough bread at home. What do I need?&lt;/p&gt;" />
              <row Id="2" PostTypeId="2" ParentId="1" Body="&lt;p&gt;You need flour, water, salt, and a sourdough starter. Mix them together and let the dough ferment for 12-24 hours.&lt;/p&gt;" />
              <row Id="3" PostTypeId="1" Title="Best temperature for baking pizza?" Body="&lt;p&gt;What temperature should I bake pizza at?&lt;/p&gt;" />
              <row Id="4" PostTypeId="2" ParentId="3" Body="&lt;p&gt;For Neapolitan style, as hot as your oven goes - ideally 450-500C. For home ovens, 250C with a pizza stone works well.&lt;/p&gt;" />
            </posts>
            """);

        int count = FormatConverters.convertStackExchangeXml(xmlFile, outputFile, "cooking-se", null);

        assertEquals(2, count, "Should produce 2 Q&A pairs");
        var content = Files.readString(outputFile);
        assertTrue(content.contains("sourdough"), "Should contain sourdough question");
        assertTrue(content.contains("flour, water, salt"), "Should contain answer content");
        assertTrue(content.contains("pizza"), "Should contain pizza question");
        assertTrue(content.contains("cooking-se:"), "Chunk IDs should use pack name");
    }

    @Test
    void convert_stackexchange_xml_strips_html_in_body() throws Exception {
        var xmlFile = tempDir.resolve("HtmlPosts.xml");
        var outputFile = tempDir.resolve("html-test.jsonl");

        Files.writeString(xmlFile, """
            <?xml version="1.0" encoding="utf-8"?>
            <posts>
              <row Id="10" PostTypeId="1" Title="Test question" Body="&lt;p&gt;This has &lt;b&gt;bold&lt;/b&gt; and &lt;a href=&quot;http://example.com&quot;&gt;links&lt;/a&gt;&lt;/p&gt;" />
              <row Id="11" PostTypeId="2" ParentId="10" Body="&lt;p&gt;The answer with &lt;code&gt;code blocks&lt;/code&gt; and formatting.&lt;/p&gt;" />
            </posts>
            """);

        FormatConverters.convertStackExchangeXml(xmlFile, outputFile, "html-test", null);
        var content = Files.readString(outputFile);
        assertFalse(content.contains("<b>"), "HTML tags should be stripped");
        assertFalse(content.contains("<code>"), "HTML tags should be stripped");
        assertTrue(content.contains("bold"), "Text content should be preserved");
    }

    // --- PackDownloader integration ---

    @Test
    void pack_downloader_handles_local_jsonl() throws Exception {
        // Create a local JSONL file
        var sourceDir = tempDir.resolve("local-source");
        Files.createDirectories(sourceDir);
        var jsonlFile = sourceDir.resolve("data.jsonl");
        Files.writeString(jsonlFile, """
            {"id":"loc:1","packName":"local","title":"Test","content":"Local content for testing","source":"test"}
            {"id":"loc:2","packName":"local","title":"Test 2","content":"More local content here","source":"test"}
            """);

        var targetDir = tempDir.resolve("local-target");
        PackDownloader.download(sourceDir.toAbsolutePath().toString(), targetDir, null);

        // Should have copied the JSONL into chunks/
        assertTrue(Files.exists(targetDir.resolve("chunks")) ||
            Files.exists(targetDir.resolve("data.jsonl")),
            "Should have content in target directory");
    }

    // --- MedQuAD XML (recurses through the GitHub "<repo>-master/" wrapper dir) ---

    /**
     * Regression for the 7-chunk medquad bug: the GitHub zip extracts a wrapper
     * dir, so the per-question .xml land two levels below packDir. The converter
     * must walk recursively and find them all — an immediate-subdir check missed
     * the wrapper and fell through to the text-tree path (readme/license only).
     */
    @Test
    void medquad_converter_walks_into_wrapper_dir() throws Exception {
        var packDir = tempDir.resolve("medquad-pack");
        // Mirror the real layout: packDir/<repo>-master/<category>/*.xml
        var category = packDir.resolve("MedQuAD-master").resolve("6_NINDS_QA");
        Files.createDirectories(category);
        Files.writeString(category.resolve("0000081.xml"), medquadXml("Colpocephaly", 3));
        Files.writeString(category.resolve("0000117.xml"), medquadXml("Anencephaly", 2));
        // A readme at the wrapper root — the kind of file the old path mis-counted.
        Files.writeString(packDir.resolve("MedQuAD-master").resolve("readme.txt"), "MedQuAD corpus");

        var out = packDir.resolve("chunks").resolve("medquad.jsonl");
        Files.createDirectories(out.getParent());
        int count = FormatConverters.convertMedQuadXml(packDir, out, "medquad", null);

        assertEquals(5, count, "should extract all QA pairs from both nested xml files");
        long lines = Files.readAllLines(out).stream().filter(l -> !l.isBlank()).count();
        assertEquals(5, lines);
        assertTrue(Files.readString(out).contains("Colpocephaly"), "answer content preserved");
    }

    /** Build a MedQuAD-shaped <Document> with {@code n} QAPairs about {@code focus}. */
    private static String medquadXml(String focus, int n) {
        var sb = new StringBuilder("<?xml version=\"1.0\"?>\n<Document id=\"x\" source=\"NINDS\">\n");
        sb.append("<Focus>").append(focus).append("</Focus>\n<QAPairs>\n");
        for (int i = 1; i <= n; i++) {
            sb.append("  <QAPair pid=\"").append(i).append("\">\n")
              .append("    <Question qid=\"x-").append(i).append("\" qtype=\"information\">")
              .append("What is ").append(focus).append(" question ").append(i).append(" ?</Question>\n")
              .append("    <Answer>").append(focus)
              .append(" is a congenital brain abnormality described in detail here, pair ")
              .append(i).append(".</Answer>\n  </QAPair>\n");
        }
        sb.append("</QAPairs>\n</Document>\n");
        return sb.toString();
    }
}
