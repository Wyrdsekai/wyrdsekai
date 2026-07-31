package org.wyrdsekai.rendezvous;

import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion — blends two ranked lists into one.
 *
 * <p>Used by {@link RendezvousMain}'s {@code /api/directory/search} to
 * merge Lucene BM25 (keyword) and semantic (cosine) results. BM25 and
 * cosine live in different score spaces; RRF ranks each input by
 * position and sums {@code 1/(k + rank)} across lists, so a result
 * ranking high in either list rises in the merged output. Robust to
 * the scale mismatch between scorers.</p>
 *
 * <p>{@code k=60} is the canonical default from the Cormack et al.
 * 2009 paper and works well for small N (up to a few hundred).</p>
 */
public final class RrfMerge {

    /** Canonical tuning constant. See class Javadoc. */
    public static final int DEFAULT_K = 60;

    private RrfMerge() {}

    /** Equivalent to {@link #merge(List, List, int, int)} with {@link #DEFAULT_K}. */
    public static List<DirectoryStore.SearchHit> merge(
            List<DirectoryStore.SearchHit> a,
            List<DirectoryStore.SearchHit> b,
            int limit) {
        return merge(a, b, limit, DEFAULT_K);
    }

    /**
     * Merge two ranked lists. Order within each input list defines rank;
     * the first element has rank 0. Duplicate DIDs across lists have
     * their RRF scores summed (which rewards agreement between rankers).
     *
     * @return top-{@code limit} hits by merged score, descending. Score
     *     is an integer chosen so the wire shape matches the single-list
     *     paths ({@code 1/(k+1) * 1000 ≈ 16}, scaled so the best hit
     *     reads ~30-100).
     */
    public static List<DirectoryStore.SearchHit> merge(
            List<DirectoryStore.SearchHit> a,
            List<DirectoryStore.SearchHit> b,
            int limit, int k) {
        if (limit <= 0) return List.of();
        var scores = new LinkedHashMap<String, Double>();
        var manifests = new HashMap<String, ZoneManifestV1>();

        accumulate(a, scores, manifests, k);
        accumulate(b, scores, manifests, k);

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit)
            .map(e -> new DirectoryStore.SearchHit(
                manifests.get(e.getKey()),
                (int) Math.max(1, Math.round(e.getValue() * 1000))))
            .toList();
    }

    private static void accumulate(List<DirectoryStore.SearchHit> hits,
                                    Map<String, Double> scores,
                                    Map<String, ZoneManifestV1> manifests,
                                    int k) {
        if (hits == null) return;
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            var did = h.manifest().did();
            scores.merge(did, 1.0 / (k + i + 1), Double::sum);
            manifests.put(did, h.manifest());
        }
    }
}
