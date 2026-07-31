package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.affordance.RequestRelevance;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.search.EmbeddingService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vector search index for tool definitions — "which of my tools does this ask call for?"
 *
 * <p>Embeds each tool's description with the in-process {@link EmbeddingService} (the same
 * bundled paraphrase-l12 ONNX model that backs memory recall and Lucene) and ranks by
 * cosine similarity. The index is dynamic: tools register/unregister as the agent equips
 * items, enters rooms, discovers MCP endpoints, or crafts new ones. This is the inventory
 * made searchable.</p>
 *
 * <h2>It was dead for the entire life of the project (fixed 2026-07-13)</h2>
 * <p>This class used to embed over HTTP against <b>Ollama's</b> {@code /api/embed}, defaulting
 * to {@code WYRDSEKAI_INFERENCE_URL} — which on every real install points at <b>llama-server</b>,
 * which has no such endpoint (and Ollama is a {@code profiles: ["ollama"]} opt-in that nobody
 * runs). So the first embed 404'd, {@code embeddingAvailable} went false <em>at debug level</em>,
 * and every search silently degraded to keyword matching. Keyword matching then scored zero for
 * anything phrased in human words, and the "pad up to topK" branch filled the menu from a
 * {@link ConcurrentHashMap}'s {@code values()} — <b>hash order</b>.
 *
 * <p>The result, measured on second-node: asked <em>"what is 17 times 3?"</em>, a companion holding 110
 * tools (calculator among them) was offered {@code summon_familiar, dispatch_bunshin,
 * bunshin_check_in}. She had no way to reach the calculator, so she handed the arithmetic to the
 * coding backend, which reported success having touched zero files. Every "talks but doesn't do"
 * report we have written against the models may be partly this: <b>we gave the agent an arbitrary
 * menu and then blamed her for what she picked off it.</b> A model can only choose from what it
 * is shown.
 *
 * <p>So: embedding is now in-process (no network, no daemon, cannot 404), the keyword fallback
 * scores by {@link RequestRelevance} instead of substring-contains, padding is in <b>registration
 * order</b> (curated and stable) rather than hash order, and losing vector search logs at WARN —
 * once, loudly — instead of debug. Degrading quietly into arbitrary behaviour is the failure mode
 * this codebase keeps paying for.
 *
 * @see InferenceClient.ToolDefinition
 */
public class ToolSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndex.class);

    /** Default top-K results. */
    private static final int DEFAULT_TOP_K = 8;

    /** Tool embeddings: tool ID → vector. */
    private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();

    /**
     * Tool definitions: tool ID → definition.
     *
     * <p>Insertion-ordered ON PURPOSE. {@link #keywordSearch} pads a short result set from this
     * map, and it used to be a {@link ConcurrentHashMap} — so an unmatched query produced a menu
     * in hash order, i.e. arbitrary. Registration order is the scoped, curated order the caller
     * built; if we must pad, pad with that.</p>
     */
    private final Map<String, InferenceClient.ToolDefinition> tools =
        Collections.synchronizedMap(new LinkedHashMap<>());

    /** Tool descriptions: tool ID → embedded/matched text. */
    private final Map<String, String> descriptions =
        Collections.synchronizedMap(new LinkedHashMap<>());

    /** False once the embedder is known to be unusable; the WARN fires once. */
    private volatile boolean embeddingAvailable = true;
    private volatile boolean warnedNoVectors;

    /** One warm-up at a time; embedding happens off the actor thread. */
    private final AtomicBoolean warming = new AtomicBoolean(false);

    public ToolSearchIndex() {}

    /** Register a single tool (metadata only — embedding is warmed separately, off-thread). */
    public void register(InferenceClient.ToolDefinition tool) {
        if (tool == null || tool.function() == null) return;
        var id = tool.function().name();
        var desc = tool.function().description() != null
            ? tool.function().description() : id;

        tools.put(id, tool);
        // Match against the NAME as well as the description. "use your calculator" shares no
        // word with the calculator's prose description, and the name is the one string a tool
        // is guaranteed to have.
        descriptions.put(id, id.replace('_', ' ') + ". " + desc);
    }

    /**
     * Register the agent's current tools and kick an off-thread warm-up. Safe to call on every
     * turn: registration is cheap, and already-embedded tools are skipped. Call it whenever the
     * tool set can change (boot, room transition, equip/doff, a freshly crafted item).
     */
    public void registerAll(List<InferenceClient.ToolDefinition> toolList) {
        for (var tool : toolList) {
            register(tool);
        }
        warmAsync();
    }

    /**
     * Embed the pending descriptions on a VIRTUAL THREAD, never on the caller's.
     *
     * <p>Callers here are Pekko actor threads. Embedding ~110 tool descriptions through the ONNX
     * model — plus a cold {@link EmbeddingService#init()} that loads a 118 MB model — takes seconds,
     * and this class is reached straight from the companion's dispatch path, which is explicitly
     * documented as MUST-NOT-BLOCK (a stalled dispatcher starves the InferenceRouter).</p>
     *
     * <p>This was invisible until the embedder actually worked: the old Ollama call refused in
     * milliseconds and latched {@code embeddingAvailable=false}, so {@code embedAll()} was free.
     * Making it real made it expensive, and the cost landed on the actor thread — three core
     * integration tests started timing out at 5s waiting for a ChatRequest that the companion was
     * too busy embedding to send. <b>Fixing a dead mechanism turns its cost on for the first
     * time.</b></p>
     *
     * <p>Until the warm-up lands, {@link #search} answers from the lexical ranker, which already
     * puts the right tool first for the cases that matter. Degrading to "good" for a few seconds
     * beats stalling the agent.</p>
     */
    private void warmAsync() {
        if (!embeddingAvailable || warming.get()) return;
        if (embeddings.size() == descriptions.size()) return;   // nothing pending
        if (!warming.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("tool-index-warm").start(() -> {
            try {
                embedAll();
            } catch (Exception e) {
                log.warn("ToolSearchIndex warm-up failed: {}", e.toString());
            } finally {
                warming.set(false);
            }
        });
    }

    /**
     * Embed every registered description that isn't embedded yet. Idempotent.
     *
     * <p>Runs on whatever thread calls it — so production callers go through {@link #warmAsync()}.
     * Public because tests want it synchronous.</p>
     */
    public void embedAll() {
        if (!embeddingAvailable || descriptions.isEmpty()) return;
        if (embeddings.size() == descriptions.size()) return; // already embedded

        var pending = new LinkedHashMap<String, String>();
        for (var e : new LinkedHashMap<>(descriptions).entrySet()) {
            if (!embeddings.containsKey(e.getKey())) pending.put(e.getKey(), e.getValue());
        }
        if (pending.isEmpty()) return;

        int ok = 0;
        for (var e : pending.entrySet()) {
            var vec = embed(e.getValue());
            if (vec == null) break;   // embedder is down; embed() has already said so
            embeddings.put(e.getKey(), vec);
            ok++;
        }
        if (ok > 0) {
            log.info("ToolSearchIndex: embedded {} tool descriptions ({}d) — {} indexed total",
                ok, embeddings.values().iterator().next().length, embeddings.size());
        }
    }

    /** The registered description for a tool id, or null — used to give the model the
     *  parameter contract when a scripted tool fails with missing args (2026-07-10). */
    public String describe(String toolId) {
        return toolId != null ? descriptions.get(toolId) : null;
    }

    /**
     * Remove a tool from the index.
     */
    public void unregister(String toolId) {
        tools.remove(toolId);
        embeddings.remove(toolId);
        descriptions.remove(toolId);
    }

    /**
     * Search for the most relevant tools given a query.
     *
     * @param query User request or plan goal text
     * @param topK  Maximum results to return
     * @return Ranked list of tool definitions, most relevant first
     */
    public List<InferenceClient.ToolDefinition> search(String query, int topK) {
        if (tools.isEmpty()) return List.of();
        if (query == null || query.isBlank()) return List.copyOf(tools.values());

        warmAsync();   // off-thread; never blocks this caller

        // Vectors ONLY if they are already warm. Calling embed() here when the index is cold would
        // drag EmbeddingService.init() (a 118 MB model load) onto the actor thread — the very stall
        // warmAsync() exists to avoid. A cold index answers lexically and gets vectors on the next
        // turn; that is a far better trade than freezing the companion mid-sentence.
        if (embeddingAvailable && !embeddings.isEmpty()) {
            var queryVec = embed(query);
            if (queryVec != null) return vectorSearch(query, queryVec, topK);
        }

        // Only shout once the warm-up has actually FAILED — a cold index on the first turn is
        // normal and must not cry wolf. But a genuinely dead embedder has to be loud: a silent
        // drop to keyword matching is how the tool surface stayed arbitrary for months without a
        // single log line to show for it.
        if (!embeddingAvailable && !warnedNoVectors) {
            warnedNoVectors = true;
            log.warn("ToolSearchIndex: embedder unavailable — tool search is on the KEYWORD "
                + "fallback. Tool choice will be markedly worse; an agent may not be offered the "
                + "tool its request plainly calls for. Check EmbeddingService / the bundled "
                + "embedding model.");
        }
        return keywordSearch(query, topK);
    }

    /**
     * Search with default top-K.
     */
    public List<InferenceClient.ToolDefinition> search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /**
     * Get all registered tools (no ranking).
     */
    public List<InferenceClient.ToolDefinition> all() {
        return List.copyOf(tools.values());
    }

    /** Number of registered tools. */
    public int size() {
        return tools.size();
    }

    /** Whether vector embeddings are available. */
    public boolean hasVectorSearch() {
        return embeddingAvailable && !embeddings.isEmpty();
    }

    // ─── Vector Search ──────────────────────────────────────────

    /**
     * How hard an unmistakable lexical match outweighs cosine similarity.
     *
     * <p>Cosine over paraphrase-l12 lands in a narrow band (~0.1–0.6) and separates tools by only
     * a tenth or two, so a weight of 1.0 lets a definite match ({@code RequestRelevance} = 1.0)
     * decide, while a partial one (0.15–0.5) merely nudges the semantic ranking.</p>
     */
    private static final double LEXICAL_WEIGHT = 1.0;

    /**
     * Cosine similarity BLENDED with {@link RequestRelevance}. Both, always — not one as the other's
     * fallback.
     *
     * <p>Switching the embeddings on is necessary and <b>not sufficient</b>, which the tests caught
     * before this shipped: with vector search working, "what is 17 times 3?" ranked
     * {@code dispatch_bunshin} above the calculator, and a plain weather question ranked
     * {@code bunshin_check_in} above the forecast tool. The reason is structural — digits carry
     * almost no semantic signal, one-line tool descriptions embed poorly, and nothing in
     * "Evaluate an arithmetic expression precisely" is close in embedding space to "17 times 3".
     * A sentence-embedding model is good at paraphrase and bad at exactly the vocabulary gap that
     * matters here.</p>
     *
     * <p>Had I trusted the mechanism because it was now "on", mia would still not have been offered
     * her calculator, and the next live run would have read as one more model failure. Semantics
     * for the fuzzy cases, lexical cues for the sharp ones, and no case where the obvious tool is
     * unreachable.</p>
     */
    private List<InferenceClient.ToolDefinition> vectorSearch(String query, float[] queryVec, int topK) {
        record Scored(String id, double score, int order) {}
        var scores = new ArrayList<Scored>();

        int i = 0;
        for (var id : List.copyOf(tools.keySet())) {
            var vec = embeddings.get(id);
            var cos = vec == null ? 0.0 : cosineSimilarity(queryVec, vec);
            var lex = RequestRelevance.score(query, id, descriptions.get(id));
            scores.add(new Scored(id, cos + LEXICAL_WEIGHT * lex, i++));
        }

        scores.sort(Comparator.comparingDouble(Scored::score).reversed()
            .thenComparingInt(Scored::order));

        var results = new ArrayList<InferenceClient.ToolDefinition>();
        for (int n = 0; n < Math.min(topK, scores.size()); n++) {
            var tool = tools.get(scores.get(n).id());
            if (tool != null) results.add(tool);
        }
        return results;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom > 0 ? dot / denom : 0.0;
    }

    // ─── Keyword Fallback ───────────────────────────────────────

    /**
     * Lexical fallback. Scores with {@link RequestRelevance} (intent cues + name + description
     * overlap) rather than raw substring-contains, which could not see "17 times 3" → calculator.
     *
     * <p>Padding is deliberate and ordered: when fewer than {@code topK} tools actually match, we
     * top up in <b>registration order</b>. The old code topped up from a {@code ConcurrentHashMap}
     * — so a zero-match query returned a hash-ordered menu, which is where
     * {@code summon_familiar, dispatch_bunshin, bunshin_check_in} came from when a companion was
     * asked to multiply two numbers. Padding is a menu of last resort; it must at least be the
     * curated order the caller handed us, and it must never look like a ranking.</p>
     */
    // Package-visible: the degraded path has its own contract (never hash-ordered) and must be
    // testable directly. Reaching it through search() only works when no embedder is present,
    // which makes the test silently depend on the machine it runs on.
    List<InferenceClient.ToolDefinition> keywordSearch(String query, int topK) {
        record Scored(String id, double score, int order) {}
        var scores = new ArrayList<Scored>();

        int i = 0;
        for (var entry : new LinkedHashMap<>(descriptions).entrySet()) {
            var score = RequestRelevance.score(query, entry.getKey(), entry.getValue());
            if (score > 0) scores.add(new Scored(entry.getKey(), score, i));
            i++;
        }

        scores.sort(Comparator.comparingDouble(Scored::score).reversed()
            .thenComparingInt(Scored::order));

        var results = new ArrayList<InferenceClient.ToolDefinition>();
        for (int n = 0; n < Math.min(topK, scores.size()); n++) {
            var tool = tools.get(scores.get(n).id());
            if (tool != null) results.add(tool);
        }

        if (results.size() < topK) {
            int matched = results.size();
            for (var tool : List.copyOf(tools.values())) {
                if (results.size() >= topK) break;
                if (!results.contains(tool)) results.add(tool);
            }
            log.debug("ToolSearchIndex keyword fallback: {} matched '{}', padded to {} in "
                + "registration order", matched, query, results.size());
        }
        return results;
    }

    // ─── Embedding (in-process) ─────────────────────────────────

    /**
     * Embed with the bundled in-process model — the SAME {@link EmbeddingService} that already
     * backs memory recall and Lucene. No HTTP, no daemon, nothing to 404.
     *
     * <p>This used to POST to Ollama's {@code /api/embed} at {@code WYRDSEKAI_INFERENCE_URL}, an
     * endpoint llama-server does not serve — so on every real install it failed on the first call
     * and disabled itself at debug level. The embedder we actually ship was sitting right here the
     * whole time.</p>
     *
     * @return the vector, or null once (and thereafter) the embedder is unusable
     */
    private float[] embed(String text) {
        if (!embeddingAvailable) return null;
        try {
            // init(), NOT get(). get() returns null unless someone else initialized the singleton
            // first — so whoever loses that race gets a null, latches embeddingAvailable=false, and
            // is silently keyword-only for the rest of the process. That is precisely the bug this
            // whole change exists to kill; it would have been very funny to reintroduce it here.
            // init() is idempotent, self-initializing, and returns null only if the model genuinely
            // cannot be loaded.
            var svc = EmbeddingService.init();
            if (svc == null) { embeddingAvailable = false; return null; }
            var vec = svc.embed(text);
            if (vec == null || vec.isEmpty()) { embeddingAvailable = false; return null; }
            var out = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) out[i] = vec.get(i);
            return out;
        } catch (Exception e) {
            log.warn("ToolSearchIndex: embedder unusable ({}) — falling back to keyword search",
                e.toString());
            embeddingAvailable = false;
            return null;
        }
    }
}
