package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Sidecars that sit next to real documents and are not documents.
     *
     * <p>A Calibre library keeps one folder per book holding the epub AND a
     * {@code metadata.opf}. Indexing the shelf full-text swept those in too:
     * the household's 74,681 epubs arrived with 74,694 .opf files, and because
     * an .opf is nothing but title/author/description, it OUT-RANKS the book's
     * own prose on exactly the queries people ask — a search for "Takeshi
     * Kovacs" answered with two mouthfuls of raw XML (2026-08-25). The Calibre
     * CATALOG path already turns that same metadata into clean prose, so the
     * .opf is redundant as well as ugly.</p>
     */
    private static final Set<String> SKIP_EXTENSIONS = Set.of(
        // epub/Calibre package descriptors — metadata, never prose
        "opf", "ncx",
        // images and media: the extractor cannot read them, so every one is a
        // futile open + failed decode (72,606 of them on that same shelf)
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "ico", "svg",
        "mp3", "mp4", "m4a", "m4b", "wav", "flac", "ogg", "avi", "mkv", "mov",
        // databases and archives — binary, and metadata.db is the catalog's job
        "db", "sqlite", "sqlite3", "zip", "gz", "bz2", "xz", "7z", "rar");

    /** True when {@code file} is a sidecar rather than a document. */
    static boolean isSidecar(Path file) {
        var name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return SKIP_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

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
    /**
     * Worker count for a bulk ingest. Extraction dominates the cost and is
     * per-file independent; Lucene's IndexWriter takes concurrent adds. The
     * measured baseline this replaces: a 74k-book Calibre library on an
     * otherwise-idle multi-core node ran ONE worker for ~10 hours to reach a
     * third of the corpus — load average 1.3, every other core asleep. Two
     * cores are left for the household itself: the node is her home first and
     * an ingest rig second. {@code WYRDSEKAI_INGEST_THREADS} overrides.
     */
    static int workerCount() {
        var env = System.getenv("WYRDSEKAI_INGEST_THREADS");
        if (env != null && !env.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(env.trim()));
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() - 2, 8));
    }

    public IndexResult indexDirectory(String userDid, String collection, Path dir,
                                       Consumer<String> progress) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        long start = System.currentTimeMillis();
        var files = new AtomicInteger();
        var chunks = new AtomicInteger();
        var errors = new AtomicInteger();
        var sinceCommit = new AtomicInteger();

        // Paths only — the cheap part. Extraction happens per file, in workers.
        List<Path> paths;
        try (var walk = Files.walk(dir)) {
            paths = walk.filter(Files::isRegularFile)
                .filter(f -> !f.getFileName().toString().startsWith("."))
                .filter(f -> !isSidecar(f))
                .sorted()
                .toList();
        }

        int skippedDone;
        try (var ledger = IngestLedger.open(userDid, collection)) {
            if (ledger.doneCount() > 0 && progress != null) {
                progress.accept("Resuming — " + ledger.doneCount()
                    + " files already indexed in a previous run.");
            }

            // Filter ONCE, up front. Workers never call isDone: the pending
            // list is fixed at start, so there is no worker-vs-worker ordering
            // question — each file is owned by exactly one task.
            var pending = paths.stream().filter(f -> !ledger.isDone(f)).toList();
            skippedDone = paths.size() - pending.size();

            int workers = workerCount();
            if (progress != null && pending.size() > 1000) {
                progress.accept("Indexing " + pending.size() + " files with "
                    + workers + " workers...");
            }
            var pool = Executors.newFixedThreadPool(workers, r -> {
                var t = new Thread(r, "ingest-worker");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);   // the household comes first
                return t;
            });
            // One commit at a time: Lucene tolerates concurrent commits, but a
            // stampede of them serializes on fsync anyway — cheaper to elect.
            var commitLock = new Object();
            try {
                var futures = new ArrayList<Future<?>>(pending.size());
                for (var file : pending) {
                    futures.add(pool.submit(() -> {
                        var extraction = DocumentExtractor.extract(file);
                        int f = files.incrementAndGet();
                        if (!extraction.success()) {
                            if (errors.incrementAndGet() <= 5 && progress != null) {
                                progress.accept("Skipped: " + file.getFileName()
                                    + " (" + extraction.error() + ")");
                            }
                            // unreadable now → unreadable next run; don't retry forever
                            ledger.markDone(file);
                            return;
                        }
                        chunks.addAndGet(indexChunks(userDid, collection, extraction));
                        ledger.markDone(file);

                        if (sinceCommit.incrementAndGet() >= COMMIT_EVERY_FILES) {
                            synchronized (commitLock) {
                                if (sinceCommit.get() >= COMMIT_EVERY_FILES) {
                                    sinceCommit.set(0);
                                    studyService.commitDocuments();
                                    ledger.flush();
                                }
                            }
                        }
                        if (f % PROGRESS_EVERY_FILES == 0 && progress != null) {
                            progress.accept("Processed " + f + " files ("
                                + chunks.get() + " chunks)...");
                        }
                    }));
                }
                for (var fut : futures) {
                    try {
                        fut.get();
                    } catch (ExecutionException e) {
                        // A single poisoned file must not end a 75k-book run.
                        errors.incrementAndGet();
                        log.warn("[DocumentIndexer] worker failed: {}",
                            e.getCause() != null ? e.getCause().toString() : e.toString());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                pool.shutdownNow();
            }

            studyService.commitDocuments();
            ledger.flush();
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[DocumentIndexer] Indexed {} for {}: {} files, {} chunks, {} errors, "
                + "{} already done, {}s",
            collection, userDid, files.get(), chunks.get(), errors.get(), skippedDone,
            elapsed / 1000);

        if (progress != null) {
            progress.accept("Done! " + files.get() + " files, " + chunks.get()
                + " chunks indexed"
                + (skippedDone > 0 ? " (" + skippedDone + " already done)" : "")
                + (errors.get() > 0 ? " (" + errors.get() + " skipped)" : ""));
        }

        return new IndexResult(collection, files.get(), chunks.get(), errors.get(),
            skippedDone, elapsed);
    }

    /**
     * Index a single file, auto-deriving collection name from parent directory.
     */
    public int indexFileAutoCollection(String userDid, Path file) {
        var collection = file.getParent() != null ? file.getParent().getFileName().toString() : "default";
        return indexFile(userDid, collection, file);
    }
}
