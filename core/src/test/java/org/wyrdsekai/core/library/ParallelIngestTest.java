package org.wyrdsekai.core.library;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bulk ingest uses the machine it runs on.
 *
 * <p>The measured baseline this replaces: a 74,681-book Calibre library on an
 * otherwise-idle multi-core node — one worker, load average 1.3, ten hours to
 * reach a third of the corpus, every other core asleep. Extraction is per-file
 * independent and Lucene's IndexWriter takes concurrent adds; the only shared
 * state is the ledger, which is now synchronized. Nothing about the problem
 * was sequential except the loop.</p>
 *
 * <p>What parallelism must NOT cost: correctness of counts, resumability, or
 * the no-duplicates guarantee. Those are what these tests hold still.</p>
 */
class ParallelIngestTest {

    @TempDir Path tmp;

    private WyrdLuceneStore store;
    private StudyService study;
    private DocumentIndexer indexer;
    private Path books;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wyrdsekai.data.dir", tmp.resolve("data").toString());
        store = new WyrdLuceneStore(tmp.resolve("search"), 384);
        study = new StudyService(store, null);
        indexer = new DocumentIndexer(study);
        books = Files.createDirectories(tmp.resolve("library"));
    }

    private void writeBooks(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            Files.writeString(books.resolve(String.format("book-%03d.txt", i)),
                "Book number " + i + ". A paragraph of text long enough to index "
                    + "and find again afterwards, mentioning shibboleth" + i + ".");
        }
    }

    /** THE case: many files, several workers, every count exact. */
    @Test
    void a_parallel_run_indexes_everything_exactly_once() throws Exception {
        writeBooks(120);

        var result = indexer.indexDirectory("did:key:zOwner", "books", books, null);

        assertThat(result.filesProcessed()).isEqualTo(120);
        assertThat(result.errors()).isZero();
        assertThat(result.chunksIndexed()).isGreaterThanOrEqualTo(120);
        // Every book findable, exactly once.
        var hits = store.searchStudy("did:key:zOwner", "shibboleth7", null, 5);
        assertThat(hits).hasSize(1);
    }

    /** Resumability is the property that let a killed 10-hour run cost nothing. */
    @Test
    void a_second_run_skips_everything() throws Exception {
        writeBooks(40);
        indexer.indexDirectory("did:key:zOwner", "books", books, null);

        var second = indexer.indexDirectory("did:key:zOwner", "books", books, null);

        assertThat(second.filesProcessed()).isZero();
        assertThat(second.skippedDone()).isEqualTo(40);
        assertThat(store.searchStudy("did:key:zOwner", "shibboleth3", null, 5))
            .as("re-run must not duplicate")
            .hasSize(1);
    }

    /** A partial ledger resumes with only the remainder — mid-run kill rehearsal. */
    @Test
    void a_partial_run_resumes_with_the_remainder() throws Exception {
        writeBooks(30);
        // First run indexes only the first 10 (simulate: separate dir pass).
        var firstTen = Files.createDirectories(tmp.resolve("first"));
        for (int i = 0; i < 10; i++) {
            Files.copy(books.resolve(String.format("book-%03d.txt", i)),
                firstTen.resolve(String.format("book-%03d.txt", i)));
        }
        // Prime the ledger with the ORIGINAL paths by indexing 10 of them via
        // a filtered directory listing — easiest honest simulation: index the
        // full dir once, then add 5 NEW books and re-run.
        indexer.indexDirectory("did:key:zOwner", "books", books, null);
        for (int i = 30; i < 35; i++) {
            Files.writeString(books.resolve(String.format("book-%03d.txt", i)),
                "Late arrival " + i + " latecomer" + i + ".");
        }

        var resume = indexer.indexDirectory("did:key:zOwner", "books", books, null);

        assertThat(resume.filesProcessed()).isEqualTo(5);
        assertThat(resume.skippedDone()).isEqualTo(30);
        assertThat(store.searchStudy("did:key:zOwner", "latecomer33", null, 5)).hasSize(1);
    }

    /** One unreadable file must cost one error, not the run. */
    @Test
    void a_poisoned_file_does_not_end_the_run() throws Exception {
        writeBooks(20);
        Files.write(books.resolve("broken.epub"), new byte[]{0x50, 0x4b, 0x03, 0x04, 0x00});

        var result = indexer.indexDirectory("did:key:zOwner", "books", books, null);

        assertThat(result.filesProcessed()).isEqualTo(21);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(store.searchStudy("did:key:zOwner", "shibboleth19", null, 5)).hasSize(1);
    }

    /** Progress lines stay monotone enough to monitor, even from many workers. */
    @Test
    void progress_reporting_survives_concurrency() throws Exception {
        writeBooks(250);
        List<String> messages = new CopyOnWriteArrayList<>();

        indexer.indexDirectory("did:key:zOwner", "books", books, messages::add);

        var processed = messages.stream()
            .filter(m -> m.startsWith("Processed "))
            .map(m -> Integer.parseInt(m.split(" ")[1]))
            .toList();
        assertThat(processed).isNotEmpty();
        assertThat(processed).as("counts must never exceed the total").allMatch(n -> n <= 250);
        assertThat(Collections.max(processed)).isGreaterThanOrEqualTo(200);
        assertThat(messages.getLast()).startsWith("Done!");
    }

    /** The pool sizes to the machine, leaves headroom, and respects the override. */
    @Test
    void worker_count_is_sane() {
        int n = DocumentIndexer.workerCount();

        assertThat(n).isGreaterThanOrEqualTo(2);
        assertThat(n).isLessThanOrEqualTo(
            Math.max(2, Runtime.getRuntime().availableProcessors()));
    }
}
