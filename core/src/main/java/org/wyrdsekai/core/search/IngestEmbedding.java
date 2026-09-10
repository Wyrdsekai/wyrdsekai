package org.wyrdsekai.core.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding at INGEST time — a chunk gets its dense vector when it is written, so the
 * library never accumulates text-only rows again.
 *
 * <p>Only when the embedder is served ({@link EmbeddingService#served()}): the in-process
 * ONNX path embeds bge-m3 at ~2 chunks/s on a CPU and would turn a 75k-volume ingest into
 * months. {@code WYRDSEKAI_EMBED_AT_INGEST=true} forces it on regardless (small shelves,
 * a box with nothing else to do); {@code =false} forces it off. A failed batch logs and
 * falls back to text-only rows for that batch — the ingest never stops for the vector.
 */
public final class IngestEmbedding {

    private static final Logger log = LoggerFactory.getLogger(IngestEmbedding.class);
    public static final String FORCE_ENV = "WYRDSEKAI_EMBED_AT_INGEST";
    public static final int BATCH = 64;

    private IngestEmbedding() {}

    public static boolean enabled() {
        var force = System.getProperty("wyrdsekai.embed.at_ingest", System.getenv(FORCE_ENV));
        if (force != null && !force.isBlank()) return force.trim().equalsIgnoreCase("true") && EmbeddingService.get() != null;
        var svc = EmbeddingService.get();
        return svc != null && svc.served();
    }

    /** Vectors for {@code texts} in order, or null (text-only) when disabled or the batch failed. */
    public static List<List<Float>> batch(List<String> texts) {
        if (!enabled() || texts == null || texts.isEmpty()) return null;
        try {
            var out = EmbeddingService.get().embedBatch(texts);
            return out != null && out.size() == texts.size() ? out : null;
        } catch (RuntimeException e) {
            log.warn("ingest embedding failed for a batch of {} — written text-only: {}", texts.size(), e.getMessage());
            return null;
        }
    }

    public static List<Float> one(String text) {
        var b = batch(text == null ? List.of() : List.of(text));
        return b == null ? null : b.getFirst();
    }

    /** "title\\ncontent" — what the offline embed job embeds too, so vectors agree. */
    public static String textOf(String title, String content) {
        var c = content == null ? "" : content;
        return title == null || title.isBlank() ? c : title + "\n" + c;
    }

    /** Small accumulator for batched ingest: call {@link #add}, then {@link #drain} at 64 or at the end. */
    public static final class Pending<T> {
        private final List<T> items = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        public void add(T item, String text) { items.add(item); texts.add(text); }
        public boolean full() { return items.size() >= BATCH; }
        public boolean isEmpty() { return items.isEmpty(); }
        /** Items with their vectors (null vectors when embedding is off or failed); clears. */
        public List<java.util.Map.Entry<T, List<Float>>> drain() {
            var vecs = batch(texts);
            var out = new ArrayList<java.util.Map.Entry<T, List<Float>>>(items.size());
            for (int i = 0; i < items.size(); i++) {
                out.add(java.util.Map.entry(items.get(i), vecs == null ? new ArrayList<Float>() : vecs.get(i)));
            }
            items.clear(); texts.clear();
            return out;
        }
    }
}
