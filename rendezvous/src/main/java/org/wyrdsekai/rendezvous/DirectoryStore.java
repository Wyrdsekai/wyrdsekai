package org.wyrdsekai.rendezvous;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.ZoneDirectory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory directory store for the rendezvous process.
 *
 * <p>Thread-safe, no persistence in V1 — zones re-publish every hour
 * with a 48h TTL, so a rendezvous restart recovers naturally within
 * an hour. SQLite persistence is a later follow-up.</p>
 *
 * <p>Maintains four indexes:</p>
 *
 * <ul>
 *   <li>DID → manifest (primary)</li>
 *   <li>tag → set of DIDs</li>
 *   <li>capability → set of DIDs (room labels, agent labels,
 *       agent skills from {@link ZoneManifestV1.PublicAgent#skills()})</li>
 *   <li>DID → last-seen timestamp (for TTL eviction + LRU cap)</li>
 * </ul>
 */
public final class DirectoryStore implements ZoneDirectory {

    private static final Logger log = LoggerFactory.getLogger(DirectoryStore.class);

    /** Listener fired after each successful publish/unpublish. Used by
     *  {@link SubscriptionHub} to fan out SSE events. */
    public interface ChangeListener {
        void onPublished(ZoneManifestV1 manifest, boolean isNew);
        void onRemoved(ZoneManifestV1 manifest);
    }

    private final int maxManifests;
    private final long ttlSec;

    private final ConcurrentMap<String, ZoneManifestV1> byDid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> byTag = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> byCapability = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastSeenAt = new ConcurrentHashMap<>();
    private volatile ChangeListener listener;
    private volatile KeywordIndex keywordIndex;

    public DirectoryStore(int maxManifests, long ttlSec) {
        this.maxManifests = maxManifests;
        this.ttlSec = ttlSec;
    }

    /** Attach a Lucene-backed keyword index. When set, {@link #searchText}
     *  prefers Lucene (BM25) over the substring fallback. */
    public void setKeywordIndex(KeywordIndex idx) {
        this.keywordIndex = idx;
    }

    /** Register a listener to observe publish/unpublish events. */
    public void setChangeListener(ChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public void publish(ZoneManifestV1 manifest) {
        manifest.validate();

        // LRU-style eviction when at cap. Drop the oldest-seen entry
        // that isn't the one we're about to insert/update.
        if (!byDid.containsKey(manifest.did()) && byDid.size() >= maxManifests) {
            evictOldest();
        }

        var prior = byDid.put(manifest.did(), manifest);

        // Remove old tag + capability indexes for the prior version.
        if (prior != null) {
            if (prior.tags() != null) {
                for (var t : prior.tags()) removeFromIndex(byTag, t, prior.did());
            }
            for (var c : capabilitiesOf(prior)) removeFromIndex(byCapability, c, prior.did());
        }

        if (manifest.tags() != null) {
            for (var t : manifest.tags()) addToIndex(byTag, t, manifest.did());
        }
        for (var c : capabilitiesOf(manifest)) addToIndex(byCapability, c, manifest.did());

        lastSeenAt.put(manifest.did(), System.currentTimeMillis());

        var l = listener;
        if (l != null) {
            try { l.onPublished(manifest, prior == null); }
            catch (Exception e) { log.debug("change listener threw: {}", e.getMessage()); }
        }
    }

    @Override
    public void unpublish(String did) {
        var prior = byDid.remove(did);
        if (prior == null) return;
        if (prior.tags() != null) {
            for (var t : prior.tags()) removeFromIndex(byTag, t, did);
        }
        for (var c : capabilitiesOf(prior)) removeFromIndex(byCapability, c, did);
        lastSeenAt.remove(did);

        var l = listener;
        if (l != null) {
            try { l.onRemoved(prior); }
            catch (Exception e) { log.debug("change listener threw: {}", e.getMessage()); }
        }
    }

    @Override
    public Optional<ZoneManifestV1> lookup(String did) {
        return Optional.ofNullable(byDid.get(did));
    }

    @Override
    public List<String> discoverByTag(String tag) {
        var set = byTag.get(tag == null ? "" : tag.toLowerCase(Locale.ROOT));
        if (set == null) return List.of();
        return List.copyOf(set);
    }

    @Override
    public List<String> discoverByCapability(String capability) {
        if (capability == null || capability.isBlank()) return List.of();
        var set = byCapability.get(capability.toLowerCase(Locale.ROOT));
        if (set == null) return List.of();
        return List.copyOf(set);
    }

    @Override
    public List<ZoneManifestV1> recent(int limit) {
        var all = new ArrayList<>(byDid.values());
        all.sort(Comparator.<ZoneManifestV1, String>comparing(
            m -> m.refreshedAt() == null ? "" : m.refreshedAt(),
            Comparator.reverseOrder()));
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /**
     * Keyword search over manifest content. Prefers Lucene (BM25 with
     * per-field boosts and tokenization) when a {@link KeywordIndex}
     * is attached; otherwise falls back to the substring scorer.
     *
     * <p>Returned as {@link SearchHit} records. Score is Lucene's BM25
     * when indexed, or an integer match-weight when using fallback —
     * clients sort by score desc in either case, so the two are
     * rank-compatible even if absolute values differ.</p>
     */
    public List<SearchHit> searchText(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        var idx = keywordIndex;
        if (idx != null) {
            var hits = idx.search(query, limit);
            var out = new ArrayList<SearchHit>(hits.size());
            for (var h : hits) {
                var m = byDid.get(h.did());
                if (m != null) {
                    // BM25 scores are floats in [0, ~30]; renormalize
                    // to int so the response shape matches the substring
                    // path (which uses int match weights).
                    out.add(new SearchHit(m, Math.max(1, Math.round(h.score() * 10f))));
                }
            }
            return out;
        }
        // Fallback: substring scorer. Used when the Lucene index isn't
        // wired (e.g., in narrow unit tests) or after an index failure.
        var needle = query.toLowerCase(Locale.ROOT).trim();
        var hits = new ArrayList<SearchHit>();
        for (var m : byDid.values()) {
            int score = textMatchScore(m, needle);
            if (score > 0) hits.add(new SearchHit(m, score));
        }
        hits.sort(Comparator.comparingInt((SearchHit h) -> h.score).reversed());
        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    /** TTL + LRU eviction sweep. Safe to call concurrently with inserts. */
    public void evictExpired() {
        long cutoff = System.currentTimeMillis() - ttlSec * 1000L;
        int dropped = 0;
        for (var entry : new ArrayList<>(lastSeenAt.entrySet())) {
            if (entry.getValue() < cutoff) {
                unpublish(entry.getKey());
                dropped++;
            }
        }
        if (dropped > 0) {
            log.info("TTL sweep: dropped {} expired manifests (cap={}, now={})",
                dropped, maxManifests, byDid.size());
        }
    }

    /** Test/diagnostic — current size. */
    public int size() {
        return byDid.size();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void evictOldest() {
        String oldestDid = null;
        long oldest = Long.MAX_VALUE;
        for (var entry : lastSeenAt.entrySet()) {
            if (entry.getValue() < oldest) {
                oldest = entry.getValue();
                oldestDid = entry.getKey();
            }
        }
        if (oldestDid != null) {
            log.debug("LRU eviction: dropping {} (oldest)", oldestDid);
            unpublish(oldestDid);
        }
    }

    /** Flatten a manifest's capabilities into indexable strings. */
    private static Set<String> capabilitiesOf(ZoneManifestV1 m) {
        var out = new LinkedHashSet<String>();
        if (m.capabilities() == null) return out;
        var caps = m.capabilities();
        if (caps.rooms() != null) {
            for (var r : caps.rooms()) {
                if (r.label() != null) out.add(r.label().toLowerCase(Locale.ROOT));
            }
        }
        if (caps.agents() != null) {
            for (var a : caps.agents()) {
                if (a.label() != null) out.add(a.label().toLowerCase(Locale.ROOT));
                if (a.role() != null) out.add(a.role().toLowerCase(Locale.ROOT));
                if (a.skills() != null) {
                    for (var s : a.skills()) {
                        if (s != null) out.add(s.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return out;
    }

    private static void addToIndex(ConcurrentMap<String, Set<String>> idx,
                                    String key, String did) {
        idx.computeIfAbsent(key.toLowerCase(Locale.ROOT),
            k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(did);
    }

    private static void removeFromIndex(ConcurrentMap<String, Set<String>> idx,
                                         String key, String did) {
        var set = idx.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
        if (set != null) set.remove(did);
    }

    private static int textMatchScore(ZoneManifestV1 m, String needleLower) {
        int score = 0;
        score += occursIn(m.tagline(), needleLower) * 3;
        score += occursIn(m.description(), needleLower) * 2;
        score += occursIn(m.displayName(), needleLower) * 4;
        score += occursIn(m.zoneLabel(), needleLower) * 5;
        if (m.tags() != null) {
            for (var t : m.tags()) score += occursIn(t, needleLower) * 4;
        }
        if (m.capabilities() != null) {
            var c = m.capabilities();
            if (c.rooms() != null) {
                for (var r : c.rooms()) {
                    score += occursIn(r.label(), needleLower) * 2;
                    score += occursIn(r.description(), needleLower);
                }
            }
            if (c.agents() != null) {
                for (var a : c.agents()) {
                    score += occursIn(a.label(), needleLower) * 2;
                    if (a.skills() != null) {
                        for (var s : a.skills()) score += occursIn(s, needleLower) * 3;
                    }
                }
            }
        }
        return score;
    }

    private static int occursIn(String haystack, String needleLower) {
        if (haystack == null) return 0;
        return haystack.toLowerCase(Locale.ROOT).contains(needleLower) ? 1 : 0;
    }

    /** Rendered in search results as {@code {manifest, score}}. */
    public record SearchHit(
        @JsonProperty("manifest") ZoneManifestV1 manifest,
        @JsonProperty("score") int score) {}
}
