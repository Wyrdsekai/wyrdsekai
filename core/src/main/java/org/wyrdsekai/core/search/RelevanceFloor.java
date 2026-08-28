package org.wyrdsekai.core.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Semantic rerank + relevance floor over BM25 candidates.
 *
 * <p><b>Why one shared place.</b> {@code CompanionActor.handleLibrarySearch} grew
 * a floor in July because BM25 on a large mixed corpus answers <em>every</em>
 * query — ask about diffusion models and it returns freedict idiom entries, which
 * the companion then presents as research. The item-script path
 * ({@code world.library.search}) never got one, so the same junk reached the same
 * companion by a different door: asked what the Librarian told Kestan about
 * velsharas, the top hits were a StackExchange gardening post (it matched "glass")
 * and a JMdict entry for ライブラリアン (it matched "librarian"). She cited them
 * (2026-08-07).</p>
 *
 * <p><b>Why rerank and not just filter.</b> Once results come from more than one
 * Lucene collection — knowledge packs plus the household's own Study — their BM25
 * scores are not comparable: each is scored against its own corpus statistics.
 * Concatenating them puts whichever collection was searched first on top
 * regardless of quality, which is exactly how eight real Glass Tide passages
 * ended up ranked below eight dictionary entries. Cosine similarity against the
 * query is a single common scale, so it both orders and filters.</p>
 */
public final class RelevanceFloor {

    private static final Logger log = LoggerFactory.getLogger(RelevanceFloor.class);

    /** Long passages cost embedding time and add little; the head carries the topic. */
    private static final int SCORE_CHARS = 512;

    private RelevanceFloor() {}

    /** The configured floor, shared by every caller so they cannot drift apart. */
    public static double floor() {
        return WyrdConfig.get().resolveDouble(
            "WYRDSEKAI_LIBRARY_RELEVANCE_FLOOR", "library.relevance_floor", 0.35);
    }

    /**
     * Rerank by semantic similarity to the query and drop anything below the floor.
     *
     * <p>Degrades to the input, unchanged and in its original order, whenever
     * embeddings are unavailable or fail — a scoring outage must not look like an
     * empty library.</p>
     *
     * @param query the person's question
     * @param hits  BM25 candidates, possibly from several collections
     * @return kept hits, most relevant first
     */
    public static List<WyrdLuceneStore.SearchResult> rank(
            String query, List<WyrdLuceneStore.SearchResult> hits) {
        return rank(query, hits, floor());
    }

    /** As {@link #rank(String, List)}, with an explicit floor (for tests). */
    public static List<WyrdLuceneStore.SearchResult> rank(
            String query, List<WyrdLuceneStore.SearchResult> hits, double floor) {
        return rank(query, hits, floor, null);
    }

    /**
     * As {@link #rank(String, List)}, reusing vectors a previous stage computed.
     *
     * @param cached looks up an already-computed embedding by document id; may be
     *               null. Supplying {@code WyrdLuceneStore::cachedRerankVector}
     *               means the Study leg's rerank is not paid for twice — measured
     *               at 7.4s of duplicate embedding on a single live query.
     */
    public static List<WyrdLuceneStore.SearchResult> rank(
            String query, List<WyrdLuceneStore.SearchResult> hits, double floor,
            Function<String, List<Float>> cached) {
        if (hits == null || hits.isEmpty() || query == null || query.isBlank()) return hits;
        var embed = EmbeddingService.get();
        if (embed == null) return hits;

        List<Float> q;
        try {
            q = embed.embed(query);
        } catch (Exception e) {
            log.debug("Relevance floor skipped — query embed failed: {}", e.toString());
            return hits;
        }
        if (q == null || q.isEmpty()) return hits;

        // Reuse anything already embedded upstream, and batch only the rest into
        // ONE call. The per-call overhead dominates at this size — the un-batched
        // form is what made the earlier rerank effectively inert on long chunks.
        var vectors = new ArrayList<List<Float>>(hits.size());
        var bodies = new ArrayList<String>(hits.size());
        var needIdx = new ArrayList<Integer>();
        var needText = new ArrayList<String>();
        int reused = 0;
        for (int i = 0; i < hits.size(); i++) {
            var r = hits.get(i);
            var text = r.content() == null ? "" : r.content();
            bodies.add(text.length() > SCORE_CHARS ? text.substring(0, SCORE_CHARS) : text);
            var hit = cached == null ? null : cached.apply(r.id());
            vectors.add(hit);
            if (hit != null) {
                reused++;
            } else if (!bodies.get(i).isBlank()) {
                needIdx.add(i);
                needText.add(bodies.get(i));
            }
        }
        if (!needText.isEmpty()) {
            try {
                var fresh = embed.embedBatch(needText);
                if (fresh == null || fresh.size() != needText.size()) return hits;
                for (int k = 0; k < needIdx.size(); k++) vectors.set(needIdx.get(k), fresh.get(k));
            } catch (Exception e) {
                log.debug("Relevance floor skipped — batch embed failed: {}", e.toString());
                return hits;
            }
        }
        if (reused > 0) {
            log.debug("Relevance floor reused {} upstream vector(s), embedded {}",
                reused, needText.size());
        }

        record Scored(WyrdLuceneStore.SearchResult hit, double sim) {}
        var kept = new ArrayList<Scored>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            if (bodies.get(i).isBlank()) continue;          // nothing to answer from
            var v = vectors.get(i);
            if (v == null) continue;                        // could not be scored
            var sim = cosineNormalized(q, v);
            if (sim >= floor) kept.add(new Scored(hits.get(i), sim));
        }
        kept.sort(Comparator.comparingDouble(Scored::sim).reversed());

        if (kept.size() < hits.size()) {
            log.info("Relevance floor ({}): {}/{} hits kept for '{}'",
                floor, kept.size(), hits.size(), query);
        }
        // CARRY THE SCALE, NOT JUST THE ORDER.
        //
        // Reranking fixed the ordering and left every hit carrying its original
        // BM25 score — numbers from two different corpora, in one list, looking
        // comparable. Downstream that is worse than the unsorted version was,
        // because a consumer now sees a sensibly-ordered list and reasonably
        // trusts the numbers attached to it.
        //
        // Live 2026-08-08: library_card gates on a RELATIVE threshold,
        // `minScore = 0.3 * results[0].score`. With a pack hit at 8.77 (BM25) and
        // Study passages at 0.766 (cosine), the gate was 2.63 and every book
        // passage was dropped before it could be read. The summarizer got a
        // 142-character prompt and she told the bondholder her books held no
        // answer. They held ten passages.
        //
        // So the score that leaves here is the similarity it was ranked by: one
        // scale, 0..1, comparable across collections, and meaning the same thing
        // as the order it arrives in.
        var out = new ArrayList<WyrdLuceneStore.SearchResult>(kept.size());
        for (var s : kept) {
            var h = s.hit();
            out.add(new WyrdLuceneStore.SearchResult(
                h.id(), h.content(), h.source(), h.metadata(), (float) s.sim()));
        }
        return out;
    }

    /** Dot product of two L2-normalized embedding vectors (== cosine similarity). */
    public static double cosineNormalized(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size()) return 0.0;
        double dot = 0;
        for (int i = 0; i < a.size(); i++) dot += (double) a.get(i) * b.get(i);
        return dot;
    }
}
