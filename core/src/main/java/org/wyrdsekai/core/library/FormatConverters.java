package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/**
 * Format converters for knowledge pack data.
 * Converts various source formats to our native JSONL chunk format.
 *
 * Supported: Parquet, EPUB, StackExchange XML, HTML, CSV (column-mapped).
 */
public final class FormatConverters {

    private static final Logger log = LoggerFactory.getLogger(FormatConverters.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CHUNK_CHARS = 2000;

    private FormatConverters() {}

    // -----------------------------------------------------------------------
    //  Parquet → JSONL (Java-native, no Python)
    // -----------------------------------------------------------------------

    /**
     * Convert a Parquet file to JSONL chunks.
     * Auto-maps common column names (title, text, content, question, answer, url, source).
     *
     * @param parquetFile  Input .parquet file
     * @param outputFile   Output .jsonl file
     * @param packName     Pack name for chunk IDs
     * @param progress     Optional callback
     * @return Number of rows converted
     */
    public static int convertParquet(Path parquetFile, Path outputFile, String packName,
                                      Consumer<String> progress) throws IOException {
        try {
            // Use LocalInputFile to avoid Hadoop FileSystem (Subject.getSubject broken in JDK 25)
            var localInput = new LocalInputFile(parquetFile);

            try (var reader = AvroParquetReader
                    .<GenericRecord>builder(localInput)
                    .build();
                 var writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {

                int count = 0;
                GenericRecord record;
                while ((record = reader.read()) != null) {
                    var title = getField(record, "title", "Title", "question", "Question", "name");
                    var content = getField(record, "text", "body", "content", "Content", "Text",
                        "answer", "Answer", "article", "passage", "summary");
                    var source = getField(record, "url", "source", "Source", "link", "document_url");

                    if (content == null || content.length() < 10) continue;
                    if (content.length() > MAX_CHUNK_CHARS) {
                        content = content.substring(0, MAX_CHUNK_CHARS);
                    }

                    var chunk = new KnowledgeChunk(
                        packName + ":" + count, packName,
                        title != null ? title : "",
                        content, source, null, null, null, null);

                    writer.write(MAPPER.writeValueAsString(chunk));
                    writer.newLine();
                    count++;

                    if (count % 10000 == 0) {
                        if (progress != null) progress.accept("Converted " + count + " rows...");
                        log.info("[Converter] Parquet: {} rows converted", count);
                    }
                }

                if (progress != null) progress.accept("Converted " + count + " total rows");
                log.info("[Converter] Parquet → JSONL: {} rows from {}", count, parquetFile.getFileName());
                return count;
            }
        } catch (NoClassDefFoundError e) {
            throw new IOException("Parquet libraries not available: " + e.getMessage(), e);
        }
    }

    private static String getField(GenericRecord record, String... fieldNames) {
        for (var name : fieldNames) {
            var val = record.get(name);
            if (val != null) {
                var s = val.toString();
                if (!s.isBlank() && !"null".equals(s)) return s;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    //  EPUB → JSONL (Java-native, epub = zip of XHTML)
    // -----------------------------------------------------------------------

    /**
     * Convert an EPUB file to JSONL chunks.
     * EPUB is a ZIP containing XHTML files + metadata.
     *
     * @param epubFile   Input .epub file
     * @param outputFile Output .jsonl file
     * @param packName   Pack name for chunk IDs
     * @param progress   Optional callback
     * @return Number of chunks generated
     */
    public static int convertEpub(Path epubFile, Path outputFile, String packName,
                                    Consumer<String> progress) throws IOException {
        int chunkIndex = 0;

        try (var zip = new ZipFile(epubFile.toFile());
             var writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {

            // Extract book title from OPF metadata if possible
            String bookTitle = epubFile.getFileName().toString().replace(".epub", "");

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName().toLowerCase();

                // Only process XHTML/HTML content files
                if (!name.endsWith(".xhtml") && !name.endsWith(".html") && !name.endsWith(".htm")) {
                    continue;
                }
                // Skip navigation and TOC files
                if (name.contains("toc") || name.contains("nav")) continue;

                try (var is = zip.getInputStream(entry)) {
                    var html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    var text = stripHtml(html);
                    if (text.length() < 50) continue;

                    // Chunk the text
                    var segments = DocumentExtractor.chunkText(bookTitle, text);
                    for (var segment : segments) {
                        var chunk = new KnowledgeChunk(
                            packName + ":" + chunkIndex, packName,
                            bookTitle + " — " + entry.getName(),
                            segment.content().length() > MAX_CHUNK_CHARS
                                ? segment.content().substring(0, MAX_CHUNK_CHARS) : segment.content(),
                            "Project Gutenberg", null, "public-domain", null, null);

                        writer.write(MAPPER.writeValueAsString(chunk));
                        writer.newLine();
                        chunkIndex++;
                    }
                }
            }
        }

        if (progress != null) progress.accept("EPUB converted: " + chunkIndex + " chunks from " + epubFile.getFileName());
        log.info("[Converter] EPUB → JSONL: {} chunks from {}", chunkIndex, epubFile.getFileName());
        return chunkIndex;
    }

    // -----------------------------------------------------------------------
    //  StackExchange XML → JSONL
    // -----------------------------------------------------------------------

    /**
     * Convert a StackExchange Posts.xml dump to JSONL chunks.
     * Each Q+A pair becomes one chunk (question as title, accepted answer as content).
     *
     * @param xmlFile    Input Posts.xml file
     * @param outputFile Output .jsonl file
     * @param packName   Pack name for chunk IDs
     * @param progress   Optional callback
     * @return Number of chunks generated
     */
    public static int convertStackExchangeXml(Path xmlFile, Path outputFile, String packName,
                                                Consumer<String> progress) throws IOException {
        int count = 0;

        try (var reader = new BufferedReader(new FileReader(xmlFile.toFile()));
             var writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {

            // StackExchange Posts.xml is one <row> per line
            // PostTypeId=1 = question, PostTypeId=2 = answer
            // We extract questions and inline accepted answers
            var questions = new HashMap<String, String[]>(); // id → [title, body]
            var answers = new HashMap<String, String>();     // parentId → body

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("<row")) continue;

                var postType = extractAttr(line, "PostTypeId");
                var id = extractAttr(line, "Id");
                var title = extractAttr(line, "Title");
                var body = extractAttr(line, "Body");
                var parentId = extractAttr(line, "ParentId");

                if (body != null) body = stripHtml(decodeXmlEntities(body));

                if ("1".equals(postType) && title != null) {
                    // Question
                    questions.put(id, new String[]{decodeXmlEntities(title), body});
                } else if ("2".equals(postType) && parentId != null && body != null) {
                    // Answer — keep the longest one per question
                    var existing = answers.get(parentId);
                    if (existing == null || body.length() > existing.length()) {
                        answers.put(parentId, body);
                    }
                }
            }

            // Merge questions with their best answers
            for (var entry : questions.entrySet()) {
                var qId = entry.getKey();
                var qTitle = entry.getValue()[0];
                var qBody = entry.getValue()[1];
                var answer = answers.get(qId);

                var content = new StringBuilder();
                if (qBody != null && !qBody.isBlank()) {
                    content.append("Q: ").append(qBody).append("\n\n");
                }
                if (answer != null && !answer.isBlank()) {
                    content.append("A: ").append(answer);
                }

                if (content.length() < 20) continue;
                var text = content.toString();
                if (text.length() > MAX_CHUNK_CHARS) text = text.substring(0, MAX_CHUNK_CHARS);

                var chunk = new KnowledgeChunk(
                    packName + ":" + count, packName,
                    qTitle != null ? qTitle : "Question " + qId,
                    text, "StackExchange", null, "CC-BY-SA-4.0", null, null);

                writer.write(MAPPER.writeValueAsString(chunk));
                writer.newLine();
                count++;

                if (count % 5000 == 0 && progress != null) {
                    progress.accept("Converted " + count + " Q&A pairs...");
                }
            }
        }

        if (progress != null) progress.accept("StackExchange converted: " + count + " Q&A pairs");
        log.info("[Converter] StackExchange XML → JSONL: {} Q&A pairs from {}", count, xmlFile.getFileName());
        return count;
    }

    // -----------------------------------------------------------------------
    //  HTML → text (shared utility)
    // -----------------------------------------------------------------------

    /** Strip HTML tags and decode common entities. */
    static String stripHtml(String html) {
        if (html == null) return "";
        return html
            .replaceAll("<script[^>]*>.*?</script>", "")
            .replaceAll("<style[^>]*>.*?</style>", "")
            .replaceAll("<br\\s*/?>", "\n")
            .replaceAll("<p[^>]*>", "\n\n")
            .replaceAll("</p>", "")
            .replaceAll("<[^>]+>", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&quot;", "\"")
            .replaceAll("&#39;", "'")
            .replaceAll("\\s+", " ")
            .replaceAll("\n ", "\n")
            .trim();
    }

    /** Decode XML entities in attribute values. */
    private static String decodeXmlEntities(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#xD;", "\n")
            .replace("&#xA;", "\n");
    }

    /** Extract an XML attribute value from a <row .../> element. */
    private static String extractAttr(String line, String attr) {
        var key = attr + "=\"";
        int start = line.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = line.indexOf("\"", start);
        if (end < 0) return null;
        return line.substring(start, end);
    }

    // -----------------------------------------------------------------------
    //  7z archive extraction (for StackExchange dumps from archive.org)
    // -----------------------------------------------------------------------

    /**
     * Extract a .7z archive to a target directory using Apache Commons Compress.
     *
     * @param sevenZFile  Input .7z file
     * @param targetDir   Directory to extract into
     * @param progress    Optional callback
     */
    public static void extract7z(Path sevenZFile, Path targetDir,
                                   Consumer<String> progress) throws IOException {
        if (progress != null) progress.accept("Extracting 7z archive...");
        try (var archive = new SevenZFile(
                sevenZFile.toFile())) {

            var entry = archive.getNextEntry();
            int count = 0;
            while (entry != null) {
                var target = targetDir.resolve(entry.getName()).normalize();
                // Security: prevent path traversal
                if (!target.startsWith(targetDir)) {
                    entry = archive.getNextEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var out = new FileOutputStream(target.toFile())) {
                        var buf = new byte[8192];
                        int len;
                        while ((len = archive.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    count++;
                }
                entry = archive.getNextEntry();
            }
            if (progress != null) progress.accept("Extracted " + count + " files from 7z");
            log.info("[Converter] Extracted {} files from {}", count, sevenZFile.getFileName());
        }
    }

    // -----------------------------------------------------------------------
    //  Wikipedia CirrusSearch NDJSON → JSONL chunks
    // -----------------------------------------------------------------------

    /**
     * Convert a Wikimedia CirrusSearch JSON dump to JSONL chunks.
     * The dump is NDJSON with alternating index/document lines.
     * Document lines have "text", "title", "category", etc.
     *
     * @param ndjsonFile  Input .json or .json.gz file (CirrusSearch format)
     * @param outputFile  Output .jsonl file
     * @param packName    Pack name for chunk IDs
     * @param progress    Optional callback
     * @return Number of articles converted
     */
    public static int convertWikipediaCirrus(Path ndjsonFile, Path outputFile, String packName,
                                               Consumer<String> progress) throws IOException {
        var mapper = new ObjectMapper();
        int count = 0;

        InputStream is = new FileInputStream(ndjsonFile.toFile());
        if (ndjsonFile.toString().endsWith(".gz")) {
            is = new GZIPInputStream(is);
        }

        try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Legacy CirrusSearch dumps alternate index-action + document lines; the 2026
                // sharded dumps may emit documents only. Skip action lines, parse the rest —
                // the title/text emptiness checks below discard anything that isn't an article.
                if (line.contains("\"index\"") && line.contains("\"_id\"")) {
                    continue;
                }

                try {
                    var node = mapper.readTree(line);
                    var title = node.path("title").asText("");
                    var text = node.path("text").asText("");

                    if (text.length() < 50) continue;
                    if (text.length() > MAX_CHUNK_CHARS) text = text.substring(0, MAX_CHUNK_CHARS);

                    var chunk = new KnowledgeChunk(
                        packName + ":" + count, packName, title, text,
                        "Wikipedia", null, "CC-BY-SA-4.0", null, null);

                    writer.write(mapper.writeValueAsString(chunk));
                    writer.newLine();
                    count++;

                    if (count % 10000 == 0) {
                        if (progress != null) progress.accept("Converted " + count + " articles...");
                    }
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        } finally {
            is.close();
        }

        if (progress != null) progress.accept("Wikipedia: " + count + " articles converted");
        log.info("[Converter] Wikipedia CirrusSearch → JSONL: {} articles", count);
        return count;
    }

    // -----------------------------------------------------------------------
    //  MedQuAD XML → JSONL (GitHub repo format)
    // -----------------------------------------------------------------------

    /**
     * Convert MedQuAD XML files to JSONL chunks.
     * Each XML file contains QAPairs with Question and Answer elements.
     *
     * @param xmlDir      Directory containing MedQuAD XML files (or subdirectories)
     * @param outputFile  Output .jsonl file
     * @param packName    Pack name for chunk IDs
     * @param progress    Optional callback
     * @return Number of Q&A pairs converted
     */
    public static int convertMedQuadXml(Path xmlDir, Path outputFile, String packName,
                                          Consumer<String> progress) throws IOException {
        var mapper = new ObjectMapper();
        int count = 0;

        try (var writer = new BufferedWriter(new FileWriter(outputFile.toFile()));
             var walk = Files.walk(xmlDir)) {

            var xmlFiles = walk.filter(f -> f.toString().endsWith(".xml")).sorted().toList();
            if (progress != null) progress.accept("Found " + xmlFiles.size() + " XML files");

            for (var xmlFile : xmlFiles) {
                try {
                    var content = Files.readString(xmlFile, StandardCharsets.UTF_8);

                    // Simple regex-based extraction (MedQuAD XML is consistent)
                    var qPattern = Pattern.compile(
                        "<Question[^>]*>(.*?)</Question>", Pattern.DOTALL);
                    var aPattern = Pattern.compile(
                        "<Answer>(.*?)</Answer>", Pattern.DOTALL);
                    var focusPattern = Pattern.compile(
                        "<QAPair[^>]*pid=\"([^\"]*)\">", Pattern.DOTALL);

                    var qMatcher = qPattern.matcher(content);
                    var aMatcher = aPattern.matcher(content);

                    while (qMatcher.find() && aMatcher.find()) {
                        var question = stripHtml(qMatcher.group(1)).trim();
                        var answer = stripHtml(aMatcher.group(1)).trim();

                        if (answer.length() < 20) continue;

                        var combined = "Q: " + question + "\n\nA: " + answer;
                        if (combined.length() > MAX_CHUNK_CHARS) {
                            combined = combined.substring(0, MAX_CHUNK_CHARS);
                        }

                        var chunk = new KnowledgeChunk(
                            packName + ":" + count, packName, question, combined,
                            "NIH/NLM MedQuAD", null, "CC-BY-4.0", null, null);

                        writer.write(mapper.writeValueAsString(chunk));
                        writer.newLine();
                        count++;
                    }
                } catch (Exception e) {
                    log.debug("[Converter] Skipping {}: {}", xmlFile.getFileName(), e.getMessage());
                }
            }
        }

        if (progress != null) progress.accept("MedQuAD: " + count + " Q&A pairs converted");
        log.info("[Converter] MedQuAD XML → JSONL: {} Q&A pairs", count);
        return count;
    }

    // -----------------------------------------------------------------------
    //  Project Gutenberg text → JSONL (strip header/footer)
    // -----------------------------------------------------------------------

    /**
     * Convert a Project Gutenberg plain text file to JSONL chunks.
     * Strips the standard Gutenberg header and footer markers.
     *
     * @param textFile    Input .txt file
     * @param outputFile  Output .jsonl file
     * @param packName    Pack name for chunk IDs
     * @param bookTitle   Book title (for chunk metadata)
     * @param progress    Optional callback
     * @return Number of chunks generated
     */
    public static int convertGutenbergText(Path textFile, Path outputFile, String packName,
                                             String bookTitle, Consumer<String> progress) throws IOException {
        var rawText = Files.readString(textFile, StandardCharsets.UTF_8);

        // Strip Gutenberg header (everything before "*** START OF")
        var startMarker = "*** START OF";
        int startIdx = rawText.indexOf(startMarker);
        if (startIdx >= 0) {
            startIdx = rawText.indexOf('\n', startIdx);
            if (startIdx >= 0) rawText = rawText.substring(startIdx + 1);
        }

        // Strip Gutenberg footer (everything after "*** END OF")
        var endMarker = "*** END OF";
        int endIdx = rawText.indexOf(endMarker);
        if (endIdx >= 0) rawText = rawText.substring(0, endIdx);

        rawText = rawText.trim();
        if (rawText.length() < 100) return 0;

        // Chunk the cleaned text
        var chunks = DocumentExtractor.chunkText(bookTitle, rawText);
        var mapper = new ObjectMapper();
        int count = 0;

        try (var writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
            for (var segment : chunks) {
                var text = segment.content();
                if (text.length() > MAX_CHUNK_CHARS) text = text.substring(0, MAX_CHUNK_CHARS);

                var chunk = new KnowledgeChunk(
                    packName + ":" + count, packName,
                    bookTitle + " (part " + (segment.chunkIndex() + 1) + "/" + segment.totalChunks() + ")",
                    text, "Project Gutenberg", null, "public-domain", null, null);

                writer.write(mapper.writeValueAsString(chunk));
                writer.newLine();
                count++;
            }
        }

        if (progress != null) progress.accept(bookTitle + ": " + count + " chunks");
        log.info("[Converter] Gutenberg text → JSONL: {} chunks from '{}'", count, bookTitle);
        return count;
    }

    // -----------------------------------------------------------------------
    //  JMdict XML (EDRDG Japanese–English dictionary) → JSONL
    // -----------------------------------------------------------------------

    /**
     * Convert JMdict XML to JSONL chunks — one chunk per entry: kanji (reading): glosses.
     * StAX streaming (the file is ~110 MB unpacked). The DTD ships inline in the file, so
     * entity references (&adj-i; etc.) resolve without network access.
     */
    public static int convertJmdictXml(Path xmlFile, Path outputFile, String packName,
                                        Consumer<String> progress) throws IOException {
        var factory = newDictionaryXmlFactory();

        int count = 0;
        try (var in = new BufferedInputStream(new FileInputStream(xmlFile.toFile()));
             var writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
            var reader = factory.createXMLStreamReader(in, "UTF-8");

            String keb = null, reb = null;
            var glosses = new ArrayList<String>(8);
            String current = null;

            while (reader.hasNext()) {
                int ev = reader.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    current = reader.getLocalName();
                    if ("entry".equals(current)) { keb = null; reb = null; glosses.clear(); }
                } else if (ev == XMLStreamConstants.CHARACTERS && current != null) {
                    var text = reader.getText().trim();
                    if (text.isEmpty()) continue;
                    switch (current) {
                        case "keb" -> { if (keb == null) keb = text; }
                        case "reb" -> { if (reb == null) reb = text; }
                        case "gloss" -> { if (glosses.size() < 10) glosses.add(text); }
                        default -> { /* skip */ }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("entry".equals(reader.getLocalName())) {
                        if (reb != null && !glosses.isEmpty()) {
                            var headword = keb != null ? keb + " (" + reb + ")" : reb;
                            var content = headword + " — " + String.join("; ", glosses);
                            var chunk = new KnowledgeChunk(
                                packName + ":" + count, packName, headword, content,
                                "JMdict (EDRDG)", null, "CC-BY-SA-4.0", null, null);
                            writer.write(MAPPER.writeValueAsString(chunk));
                            writer.newLine();
                            count++;
                            if (progress != null && count % 50_000 == 0) {
                                progress.accept("JMdict: " + count + " entries...");
                            }
                        }
                        current = null;
                    } else {
                        current = null;
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IOException("JMdict XML parse failed: " + e.getMessage(), e);
        }

        if (progress != null) progress.accept("JMdict: " + count + " entries");
        log.info("[Converter] JMdict XML → JSONL: {} entries", count);
        return count;
    }

    /**
     * Convert a FreeDict TEI dictionary file to JSONL chunks — one chunk per entry:
     * {@code orth — translation; translation}. Structure (freedict-spa-eng / eng-spa src):
     * {@code <entry><form><orth>word</orth></form><sense><cit type="trans"><quote>t</quote>
     * </cit></sense></entry>}. StAX streaming; multiple senses/quotes are joined.
     */
    public static int convertFreedictTei(Path teiFile, Path outputFile, String packName,
                                          Consumer<String> progress) throws IOException {
        var factory = newDictionaryXmlFactory();

        int count = 0;
        try (var in = new BufferedInputStream(new FileInputStream(teiFile.toFile()));
             var writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
            var reader = factory.createXMLStreamReader(in, "UTF-8");

            String orth = null;
            var quotes = new ArrayList<String>(8);
            String current = null;
            boolean inEntry = false;

            while (reader.hasNext()) {
                int ev = reader.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    current = reader.getLocalName();
                    if ("entry".equals(current)) { inEntry = true; orth = null; quotes.clear(); }
                } else if (ev == XMLStreamConstants.CHARACTERS && inEntry && current != null) {
                    var text = reader.getText().trim();
                    if (text.isEmpty()) continue;
                    switch (current) {
                        case "orth" -> { if (orth == null) orth = text; }
                        case "quote" -> { if (quotes.size() < 10) quotes.add(text); }
                        default -> { /* skip pron/pos/usg/… */ }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("entry".equals(reader.getLocalName())) {
                        if (orth != null && !quotes.isEmpty()) {
                            var content = orth + " — " + String.join("; ", quotes);
                            var chunk = new KnowledgeChunk(
                                packName + ":" + count, packName, orth, content,
                                "FreeDict", null, "GPL", null, null);
                            writer.write(MAPPER.writeValueAsString(chunk));
                            writer.newLine();
                            count++;
                            if (progress != null && count % 50_000 == 0) {
                                progress.accept("FreeDict: " + count + " entries...");
                            }
                        }
                        inEntry = false;
                    }
                    current = null;
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IOException("FreeDict TEI parse failed: " + e.getMessage(), e);
        }

        if (progress != null) progress.accept("FreeDict: " + count + " entries");
        log.info("[Converter] FreeDict TEI → JSONL: {} entries ({})", count, teiFile.getFileName());
        return count;
    }

    /**
     * StAX factory for dictionary XML (JMdict, FreeDict TEI). The full JMdict_e
     * (~200K entries) expands inline-DTD entities (&n;, &adj-i;, …) millions of
     * times — past the default expansion/size caps, which abort the parse
     * mid-file. The DTDs are inline and external entities are disabled, so
     * lifting the caps is not an XXE surface. Which knob applies depends on
     * which StAX impl ServiceLoader picks: the JDK impl wants the jdk.xml.*
     * names (String "0" = unlimited); Woodstox (on classpaths with
     * jackson-dataformat-xml) rejects those and enforces its own
     * com.ctc.wstx.maxEntityCount instead.
     */
    private static XMLInputFactory newDictionaryXmlFactory() {
        var factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, true);
        for (var limit : new String[]{"jdk.xml.entityExpansionLimit",
                "jdk.xml.totalEntitySizeLimit", "jdk.xml.maxGeneralEntitySizeLimit"}) {
            try {
                factory.setProperty(limit, "0");
            } catch (IllegalArgumentException ignored) {
                // Not the JDK impl — Woodstox knob below covers it.
            }
        }
        try {
            factory.setProperty("com.ctc.wstx.maxEntityCount", Integer.MAX_VALUE);
        } catch (IllegalArgumentException ignored) {
            // Not Woodstox — JDK knobs above covered it.
        }
        // FreeDict TEI declares <!DOCTYPE TEI SYSTEM "freedict-P5.dtd"> — an
        // external DTD that doesn't ship in the src tarball and would be read
        // from the filesystem otherwise. The files only use built-in entities
        // (&amp; &lt; &gt;), so resolving the external DTD to an empty stream
        // is lossless. JMdict's DTD is inline and never hits this resolver.
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) ->
            new ByteArrayInputStream(new byte[0]));
        return factory;
    }

    // -----------------------------------------------------------------------
    //  Plain-text / markdown documentation tree → JSONL (e.g. python-docs-text)
    // -----------------------------------------------------------------------

    /**
     * Chunk every .txt/.md file under {@code rootDir} (excluding the chunks dir itself) into a
     * single JSONL. Used as the fallback when an extracted pack produced no chunks — e.g. the
     * python-docs-text tarball, repackaged codeplane pattern packs, or any markdown corpus.
     *
     * @return total chunks written (0 when no text files exist — caller decides what that means)
     */
    public static int convertPlainTextTree(Path rootDir, Path chunksDir, String packName,
                                            Consumer<String> progress) throws IOException {
        Files.createDirectories(chunksDir);
        var output = chunksDir.resolve("text-tree.jsonl");
        int count = 0;

        try (var writer = new BufferedWriter(new FileWriter(output.toFile()));
             var walk = Files.walk(rootDir)) {
            var textFiles = walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    var n = p.getFileName().toString().toLowerCase();
                    return (n.endsWith(".txt") || n.endsWith(".md")) && !p.startsWith(chunksDir);
                })
                .sorted()
                .toList();

            for (var f : textFiles) {
                String text;
                try {
                    text = Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue; // skip undecodable files
                }
                if (text.length() < 200) continue;

                var rel = rootDir.relativize(f).toString()
                    .replaceAll("\\.(txt|md)$", "").replace('/', ' ').replace('\\', ' ').trim();
                var segments = DocumentExtractor.chunkText(rel, text);
                for (var segment : segments) {
                    var content = segment.content();
                    if (content.length() > MAX_CHUNK_CHARS) content = content.substring(0, MAX_CHUNK_CHARS);
                    var chunk = new KnowledgeChunk(
                        packName + ":" + count, packName,
                        rel + " (part " + (segment.chunkIndex() + 1) + "/" + segment.totalChunks() + ")",
                        content, packName, null, null, null, null);
                    writer.write(MAPPER.writeValueAsString(chunk));
                    writer.newLine();
                    count++;
                }
            }
        }

        if (count == 0) {
            Files.deleteIfExists(output);
        } else {
            if (progress != null) progress.accept("Text tree: " + count + " chunks");
            log.info("[Converter] Text tree → JSONL: {} chunks from {}", count, rootDir.getFileName());
        }
        return count;
    }
}
