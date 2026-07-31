package org.wyrdsekai.rendezvous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * In-memory semantic index — embeddings + cosine similarity.
 *
 * <p>Kept behind the same {@link DirectoryStore.SearchHit} response
 * shape that the substring search returns, so flipping between the two
 * is transparent to clients. Flat array + cosine is sub-millisecond at
 * 50k manifests; past that we'd upgrade to sqlite-vec or hnswlib.</p>
 *
 * <h2>Degrade-gracefully</h2>
 *
 * <p>If the {@link EmbeddingClient} fails to embed a manifest at
 * publish time (service down, malformed response), the manifest is
 * still stored in the directory but NOT indexed for semantic search.
 * {@code search(query)} falls back to substring results when the
 * query itself can't be embedded. Operators can run without an
 * embedding service and still get useful results.</p>
 */
public final class SemanticIndex {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndex.class);

    private final Function<String, Optional<float[]>> embedFn;
    private final DirectoryStore store;
    private final ConcurrentMap<String, float[]> vectorByDid = new ConcurrentHashMap<>();

    public SemanticIndex(EmbeddingClient client, DirectoryStore store) {
        this.embedFn = client == null ? null : client::embed;
        this.store = store;
    }

    /** Test-friendly constructor — supply a deterministic embedding function. */
    public SemanticIndex(Function<String, Optional<float[]>> embedFn, DirectoryStore store) {
        this.embedFn = embedFn;
        this.store = store;
    }

    /**
     * Embed and cache the manifest's search text. Non-fatal failure —
     * skipping here just means this manifest won't be semantically
     * matched until the next publish or until a manual rebuild.
     */
    public void indexManifest(ZoneManifestV1 m) {
        if (embedFn == null) return;
        var text = searchText(m);
        var vec = embedFn.apply(text);
        vec.ifPresent(v -> vectorByDid.put(m.did(), v));
        if (vec.isEmpty()) {
            log.debug("no embedding for {} (embedding service unreachable or query empty)",
                m.did());
        }
    }

    /** Drop the cached vector when a manifest is tombstoned. */
    public void removeManifest(String did) {
        vectorByDid.remove(did);
    }

    /**
     * Semantic search with substring fallback. If the embedding
     * service is reachable, ranks by cosine similarity; otherwise
     * defers to {@link DirectoryStore#searchText}.
     */
    public List<DirectoryStore.SearchHit> search(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        if (embedFn == null || vectorByDid.isEmpty()) {
            return store.searchText(query, limit);
        }
        var qvec = embedFn.apply(query);
        if (qvec.isEmpty()) {
            log.debug("query embedding unavailable — falling back to substring search");
            return store.searchText(query, limit);
        }
        var q = qvec.get();
        var scored = new ArrayList<Scored>();
        for (var entry : vectorByDid.entrySet()) {
            var m = store.lookup(entry.getKey()).orElse(null);
            if (m == null) continue;  // tombstoned but index stale
            var sim = cosineSim(q, entry.getValue());
            if (sim > 0.0f) scored.add(new Scored(m, sim));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.sim).reversed());
        var out = new ArrayList<DirectoryStore.SearchHit>();
        for (var s : scored) {
            // Score is rendered as int (0-100) to keep wire shape uniform
            // with the substring path.
            int intScore = Math.max(1, Math.round(s.sim * 100f));
            out.add(new DirectoryStore.SearchHit(s.manifest, intScore));
            if (out.size() >= limit) break;
        }
        return out;
    }

    public int indexSize() {
        return vectorByDid.size();
    }

    /** Build the search text we embed for a manifest — same fields the
     *  substring path weights. Operators should write descriptions for
     *  both human and agent consumption (spec §5.3 accessibility
     *  invariant); that's what we feed the embedder. */
    private static String searchText(ZoneManifestV1 m) {
        var sb = new StringBuilder();
        sb.append(nullSafe(m.displayName())).append('\n');
        sb.append(nullSafe(m.zoneLabel())).append('\n');
        sb.append(nullSafe(m.tagline())).append('\n');
        sb.append(nullSafe(m.description())).append('\n');
        if (m.tags() != null) sb.append(String.join(" ", m.tags())).append('\n');
        if (m.capabilities() != null) {
            var c = m.capabilities();
            if (c.rooms() != null) {
                for (var r : c.rooms()) {
                    sb.append(nullSafe(r.label())).append(' ')
                      .append(nullSafe(r.description())).append('\n');
                }
            }
            if (c.agents() != null) {
                for (var a : c.agents()) {
                    sb.append(nullSafe(a.label())).append(' ')
                      .append(nullSafe(a.description())).append('\n');
                    if (a.skills() != null) {
                        sb.append(String.join(" ", a.skills())).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static float cosineSim(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0f || nb == 0f) return 0f;
        return dot / ((float) Math.sqrt(na) * (float) Math.sqrt(nb));
    }

    private record Scored(ZoneManifestV1 manifest, float sim) {}
}
