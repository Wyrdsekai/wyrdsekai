package org.wyrdsekai.core.search;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process embedding service using ONNX Runtime + a sentence-transformer
 * selected from {@link EmbeddingModel}.
 *
 * <p>The active model is chosen at {@link #init()} time using this precedence:
 * <ol>
 *   <li>{@code WYRDSEKAI_EMBEDDING_MODEL} env var (e.g. {@code "bge-m3"})</li>
 *   <li>{@code ~/.wyrdsekai/embedding-model.txt} (single line, model id)</li>
 *   <li>The bundled default ({@link EmbeddingModel#PARAPHRASE_L12})</li>
 * </ol>
 *
 * <p>If the selected model's ONNX file is not on the classpath nor under
 * {@code ~/.wyrdsekai/models/}, the service logs a clear warning and falls
 * back to the bundled default. (Operator likely chose a model and forgot to
 * run {@code wyrd embedding-model download <id>}.)
 *
 * <p>Singleton lifecycle: call {@link #init()} at startup, {@link #close()} at
 * shutdown. Thread-safe after initialization.
 *
 * <p>Tokenizer: DJL's HuggingFaceTokenizer reads any {@code tokenizer.json}
 * (BERT WordPiece, XLM-R Unigram, etc.) so swapping between the bundled L12
 * and an E5 / BGE variant doesn't change the call site.
 */
public final class EmbeddingService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Selector env var. Value is an {@link EmbeddingModel#id()}. */
    public static final String SELECTOR_ENV = "WYRDSEKAI_EMBEDDING_MODEL";
    /**
     * Persistent selector file written by {@code wyrd embedding-model set <id>}.
     * Single line, plain text, model id.
     */
    public static final String SELECTOR_FILE = ".wyrdsekai/embedding-model.txt";

    /**
     * Selector env var for the <em>classifier</em> feature encoder, independent of
     * the retrieval encoder ({@link #SELECTOR_ENV}). Value is an
     * {@link EmbeddingModel#id()}. Defaults to {@link EmbeddingModel#classifierDefault()}
     * (the SetFit-tuned encoder). The classifier and retrieval encoders are decoupled
     * because SetFit tuning that sharpens classification degrades general retrieval —
     */
    public static final String CLASSIFIER_SELECTOR_ENV = "WYRDSEKAI_CLASSIFIER_ENCODER";

    private static volatile EmbeddingService instance;

    /**
     * Secondary instance bound to the classifier feature encoder (SetFit-tuned),
     * kept entirely separate from the retrieval singleton {@link #instance} so the
     * two encoders never collide. Lazily built by {@link #classifierEncoder()}.
     */
    private static volatile EmbeddingService classifierInstance;

    /**
     * Latches once {@link #classifierEncoder()} has run its build, so a FAILED
     * build (which leaves {@code classifierInstance} null when retrieval is also
     * unavailable) is not retried on every call. Without this, each classify
     * re-ran the expensive {@code loadModel()} (ORT native init) + {@code
     * loadTokenizer()} on the calling thread — a per-inference cost on any node
     * whose encoder can't load, and the source of AutonomyIntegrationTest
     * flaking (repeated multi-hundred-ms loads on the actor dispatcher pushing
     * the first inference past its probe). Attempt once; cache the outcome.
     */
    private static volatile boolean classifierEncoderAttempted;

    /**
     * The model the service is currently configured for. Set at {@link #init()}
     * time before the session is built; never mutated afterwards.
     */
    private EmbeddingModel model;
    private int dimension;
    private int maxSeqLength;

    private OrtEnvironment env;
    /**
     * {@link OrtEnvironment#getEnvironment()} returns a process-global singleton.
     * Only the primary (retrieval) instance closes it on {@link #close()}; the
     * secondary classifier instance shares the same global env and must NOT close
     * it (doing so would break the other instance's sessions). Set false on the
     * standalone classifier instance.
     */
    private boolean ownsEnv = true;
    /**
     * Volatile so the periodic-recycle path (see {@link #recycle()}) can swap the
     * session reference atomically without a lock — readers in {@link #embed} see
     * either the old or new session, both valid. The old session is closed after
     * a grace period to let in-flight {@code session.run(...)} calls complete.
     */
    private volatile OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    // ── Memory leak defense (CodeZaiku Issue 2: ORT RSS accumulation) ────
    // ONNX Runtime's session memory grows over weeks even with proper close()
    // calls (see ORT issues #5176, #6058, #11118, #22271, #26831). Defense:
    // periodically rebuild the session to bound the leak. Old sessions are
    // closed after a grace period so in-flight inference calls complete.
    private static final long DEFAULT_RECYCLE_HOURS = 24;
    private static final long CLOSE_GRACE_MS = 30_000;
    private static final long RECYCLE_HOURS = parseRecycleHours();

    private static long parseRecycleHours() {
        var raw = System.getenv("WYRDSEKAI_EMBEDDING_RECYCLE_HOURS");
        if (raw == null || raw.isBlank()) return DEFAULT_RECYCLE_HOURS;
        try {
            return Math.max(1, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_RECYCLE_HOURS;
        }
    }

    private ScheduledExecutorService recycleScheduler;
    private final AtomicInteger recycleCount = new AtomicInteger(0);

    private EmbeddingService() {}

    /** Initialize the singleton. Safe to call multiple times (idempotent). */
    public static EmbeddingService init() {
        if (instance != null) return instance;
        synchronized (EmbeddingService.class) {
            if (instance != null) return instance;
            var svc = new EmbeddingService();
            try {
                // Resolve the requested model first; fall back to bundled if its
                // weights aren't actually present on disk. This must happen before
                // loadModel() so dimension() and currentModelVersion() reflect the
                // active choice if anyone reads them post-init.
                svc.model = resolveActiveModel();
                if (!modelFilesPresent(svc.model)) {
                    if (svc.model != EmbeddingModel.bundledDefault()) {
                        log.warn("Embedding model '{}' selected but ONNX not found in "
                            + "classpath ({}) or ~/.wyrdsekai/models/{}. Falling back to default '{}'. "
                            + "Run `wyrd embedding-model download {}` to fetch it.",
                            svc.model.id(), svc.model.onnxResource(),
                            svc.model.fallbackModelFile(),
                            EmbeddingModel.bundledDefault().id(),
                            svc.model.id());
                        svc.model = EmbeddingModel.bundledDefault();
                    }
                }
                svc.dimension = svc.model.dimension();
                svc.maxSeqLength = svc.model.maxSeqLength();

                svc.loadModel();
                svc.loadTokenizer();
                // Build-time invariant: the loaded ONNX must emit exactly the
                // dimension declared by the registry entry. Failing here is
                // preferable to silently corrupting the Lucene index later.
                var probe = svc.embed("dimension probe");
                if (probe.size() != svc.dimension) {
                    throw new IllegalStateException("Embedding model '" + svc.model.id()
                        + "' emits " + probe.size() + " dims, registry says " + svc.dimension
                        + ". Lucene HNSW index requires fixed dimension.");
                }
                svc.startRecycleScheduler();
                instance = svc;
                log.info("EmbeddingService initialized ({}, {}d, recycle every {}h)",
                    svc.model.version(), svc.dimension, RECYCLE_HOURS);
            } catch (Exception | LinkageError e) {
                // LinkageError catches native-lib load failures (UnsatisfiedLinkError
                // from DJL's libtokenizers.so / the ORT native lib). Without it, such
                // a failure on an actor dispatcher thread escapes as a fatal Error and
                // pekko.jvm-exit-on-fatal-error takes down the whole node — a missing
                // or ABI-mismatched native lib must only disable embeddings, not crash.
                log.warn("EmbeddingService initialization failed: {}. Embeddings will be unavailable.", e.getMessage());
                return null;
            }
            return instance;
        }
    }

    /** Get the singleton instance, or null if not initialized. */
    public static EmbeddingService get() {
        return instance;
    }

    /**
     * The classifier feature encoder — a SECOND instance bound to the SetFit-tuned
     * model ({@link EmbeddingModel#classifierDefault()}), independent of the retrieval
     * singleton {@link #get()}. {@code ClassifierArm} embeds through this so the
     * classifier heads see the SetFit feature space they were trained on, while
     * memory / soul-fragment / library retrieval keeps the stock encoder.
     *
     * <p>Lazily built on first call (idempotent). If the SetFit encoder isn't present
     * on disk, falls back to the retrieval singleton with a loud warning — stock-encoder
     * + SetFit-heads is a known-degraded pairing, but better than the classifier going
     * dark. Returns null only if neither encoder can be built.
     */
    public static EmbeddingService classifierEncoder() {
        if (classifierInstance != null) return classifierInstance;
        synchronized (EmbeddingService.class) {
            if (classifierInstance != null) return classifierInstance;
            // Already tried and it couldn't build (and retrieval was null too) —
            // don't re-run the expensive load on every call. Attempt exactly once.
            if (classifierEncoderAttempted) return classifierInstance;
            classifierEncoderAttempted = true;
            var model = resolveClassifierModel();
            if (!modelFilesPresent(model)) {
                log.warn("Classifier encoder '{}' not present (onnx {} / disk {}); "
                    + "falling back to retrieval encoder '{}'. The classifier heads were "
                    + "trained on the SetFit feature space — accuracy will degrade until the "
                    + "SetFit encoder is installed. Run `wyrd embedding-model download {}` "
                    + "or reinstall to restore it.",
                    model.id(), model.onnxResource(), model.fallbackModelFile(),
                    EmbeddingModel.bundledDefault().id(), model.id());
                classifierInstance = get();  // may be null if retrieval also unbuilt
                return classifierInstance;
            }
            var svc = new EmbeddingService();
            svc.ownsEnv = false;  // shares the process-global OrtEnvironment; doesn't close it
            try {
                svc.model = model;
                svc.dimension = model.dimension();
                svc.maxSeqLength = model.maxSeqLength();
                svc.loadModel();
                svc.loadTokenizer();
                var probe = svc.embed("dimension probe");
                if (probe.size() != svc.dimension) {
                    throw new IllegalStateException("Classifier encoder '" + model.id()
                        + "' emits " + probe.size() + " dims, registry says " + svc.dimension);
                }
                svc.startRecycleScheduler();
                classifierInstance = svc;
                log.info("Classifier encoder initialized ({}, {}d) — decoupled from retrieval",
                    model.version(), svc.dimension);
            } catch (Exception | LinkageError e) {
                // See init(): LinkageError covers native-lib load failure so a broken
                // libtokenizers.so / ORT native lib degrades the classifier instead of
                // killing the JVM via pekko.jvm-exit-on-fatal-error.
                log.warn("Classifier encoder init failed: {}. Falling back to retrieval encoder.",
                    e.getMessage());
                classifierInstance = get();
            }
            return classifierInstance;
        }
    }

    /**
     * Resolve the classifier feature encoder: {@link #CLASSIFIER_SELECTOR_ENV} if set
     * to a registered id, else {@link EmbeddingModel#classifierDefault()}.
     */
    public static EmbeddingModel resolveClassifierModel() {
        var sel = System.getenv(CLASSIFIER_SELECTOR_ENV);
        if (sel != null && !sel.isBlank()) {
            var s = sel.trim();
            // The classifier default is intentionally NOT in the retrieval REGISTRY,
            // so match it by id directly before falling through to byId().
            if (s.equalsIgnoreCase(EmbeddingModel.classifierDefault().id())) {
                return EmbeddingModel.classifierDefault();
            }
            var m = EmbeddingModel.byId(s);
            if (m != null) return m;
            log.warn("{}='{}' but no such model registered — using classifier default.",
                CLASSIFIER_SELECTOR_ENV, sel);
        }
        return EmbeddingModel.classifierDefault();
    }

    /** Embedding dimension of the currently loaded model. */
    public static int dimension() {
        return instance != null ? instance.dimension : EmbeddingModel.bundledDefault().dimension();
    }

    /**
     * The model currently loaded, or {@code null} if {@link #init()} hasn't run yet.
     * Useful for status displays + diagnostics.
     */
    public static EmbeddingModel currentModel() {
        return instance != null ? instance.model : null;
    }

    /**
     * Identifier for the currently loaded model. Persisted alongside stored
     * embeddings (e.g. {@code soul_fragments.embedding_model}) so the
     * {@link org.wyrdsekai.core.embedding.EmbeddingMigration} tool can tell
     * which rows still need re-embedding after a model swap.
     */
    public static String currentModelVersion() {
        return instance != null
            ? instance.model.version()
            : EmbeddingModel.bundledDefault().version();
    }

    // ── Selection ───────────────────────────────────────────────────────

    /**
     * Apply the selection precedence. Public for diagnostics + tests.
     */
    public static EmbeddingModel resolveActiveModel() {
        // 1. Env var
        var env = System.getenv(SELECTOR_ENV);
        if (env != null && !env.isBlank()) {
            var m = EmbeddingModel.byId(env.trim());
            if (m != null) return m;
            log.warn("{}='{}' but no such model registered — falling back through other selectors.",
                SELECTOR_ENV, env);
        }
        // 2. Selector file under $HOME
        try {
            var p = Path.of(System.getProperty("user.home"), SELECTOR_FILE);
            if (Files.isReadable(p)) {
                var line = Files.readString(p).trim();
                if (!line.isEmpty()) {
                    var m = EmbeddingModel.byId(line);
                    if (m != null) return m;
                    log.warn("Selector file {} contains '{}' which is not a registered model id.", p, line);
                }
            }
        } catch (Exception e) {
            log.debug("Selector file read failed: {}", e.getMessage());
        }
        // 3. Bundled default — what's actually on disk in core resources.
        return EmbeddingModel.bundledDefault();
    }

    /**
     * True if the model's ONNX file is reachable either via classpath or via the
     * fallback path under {@code ~/.wyrdsekai/models/}. The tokenizer is checked
     * with the same logic — a working install needs both.
     */
    public static boolean modelFilesPresent(EmbeddingModel m) {
        var cl = EmbeddingService.class.getClassLoader();
        boolean onnxOnCp = cl.getResource(m.onnxResource()) != null;
        boolean tokOnCp = cl.getResource(m.tokenizerResource()) != null;
        var modelsDir = Path.of(System.getProperty("user.home"), ".wyrdsekai", "models");
        boolean onnxOnDisk = Files.exists(modelsDir.resolve(m.fallbackModelFile()));
        boolean tokOnDisk = Files.exists(modelsDir.resolve(m.fallbackTokenizerFile()));
        return (onnxOnCp || onnxOnDisk) && (tokOnCp || tokOnDisk);
    }

    // ── Core API ────────────────────────────────────────────────────────

    /**
     * Compute embedding for a text string.
     * Returns a normalized float list of {@link #dimension()} elements.
     */
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return Collections.nCopies(dimension, 0f);
        }

        try {
            // Tokenize with DJL HuggingFace tokenizer (Rust, exact parity)
            var tokens = tokenize(text);
            int seqLen = tokens.inputIds().length;

            // Create token type IDs (all zeros for single-sentence)
            long[] tokenTypeIds = new long[seqLen];

            var inputTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(tokens.inputIds()), new long[]{1, seqLen});
            var maskTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(tokens.attentionMask()), new long[]{1, seqLen});
            var typeTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(tokenTypeIds), new long[]{1, seqLen});

            // Feed ONLY the inputs the model actually declares. Encoders differ:
            // BERT/MiniLM (the classifier encoder) declares input_ids +
            // attention_mask + token_type_ids; XLM-RoBERTa models like bge-m3
            // (the decoupled RETRIEVAL encoder) declare FEWER — passing extras
            // throws "expected [1,2) found 3" and every retrieval embedding
            // silently fails, killing memory storage+recall (second-node 2026-07-07:
            // bge-m3 retrieval 100% failing → companion confabulated + looped).
            // Gate each input on the session's declared names so any encoder
            // (1-, 2-, or 3-input) gets exactly what it wants.
            var declared = session.getInputNames();
            var inputs = new HashMap<String, OnnxTensorLike>();
            if (declared.contains("input_ids"))      inputs.put("input_ids", inputTensor);
            if (declared.contains("attention_mask")) inputs.put("attention_mask", maskTensor);
            if (declared.contains("token_type_ids")) inputs.put("token_type_ids", typeTensor);

            // Run inference
            try (var result = session.run(inputs)) {
                // Output shape: [1, seqLen, D] — mean pool over token dimension,
                // weighted by attention mask (so padding contributes nothing).
                var output = (OnnxTensor) result.get(0);
                float[][][] raw = (float[][][]) output.getValue();

                long[] mask = tokens.attentionMask();
                float[] pooled = new float[dimension];
                int active = 0;
                for (int t = 0; t < seqLen; t++) {
                    if (mask[t] == 0) continue;
                    active++;
                    for (int d = 0; d < dimension; d++) {
                        pooled[d] += raw[0][t][d];
                    }
                }
                if (active > 0) {
                    for (int d = 0; d < dimension; d++) {
                        pooled[d] /= active;
                    }
                }

                // L2 normalize
                float norm = 0;
                for (float v : pooled) norm += v * v;
                norm = (float) Math.sqrt(norm);
                if (norm > 0) {
                    for (int d = 0; d < dimension; d++) pooled[d] /= norm;
                }

                // Convert to List<Float> for Lucene
                var embedding = new ArrayList<Float>(dimension);
                for (float v : pooled) embedding.add(v);
                return embedding;
            } finally {
                inputTensor.close();
                maskTensor.close();
                typeTensor.close();
            }
        } catch (Exception e) {
            log.warn("Embedding failed for text ({}chars): {}", text.length(), e.getMessage());
            return Collections.nCopies(dimension, 0f);
        }
    }

    /**
     * Embed MANY texts in a single ONNX session run.
     *
     * <p><b>Why this exists.</b> {@link #embed(String)} was the only entry point
     * and pinned the batch dimension to 1, so every rerank candidate cost a
     * separate session run. Measured on a live household: a two-stage Study
     * search scored <b>2 of 60</b> candidates inside its 2.5s budget, leaving the
     * semantic half of retrieval essentially decorative. Truncating inputs to 600
     * chars raised that only to 6 of 60 — which showed the cost is dominated by
     * per-call overhead, not sequence length. Batching is the actual fix.</p>
     *
     * <p>Sequences are padded to the longest in the batch and masked, so padding
     * contributes nothing to the pooled vector — each row is pooled with its own
     * attention mask, exactly as the single-text path does.</p>
     *
     * <p>Falls back to per-text embedding if the batched run fails, so a model
     * that dislikes batching degrades in speed rather than breaking retrieval.</p>
     *
     * @param texts inputs; null/blank entries yield zero vectors
     * @return one embedding per input, in the same order
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (texts.size() == 1) return List.of(embed(texts.get(0)));

        try {
            int n = texts.size();
            var toks = new ArrayList<TokenizerOutput>(n);
            var blank = new boolean[n];
            int maxLen = 1;
            for (int i = 0; i < n; i++) {
                var t = texts.get(i);
                if (t == null || t.isBlank()) {
                    blank[i] = true;
                    toks.add(null);
                    continue;
                }
                var tk = tokenize(t);
                toks.add(tk);
                maxLen = Math.max(maxLen, tk.inputIds().length);
            }

            var ids = new long[n * maxLen];
            var mask = new long[n * maxLen];
            var types = new long[n * maxLen];
            for (int i = 0; i < n; i++) {
                var tk = toks.get(i);
                if (tk == null) continue;
                var rowIds = tk.inputIds();
                var rowMask = tk.attentionMask();
                System.arraycopy(rowIds, 0, ids, i * maxLen, rowIds.length);
                System.arraycopy(rowMask, 0, mask, i * maxLen, rowMask.length);
                // remaining positions stay 0 — padding, masked out below
            }

            var shape = new long[]{n, maxLen};
            var inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
            var maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
            var typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape);

            var declared = session.getInputNames();
            var inputs = new HashMap<String, OnnxTensorLike>();
            if (declared.contains("input_ids"))      inputs.put("input_ids", inputTensor);
            if (declared.contains("attention_mask")) inputs.put("attention_mask", maskTensor);
            if (declared.contains("token_type_ids")) inputs.put("token_type_ids", typeTensor);

            try (var result = session.run(inputs)) {
                var output = (OnnxTensor) result.get(0);
                float[][][] raw = (float[][][]) output.getValue();

                var out = new ArrayList<List<Float>>(n);
                for (int i = 0; i < n; i++) {
                    if (blank[i]) {
                        out.add(Collections.nCopies(dimension, 0f));
                        continue;
                    }
                    var rowMask = toks.get(i).attentionMask();
                    var pooled = new float[dimension];
                    int active = 0;
                    for (int t = 0; t < rowMask.length; t++) {
                        if (rowMask[t] == 0) continue;
                        active++;
                        for (int d = 0; d < dimension; d++) pooled[d] += raw[i][t][d];
                    }
                    if (active > 0) {
                        for (int d = 0; d < dimension; d++) pooled[d] /= active;
                    }
                    float norm = 0;
                    for (float v : pooled) norm += v * v;
                    norm = (float) Math.sqrt(norm);
                    if (norm > 0) {
                        for (int d = 0; d < dimension; d++) pooled[d] /= norm;
                    }
                    var emb = new ArrayList<Float>(dimension);
                    for (float v : pooled) emb.add(v);
                    out.add(emb);
                }
                return out;
            } finally {
                inputTensor.close();
                maskTensor.close();
                typeTensor.close();
            }
        } catch (Exception e) {
            log.warn("Batched embedding of {} texts failed ({}) — falling back to per-text",
                texts.size(), e.getMessage());
            var out = new ArrayList<List<Float>>(texts.size());
            for (var t : texts) out.add(embed(t));
            return out;
        }
    }

    /**
     * Compute cosine similarity between two texts.
     * Returns 0.0 (unrelated) to 1.0 (identical meaning).
     */
    public float similarity(String text1, String text2) {
        var e1 = embed(text1);
        var e2 = embed(text2);
        float dot = 0;
        for (int i = 0; i < dimension; i++) {
            dot += e1.get(i) * e2.get(i);
        }
        return Math.max(0f, dot); // cosine similarity on normalized vectors = dot product
    }

    // ── Tokenizer ───────────────────────────────────────────────────────

    /**
     * Tokenize text using DJL's HuggingFace tokenizer (Rust JNI, exact parity).
     * Returns (inputIds, attentionMask) pair, padded/truncated to MAX_SEQ_LENGTH.
     */
    private record TokenizerOutput(long[] inputIds, long[] attentionMask) {}

    private TokenizerOutput tokenize(String text) {
        var encoding = tokenizer.encode(text, true, true);
        var ids = encoding.getIds();
        var mask = encoding.getAttentionMask();

        // Truncate if needed
        int seqLen = Math.min(ids.length, maxSeqLength);
        long[] inputIds = new long[seqLen];
        long[] attentionMask = new long[seqLen];
        System.arraycopy(ids, 0, inputIds, 0, seqLen);
        System.arraycopy(mask, 0, attentionMask, 0, seqLen);

        return new TokenizerOutput(inputIds, attentionMask);
    }

    // ── Loading ─────────────────────────────────────────────────────────

    private void loadModel() throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = createSession();
    }

    /**
     * Build a fresh OrtSession from the bundled model. Used both for initial load
     * and for periodic recycle (see {@link #recycle()}). Uses the same SessionOptions
     * each time so readers see consistent runtime behavior across recycles.
     */
    private OrtSession createSession() throws Exception {
        var opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(1); // single thread — embedding is fast
        // Disable ONNX Runtime's default thread spin-waiting between calls. Without this,
        // intra/inter-op threads busy-wait for new work and burn CPU while idle (Microsoft
        // documents this; Inworld engineering reported 47% → 0.5% CPU on the same model
        // by flipping these to 0). For a long-running singleton service like this one,
        // spin-waiting is pure overhead — every embedding call is followed by idle time.
        opts.addConfigEntry("session.intra_op.allow_spinning", "0");
        opts.addConfigEntry("session.inter_op.allow_spinning", "0");

        // Try classpath resource first, then file path
        var resource = getClass().getClassLoader().getResourceAsStream(model.onnxResource());
        if (resource != null) {
            var bytes = resource.readAllBytes();
            resource.close();
            return env.createSession(bytes, opts);
        }
        var dataPath = Path.of(System.getProperty("user.home"), ".wyrdsekai", "models",
            model.fallbackModelFile());
        if (Files.exists(dataPath)) {
            return env.createSession(dataPath.toString(), opts);
        }
        throw new RuntimeException("Embedding model '" + model.id() + "' ONNX not found in classpath ("
            + model.onnxResource() + ") or " + dataPath
            + ". Run `wyrd embedding-model download " + model.id() + "` or revert to the bundled default.");
    }

    // ── Periodic session recycle (memory leak defense) ──────────────────

    /**
     * Rebuild the OrtSession from disk and atomically swap it. Bounds the
     * documented ORT memory accumulation over long-running services.
     *
     * <p>The old session is closed after {@link #CLOSE_GRACE_MS} so any
     * in-flight {@code session.run(...)} calls have time to complete — embeddings
     * typically take &lt;100ms, so 30s is a safe ceiling. Subsequent {@code embed()}
     * calls see the new session via the volatile field.
     *
     * <p>Public for testability + admin invocation. The scheduler calls this
     * automatically every {@link #RECYCLE_HOURS} hours.
     */
    public synchronized void recycle() {
        if (env == null || recycleScheduler == null || recycleScheduler.isShutdown()) {
            return; // not initialized or already shutting down
        }
        OrtSession oldSession = session;
        try {
            OrtSession newSession = createSession();
            session = newSession; // volatile write — atomic swap
            int count = recycleCount.incrementAndGet();
            log.info("ONNX session recycled (#{}, prior session close-grace {}ms)",
                count, CLOSE_GRACE_MS);
            if (oldSession != null) {
                recycleScheduler.schedule(() -> {
                    try {
                        oldSession.close();
                    } catch (Exception e) {
                        log.debug("Old session close failed: {}", e.getMessage());
                    }
                }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("Session recycle failed, keeping previous session: {}", e.getMessage());
        }
    }

    /** Total number of times the session has been recycled (observability + tests). */
    public int recycleCount() { return recycleCount.get(); }

    /** Configured recycle interval in hours (env: {@code WYRDSEKAI_EMBEDDING_RECYCLE_HOURS}). */
    public static long recycleHours() { return RECYCLE_HOURS; }

    /**
     * Test-only seam: clear the singleton so callers see
     * {@link #get()} return {@code null}. Lets tests deterministically
     * assert behavior of code paths that gate on "EmbeddingService is
     * unavailable" — those paths can't otherwise be exercised once any
     * earlier test in the same JVM has called {@link #init()} (the
     * singleton sticks across the test run).
     *
     * <p>Does not shut down a previously-loaded ORT session — that lives
     * on the previous instance, which becomes unreferenced. Only the
     * static handle is cleared.</p>
     */
    public static synchronized void resetForTests() {
        instance = null;
        classifierInstance = null;
        classifierEncoderAttempted = false;
    }

    private void startRecycleScheduler() {
        recycleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "embedding-session-recycle");
            t.setDaemon(true);
            return t;
        });
        recycleScheduler.scheduleAtFixedRate(this::recycle,
            RECYCLE_HOURS, RECYCLE_HOURS, TimeUnit.HOURS);
        log.debug("Embedding session recycle scheduled every {}h", RECYCLE_HOURS);
    }

    private void loadTokenizer() throws Exception {
        // DJL HuggingFaceTokenizer.newInstance(Path) expects a PATH to tokenizer.json.
        // Extract from classpath to temp file if needed.
        Path tokenizerPath = null;
        boolean tempFile = false;

        var resource = getClass().getClassLoader().getResourceAsStream(model.tokenizerResource());
        if (resource != null) {
            var tempDir = Files.createTempDirectory("wyrdsekai-tokenizer-");
            tokenizerPath = tempDir.resolve("tokenizer.json");
            Files.copy(resource, tokenizerPath);
            resource.close();
            tempFile = true;
        } else {
            var dataPath = Path.of(System.getProperty("user.home"), ".wyrdsekai", "models",
                model.fallbackTokenizerFile());
            if (Files.exists(dataPath)) {
                tokenizerPath = dataPath;
            }
        }

        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            throw new RuntimeException("Tokenizer for '" + model.id() + "' not found");
        }

        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath,
            Map.of("maxLength", String.valueOf(maxSeqLength),
                   "padding", "true", "truncation", "true"));

        // Clean up temp file
        if (tempFile) {
            try {
                Files.deleteIfExists(tokenizerPath);
                Files.deleteIfExists(tokenizerPath.getParent());
            } catch (Exception e) { /* ignore */ }
        }

        log.debug("HuggingFace tokenizer loaded ({})", model.id());
    }

    @Override
    public void close() {
        try {
            // Stop the recycle scheduler first so it doesn't try to swap a session mid-shutdown.
            if (recycleScheduler != null) {
                recycleScheduler.shutdownNow();
                try {
                    recycleScheduler.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (tokenizer != null) tokenizer.close();
            if (session != null) session.close();
            // Only the env-owning (primary/retrieval) instance closes the
            // process-global OrtEnvironment; the secondary classifier instance shares it.
            if (ownsEnv && env != null) env.close();
        } catch (Exception e) {
            log.debug("Error closing EmbeddingService: {}", e.getMessage());
        }
        if (this == classifierInstance) classifierInstance = null;
        if (this == instance) instance = null;
    }
}
