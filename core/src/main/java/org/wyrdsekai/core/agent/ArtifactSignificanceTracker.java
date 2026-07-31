package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1C: per-artifact significance tracking.
 *
 * <p>Replaces the Phase 1B {@code unreadArtifactCount} bulk counter with per-artifact records.
 * Per spec: "+0.015 per produced artifact going &gt;24h with no read/use/cite/build-on/ack."
 * The bulk counter couldn't represent the 24h-aging rule; this tracker can.</p>
 *
 * <p>Each artifact carries:
 * <ul>
 *   <li>{@code artifactId} — caller-supplied or auto-generated identifier</li>
 *   <li>{@code createdAt} — when the companion produced it</li>
 *   <li>{@code seen} — true once read/used/cited/built-on/acknowledged</li>
 *   <li>{@code seenAt} — timestamp of first ack (null until seen)</li>
 *   <li>{@code kind} — taxonomic label ("journal_entry", "craft_script_draft", "note", ...)</li>
 * </ul>
 *
 * <p>The {@link #unseenAfterThreshold(Instant)} count is what feeds
 * {@link AccumulationContext#unreadArtifactCount()} per tick.</p>
 *
 * <p>Hot-path is in-memory; CompanionActor wires
 * {@link org.wyrdsekai.core.persistence.ArtifactSignificancePersistence} as a write-through
 * mirror.</p>
 */
public final class ArtifactSignificanceTracker {

    /** Spec §3.5: artifacts only count toward significance after &gt;24h unseen. */
    public static final Duration AGE_THRESHOLD = Duration.ofHours(24);

    /** A single tracked artifact. */
    public static final class Artifact {
        private final String artifactId;
        private final Instant createdAt;
        private final String kind;
        private boolean seen;
        private Instant seenAt;
        /** Phase 1D: optional content embedding (384-d for MiniLM-L6-v2). Null when unavailable. */
        private float[] embedding;

        public Artifact(String artifactId, Instant createdAt, String kind,
                        boolean seen, Instant seenAt) {
            this(artifactId, createdAt, kind, seen, seenAt, null);
        }

        public Artifact(String artifactId, Instant createdAt, String kind,
                        boolean seen, Instant seenAt, float[] embedding) {
            this.artifactId = artifactId;
            this.createdAt = createdAt;
            this.kind = kind == null ? "artifact" : kind;
            this.seen = seen;
            this.seenAt = seenAt;
            this.embedding = embedding;
        }

        public String artifactId() { return artifactId; }
        public Instant createdAt() { return createdAt; }
        public String kind() { return kind; }
        public boolean seen() { return seen; }
        public Instant seenAt() { return seenAt; }
        /** Phase 1D: content embedding for semantic ack-matching. May be null. */
        public float[] embedding() { return embedding; }

        void markSeen(Instant at) {
            this.seen = true;
            this.seenAt = at == null ? Instant.now() : at;
        }

        /** Phase 1D: stash the content embedding (called once at production time). */
        void setEmbedding(float[] e) { this.embedding = e; }
    }

    /** artifactId → Artifact. LinkedHashMap for stable iteration / "most recent" semantics. */
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();

    /** Record a newly-produced artifact. Returns the assigned id. */
    public String recordProduced(String artifactId, String kind, Instant at) {
        return recordProduced(artifactId, kind, at, null);
    }

    /**
     * Phase 1D: record a newly-produced artifact along with an optional content
     * embedding for later semantic ack-matching. {@code embedding} may be null
     * when the embedding service is unavailable — the keyword path still works.
     */
    public String recordProduced(String artifactId, String kind, Instant at, float[] embedding) {
        Instant t = at == null ? Instant.now() : at;
        String id = (artifactId == null || artifactId.isBlank())
            ? ("art-" + t.toEpochMilli() + "-" + artifacts.size())
            : artifactId;
        artifacts.put(id, new Artifact(id, t, kind, false, null, embedding));
        return id;
    }

    /** Phase 1D: attach a freshly-computed embedding to an existing artifact. */
    public boolean setEmbedding(String artifactId, float[] embedding) {
        if (artifactId == null) return false;
        var a = artifacts.get(artifactId);
        if (a == null) return false;
        a.setEmbedding(embedding);
        return true;
    }

    /** Mark an artifact seen (read/used/cited/built-on/ack). No-op if unknown id. */
    public boolean markSeen(String artifactId, Instant at) {
        if (artifactId == null) return false;
        var a = artifacts.get(artifactId);
        if (a == null) return false;
        if (a.seen()) return false;
        a.markSeen(at);
        return true;
    }

    /**
     * Phase 1C ack-matching (best-effort, time-window heuristic): mark the most recently
     * produced unseen artifact as seen if it was created within {@code window} of {@code at}.
     * Per spec: "if the bondholder tells the companion 'thanks for that note about X' within
     * 1h of the companion writing about X, mark the most recent artifact seen=true."
     *
     * <p>TODO Phase 1D: semantic ack matching — for now we use pure time-window proximity.</p>
     *
     * @return id of the artifact marked seen, or null if no candidate fit the window
     */
    public String markMostRecentSeenWithinWindow(Instant at, Duration window) {
        if (at == null) return null;
        Duration w = (window == null) ? Duration.ofHours(1) : window;
        // Iterate from most-recent backward.
        var ids = new ArrayList<>(artifacts.keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            var a = artifacts.get(ids.get(i));
            if (a == null || a.seen()) continue;
            var age = Duration.between(a.createdAt(), at);
            if (age.isNegative()) continue;
            if (age.compareTo(w) <= 0) {
                a.markSeen(at);
                return a.artifactId();
            }
            // Once we pass an artifact older than the window, stop.
            break;
        }
        return null;
    }

    /**
     * Phase 1D semantic ack-matching: find the unseen artifact within {@code window}
     * of {@code at} whose embedding is most similar to {@code ackEmbedding} above
     * {@code threshold}, and mark it seen.
     *
     * <p>Additive to the keyword/time-window path — the bondholder may say
     * "loved that mythology summary" with no time-window match, and we still
     * pin the right artifact via cosine similarity. Returns the id of the
     * artifact marked seen, or null if no candidate clears the threshold.</p>
     *
     * <p>Pure function over the in-memory state — does not call out to any
     * service. The caller supplies the ack embedding (already computed) and
     * the threshold (typically 0.55 for MiniLM-L6-v2).</p>
     *
     * @param ackEmbedding embedding of the bondholder's tell text; null/empty → no-op
     * @param at           ack timestamp (typically now)
     * @param window       only consider artifacts produced within this window of {@code at}
     * @param threshold    minimum cosine similarity to mark seen (e.g. 0.55)
     * @return id of the artifact marked seen, or null if no match
     */
    public String markBestSemanticMatchSeen(float[] ackEmbedding, Instant at,
                                              Duration window, float threshold) {
        if (ackEmbedding == null || ackEmbedding.length == 0) return null;
        if (at == null) return null;
        Duration w = (window == null) ? Duration.ofHours(24) : window;

        String bestId = null;
        float bestSim = threshold;  // anything below this is rejected
        for (var a : artifacts.values()) {
            if (a.seen()) continue;
            var emb = a.embedding();
            if (emb == null || emb.length != ackEmbedding.length) continue;
            var age = Duration.between(a.createdAt(), at);
            if (age.isNegative()) continue;
            if (age.compareTo(w) > 0) continue;
            float sim = cosine(ackEmbedding, emb);
            if (sim > bestSim) {
                bestSim = sim;
                bestId = a.artifactId();
            }
        }
        if (bestId != null) {
            var winner = artifacts.get(bestId);
            if (winner != null) winner.markSeen(at);
        }
        return bestId;
    }

    /** Cosine similarity for L2-normalized vectors == dot product, clamped to [0,1]. */
    private static float cosine(float[] a, float[] b) {
        float dot = 0f;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        if (dot < 0f) return 0f;
        if (dot > 1f) return 1f;
        return dot;
    }

    /**
     * Count of artifacts that are (a) older than {@link #AGE_THRESHOLD} and (b) unseen.
     * This is what feeds the per-tick significance accumulation.
     */
    public int unseenAfterThreshold(Instant now) {
        Instant t = now == null ? Instant.now() : now;
        int n = 0;
        for (var a : artifacts.values()) {
            if (a.seen()) continue;
            if (Duration.between(a.createdAt(), t).compareTo(AGE_THRESHOLD) > 0) {
                n++;
            }
        }
        return n;
    }

    /** All tracked artifacts (snapshot, for tests / persistence). */
    public List<Artifact> all() {
        return new ArrayList<>(artifacts.values());
    }

    /** Bulk-load from persistence. Replaces in-memory state. */
    public void loadAll(List<Artifact> items) {
        artifacts.clear();
        if (items == null) return;
        for (var a : items) {
            if (a == null || a.artifactId() == null) continue;
            artifacts.put(a.artifactId(), a);
        }
    }

    public int totalCount() { return artifacts.size(); }

    public boolean isEmpty() { return artifacts.isEmpty(); }

    /** Direct lookup (for tests). */
    public Artifact get(String artifactId) {
        return artifacts.get(artifactId);
    }
}
