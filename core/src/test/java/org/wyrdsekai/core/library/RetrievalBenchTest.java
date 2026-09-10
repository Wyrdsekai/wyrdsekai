package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bench scores only what is labeled, counts what is not, and never invents gold.
 */
class RetrievalBenchTest {

    private static RetrievalBench.Hit hit(String id, String title, String pack, String text) {
        return new RetrievalBench.Hit(id, title, pack, text, 0.7);
    }

    @Test
    void labeled_queries_are_scored_by_rank_and_unlabeled_ones_are_only_counted() {
        Map<String, List<RetrievalBench.Hit>> world = Map.of(
            "kovacs", List.of(
                hit("garden:1", "Winter mulch", "stackexchange-gardening", "snow cover on beds"),
                hit("books:7", "Altered Carbon — chapter 3", "study-share-books", "Takeshi Kovacs woke")),
            "snow", List.of(hit("dict:1", "snow (n.)", "freedict-spa-eng", "nieve")),
            "immortality", List.of());
        RetrievalBench.Searcher s = (q, k) -> world.getOrDefault(q, List.of());

        var gold = List.of(
            new RetrievalBench.Gold("kovacs", List.of("altered carbon")),
            new RetrievalBench.Gold("snow", List.of("glass tide")));

        var r = RetrievalBench.run(
            List.of("kovacs", "kovacs", "snow", "immortality", "  ", "kovacs"), gold, s, 10);

        assertEquals(6 - 1, r.askedTotal(), "blank queries are not queries");
        assertEquals(3, r.distinctQueries());
        assertEquals(1, r.repeated(), "kovacs was asked three times");
        assertEquals(1, r.zeroHit(), "immortality found nothing");
        assertEquals(1, r.dictionaryTop(), "snow's top hit is a gloss");
        assertEquals(2, r.labeled());
        assertEquals(1, r.labeledMiss(), "snow wanted Glass Tide and got a dictionary");
        assertEquals(0.5, r.missRate(), 1e-9);

        var kovacs = r.rows().stream().filter(x -> x.query().equals("kovacs")).findFirst().orElseThrow();
        assertEquals(2, kovacs.goldRank(), "the book is second, behind the mulch");
        var imm = r.rows().stream().filter(x -> x.query().equals("immortality")).findFirst().orElseThrow();
        assertEquals(-1, imm.goldRank(), "unlabeled: reported, not scored");
        assertFalse(imm.labeled());
    }

    @Test
    void gold_beyond_k_is_a_miss_and_id_matches_count() {
        RetrievalBench.Searcher s = (q, k) -> List.of(
            hit("a", "x", "p", "x"), hit("b", "y", "p", "y"), hit("books:9", "z", "p", "z"));
        var gold = List.of(new RetrievalBench.Gold("q", List.of("books:9")));
        assertEquals(0, RetrievalBench.run(List.of("q"), gold, s, 2).rows().getFirst().goldRank());
        assertEquals(3, RetrievalBench.run(List.of("q"), gold, s, 3).rows().getFirst().goldRank());
    }

    @Test
    void no_gold_means_no_miss_rate_not_a_perfect_one(@TempDir Path dir) throws Exception {
        assertTrue(RetrievalBench.loadGold(dir.resolve("absent.json")).isEmpty());
        var r = RetrievalBench.run(List.of("anything"), List.of(), (q, k) -> List.of(), 10);
        assertEquals(0, r.labeled());
        assertTrue(Double.isNaN(r.missRate()));
    }

    @Test
    void gold_file_round_trips(@TempDir Path dir) throws Exception {
        var f = dir.resolve("bench-gold.json");
        Files.writeString(f, """
            [{"query":"kovacs","expect":["altered carbon","books:7"]},
             {"query":"snow","expect":"glass tide"}]
            """);
        var gold = RetrievalBench.loadGold(f);
        assertEquals(2, gold.size());
        assertEquals(List.of("altered carbon", "books:7"), gold.get(0).expect());
        assertEquals(List.of("glass tide"), gold.get(1).expect());
    }

    @Test
    void a_throwing_searcher_counts_as_zero_hits_not_a_crash() {
        var r = RetrievalBench.run(List.of("boom"), List.of(),
            (q, k) -> { throw new IllegalStateException("index closed"); }, 10);
        assertEquals(1, r.zeroHit());
    }
}
