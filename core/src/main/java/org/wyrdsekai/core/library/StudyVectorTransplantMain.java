package org.wyrdsekai.core.library;

import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline: copy a published pack's vectors onto the Study documents it was projected from.
 * <pre>StudyVectorTransplantMain --index &lt;dataDir&gt; --pack study-share-books [--commit-every 20000]</pre>
 */
public final class StudyVectorTransplantMain {
    private StudyVectorTransplantMain() {}

    public static void main(String[] args) throws Exception {
        Path index = null; String pack = "study-share-books"; int commitEvery = 20000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--index" -> index = Path.of(args[++i]);
                case "--pack" -> pack = args[++i];
                case "--commit-every" -> commitEvery = Integer.parseInt(args[++i]);
                default -> { System.err.println("unknown arg " + args[i]); System.exit(2); }
            }
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
            long before = store.countWithVectors(SearchCollections.STUDY);
            long t0 = System.nanoTime();
            System.out.printf("study vectors before: %d%n", before);
            long n = store.copyVectorsToStudy(pack, commitEvery, c ->
                System.out.printf("  copied %d (%.0f/s)%n", c, c / Math.max(1e-6, (System.nanoTime() - t0) / 1e9)));
            System.out.printf("copied %d vector(s) from %s in %.0fs; study vectors after: %d%n",
                n, pack, (System.nanoTime() - t0) / 1e9, store.countWithVectors(SearchCollections.STUDY));
        } finally {
            store.close();
        }
    }
}
