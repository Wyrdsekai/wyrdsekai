package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.WebSearchService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns an APPROVED {@link ProposedPack} into knowledge chunks indexed in
 * {@link WyrdLuceneStore} ( ingest pipeline).
 *
 * <p>For each {@link Provenance.Source} on the proposal:</p>
 * <ol>
 *   <li>Fetch raw text via {@link WebSearchService#fetchContent}.</li>
 *   <li>Chunk on paragraph boundaries (~700 chars per chunk, soft).</li>
 *   <li>Insert into Lucene with {@code pack=<topic-slug>}, original source URL,
 *       and {@code subject=<trust-tier>:<source-kind>} so the search path can
 *       surface trust info.</li>
 * </ol>
 *
 * <p>Provenance metadata (full record) lives on the {@link ProposedPack} in
 * the arrival-table — durable audit. Per-chunk provenance fields in the
 * Lucene schema are out of scope for v1; the chunk's {@code source} +
 * {@code subject} fields carry the essentials.</p>
 *
 * <p>Synchronous: callers should run this on a worker pool when ingesting
 * proposals during the sleep cycle. Returns the count of chunks indexed; on
 * failure, returns the partial count and logs the source that broke.</p>
 */
public final class PackIngester {

    private static final Logger log = LoggerFactory.getLogger(PackIngester.class);

    private static final int TARGET_CHUNK_CHARS = 700;
    private static final int MAX_FETCH_CHARS = 32_000;

    private final WyrdLuceneStore store;

    public PackIngester(WyrdLuceneStore store) {
        if (store == null) throw new IllegalArgumentException("WyrdLuceneStore required");
        this.store = store;
    }

    /**
     * Ingest a proposal. Side-effects: writes chunks to Lucene, marks the
     * proposal {@link ProposedPack.Status#INGESTED} on the arrival table
     * (when present), commits the index. Returns the result.
     */
    public Result ingest(ProposedPack proposal) {
        if (proposal == null) {
            return new Result(0, 0, "null proposal");
        }
        if (proposal.status() != ProposedPack.Status.APPROVED) {
            return new Result(0, 0, "proposal not APPROVED: status=" + proposal.status());
        }
        var pack = packSlug(proposal.topic());
        var sources = proposal.sources();
        if (sources == null || sources.isEmpty()) {
            log.warn("PackIngester: proposal '{}' has no sources, skipping", proposal.topic());
            return new Result(0, 0, "no sources");
        }

        var web = WebSearchService.get();
        if (web == null) {
            log.warn("PackIngester: WebSearchService unavailable, can't fetch");
            return new Result(0, 0, "web service unavailable");
        }

        int totalChunks = 0;
        int sourceFailures = 0;
        for (int sIdx = 0; sIdx < sources.size(); sIdx++) {
            var src = sources.get(sIdx);
            if (src == null || src.url() == null || src.url().isBlank()) {
                sourceFailures++;
                continue;
            }
            try {
                var content = web.fetchContent(src.url(), MAX_FETCH_CHARS);
                if (content == null || content.isBlank()) {
                    log.warn("PackIngester: empty fetch for {}", src.url());
                    sourceFailures++;
                    continue;
                }
                var chunks = chunkText(content);
                for (int i = 0; i < chunks.size(); i++) {
                    var id = pack + ":" + sIdx + ":" + i;
                    var title = src.title() != null && !src.title().isBlank()
                        ? src.title() + (chunks.size() > 1 ? " (part " + (i + 1) + ")" : "")
                        : pack + " §" + (i + 1);
                    var subject = (proposal.trustTier() != null
                            ? proposal.trustTier().name().toLowerCase() : "unknown")
                        + (src.kind() != null ? "|" + src.kind() : "");
                    store.insertKnowledge(id, pack, title, chunks.get(i),
                        src.url(), subject, null);
                    totalChunks++;
                }
            } catch (Exception e) {
                log.warn("PackIngester: source {} failed: {}", src.url(), e.getMessage());
                sourceFailures++;
            }
        }

        try {
            store.commitAll();
        } catch (Exception e) {
            log.warn("PackIngester: commit failed: {}", e.getMessage());
        }

        // Mark INGESTED on the arrival table when one is wired up.
        var table = LibraryServices.arrivalTable();
        if (table != null) {
            table.markIngested(proposal.id());
        }

        log.info("PackIngester: '{}' ingested {} chunks from {}/{} sources " +
                "(failures: {}) at {}",
            proposal.topic(), totalChunks,
            sources.size() - sourceFailures, sources.size(),
            sourceFailures, Instant.now());
        return new Result(totalChunks, sourceFailures, null);
    }

    /** Convert a free-form topic into a stable pack slug. */
    static String packSlug(String topic) {
        if (topic == null || topic.isBlank()) return "pack";
        return topic.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }

    /**
     * Paragraph-aware chunking. Splits on blank lines, then merges short
     * paragraphs to approach {@link #TARGET_CHUNK_CHARS}. Soft max — long
     * single paragraphs stay intact rather than break mid-thought.
     */
    static List<String> chunkText(String text) {
        var out = new ArrayList<String>();
        if (text == null || text.isBlank()) return out;
        var paragraphs = text.split("\\n\\s*\\n");
        var current = new StringBuilder();
        for (var p : paragraphs) {
            var trimmed = p.strip();
            if (trimmed.isEmpty()) continue;
            if (current.length() == 0) {
                current.append(trimmed);
            } else if (current.length() + trimmed.length() + 2 <= TARGET_CHUNK_CHARS) {
                current.append("\n\n").append(trimmed);
            } else {
                out.add(current.toString());
                current = new StringBuilder(trimmed);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    /** Outcome of an ingest run. */
    public record Result(int chunksIndexed, int sourceFailures, String error) {
        public boolean ok() { return error == null; }
    }

    // Compile-time pull to keep WebSearchService import non-redundant.
    @SuppressWarnings("unused")
    private static final Map<String, ?> PIN_IMPORTS = null;
}
