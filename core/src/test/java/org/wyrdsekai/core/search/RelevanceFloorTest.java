package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * BM25 scores from two collections are not comparable.
 *
 * <p>Live, 2026-08-07. The item-script library search merged knowledge-pack hits
 * with the household's own Study hits by <b>concatenation</b>. Each set is scored
 * against its own corpus statistics, so the packs — searched first — sat on top
 * regardless of quality: eight real Glass Tide passages ranked below a
 * StackExchange gardening post (it matched "glass") and a JMdict entry for
 * ライブラリアン (it matched "librarian"). Those dictionary entries are the
 * sources the companion cited.</p>
 *
 * <p>These tests pin the two properties that matter and can be checked without an
 * embedding model present: <b>degrade to unchanged</b> when scoring is
 * unavailable, and never invent or lose results. The ordering itself is exercised
 * against the live index.</p>
 */
class RelevanceFloorTest {

    private static WyrdLuceneStore.SearchResult hit(String id, String text, float score) {
        return new WyrdLuceneStore.SearchResult(
            id, text, "pack", Map.of("title", id), score);
    }

    private static final List<WyrdLuceneStore.SearchResult> MIXED = List.of(
        hit("gardening", "Do I put my pots with newly planted bulbs outside "
            + "during the winter? We get glass and ice.", 8.77f),
        hit("jmdict", "ライブラリアン — librarian", 8.27f),
        hit("glasstide", "The Librarian explained that a vel-shara is a speech "
            + "with power — the vel-shara of Adrun was a counter-virus.", 2.1f));

    /**
     * A scoring outage must look like "unranked", never like "empty library".
     * With no EmbeddingService installed this is the path that runs in CI.
     */
    @Test
    void degrades_to_the_input_when_scoring_is_unavailable() {
        var out = RelevanceFloor.rank("velshara", MIXED);

        assertThat(out)
            .as("no embedder must mean unchanged, not filtered to nothing")
            .isEqualTo(MIXED);
    }

    /** Degenerate inputs must not throw and must not fabricate. */
    @Test
    void handles_empty_and_null_input() {
        assertThat(RelevanceFloor.rank("q", List.of())).isEmpty();
        assertThat(RelevanceFloor.rank("q", null)).isNull();
        assertThat(RelevanceFloor.rank(null, MIXED)).isEqualTo(MIXED);
        assertThat(RelevanceFloor.rank("   ", MIXED)).isEqualTo(MIXED);
    }

    /** The floor is one configured value, so the two callers cannot drift apart. */
    @Test
    void exposes_a_single_shared_floor() {
        var floor = RelevanceFloor.floor();

        assertThat(floor).isBetween(0.0, 1.0);
        assertThat(RelevanceFloor.floor())
            .as("must be stable between reads")
            .isEqualTo(floor);
    }

    /** Cosine of a unit vector with itself is 1; with an orthogonal one, 0. */
    @Test
    void cosine_is_a_dot_product_over_normalized_vectors() {
        var a = List.of(1.0f, 0.0f);
        var b = List.of(0.0f, 1.0f);

        assertThat(RelevanceFloor.cosineNormalized(a, a)).isCloseTo(1.0, within());
        assertThat(RelevanceFloor.cosineNormalized(a, b)).isCloseTo(0.0, within());
    }

    /** Mismatched or missing vectors score zero rather than throwing. */
    @Test
    void cosine_is_defensive() {
        assertThat(RelevanceFloor.cosineNormalized(null, List.of(1.0f))).isZero();
        assertThat(RelevanceFloor.cosineNormalized(List.of(1.0f), null)).isZero();
        assertThat(RelevanceFloor.cosineNormalized(List.of(1.0f), List.of(1.0f, 2.0f)))
            .as("different dimensions must not be compared")
            .isZero();
    }

    /**
     * The caller-side guard: the item path must go through the shared floor, not
     * concatenate and hope. This is the regression that reached production.
     */
    @Test
    void the_item_path_reranks_the_merged_set() throws Exception {
        // The merge moved out of the provider into KnowledgeSearch, so that ONE
        // implementation serves a companion and a person alike — the provider
        // used to be the only way in, which is how a person's search ended up
        // running under a placeholder identity (2026-08-25).
        var src = Files.readString(sourceOf(
            "core/src/main/java/org/wyrdsekai/core/item/KnowledgeSearch.java"));

        int addAll = src.indexOf("results.addAll(studyHits)");
        int rank = src.indexOf(
            "RelevanceFloor.rank(WyrdLuceneStore.stripProtectionMarkers(query),");

        assertThat(addAll).as("the merge must still happen").isGreaterThan(0);
        assertThat(rank).as("the merged set must be reranked").isGreaterThan(0);
        assertThat(rank)
            .as("rerank must come AFTER the merge, or Study is ranked alone")
            .isGreaterThan(addAll);
        assertThat(src)
            .as("the item path must reuse upstream vectors — it pays 7.4s otherwise")
            .contains("cachedRerankVector");
    }

    /** And the action path must use the same one, so they cannot diverge again. */
    @Test
    void the_action_path_uses_the_same_floor() throws Exception {
        var src = Files.readString(sourceOf(
            "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));

        assertThat(src).contains("RelevanceFloor.rank(query, hits");
        assertThat(src)
            .as("the private copy must be gone, not merely bypassed")
            .doesNotContain("WYRDSEKAI_LIBRARY_RELEVANCE_FLOOR");
        assertThat(src)
            .as("and it must reuse the rerank's vectors rather than embed twice")
            .contains("cachedRerankVector");
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-6);
    }

    private static Path sourceOf(String repoRelative) {
        var fromCore = Paths.get("..", repoRelative);
        return Files.exists(fromCore)
            ? fromCore : Paths.get(repoRelative);
    }

    /** Unused helper kept honest: the fixture list is immutable where it matters. */
    @Test
    void fixture_is_not_mutated_by_ranking() {
        var copy = new ArrayList<>(MIXED);
        RelevanceFloor.rank("velshara", MIXED);
        assertThat(MIXED).isEqualTo(copy);
    }

    /**
     * The score that leaves here must be the one it was ranked by.
     *
     * <p>Reranking fixed the order and left BM25 scores attached — two corpora's
     * numbers in one list, looking comparable. {@code library_card} gates on
     * {@code 0.3 * results[0].score}; with a pack hit at 8.77 (BM25) and Study
     * passages at 0.766 (cosine) the gate was 2.63 and every book passage was
     * dropped before it could be read (live, 2026-08-08).</p>
     */
    @Test
    void the_ranked_output_carries_the_similarity_not_the_bm25_score() throws Exception {
        var src = Files.readString(sourceOf(
            "core/src/main/java/org/wyrdsekai/core/search/RelevanceFloor.java"));

        assertThat(src)
            .as("hits must be rebuilt with the similarity as their score")
            .contains("(float) s.sim()");
        assertThat(src)
            .as("returning the original hit keeps the incomparable BM25 number")
            .doesNotContain("for (var s : kept) out.add(s.hit());");
    }

    /** Degraded mode must NOT rewrite scores — unranked means untouched. */
    @Test
    void the_no_embedder_path_leaves_scores_alone() {
        var out = RelevanceFloor.rank("velshara", MIXED);

        assertThat(out).isEqualTo(MIXED);
        assertThat(out.getFirst().score())
            .as("without scoring there is no similarity to substitute")
            .isEqualTo(8.77f);
    }
}
