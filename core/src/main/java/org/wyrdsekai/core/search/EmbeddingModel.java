package org.wyrdsekai.core.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of embedding models that wyrdsekai knows how to load.
 *
 * <p>Each entry is identified by a short stable {@code id} (e.g. {@code "e5-small"})
 * which is what operators type into {@code WYRDSEKAI_EMBEDDING_MODEL=...} or
 * {@code wyrd embedding-model set <id>}. The {@code version} string is a
 * dated identifier ({@code "<id>-YYYY-MM-DD"}) that gets persisted alongside
 * each stored embedding so the {@link org.wyrdsekai.core.embedding.EmbeddingMigration}
 * tool can detect rows that need re-embedding after a model swap.
 *
 * <h2>Why a per-installation registry rather than per-call</h2>
 *
 * <p>The Lucene HNSW vector field has a fixed dimension (384 / 768 / 1024),
 * so changing the active model's dimension requires a re-emit of every stored
 * vector. The registry lets us route the boring metadata (paths, dimensions,
 * minimum RAM hint, HuggingFace download id) through one place and lets the
 * CLI render a list of choices without digging into ONNX internals.
 *
 * <h2>Default vs. on-demand</h2>
 *
 * <p>{@link #PARAPHRASE_L12} is the default. None of the registered models
 * are checked into git — they are fetched at packaging time by
 * {@code packaging/fetch-embedding-models.sh} (which stages the default
 * under {@code packaging/embedding-models/} for {@code .deb}/{@code .pkg}
 * to ship to {@code <prefix>/share/embedding-models/}), or downloaded at
 * first run by {@code wyrd setup}. The non-default registered models
 * ({@link #E5_SMALL}, {@link #E5_BASE}, {@link #BGE_M3}) advertise a
 * HuggingFace URL and land in {@code ~/.wyrdsekai/models/<id>-q8.onnx}
 * when the operator opts in via {@code wyrd embedding-model download <id>}.
 *
 * <p>{@link #E5_SMALL} is registered as a small alternative (also 384-d, also
 * multilingual via E5's training mix) — same dimension as the bundled default,
 * so swapping to it requires only a re-embed migration, not a Lucene index
 * rebuild. Useful for operators who specifically want E5's instruction-tuned
 * behavior.
 */
public final class EmbeddingModel {

    /** Stable short identifier — what {@code WYRDSEKAI_EMBEDDING_MODEL} accepts. */
    private final String id;
    /** Dated version string persisted with each embedding row. */
    private final String version;
    /** Output dimension. Lucene HNSW fields are pinned to one value per installation. */
    private final int dimension;
    /** Maximum tokens the tokenizer is configured to emit (truncation cap). */
    private final int maxSeqLength;
    /** Classpath resource path inside {@code core/src/main/resources}. */
    private final String onnxResource;
    /** Classpath resource path inside {@code core/src/main/resources}. */
    private final String tokenizerResource;
    /** Filename to look for under {@code ~/.wyrdsekai/models/} when classpath miss. */
    private final String fallbackModelFile;
    /** Filename to look for under {@code ~/.wyrdsekai/models/} when classpath miss. */
    private final String fallbackTokenizerFile;
    /** Operator-facing name for CLI listings. */
    private final String displayName;
    /** Approximate disk footprint in megabytes — used in download prompts. */
    private final int approxSizeMB;
    /** Recommended minimum host RAM in GB. 0 = runs anywhere. */
    private final int minRamGB;
    /** HuggingFace repo id (for download URL templating). */
    private final String hfId;
    /** Path within the HF repo to the ONNX file. */
    private final String hfOnnxPath;
    /** Path within the HF repo to {@code tokenizer.json}. */
    private final String hfTokenizerPath;
    /** True if shipped in the installer resources/. */
    private final boolean bundled;

    private EmbeddingModel(Builder b) {
        this.id = b.id;
        this.version = b.version;
        this.dimension = b.dimension;
        this.maxSeqLength = b.maxSeqLength;
        this.onnxResource = b.onnxResource;
        this.tokenizerResource = b.tokenizerResource;
        this.fallbackModelFile = b.fallbackModelFile;
        this.fallbackTokenizerFile = b.fallbackTokenizerFile;
        this.displayName = b.displayName;
        this.approxSizeMB = b.approxSizeMB;
        this.minRamGB = b.minRamGB;
        this.hfId = b.hfId;
        this.hfOnnxPath = b.hfOnnxPath;
        this.hfTokenizerPath = b.hfTokenizerPath;
        this.bundled = b.bundled;
    }

    public String id()                  { return id; }
    public String version()             { return version; }
    public int dimension()              { return dimension; }
    public int maxSeqLength()           { return maxSeqLength; }
    public String onnxResource()        { return onnxResource; }
    public String tokenizerResource()   { return tokenizerResource; }
    public String fallbackModelFile()   { return fallbackModelFile; }
    public String fallbackTokenizerFile() { return fallbackTokenizerFile; }
    public String displayName()         { return displayName; }
    public int approxSizeMB()           { return approxSizeMB; }
    public int minRamGB()               { return minRamGB; }
    public String hfId()                { return hfId; }
    public String hfOnnxPath()          { return hfOnnxPath; }
    public String hfTokenizerPath()     { return hfTokenizerPath; }
    public boolean bundled()            { return bundled; }

    /**
     * Direct HuggingFace resolve URL for the ONNX file. {@code null} for models
     * with no {@code hfId} configured.
     */
    public String onnxDownloadUrl() {
        if (hfId == null || hfOnnxPath == null) return null;
        return "https://huggingface.co/" + hfId + "/resolve/main/" + hfOnnxPath;
    }

    /** Direct HuggingFace resolve URL for the tokenizer.json file. */
    public String tokenizerDownloadUrl() {
        if (hfId == null || hfTokenizerPath == null) return null;
        return "https://huggingface.co/" + hfId + "/resolve/main/" + hfTokenizerPath;
    }

    @Override public String toString() { return id + " (" + version + ", " + dimension + "d)"; }

    // ── Registry entries ───────────────────────────────────────────────

    /**
     * Default embedding model — paraphrase-multilingual-MiniLM-L12-v2, int8 quant.
     * 384 dimensions, ~112MB on disk. Multilingual (XLM-R Unigram tokenizer).
     * Fetched at packaging time (see packaging/fetch-embedding-models.sh) and
     * shipped to {@code <prefix>/share/embedding-models/}, or downloaded at
     * first run by {@code wyrd setup} if the bundle isn't present.
     *
     * <p>2026-05-29 encoder decouple: this default reverted to the <b>stock</b>
     * (non-SetFit) weights. The 2026-05-25 SetFit fine-tune sharpened classifier-head
     * separation but degraded general semantic retrieval (frustration≉anger;
     * "neural network drives" missed the CfC/drive fragments) — a shared encoder
     * can't optimize classification and retrieval at once. The SetFit weights now
     * live in {@link #PARAPHRASE_L12_SETFIT}, used ONLY by the classifier
     * ({@code ClassifierArm} → {@code EmbeddingService.classifierEncoder()}), while
     * this default carries the stock weights for memory / soul-fragment / library
     * retrieval + admission-controller dedup. Operators with Lucene HNSW indexes
     * embedded under the SetFit version will see their next {@code wyrd embed-migrate}
     * re-embed in place onto stock (same 384-d width, no index rebuild).
     */
    public static final EmbeddingModel PARAPHRASE_L12 = new Builder()
        .id("paraphrase-l12")
        .version("multilingual-MiniLM-L12-v2-2026-04-30")
        .dimension(384)
        .maxSeqLength(128)
        .onnxResource("models/paraphrase-multilingual-MiniLM-L12-v2-q8.onnx")
        .tokenizerResource("models/paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json")
        .fallbackModelFile("paraphrase-multilingual-MiniLM-L12-v2-q8.onnx")
        .fallbackTokenizerFile("paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json")
        .displayName("Paraphrase Multilingual MiniLM L12 (default, stock)")
        .approxSizeMB(120)
        .minRamGB(0)
        // Xenova hosts the int8-quantized ONNX export at this path; the
        // sentence-transformers repo only ships PyTorch weights.
        .hfId("Xenova/paraphrase-multilingual-MiniLM-L12-v2")
        .hfOnnxPath("onnx/model_quantized.onnx")
        .hfTokenizerPath("tokenizer.json")
        .bundled(false)
        .build();

    /**
     * SetFit-fine-tuned variant of {@link #PARAPHRASE_L12} — the classifier's
     * private feature encoder, NOT a retrieval model. Same XLM-R tokenizer and
     * 384-d output as the stock default (byte-identical token IDs), but the encoder
     * weights are contrastively tuned on the classifier-head seeds
     * (task_present + request_type + cleanliness + substrate_present) via
     * {@code scripts/classifier/train_setfit.py}. This is what closed the
     * frozen-embedding ceiling (substrate_present 15/90 → ~1/90).
     *
     * <p>It must stay paired with the SetFit-trained heads in
     * {@code classifier/pretrained/*.onnx}: those heads consume THIS encoder's
     * feature space. {@code EmbeddingService.classifierEncoder()} loads it; nothing
     * in the retrieval / memory path touches it. Selectable via
     * {@code WYRDSEKAI_CLASSIFIER_ENCODER=paraphrase-l12-setfit} (the default).
     * Bundled artifact — no public HF mirror.
     */
    public static final EmbeddingModel PARAPHRASE_L12_SETFIT = new Builder()
        .id("paraphrase-l12-setfit")
        .version("multilingual-MiniLM-L12-v2-setfit-2026-05-25")
        .dimension(384)
        .maxSeqLength(128)
        .onnxResource("models/paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx")
        .tokenizerResource("models/paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json")
        .fallbackModelFile("paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx")
        .fallbackTokenizerFile("paraphrase-multilingual-MiniLM-L12-v2-tokenizer.json")
        .displayName("Paraphrase Multilingual MiniLM L12 (SetFit, classifier-only)")
        .approxSizeMB(120)
        .minRamGB(0)
        // No public HF mirror — produced by scripts/classifier/train_setfit.py and
        // copied into resources/models at build time (same as the stock default, which
        // is also bundled() == false: the flag is reserved/not load-bearing, and
        // modelFilesPresent() resolves via classpath-or-disk regardless).
        .bundled(false)
        .build();

    /**
     * Multilingual E5 small — same 384 dimensions as the bundled default, so a
     * swap requires only a re-embed migration (no Lucene index rebuild).
     * Slightly larger context window, instruction-tuned. ~135MB.
     */
    public static final EmbeddingModel E5_SMALL = new Builder()
        .id("e5-small")
        .version("multilingual-e5-small-2026-04-30")
        .dimension(384)
        .maxSeqLength(512)
        .onnxResource("models/multilingual-e5-small-q8.onnx")
        .tokenizerResource("models/multilingual-e5-small-tokenizer.json")
        .fallbackModelFile("multilingual-e5-small-q8.onnx")
        .fallbackTokenizerFile("multilingual-e5-small-tokenizer.json")
        .displayName("Multilingual E5 small")
        .approxSizeMB(135)
        .minRamGB(0)
        .hfId("intfloat/multilingual-e5-small")
        .hfOnnxPath("onnx/model_quantized.onnx")
        .hfTokenizerPath("tokenizer.json")
        .bundled(false)
        .build();

    /**
     * Multilingual E5 base — 768 dimensions, ~280MB quantized. Substantial
     * retrieval-quality bump over -small. Switching to this requires a
     * Lucene index rebuild (different vector field width).
     */
    public static final EmbeddingModel E5_BASE = new Builder()
        .id("e5-base")
        .version("multilingual-e5-base-2026-04-30")
        .dimension(768)
        .maxSeqLength(512)
        .onnxResource("models/multilingual-e5-base-q8.onnx")
        .tokenizerResource("models/multilingual-e5-base-tokenizer.json")
        .fallbackModelFile("multilingual-e5-base-q8.onnx")
        .fallbackTokenizerFile("multilingual-e5-base-tokenizer.json")
        .displayName("Multilingual E5 base")
        .approxSizeMB(350)
        .minRamGB(4)
        .hfId("intfloat/multilingual-e5-base")
        .hfOnnxPath("onnx/model_quantized.onnx")
        .hfTokenizerPath("tokenizer.json")
        .bundled(false)
        .build();

    /**
     * BGE-M3 — 1024-dim, multilingual + multi-functionality. ~600MB quantized.
     * Best retrieval quality of the registered set. Switching to this requires
     * a Lucene index rebuild.
     *
     * <p>Note: BGE-M3's ONNX export uses XLM-R sentencepiece tokenizer (same
     * family as the bundled default), so DJL's HuggingFaceTokenizer should
     * load {@code tokenizer.json} without changes. If the export fails to
     * produce a {@code [batch, seq, 1024]} hidden-state output (some BGE-M3
     * exports return only the [CLS] vector at {@code [batch, 1024]}),
     * EmbeddingService's mean-pool path will need a shape branch — flagged
     * here so the next operator hits the issue with context.
     */
    public static final EmbeddingModel BGE_M3 = new Builder()
        .id("bge-m3")
        .version("bge-m3-2026-04-30")
        .dimension(1024)
        .maxSeqLength(512)
        .onnxResource("models/bge-m3-q8.onnx")
        .tokenizerResource("models/bge-m3-tokenizer.json")
        .fallbackModelFile("bge-m3-q8.onnx")
        .fallbackTokenizerFile("bge-m3-tokenizer.json")
        .displayName("BGE-M3 (multilingual, multi-functional)")
        .approxSizeMB(600)
        .minRamGB(8)
        // Xenova's transformers.js conversion — the upstream BAAI/bge-m3
        // repo ships only fp32 ONNX with external data (model.onnx +
        // model.onnx_data), no single-file quantized export.
        .hfId("Xenova/bge-m3")
        .hfOnnxPath("onnx/model_quantized.onnx")
        .hfTokenizerPath("tokenizer.json")
        .bundled(false)
        .build();

    private static final Map<String, EmbeddingModel> REGISTRY;
    static {
        var m = new LinkedHashMap<String, EmbeddingModel>();
        m.put(PARAPHRASE_L12.id, PARAPHRASE_L12);
        // NOTE: PARAPHRASE_L12_SETFIT is deliberately NOT registered here. It is the
        // classifier's internal feature encoder, not a user-selectable retrieval model —
        // `wyrd embedding-model set paraphrase-l12-setfit` must NOT resolve it (that would
        // wreck retrieval geometry). It's reached only via EmbeddingService.classifierEncoder()
        // / classifierDefault(). It also has no HF mirror, so excluding it keeps the
        // "every registered model is HF-downloadable" invariant intact.
        m.put(E5_SMALL.id, E5_SMALL);
        m.put(E5_BASE.id, E5_BASE);
        m.put(BGE_M3.id, BGE_M3);
        REGISTRY = Map.copyOf(m);
    }

    /** Default model id when nothing else is configured. */
    public static final String DEFAULT_ID = PARAPHRASE_L12.id;

    /** All registered models, in insertion order. */
    public static List<EmbeddingModel> all() {
        return List.copyOf(REGISTRY.values());
    }

    /** Look up a model by id. Returns null if unknown. */
    public static EmbeddingModel byId(String id) {
        if (id == null) return null;
        return REGISTRY.get(id.trim().toLowerCase());
    }

    /** Look up a model by id, or fall back to the bundled default. */
    public static EmbeddingModel byIdOrDefault(String id) {
        var m = byId(id);
        return m != null ? m : PARAPHRASE_L12;
    }

    /** The bundled default ({@link #PARAPHRASE_L12}) — stock weights, retrieval/memory. */
    public static EmbeddingModel bundledDefault() {
        return PARAPHRASE_L12;
    }

    /**
     * The default <em>classifier</em> feature encoder ({@link #PARAPHRASE_L12_SETFIT}).
     * Distinct from {@link #bundledDefault()}: the classifier heads were trained on
     * this SetFit-tuned feature space, while retrieval/memory uses the stock default.
     */
    public static EmbeddingModel classifierDefault() {
        return PARAPHRASE_L12_SETFIT;
    }

    // ── Builder ────────────────────────────────────────────────────────

    private static final class Builder {
        String id;
        String version;
        int dimension;
        int maxSeqLength = 128;
        String onnxResource;
        String tokenizerResource;
        String fallbackModelFile;
        String fallbackTokenizerFile;
        String displayName;
        int approxSizeMB;
        int minRamGB;
        String hfId;
        String hfOnnxPath;
        String hfTokenizerPath;
        boolean bundled;

        Builder id(String v)                  { this.id = v; return this; }
        Builder version(String v)             { this.version = v; return this; }
        Builder dimension(int v)              { this.dimension = v; return this; }
        Builder maxSeqLength(int v)           { this.maxSeqLength = v; return this; }
        Builder onnxResource(String v)        { this.onnxResource = v; return this; }
        Builder tokenizerResource(String v)   { this.tokenizerResource = v; return this; }
        Builder fallbackModelFile(String v)   { this.fallbackModelFile = v; return this; }
        Builder fallbackTokenizerFile(String v) { this.fallbackTokenizerFile = v; return this; }
        Builder displayName(String v)         { this.displayName = v; return this; }
        Builder approxSizeMB(int v)           { this.approxSizeMB = v; return this; }
        Builder minRamGB(int v)               { this.minRamGB = v; return this; }
        Builder hfId(String v)                { this.hfId = v; return this; }
        Builder hfOnnxPath(String v)          { this.hfOnnxPath = v; return this; }
        Builder hfTokenizerPath(String v)     { this.hfTokenizerPath = v; return this; }
        Builder bundled(boolean v)            { this.bundled = v; return this; }

        EmbeddingModel build() { return new EmbeddingModel(this); }
    }
}
