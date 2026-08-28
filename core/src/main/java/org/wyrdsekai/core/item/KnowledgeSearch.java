package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.search.RelevanceFloor;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The household's knowledge search: one implementation, two legs.
 *
 * <h2>Why it lives here and not on a provider</h2>
 * This routine used to be the body of
 * {@code ItemWorldApiProviderImpl.searchKnowledge}, which meant only a
 * companion's provider could run it. A person holding an item reached it by
 * forwarding to a SHARED provider instance — and that instance answered the
 * one identity-dependent step (the Study leg) as the placeholder
 * {@code "household"}, so the person's own books were silently unreachable
 * from their own hands.
 *
 * <p>The merge, the dedup, the rerank and the floor are caller-agnostic and
 * belong in one place; this repo has watched the same feature diverge across
 * five surfaces before. The identity-dependent step is passed in as a
 * {@link StudyReach} by whoever knows who is asking.</p>
 */
public final class KnowledgeSearch {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearch.class);

    private KnowledgeSearch() {}

    /**
     * Search packs and — as far as {@code reach} allows — the household's
     * private shelves, merged and reranked as one result set.
     *
     * @param store     the household index; {@code null} yields no results
     * @param reach     this caller's private reach; {@link StudyReach#NONE} for
     *                  a caller-agnostic surface
     * @param callerDid identity for the reading log only (nullable)
     */
    public static List<Map<String, Object>> search(WyrdLuceneStore store, String query,
                                                   int limit, StudyReach reach,
                                                   String callerDid) {
        if (store == null || query == null || query.isBlank()) {
            log.warn("searchKnowledge skipped: luceneStore={}, query='{}'",
                store != null ? "present" : "NULL", query);
            return List.of();
        }
        try {
            var results = new ArrayList<>(store.searchKnowledge(query, null, Math.min(limit, 20)));
            int packHits = results.size();

            // THE BOOKS. searchKnowledge reads SearchCollections.KNOWLEDGE —
            // Wikipedia, WikiHow, installed packs. The household's own library
            // lives in SearchCollections.STUDY and was unreachable from every
            // item script, so a furnishing named "library shelves" could not
            // find a book by construction (2026-08-07). Consent is NOT
            // bypassed: the reach is whatever THIS caller's identity earns.
            var studyHits = reach == null ? List.<WyrdLuceneStore.SearchResult>of()
                : reach.search(query, Math.min(limit, 20));
            results.addAll(studyHits);

            // A SHARED SHELF IS THE SAME BOOK TWICE. share-collection projects a
            // Study collection into a zone-wide pack, so the owner's merged
            // search finds each passage in both legs under different ids. Dedup
            // by title + normalized content head — generic, so ANY duplicated
            // content across legs collapses, not just study-shares.
            var seenContent = new HashSet<String>();
            results.removeIf(r -> {
                var meta = r.metadata();
                var title = meta != null ? String.valueOf(meta.getOrDefault("title", "")) : "";
                var head = r.content() == null ? "" : r.content()
                    .replaceAll("\\s+", " ").trim();
                head = head.substring(0, Math.min(head.length(), 160));
                return !seenContent.add(title + "|" + head);
            });

            // RERANK THE MERGED SET, don't just concatenate it. BM25 scores from
            // two collections are not comparable — each is scored against its own
            // corpus statistics — so appending Study after packs put whichever was
            // searched first on top regardless of quality (live 2026-08-07: eight
            // real Glass Tide passages ranked below a gardening post that matched
            // "snow"). Cosine against the query is one common scale, so it orders
            // and floors in one pass.
            int beforeFloor = results.size();
            // Protection markers are a lexical-path protocol; the embedding
            // must see plain words.
            var ranked = RelevanceFloor.rank(WyrdLuceneStore.stripProtectionMarkers(query),
                results, RelevanceFloor.floor(), store::cachedRerankVector);

            // SCENE PRECISION: the chunk where the person's terms CO-OCCUR beats
            // the chunk that merely mentions one of them. A question is usually a
            // conjunction; the passage that answers it is the one where MOST of
            // the person's words appear together. Stable sort by protected-term
            // presence, so cosine still breaks ties and nothing below the floor
            // comes back.
            var personTerms = WyrdLuceneStore.protectedQueryTerms(query);
            if (personTerms.size() >= 2 && ranked.size() > 1) {
                var reordered = new ArrayList<>(ranked);
                reordered.sort(Comparator.comparingInt(
                    (WyrdLuceneStore.SearchResult r) -> {
                        if (r.content() == null) return 0;
                        var c = r.content().toLowerCase(Locale.ROOT);
                        int hits = 0;
                        for (var t : personTerms) if (c.contains(t)) hits++;
                        return hits;
                    }).reversed());
                ranked = reordered;
            }

            log.info("searchKnowledge('{}', limit={}) → {} results ({} pack, {} study, "
                    + "{} kept after floor)",
                query, limit, beforeFloor, packHits, studyHits.size(), ranked.size());
            results = new ArrayList<>(ranked);
            if (results.size() > limit) results.subList(limit, results.size()).clear();

            // gap-detection substrate.
            var rl = LibraryServices.readingLog();
            if (rl != null) {
                if (results.isEmpty()) {
                    rl.recordMiss(query, callerDid);
                } else {
                    var top = results.getFirst();
                    rl.recordLocal(query, callerDid, results.size(), top.score(), top.source());
                }
            }

            var mapped = new ArrayList<Map<String, Object>>(results.size());
            for (var r : results) {
                var m = new HashMap<String, Object>();
                m.put("id", r.id());
                m.put("title", r.metadata() != null
                    ? r.metadata().getOrDefault("title", r.id()) : r.id());
                m.put("text", truncate(r.content(), 500));
                m.put("pack", r.source());
                m.put("score", r.score());
                mapped.add(m);
            }
            return mapped;
        } catch (Exception e) {
            log.error("Knowledge search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
