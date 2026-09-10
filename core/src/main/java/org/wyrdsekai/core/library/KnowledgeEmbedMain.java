package org.wyrdsekai.core.library;

import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Offline embed job — give text-only knowledge chunks their dense vectors, beside a COPY of
 * the index, never the live one (the live server holds the writer; and the household comes
 * first). Usage:
 * <pre>
 *   KnowledgeEmbedMain --index &lt;dataDir&gt; [--pack &lt;name&gt;]... [--drop-pack &lt;name&gt;]...
 *                      [--threads N] [--batch 32] [--limit N] [--commit-every 5000] [--merge]
 * </pre>
 * The embedding model and dimension come from the usual configuration
 * ({@code WYRDSEKAI_EMBEDDING_MODEL}); the index must have been created for that dimension.
 * Prints chunks/second as it goes so the sizing question gets a measured answer.
 */
public final class KnowledgeEmbedMain {

    private KnowledgeEmbedMain() {}

    public static void main(String[] args) throws Exception {
        Path index = null;
        var packs = new ArrayList<String>();
        var drop = new ArrayList<String>();
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        int batch = 32, limit = Integer.MAX_VALUE, commitEvery = 5000;
        boolean merge = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--index" -> index = Path.of(args[++i]);
                case "--pack" -> packs.add(args[++i]);
                case "--drop-pack" -> drop.add(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--batch" -> batch = Integer.parseInt(args[++i]);
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--commit-every" -> commitEvery = Integer.parseInt(args[++i]);
                case "--merge" -> merge = true;
                default -> { System.err.println("unknown arg " + args[i]); System.exit(2); }
            }
        }
        if (index == null) { System.err.println("--index <dataDir> is required"); System.exit(2); }
        if (System.getProperty(WyrdLuceneStore.RAM_BUFFER_MB_PROP) == null) {
            System.setProperty(WyrdLuceneStore.RAM_BUFFER_MB_PROP, "1024");   // bulk job: few big flushes
        }

        var svc = EmbeddingService.init();
        int dim = svc.embed("dimension probe").size();
        System.out.printf("embedder %s (%dd), %d thread(s), batch %d%n",
            EmbeddingService.currentModelVersion(), dim, threads, batch);

        var store = new WyrdLuceneStore(index, dim);
        try {
            store.ensureAllCollections();
            for (var p : drop) {
                long n = store.deleteKnowledgeByPack(p);
                System.out.printf("dropped pack %s: %d chunk(s)%n", p, n);
            }
            long before = store.countWithVectors(SearchCollections.KNOWLEDGE);
            System.out.printf("vectors before: %d%n", before);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            var done = new AtomicLong();
            long t0 = System.nanoTime();
            var targets = packs.isEmpty() ? java.util.Collections.<String>singletonList(null) : packs;   // List.of refuses null
            try {
                for (var pack : targets) {
                    // One pass lists the work; then batches walk the list — no rescans.
                    var todo = store.knowledgeIdsWithoutVectors(pack, (int) Math.min(Integer.MAX_VALUE, (long) limit - done.get()));
                    System.out.printf("%s: %d text-only chunk(s) to embed%n", pack == null ? "all" : pack, todo.size());
                    for (int from = 0; from < todo.size() && done.get() < limit; from += commitEvery) {
                        var ids = todo.subList(from, Math.min(todo.size(), from + commitEvery));
                        if (ids.isEmpty()) break;
                        var futures = new ArrayList<Future<Integer>>();
                        for (int i = 0; i < ids.size(); i += batch) {
                            var slice = ids.subList(i, Math.min(ids.size(), i + batch));
                            futures.add(pool.submit(() -> {
                                var texts = new ArrayList<String>(slice.size());
                                var keep = new ArrayList<String>(slice.size());
                                for (var id : slice) {
                                    var r = store.getById(SearchCollections.KNOWLEDGE, id);
                                    if (r == null || r.content() == null || r.content().isBlank()) continue;
                                    var meta = r.metadata();
                                    var title = meta == null ? "" : String.valueOf(meta.getOrDefault("title", ""));
                                    texts.add((title.isBlank() ? "" : title + "\n") + r.content());
                                    keep.add(id);
                                }
                                if (texts.isEmpty()) return 0;
                                var vecs = svc.embedBatch(texts);
                                // Writes run in parallel: Lucene's writer takes concurrent updates, and
                                // a lock here left 32 of 36 threads blocked while one wrote — the GPUs
                                // sat at a quarter load behind it (gpu-host, 2026-09-05).
                                int n = 0;
                                for (int k = 0; k < keep.size() && k < vecs.size(); k++) {
                                    if (store.addKnowledgeVector(keep.get(k), vecs.get(k))) n++;
                                }
                                return n;
                            }));
                        }
                        int wrote = 0;
                        for (var f : futures) wrote += f.get();
                        store.commitKnowledge();
                        done.addAndGet(wrote);
                        double secs = (System.nanoTime() - t0) / 1e9;
                        System.out.printf("%s: +%d (total %d) at %.0f/s%n",
                            pack == null ? "all" : pack, wrote, done.get(), done.get() / Math.max(secs, 1e-6));
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            store.commitKnowledge();
            if (merge) {
                // Every vector add is an update — delete + re-add — so the index carries the
                // old copies until they merge away. One merge at the end makes the copy that
                // ships to a household a third of the size.
                long m0 = System.nanoTime();
                System.out.println("merging knowledge segments...");
                boolean ok = store.forceMergeCollection(SearchCollections.KNOWLEDGE);
                System.out.printf("merge %s in %.0fs%n", ok ? "done" : "FAILED", (System.nanoTime() - m0) / 1e9);
            }
            double secs = (System.nanoTime() - t0) / 1e9;
            long after = store.countWithVectors(SearchCollections.KNOWLEDGE);
            System.out.printf("vectors after: %d (+%d) in %.0fs = %.0f/s%n", after, after - before, secs,
                (after - before) / Math.max(secs, 1e-6));
        } finally {
            store.close();
        }
    }
}
