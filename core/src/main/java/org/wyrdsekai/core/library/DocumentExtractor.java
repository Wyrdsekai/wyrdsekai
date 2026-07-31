package org.wyrdsekai.core.library;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts text content from documents for Study indexing.
 * Supports: PDF (PDFBox), DOCX/PPTX (Apache POI), EPUB (zip+XHTML, spine-ordered),
 * Markdown, plain text. Chunks content into ~500-word segments for Lucene indexing.
 */
public final class DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractor.class);
    private static final int CHUNK_TARGET_WORDS = 500;
    private static final int CHUNK_OVERLAP_WORDS = 50;

    private DocumentExtractor() {}

    /** Extracted chunk from a document. */
    public record Chunk(String title, String content, int chunkIndex, int totalChunks) {}

    /** Result of extracting a single file. */
    public record ExtractionResult(Path file, String title, List<Chunk> chunks, String error) {
        public boolean success() { return error == null; }
    }

    /**
     * Extract text from a file and chunk it.
     */
    public static ExtractionResult extract(Path file) {
        var filename = file.getFileName().toString();
        var ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";

        try {
            String rawText = switch (ext) {
                case "pdf" -> extractPdf(file);
                case "docx" -> extractDocx(file);
                case "pptx" -> extractPptx(file);
                case "epub" -> extractEpub(file);
                case "md", "markdown" -> Files.readString(file);
                case "txt", "text", "log", "csv", "tsv", "json", "xml", "yaml", "yml",
                     "html", "htm", "rtf", "org" -> Files.readString(file);
                default -> {
                    // Try reading as text — if it fails, skip
                    try {
                        yield Files.readString(file);
                    } catch (Exception e) {
                        yield null;
                    }
                }
            };

            if (rawText == null || rawText.isBlank()) {
                return new ExtractionResult(file, filename, List.of(), "Empty or unreadable file");
            }

            var chunks = chunkText(filename, rawText);
            return new ExtractionResult(file, filename, chunks, null);

        } catch (Exception e) {
            log.debug("[DocumentExtractor] Failed to extract {}: {}", filename, e.getMessage());
            return new ExtractionResult(file, filename, List.of(), e.getMessage());
        }
    }

    /**
     * Extract text from a PDF using PDFBox.
     */
    private static String extractPdf(Path file) throws IOException {
        try (var doc = Loader.loadPDF(file.toFile())) {
            var stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    /**
     * Extract text from a DOCX using Apache POI.
     */
    private static String extractDocx(Path file) throws Exception {
        try (var fis = Files.newInputStream(file);
             var doc = new XWPFDocument(fis)) {
            var sb = new StringBuilder();
            for (var para : doc.getParagraphs()) {
                sb.append(para.getText()).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Extract text from a PPTX using Apache POI.
     */
    private static String extractPptx(Path file) throws Exception {
        try (var fis = Files.newInputStream(file);
             var ppt = new XMLSlideShow(fis)) {
            var sb = new StringBuilder();
            for (var slide : ppt.getSlides()) {
                for (var shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        sb.append(textShape.getText()).append("\n");
                    }
                }
                sb.append("\n---\n");
            }
            return sb.toString();
        }
    }

    /**
     * Extract text from an EPUB. An EPUB is a zip of XHTML documents; we
     * read them in spine order when the OPF manifest is parseable, else in
     * sorted entry order, and strip markup to plain text.
     */
    private static String extractEpub(Path file) throws IOException {
        try (var zip = new ZipFile(file.toFile())) {
            var htmlEntries = new ArrayList<ZipEntry>();
            ZipEntry opfEntry = null;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                var name = e.getName().toLowerCase();
                if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                    htmlEntries.add(e);
                } else if (name.endsWith(".opf") && opfEntry == null) {
                    opfEntry = e;
                }
            }
            htmlEntries.sort(Comparator.comparing(ZipEntry::getName));

            // Reorder by spine when the OPF is readable (regex-light parse;
            // falls back to sorted order on any mismatch).
            if (opfEntry != null) {
                try {
                    var opf = new String(zip.getInputStream(opfEntry).readAllBytes(),
                        StandardCharsets.UTF_8);
                    var ordered = spineOrder(opf, opfEntry.getName(), htmlEntries);
                    if (!ordered.isEmpty()) htmlEntries = ordered;
                } catch (Exception e) {
                    log.debug("[DocumentExtractor] epub spine parse failed for {}: {}",
                        file.getFileName(), e.getMessage());
                }
            }

            var sb = new StringBuilder();
            for (var entry : htmlEntries) {
                var html = new String(zip.getInputStream(entry).readAllBytes(),
                    StandardCharsets.UTF_8);
                var text = htmlToText(html);
                if (!text.isBlank()) sb.append(text).append("\n\n");
            }
            return sb.toString();
        }
    }

    /** Resolve OPF manifest+spine to an ordered list of the zip's html entries. */
    private static ArrayList<ZipEntry> spineOrder(
            String opf, String opfName, List<ZipEntry> htmlEntries) {
        var idToHref = new LinkedHashMap<String, String>();
        var itemPattern = Pattern.compile(
            "<item\\b[^>]*\\bid=\"([^\"]+)\"[^>]*\\bhref=\"([^\"]+)\"[^>]*>|"
            + "<item\\b[^>]*\\bhref=\"([^\"]+)\"[^>]*\\bid=\"([^\"]+)\"[^>]*>");
        var m = itemPattern.matcher(opf);
        while (m.find()) {
            if (m.group(1) != null) idToHref.put(m.group(1), m.group(2));
            else idToHref.put(m.group(4), m.group(3));
        }
        var opfDir = opfName.contains("/")
            ? opfName.substring(0, opfName.lastIndexOf('/') + 1) : "";
        var ordered = new ArrayList<ZipEntry>();
        var spinePattern = Pattern.compile("<itemref\\b[^>]*\\bidref=\"([^\"]+)\"");
        var s = spinePattern.matcher(opf);
        while (s.find()) {
            var href = idToHref.get(s.group(1));
            if (href == null) continue;
            var target = opfDir + href;
            for (var entry : htmlEntries) {
                if (entry.getName().equals(target) || entry.getName().endsWith("/" + href)) {
                    ordered.add(entry);
                    break;
                }
            }
        }
        return ordered;
    }

    /** Markup stripper for epub XHTML — block tags become line breaks. */
    static String htmlToText(String html) {
        var text = html
            .replaceAll("(?is)<(head|style|script)\\b.*?</\\1>", " ")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article)>", "\n\n")
            .replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'");
        // Decode remaining numeric entities
        var entity = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
        var m = entity.matcher(text);
        var sb = new StringBuilder();
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(
                    new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * Split text into overlapping chunks of ~CHUNK_TARGET_WORDS words.
     * Tries to break at paragraph boundaries.
     */
    static List<Chunk> chunkText(String title, String text) {
        // Split into paragraphs first
        var paragraphs = text.split("\n\n+");
        var chunks = new ArrayList<Chunk>();
        var currentChunk = new StringBuilder();
        int currentWords = 0;

        for (var para : paragraphs) {
            var paraWords = para.trim().split("\\s+").length;

            if (currentWords + paraWords > CHUNK_TARGET_WORDS && currentWords > 0) {
                // Emit current chunk
                chunks.add(new Chunk(title, currentChunk.toString().trim(), chunks.size(), -1));
                // Start new chunk with overlap from end of current
                var words = currentChunk.toString().trim().split("\\s+");
                currentChunk = new StringBuilder();
                currentWords = 0;
                // Add overlap words from the end
                int overlapStart = Math.max(0, words.length - CHUNK_OVERLAP_WORDS);
                for (int i = overlapStart; i < words.length; i++) {
                    currentChunk.append(words[i]).append(" ");
                    currentWords++;
                }
                currentChunk.append("\n\n");
            }

            currentChunk.append(para.trim()).append("\n\n");
            currentWords += paraWords;
        }

        // Emit remaining
        if (currentWords > 0) {
            chunks.add(new Chunk(title, currentChunk.toString().trim(), chunks.size(), -1));
        }

        // Fix totalChunks
        int total = chunks.size();
        var fixed = new ArrayList<Chunk>(total);
        for (var c : chunks) {
            fixed.add(new Chunk(c.title(), c.content(), c.chunkIndex(), total));
        }
        return fixed;
    }

    /**
     * Extract all documents from a directory recursively.
     */
    public static List<ExtractionResult> extractDirectory(Path dir) throws IOException {
        var results = new ArrayList<ExtractionResult>();
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(f -> !f.getFileName().toString().startsWith("."))
                .sorted()
                .forEach(file -> results.add(extract(file)));
        }
        return results;
    }
}
