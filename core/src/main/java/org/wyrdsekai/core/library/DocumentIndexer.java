package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Indexes documents from the filesystem into a user's private Study.
 * Combines DocumentExtractor (text extraction + chunking) with StudyService (Lucene indexing).
 *
 * <p>Built to survive bulk ingests (a 75k-ebook Calibre library): files are
 * extracted and indexed ONE AT A TIME (never the whole tree in memory),
 * the index is committed every {@link #COMMIT_EVERY_FILES} files, chunk ids
 * are deterministic per (file, chunkIndex) so re-runs upsert instead of
 * duplicating, and an {@link IngestLedger} skips files already indexed —
 * an interrupted run resumes where it stopped.</p>
 *
 * Usage:
 * <pre>
 *   var indexer = new DocumentIndexer(studyService);
 *   var result = indexer.indexDirectory(userDid, "taxes-2025", Path.of("~/Documents/taxes/"));
 * </pre>
 */
public final class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);
    private static final int COMMIT_EVERY_FILES = 500;
    private static final int PROGRESS_EVERY_FILES = 100;

    private final StudyService studyService;

    public DocumentIndexer(StudyService studyService) {
        this.studyService = studyService;
    }

    public record IndexResult(String collection, int filesProcessed, int chunksIndexed,
                               int errors, int skippedDone, long elapsedMs) {
        public boolean success() { return errors == 0 || chunksIndexed > errors; }
    }

    /**
     * Index a single file into the user's Study.
     */
    public int indexFile(String userDid, String collection, Path file) {
        var extraction = DocumentExtractor.extract(file);
        if (!extraction.success()) {
            log.debug("[DocumentIndexer] Skipping {}: {}", file.getFileName(), extraction.error());
            return 0;
        }
        return indexChunks(userDid, collection, extraction);
    }

    private int indexChunks(String userDid, String collection,
                             DocumentExtractor.ExtractionResult extraction) {
        var source = extraction.file().toAbsolutePath().toString();
        for (var chunk : extraction.chunks()) {
            var title = extraction.title();
            if (chunk.totalChunks() > 1) {
                title += " (part " + (chunk.chunkIndex() + 1) + "/" + chunk.totalChunks() + ")";
            }
            studyService.indexDocumentChunk(userDid, collection, title, chunk.content(),
                source, chunk.chunkIndex());
        }
        return extraction.chunks().size();
    }

    /**
     * Index all documents in a directory into the user's Study. Streaming,
     * resumable (ledger), committed in batches — see class doc.
     *
     * @param userDid     User's DID
     * @param collection  Sub-collection name (e.g., "taxes-2025", "research")
     * @param dir         Directory to scan recursively
     * @param progress    Optional progress callback
     * @return IndexResult with stats
     */
    public IndexResult indexDirectory(String userDid, String collection, Path dir,
                                       Consumer<String> progress) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        long start = System.currentTimeMillis();
        int files = 0;
        int chunks = 0;
        int errors = 0;
        int skippedDone = 0;
        int sinceCommit = 0;

        // Paths only — the cheap part. Extraction happens one file at a time.
        List<Path> paths;
        try (var walk = Files.walk(dir)) {
            paths = walk.filter(Files::isRegularFile)
                .filter(f -> !f.getFileName().toString().startsWith("."))
                .sorted()
                .toList();
        }

        try (var ledger = IngestLedger.open(userDid, collection)) {
            if (ledger.doneCount() > 0 && progress != null) {
                progress.accept("Resuming — " + ledger.doneCount()
                    + " files already indexed in a previous run.");
            }

            for (var file : paths) {
                if (ledger.isDone(file)) {
                    skippedDone++;
                    continue;
                }
                files++;
                var extraction = DocumentExtractor.extract(file);
                if (!extraction.success()) {
                    errors++;
                    if (errors <= 5 && progress != null) {
                        progress.accept("Skipped: " + file.getFileName()
                            + " (" + extraction.error() + ")");
                    }
                    ledger.markDone(file);  // unreadable now → unreadable next run; don't retry forever
                    continue;
                }

                chunks += indexChunks(userDid, collection, extraction);
                ledger.markDone(file);

                if (++sinceCommit >= COMMIT_EVERY_FILES) {
                    studyService.commitDocuments();
                    ledger.flush();
                    sinceCommit = 0;
                }
                if (files % PROGRESS_EVERY_FILES == 0 && progress != null) {
                    progress.accept("Processed " + files + " files (" + chunks + " chunks)...");
                }
            }

            studyService.commitDocuments();
            ledger.flush();
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[DocumentIndexer] Indexed {} for {}: {} files, {} chunks, {} errors, "
                + "{} already done, {}s",
            collection, userDid, files, chunks, errors, skippedDone, elapsed / 1000);

        if (progress != null) {
            progress.accept("Done! " + files + " files, " + chunks + " chunks indexed"
                + (skippedDone > 0 ? " (" + skippedDone + " already done)" : "")
                + (errors > 0 ? " (" + errors + " skipped)" : ""));
        }

        return new IndexResult(collection, files, chunks, errors, skippedDone, elapsed);
    }

    /**
     * Index a single file, auto-deriving collection name from parent directory.
     */
    public int indexFileAutoCollection(String userDid, Path file) {
        var collection = file.getParent() != null ? file.getParent().getFileName().toString() : "default";
        return indexFile(userDid, collection, file);
    }
}
