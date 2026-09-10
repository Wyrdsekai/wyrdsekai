package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.item.KnowledgeSearch;
import org.wyrdsekai.core.item.StudyReach;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.search.RelevanceFloor;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Offline bench — the same {@link RetrievalBench} the server runs, against any index copy,
 * in two arms: the production path (dense + sparse, set-union, relevance floor) and text-only
 * (BM25 + the same floor). Usage:
 * <pre>
 *   LibraryBenchMain --index &lt;dataDir&gt; --log reading-log.json [--gold bench-gold.json] [--k 10] [--limit 300]
 * </pre>
 */
public final class LibraryBenchMain {

    private LibraryBenchMain() {}

    public static void main(String[] args) throws Exception {
        Path index = null, log = null, gold = null;
        int k = 10, limit = 300;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--index" -> index = Path.of(args[++i]);
                case "--log" -> log = Path.of(args[++i]);
                case "--gold" -> gold = Path.of(args[++i]);
                case "--k" -> k = Integer.parseInt(args[++i]);
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                default -> { System.err.println("unknown arg " + args[i]); System.exit(2); }
            }
        }
        if (index == null || log == null) { System.err.println("--index and --log are required"); System.exit(2); }

        var svc = EmbeddingService.init();
        int dim = svc.embed("dimension probe").size();
        var store = new WyrdLuceneStore(index, dim);
        try {
            store.ensureAllCollections();
            var mapper = new ObjectMapper();
            var entries = mapper.readValue(Files.readString(log), List.class);
            var queries = new ArrayList<String>();
            int n = 0;
            for (int i = entries.size() - 1; i >= 0 && n < limit; i--, n++) {
                if (entries.get(i) instanceof Map<?, ?> m && m.get("query") != null) queries.add(String.valueOf(m.get("query")));
            }
            var goldList = RetrievalBench.loadGold(gold);
            System.out.printf("index %s  embedder %s (%dd)  vectors=%d  queries=%d  gold=%d%n", index,
                EmbeddingService.currentModelVersion(), dim, store.countWithVectors("knowledge"), queries.size(), goldList.size());

            RetrievalBench.Searcher production = (q, kk) -> KnowledgeSearch.search(store, q, kk, StudyReach.NONE, null, false)
                .stream().map(LibraryBenchMain::hit).toList();
            RetrievalBench.Searcher textOnly = (q, kk) -> {
                var raw = store.searchKnowledgeText(q, Math.min(kk * 2, 20));
                var ranked = RelevanceFloor.rank(WyrdLuceneStore.stripProtectionMarkers(q), raw,
                    RelevanceFloor.floor(), store::cachedRerankVector);
                var out = new ArrayList<RetrievalBench.Hit>();
                for (var r : ranked) {
                    var meta = r.metadata();
                    out.add(new RetrievalBench.Hit(r.id(), meta == null ? r.id() : String.valueOf(meta.getOrDefault("title", r.id())),
                        meta == null ? null : String.valueOf(meta.getOrDefault("pack", "")), r.content(), r.score()));
                    if (out.size() >= kk) break;
                }
                return out;
            };
            print("text-only (BM25 + floor)", RetrievalBench.run(queries, goldList, textOnly, k));
            print("production (dense+sparse set-union + floor)", RetrievalBench.run(queries, goldList, production, k));
        } finally {
            store.close();
        }
    }

    private static RetrievalBench.Hit hit(Map<String, Object> m) {
        var id = String.valueOf(m.get("id"));
        int c = id.indexOf(':');
        return new RetrievalBench.Hit(id, m.get("title") == null ? null : String.valueOf(m.get("title")),
            c > 0 ? id.substring(0, c) : id, m.get("text") == null ? null : String.valueOf(m.get("text")),
            m.get("score") instanceof Number s ? s.doubleValue() : 0.0);
    }

    private static void print(String arm, RetrievalBench.Report r) {
        System.out.printf("%n== %s  (k=%d)%n", arm, r.k());
        System.out.printf("  distinct %d  zero-hit %d  dictionary-top %d  labeled %d  miss %d  (miss rate %s)%n",
            r.distinctQueries(), r.zeroHit(), r.dictionaryTop(), r.labeled(), r.labeledMiss(),
            Double.isNaN(r.missRate()) ? "n/a" : String.format("%.0f%%", 100 * r.missRate()));
        for (var row : r.rows()) {
            if (row.labeled()) System.out.printf("  gold@%-3d %s%n", row.goldRank(), row.query().length() > 70 ? row.query().substring(0, 70) : row.query());
        }
    }
}
