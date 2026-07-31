package org.wyrdsekai.core.library;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-zone log of every retrieval against the household library
 * ( trigger #2 + #3).
 *
 * <p>Records: query text, timestamp, hit count, top-score, whether the result
 * fell back to web search, the asking agent's DID. The substrate for gap
 * detection: a query that fails N times in M days, or a topic that keeps
 * landing in {@code searching_glass} instead of the local pack, is a signal
 * to acquire a focused pack on that topic.</p>
 *
 * <p>Bounded ring buffer — keeps the last {@code maxEntries} queries in memory.
 * Periodic flush to disk; full-rewrite model. Gap analysis runs over the
 * in-memory window; deeper history (if needed) re-reads from disk.</p>
 */
public final class ReadingLog {

    private static final Logger log = LoggerFactory.getLogger(ReadingLog.class);
    private static final String FILENAME = "reading-log.json";
    private static final int DEFAULT_MAX_ENTRIES = 5000;

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        var m = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
        m.findAndRegisterModules();
        return m;
    }

    /**
     * One reading-log entry.
     *
     * @param query        the query text
     * @param at           timestamp
     * @param askerDid     the agent that asked, nullable
     * @param hitCount     number of relevant chunks returned (after filtering)
     * @param topScore     the top hit's relevance score, or 0 if no hits
     * @param fallbackKind null if the query was answered locally; otherwise
     *                     {@code "web"} when searching_glass took over, or
     *                     {@code "none"} when nothing answered.
     * @param topPack      the pack that contributed the top hit, nullable
     */
    public record Entry(
        String query,
        Instant at,
        String askerDid,
        int hitCount,
        double topScore,
        String fallbackKind,
        String topPack
    ) {}

    private final Path file;
    private final int maxEntries;
    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();

    public ReadingLog(Path root) {
        this(root, DEFAULT_MAX_ENTRIES);
    }

    public ReadingLog(Path root, int maxEntries) {
        this.file = root.resolve(FILENAME);
        this.maxEntries = maxEntries;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            var bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return;
            List<Entry> loaded = MAPPER.readValue(bytes, new TypeReference<List<Entry>>() {});
            entries.addAll(loaded);
            log.info("Loaded {} reading-log entries", entries.size());
        } catch (IOException e) {
            log.warn("Failed to load reading log: {}", e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(new ArrayList<>(entries)));
        } catch (IOException e) {
            log.warn("Failed to save reading log: {}", e.getMessage());
        }
    }

    /** Record a successful local answer. */
    public void recordLocal(String query, String askerDid, int hitCount,
                            double topScore, String topPack) {
        record0(new Entry(query, Instant.now(), askerDid, hitCount, topScore, null, topPack));
    }

    /** Record a fallback to web search. */
    public void recordWebFallback(String query, String askerDid, int localHitCount) {
        record0(new Entry(query, Instant.now(), askerDid, localHitCount, 0.0, "web", null));
    }

    /** Record a query that found nothing anywhere. */
    public void recordMiss(String query, String askerDid) {
        record0(new Entry(query, Instant.now(), askerDid, 0, 0.0, "none", null));
    }

    private void record0(Entry e) {
        entries.addLast(e);
        while (entries.size() > maxEntries) entries.pollFirst();
        save();
    }

    /** Recent entries, newest-first. */
    public List<Entry> recent(int limit) {
        var out = new ArrayList<Entry>(Math.min(limit, entries.size()));
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) out.add(it.next());
        return Collections.unmodifiableList(out);
    }

    /**
     * Group recent queries by lowercase prefix term, returning
     * {@code term → count}. Cheap heuristic for "topics we keep asking about".
     * The acquire path can rank these to propose focused packs.
     */
    public Map<String, Integer> topRepeatedTerms(int windowSize, int minCount) {
        var counts = new HashMap<String, Integer>();
        var seen = 0;
        var it = entries.descendingIterator();
        while (it.hasNext() && seen < windowSize) {
            var e = it.next();
            seen++;
            var term = firstSignificantTerm(e.query());
            if (term == null) continue;
            counts.merge(term, 1, Integer::sum);
        }
        var out = new HashMap<String, Integer>();
        counts.forEach((k, v) -> { if (v >= minCount) out.put(k, v); });
        return out;
    }

    /** Crude first-significant-token extractor — strip stopwords + short words. */
    private static String firstSignificantTerm(String query) {
        if (query == null) return null;
        var lower = query.toLowerCase();
        for (var w : lower.split("[^a-z]+")) {
            if (w.length() < 4) continue;
            if (STOPWORDS.contains(w)) continue;
            return w;
        }
        return null;
    }

    private static final Set<String> STOPWORDS = Set.of(
        "what", "where", "when", "which", "this", "that", "these", "those",
        "have", "with", "from", "your", "tell", "find", "about", "should",
        "does", "doing", "done", "would", "could", "want", "need", "give",
        "take", "make", "made", "look", "show", "explain", "describe",
        "why", "how", "the", "and", "but", "for", "are", "you");

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        save();
    }
}
