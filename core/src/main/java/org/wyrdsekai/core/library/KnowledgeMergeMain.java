package org.wyrdsekai.core.library;

import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline: force-merge the knowledge collection to one segment, without an embedder in
 * the loop. The embed job's {@code --merge} does the same at the end of a run; this exists
 * for a merge that must be retried on its own (gpu-host, 2026-09-05).
 * <pre>KnowledgeMergeMain --index &lt;dataDir&gt; [--steps 64,16,4,1]   [-Dwyrdsekai.lucene.hnsw_merge_workers=N]</pre>
 */
public final class KnowledgeMergeMain {
    private KnowledgeMergeMain() {}

    public static void main(String[] args) throws Exception {
        Path index = null;
        int[] steps = {64, 16, 4, 1};
        for (int i = 0; i < args.length; i++) {
            if ("--index".equals(args[i])) index = Path.of(args[++i]);
            else if ("--steps".equals(args[i])) steps = java.util.Arrays.stream(args[++i].split(",")).mapToInt(Integer::parseInt).toArray();
            else { System.err.println("unknown arg " + args[i]); System.exit(2); }
        }
        if (index == null) { System.err.println("--index <dataDir> is required"); System.exit(2); }
        var metaFile = index.resolve("search").resolve("index-meta.json");
        int dim = 1024;
        if (Files.isRegularFile(metaFile)) {
            var m = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(metaFile));
            dim = m.path("dense_dim").asInt(dim);
        }
        var store = new WyrdLuceneStore(index, dim);
        try {
            store.ensureAllCollections();
            long vectors = store.countWithVectors(SearchCollections.KNOWLEDGE);
            System.out.printf("knowledge vectors: %d; hnsw merge workers: %s%n", vectors,
                System.getProperty(WyrdLuceneStore.HNSW_MERGE_WORKERS_PROP, "1"));
            long t0 = System.nanoTime();
            System.out.printf("segments before: %d; steps: %s%n", store.segmentCount(SearchCollections.KNOWLEDGE),
                java.util.Arrays.toString(steps));
            boolean ok = true;
            for (int max : steps) {
                if (store.segmentCount(SearchCollections.KNOWLEDGE) <= max) {
                    System.out.printf("step %d: already at or below; skipped%n", max);
                    continue;
                }
                long s0 = System.nanoTime();
                System.out.printf("step %d: merging...%n", max);
                ok = store.forceMergeCollection(SearchCollections.KNOWLEDGE, max);
                System.out.printf("step %d: %s in %.0fs; segments now %d%n", max, ok ? "committed" : "FAILED",
                    (System.nanoTime() - s0) / 1e9, store.segmentCount(SearchCollections.KNOWLEDGE));
                if (!ok) break;
            }
            System.out.printf("merge %s in %.0fs; vectors after: %d%n", ok ? "done" : "FAILED",
                (System.nanoTime() - t0) / 1e9, store.countWithVectors(SearchCollections.KNOWLEDGE));
        } finally {
            store.close();
        }
    }
}
