package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The library's own retrieval bench — the number every retrieval change is judged by.
 *
 * <p>Codezaiku's Librarian derives its bench from the corpus itself and refused to ship a
 * reranker because the number did not move. We have better input: the companion's REAL
 * reading log — every query she or the household asked, with who asked and what came back.
 * The bench replays those queries through the same search path and reports:
 * <ul>
 *   <li><b>zero-hit rate</b> over all distinct queries — "the shelves answered nothing";</li>
 *   <li><b>miss rate at k</b> over the LABELED queries — a gold file
 *       ({@code <library>/bench-gold.json}) says what a query should have surfaced (a chunk
 *       id, or a lowercase substring of the title/text). Unlabeled queries are reported, not
 *       scored: a bench that invents gold is not a bench;</li>
 *   <li><b>dictionary-top rate</b> — how often the top hit is a gloss (the freedict drift);</li>
 *   <li><b>repeat rate</b> — identical queries asked again and again (an item's canned lookup,
 *       or a want that keeps returning to the same door).</li>
 * </ul>
 * Pure: no store, no clock, no I/O except {@link #loadGold(Path)}. The replay is the caller's
 * {@link Searcher}, so the server replays through the production path with recording OFF.
 */
public final class RetrievalBench {

    private RetrievalBench() {}

    /** What a query should have surfaced: a chunk id, or a lowercase substring of title/text. */
    public record Gold(String query, List<String> expect) {}

    /** One replayed hit. */
    public record Hit(String id, String title, String pack, String text, double score) {}

    /** The replay function — production search, recording off. */
    @FunctionalInterface
    public interface Searcher {
        List<Hit> search(String query, int k);
    }

    /** One replayed query. {@code goldRank} is 1-based; 0 = labeled and missed; -1 = unlabeled. */
    public record Row(String query, int askedTimes, int hits, double topScore, String topPack,
                      boolean dictionaryTop, int goldRank) {
        public boolean labeled() { return goldRank >= 0; }
        public boolean missed() { return goldRank == 0; }
    }

    public record Report(int k, int askedTotal, int distinctQueries, int zeroHit, int repeated,
                         int dictionaryTop, int labeled, int labeledMiss, List<Row> rows) {
        /** Miss rate at k over labeled queries; NaN when nothing is labeled. */
        public double missRate() { return labeled == 0 ? Double.NaN : (double) labeledMiss / labeled; }
        public double zeroHitRate() { return distinctQueries == 0 ? 0 : (double) zeroHit / distinctQueries; }
    }

    static final List<String> DICTIONARY_PACKS = List.of("freedict", "jmdict", "dict-", "wiktionary");

    public static boolean isDictionaryPack(String pack) {
        if (pack == null) return false;
        var p = pack.toLowerCase(Locale.ROOT);
        for (var d : DICTIONARY_PACKS) if (p.contains(d)) return true;
        return false;
    }

    /**
     * Replay {@code queries} (as asked, duplicates included — they count as repeats) through
     * {@code searcher} at {@code k}, scoring the ones {@code gold} labels.
     */
    public static Report run(List<String> queries, List<Gold> gold, Searcher searcher, int k) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var q : queries) {
            if (q == null) continue;
            var key = q.strip();
            if (key.isEmpty()) continue;
            counts.merge(key, 1, Integer::sum);
        }
        var goldByQuery = new LinkedHashMap<String, List<String>>();
        if (gold != null) {
            for (var g : gold) {
                if (g == null || g.query() == null) continue;
                goldByQuery.put(g.query().strip().toLowerCase(Locale.ROOT),
                    g.expect() == null ? List.of() : g.expect());
            }
        }

        var rows = new ArrayList<Row>();
        int zero = 0, repeated = 0, dictTop = 0, labeled = 0, labeledMiss = 0, asked = 0;
        for (var e : counts.entrySet()) {
            var q = e.getKey();
            int times = e.getValue();
            asked += times;
            if (times > 1) repeated++;
            List<Hit> hits;
            try {
                hits = searcher.search(q, k);
            } catch (RuntimeException ex) {
                hits = List.of();
            }
            if (hits == null) hits = List.of();
            if (hits.isEmpty()) zero++;
            var top = hits.isEmpty() ? null : hits.getFirst();
            boolean dictionaryTop = top != null && isDictionaryPack(top.pack());
            if (dictionaryTop) dictTop++;

            int goldRank = -1;
            var expect = goldByQuery.get(q.toLowerCase(Locale.ROOT));
            if (expect != null && !expect.isEmpty()) {
                labeled++;
                goldRank = rankOf(hits, expect, k);
                if (goldRank == 0) labeledMiss++;
            }
            rows.add(new Row(q, times, hits.size(),
                top == null ? 0.0 : top.score(),
                top == null ? null : top.pack(),
                dictionaryTop, goldRank));
        }
        return new Report(k, asked, counts.size(), zero, repeated, dictTop, labeled, labeledMiss,
            List.copyOf(rows));
    }

    /** 1-based rank of the first hit matching any expectation within the top k; 0 if none. */
    static int rankOf(List<Hit> hits, List<String> expect, int k) {
        int limit = Math.min(k, hits.size());
        for (int i = 0; i < limit; i++) {
            var h = hits.get(i);
            for (var x : expect) {
                if (x == null || x.isBlank()) continue;
                if (x.equals(h.id())) return i + 1;
                var needle = x.toLowerCase(Locale.ROOT);
                if (h.title() != null && h.title().toLowerCase(Locale.ROOT).contains(needle)) return i + 1;
                if (h.text() != null && h.text().toLowerCase(Locale.ROOT).contains(needle)) return i + 1;
            }
        }
        return 0;
    }

    /**
     * Gold file: a JSON array of {@code {"query": "...", "expect": ["chunk-id or substring", ...]}}.
     * Missing file ⇒ no labels (the bench still reports the unlabeled metrics).
     */
    public static List<Gold> loadGold(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        var mapper = new ObjectMapper();
        var raw = mapper.readValue(Files.readString(file), List.class);
        var out = new ArrayList<Gold>();
        for (var o : raw) {
            if (!(o instanceof Map<?, ?> m)) continue;
            var q = m.get("query");
            var ex = m.get("expect");
            if (q == null) continue;
            var expect = new ArrayList<String>();
            if (ex instanceof List<?> l) {
                for (var x : l) if (x != null) expect.add(String.valueOf(x));
            } else if (ex != null) {
                expect.add(String.valueOf(ex));
            }
            out.add(new Gold(String.valueOf(q), List.copyOf(expect)));
        }
        return List.copyOf(out);
    }

    /** Gold file location beside the reading log. */
    public static Path goldFile(Path libraryRoot) {
        return libraryRoot == null ? null : libraryRoot.resolve("bench-gold.json");
    }
}
