package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EPUB extraction — the ebook-directory ingest path. Builds a minimal
 * EPUB (zip + OPF spine + XHTML chapters) and asserts spine-ordered,
 * markup-free text comes out.
 */
class DocumentExtractorEpubTest {

    @TempDir
    Path tmp;

    @Test
    void extracts_chapters_in_spine_order_with_markup_stripped() throws Exception {
        var epub = tmp.resolve("book.epub");
        try (var zip = new ZipOutputStream(Files.newOutputStream(epub))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "OEBPS/content.opf", """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf">
                  <manifest>
                    <item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
                </package>
                """);
            // Entry names sort ch1 < ch2 anyway, so to prove SPINE order is
            // honored the spine puts ch1 first but the zip stores ch2 first.
            put(zip, "OEBPS/chapter2.xhtml",
                "<html><head><title>x</title></head><body><h1>Second</h1>"
                + "<p>The road goes ever on &amp; on.</p></body></html>");
            put(zip, "OEBPS/chapter1.xhtml",
                "<html><head><style>p{color:red}</style></head><body><h1>First</h1>"
                + "<p>In a hole in the ground&#44; there lived a hobbit.</p></body></html>");
        }

        var result = DocumentExtractor.extract(epub);
        assertTrue(result.success(), () -> "extract failed: " + result.error());
        assertFalse(result.chunks().isEmpty());

        var text = result.chunks().get(0).content();
        assertTrue(text.contains("In a hole in the ground, there lived a hobbit."));
        assertTrue(text.contains("The road goes ever on & on."));
        assertTrue(text.indexOf("hobbit") < text.indexOf("road goes"),
            "spine order should put chapter1 before chapter2");
        assertFalse(text.contains("<p>"), "markup must be stripped");
        assertFalse(text.contains("color:red"), "style blocks must be dropped");
    }

    @Test
    void htmlToText_strips_blocks_and_decodes_entities() {
        var text = DocumentExtractor.htmlToText(
            "<head><meta x=\"1\"/></head><body><p>a&nbsp;b</p><p>c &#x26; d</p></body>");
        assertTrue(text.contains("a b"));
        assertTrue(text.contains("c & d"));
        assertFalse(text.contains("meta"));
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
