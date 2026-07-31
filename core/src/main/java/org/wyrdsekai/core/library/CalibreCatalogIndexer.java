package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.function.Consumer;

/**
 * Catalog-first ingest for a Calibre library. Calibre keeps a
 * {@code metadata.db} (SQLite) with title/authors/series/tags/description
 * for every book — indexing THAT gives a complete, searchable card catalog
 * of a 75k-book collection in seconds, without extracting 75k epubs.
 *
 * <p>One Study document per book: title + authors as the title line;
 * authors, series, tags, formats, and the (HTML-stripped) description as
 * the body; the book's folder as the source. Ids are deterministic per
 * book path ({@link StudyService#indexDocumentChunk}), so re-running after
 * Calibre adds books upserts cleanly.</p>
 *
 * <p>The database is opened READ-ONLY — we never touch a user's Calibre
 * state. Full-text of individual books can still be ingested afterwards
 * with the normal directory ingest pointed at a book's folder.</p>
 */
public final class CalibreCatalogIndexer {

    private static final Logger log = LoggerFactory.getLogger(CalibreCatalogIndexer.class);
    private static final int COMMIT_EVERY_BOOKS = 1000;

    private static final String CATALOG_SQL = """
        SELECT b.id, b.title, b.path, b.pubdate, b.series_index,
               (SELECT GROUP_CONCAT(a.name, ' & ') FROM books_authors_link bal
                  JOIN authors a ON a.id = bal.author WHERE bal.book = b.id)  AS authors,
               (SELECT s.name FROM books_series_link bsl
                  JOIN series s ON s.id = bsl.series WHERE bsl.book = b.id)   AS series,
               (SELECT c.text FROM comments c WHERE c.book = b.id)            AS comment,
               (SELECT GROUP_CONCAT(t.name, ', ') FROM books_tags_link btl
                  JOIN tags t ON t.id = btl.tag WHERE btl.book = b.id)        AS tags,
               (SELECT GROUP_CONCAT(d.format, ', ') FROM data d
                  WHERE d.book = b.id)                                        AS formats
        FROM books b
        ORDER BY b.id
        """;

    private final StudyService studyService;

    public CalibreCatalogIndexer(StudyService studyService) {
        this.studyService = studyService;
    }

    public record CatalogResult(String collection, int books, int errors, long elapsedMs) {}

    /** True when {@code dir} is a Calibre library (has a metadata.db). */
    public static boolean isCalibreLibrary(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve("metadata.db"));
    }

    /**
     * Index the catalog of the Calibre library at {@code dir} into the
     * user's Study under {@code collection}.
     */
    public CatalogResult indexCatalog(String userDid, String collection, Path dir,
                                       Consumer<String> progress) throws Exception {
        var db = dir.resolve("metadata.db");
        if (!Files.isRegularFile(db)) {
            throw new IllegalArgumentException("Not a Calibre library (no metadata.db): " + dir);
        }

        long start = System.currentTimeMillis();
        int books = 0;
        int errors = 0;

        var config = new SQLiteConfig();
        config.setReadOnly(true);
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + db, config.toProperties());
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery(CATALOG_SQL)) {

            while (rs.next()) {
                try {
                    var title = blankToNull(rs.getString("title"));
                    var authors = blankToNull(rs.getString("authors"));
                    var series = blankToNull(rs.getString("series"));
                    var tags = blankToNull(rs.getString("tags"));
                    var formats = blankToNull(rs.getString("formats"));
                    var comment = blankToNull(rs.getString("comment"));
                    var bookPath = rs.getString("path");
                    if (title == null || bookPath == null) continue;

                    var docTitle = authors != null ? title + " — " + authors : title;
                    var body = new StringBuilder();
                    body.append("Title: ").append(title).append('\n');
                    if (authors != null) body.append("Author: ").append(authors).append('\n');
                    if (series != null) {
                        body.append("Series: ").append(series);
                        var idx = rs.getDouble("series_index");
                        if (idx > 0) body.append(" #").append(trimIndex(idx));
                        body.append('\n');
                    }
                    if (tags != null) body.append("Tags: ").append(tags).append('\n');
                    if (formats != null) body.append("Formats: ").append(formats).append('\n');
                    if (comment != null) {
                        body.append('\n').append(DocumentExtractor.htmlToText(comment)).append('\n');
                    }
                    body.append("\nShelf: ").append(bookPath);

                    var source = dir.resolve(bookPath).toAbsolutePath().toString();
                    studyService.indexDocumentChunk(userDid, collection, docTitle,
                        body.toString(), source, 0);
                    books++;

                    if (books % COMMIT_EVERY_BOOKS == 0) {
                        studyService.commitDocuments();
                        if (progress != null) progress.accept("Cataloged " + books + " books...");
                    }
                } catch (Exception e) {
                    errors++;
                    if (errors <= 5) {
                        log.debug("[CalibreCatalog] skipping book: {}", e.getMessage());
                    }
                }
            }
        }

        studyService.commitDocuments();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[CalibreCatalog] Indexed {} for {}: {} books, {} errors, {}s",
            collection, userDid, books, errors, elapsed / 1000);
        if (progress != null) {
            progress.accept("Catalog done! " + books + " books indexed"
                + (errors > 0 ? " (" + errors + " skipped)" : "")
                + ". Full text of any book can be ingested with mode=full on its folder.");
        }
        return new CatalogResult(collection, books, errors, elapsed);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trimIndex(double idx) {
        return idx == Math.floor(idx) ? String.valueOf((long) idx) : String.valueOf(idx);
    }
}
