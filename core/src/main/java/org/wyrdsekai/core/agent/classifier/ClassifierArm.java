package org.wyrdsekai.core.agent.classifier;

import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.search.EmbeddingService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Procedural classifier arm — reflexive classification of inbound text.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Layer 1 (this class): fast sklearn classifier over minilm embeddings,
 *       loaded from ONNX. Microsecond-scale inference.</li>
 *   <li>Layer 2: LLM fallback, invoked by callers when Layer 1 is unavailable
 *       or its confidence is below threshold. Not implemented here — callers
 *       own the LLM path and decide when to escalate.</li>
 * </ul>
 *
 * <p>Per-companion storage at {@code ~/.wyrdsekai/classifiers/<did>/<head>.onnx}
 * — seeded from shipped pretrained resources on first spawn, updated by the
 * Forge cycle during sleep. A missing model returns
 * {@link Classification#unavailable()} so callers can escalate gracefully.
 *
 * <p>Thread-safety: initialization is guarded; classification is lock-free
 * per-call (ONNX sessions are thread-safe for inference).
 */
public final class ClassifierArm {

    private static final Logger log = LoggerFactory.getLogger(ClassifierArm.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The classification confidence below which the caller should escalate
     * to Layer 2 (or fall through to heuristic). Lowered stepwise:
     *   0.80 → 0.75 (2026-04-22): 0.72-confidence textbook delegates weren't
     *     firing even though the label was right.
     *   0.75 → 0.70 (2026-04-23): tool-efficiency probe showed delegate tells
     *     landing at 0.70-0.72 with correct labels but no dispatch. At 0.70
     *     we capture those without losing much precision — 8-way softmax
     *     above 0.70 is a pretty confident prediction.
     */
    public static final double DEFAULT_ESCALATION_THRESHOLD = 0.70;

    private final String agentDid;
    private final Path perAgentDir;
    private final OrtEnvironment env;
    private final Map<ClassifierHead, Head> heads = new ConcurrentHashMap<>();
    private final ClassifierEventLog eventLog;

    /** Loaded state for a single head. */
    private static final class Head {
        final OrtSession session;
        final List<String> labels;
        Head(OrtSession session, List<String> labels) {
            this.session = session;
            this.labels = labels;
        }
    }

    private ClassifierArm(String agentDid, Path perAgentDir, OrtEnvironment env) {
        this.agentDid = agentDid;
        this.perAgentDir = perAgentDir;
        this.env = env;
        this.eventLog = ClassifierEventLog.forAgent(perAgentDir);
    }

    /** Exposed for Forge consolidation to rotate + read accumulated events. */
    public ClassifierEventLog eventLog() {
        return eventLog;
    }

    /** Exposed for Forge consolidation to know where per-agent model overrides live. */
    public Path perAgentDir() {
        return perAgentDir;
    }

    /**
     * Create an arm for one companion. Tries per-agent overrides first, then
     * falls back to shipped pretrained resources. If neither path works for
     * a head, classification for that head returns {@code unavailable()} and
     * callers escalate to Layer 2.
     *
     * @param agentDid identity key — used to locate per-agent model overrides
     */
    public static ClassifierArm forAgent(String agentDid) {
        OrtEnvironment env;
        try {
            env = OrtEnvironment.getEnvironment();
        } catch (Throwable t) {
            log.warn("OrtEnvironment unavailable — classifier arm disabled: {}", t.getMessage());
            return null;
        }
        var homeDir = WyrdConfig.get().dataDir();
        if (homeDir == null || homeDir.isBlank()) {
            homeDir = System.getProperty("user.home") + "/.wyrdsekai";
        }
        var perAgent = Path.of(homeDir, "classifiers", safeDid(agentDid));
        var arm = new ClassifierArm(agentDid, perAgent, env);
        // Preload all heads eagerly — they're all on hot paths and share
        // construction cost. Having them warm avoids blocking the actor
        // dispatcher on an ONNX session load mid-call.
        for (var head : ClassifierHead.values()) {
            arm.loadHead(head);
        }
        return arm;
    }

    /**
     * Classify text under the given head. Returns {@link Classification#unavailable()}
     * if the model isn't loaded (cold start, missing resource). Callers MUST
     * check confidence against {@link #DEFAULT_ESCALATION_THRESHOLD} and escalate
     * to Layer 2 (LLM) when below.
     */
    public Classification classify(ClassifierHead head, String text) {
        if (text == null || text.isBlank()) return Classification.unavailable();
        var h = heads.get(head);
        if (h == null) {
            // Lazy-load if not preloaded
            h = loadHead(head);
            if (h == null) return Classification.unavailable();
        }
        // Classifier heads were trained on the SetFit-tuned feature space — use the
        // dedicated classifier encoder, NOT the stock retrieval encoder. The two are
        // decoupled because SetFit tuning that sharpens classification degrades general
        // retrieval.
        var svc = EmbeddingService.classifierEncoder();
        if (svc == null) {
            log.debug("Classifier encoder unavailable, cannot classify");
            return Classification.unavailable();
        }
        var embedding = svc.embed(text);
        if (embedding.isEmpty() || allZeros(embedding)) {
            return Classification.unavailable();
        }
        var result = runInference(env, h, embedding);
        // Log to the event stream if we got a real answer. The Forge cycle
        // reads these events during sleep to consolidate into the next
        // training corpus. Stamps the
        // returned Classification with the event UUID so callers (e.g.
        // CompanionActor) can later attach an outcome via
        // {@link ClassifierEventLog#markOutcome}.
        if (eventLog != null && result.label() != null) {
            var id = eventLog.record(new ClassifierEventLog.Event(
                Instant.now(),
                head.name(), text, result.label(),
                result.confidence(), result.source()));
            if (id != null) {
                result = new Classification(result.label(), result.confidence(),
                    result.probs(), result.source(), id);
            }
        }
        return result;
    }

    // ── Loading ─────────────────────────────────────────────────────────

    private Head loadHead(ClassifierHead head) {
        var existing = heads.get(head);
        if (existing != null) return existing;
        byte[] modelBytes = null;
        String labelsJson = null;

        // 1. Per-agent override
        var perAgentModel = perAgentDir.resolve(head.resourceName() + ".onnx");
        var perAgentLabels = perAgentDir.resolve(head.resourceName() + ".labels.json");
        if (Files.exists(perAgentModel) && Files.exists(perAgentLabels)) {
            try {
                modelBytes = Files.readAllBytes(perAgentModel);
                labelsJson = Files.readString(perAgentLabels);
                log.info("Loaded classifier head {} from per-agent path {}",
                    head.name(), perAgentModel);
            } catch (Exception e) {
                log.warn("Failed to load per-agent classifier {}: {}",
                    perAgentModel, e.getMessage());
            }
        }

        // 2. Shipped resources
        if (modelBytes == null) {
            try (var in = ClassifierArm.class.getClassLoader()
                    .getResourceAsStream(head.modelResourcePath())) {
                if (in != null) {
                    modelBytes = in.readAllBytes();
                }
            } catch (Exception e) {
                log.debug("Failed to read shipped classifier resource: {}", e.getMessage());
            }
            try (var in = ClassifierArm.class.getClassLoader()
                    .getResourceAsStream(head.labelsResourcePath())) {
                if (in != null) {
                    labelsJson = new String(in.readAllBytes());
                }
            } catch (Exception e) {
                log.debug("Failed to read shipped labels resource: {}", e.getMessage());
            }
            if (modelBytes != null) {
                log.info("Loaded classifier head {} from shipped resources", head.name());
            }
        }

        if (modelBytes == null || labelsJson == null) {
            log.info("Classifier head {} unavailable (no model found)", head.name());
            return null;
        }

        try {
            var opts = new OrtSession.SessionOptions();
            var session = env.createSession(modelBytes, opts);
            var labels = parseLabels(labelsJson);
            if (labels.isEmpty()) {
                log.warn("Classifier head {} has no labels — treating as unavailable",
                    head.name());
                session.close();
                return null;
            }
            var loaded = new Head(session, labels);
            heads.put(head, loaded);
            return loaded;
        } catch (Exception e) {
            log.warn("Failed to initialize classifier head {}: {}", head.name(), e.getMessage());
            return null;
        }
    }

    private static List<String> parseLabels(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            var out = new ArrayList<String>();
            for (JsonNode label : node.path("labels")) {
                out.add(label.asText());
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse labels JSON: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Candidate probe (gate-runtime parity, 2026-07-22) ───────────────

    /**
     * Load a CANDIDATE head from explicit paths — not the deployed resources.
     * Built for the release bake's runtime-space regression gate: the bake
     * must decide deploy/keep-baseline by classifying anchors through THIS
     * class's exact inference path (same encoder, same tokenizer, same
     * ZipMap handling), not through the offline Python probe whose tokenizer
     * diverges from production (proven to veto a true runtime improvement,
     * 2026-07-22). Caller closes. ONNX exceptions are wrapped in IOException
     * so callers (cli) don't need onnxruntime on their compile classpath.
     */
    public static CandidateHead loadCandidate(Path onnxPath, Path labelsPath)
            throws IOException {
        var env = OrtEnvironment.getEnvironment();
        var modelBytes = Files.readAllBytes(onnxPath);
        var labels = parseLabels(Files.readString(labelsPath));
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("no labels parsed from " + labelsPath);
        }
        try {
            var session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            return new CandidateHead(env, new Head(session, labels));
        } catch (OrtException e) {
            throw new IOException("failed to load candidate onnx " + onnxPath, e);
        }
    }

    /** A candidate head classifying through the production inference path. */
    public static final class CandidateHead implements AutoCloseable {
        private final OrtEnvironment env;
        private final Head head;

        private CandidateHead(OrtEnvironment env, Head head) {
            this.env = env;
            this.head = head;
        }

        /** Same pipeline as {@link ClassifierArm#classify}: classifier encoder
         * embed → {@code runInference}. No event logging — probes are not
         * lived experience. */
        public Classification classify(String text) {
            if (text == null || text.isBlank()) return Classification.unavailable();
            var svc = EmbeddingService.classifierEncoder();
            if (svc == null) return Classification.unavailable();
            var embedding = svc.embed(text);
            if (embedding.isEmpty() || allZeros(embedding)) {
                return Classification.unavailable();
            }
            return runInference(env, head, embedding);
        }

        @Override public void close() {
            try {
                head.session.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ── Inference ────────────────────────────────────────────────────────

    private static Classification runInference(OrtEnvironment env, Head head,
            List<Float> embedding) {
        try {
            var shape = new long[] { 1, embedding.size() };
            var buf = FloatBuffer.allocate(embedding.size());
            for (Float f : embedding) buf.put(f == null ? 0f : f);
            buf.rewind();
            try (var tensor = OnnxTensor.createTensor(env, buf, shape)) {
                // Use the model's actual input name rather than hardcoding it —
                // skl2onnx exports name the single input "embedding_input" (older
                // exports used "embedding"), and a mismatch makes session.run throw
                // "Unknown input name", silently disabling the whole classifier arm.
                var inputName = head.session.getInputNames().iterator().next();
                var inputs = Map.of(inputName, tensor);
                try (var result = head.session.run(inputs)) {
                    // sklearn's ONNX export typically produces two outputs:
                    // "label" (int64 predicted class) and "probabilities"
                    // (map<int64, float> per row OR float[] of shape [1, n_classes]).
                    // Read probabilities for calibration + full distribution.
                    Map<String, Double> probs = extractProbabilities(result, head.labels);
                    if (probs.isEmpty()) {
                        return Classification.unavailable();
                    }
                    // Top-1
                    String topLabel = null;
                    double topProb = -1;
                    for (var e : probs.entrySet()) {
                        if (e.getValue() > topProb) {
                            topProb = e.getValue();
                            topLabel = e.getKey();
                        }
                    }
                    return new Classification(topLabel, topProb, probs, "L1");
                }
            }
        } catch (OrtException | RuntimeException e) {
            log.warn("Classifier inference failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return Classification.unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> extractProbabilities(
            OrtSession.Result result, List<String> labels) {
        for (var entry : result) {
            var name = entry.getKey();
            var value = entry.getValue();
            try {
                Object java = value.getValue();
                // skl2onnx emits probabilities as a sequence of maps —
                // one per input row. For batch=1 we get List of one map,
                // where the map entries are class-index → probability.
                // OnnxMap wraps the native map; .getValue() unwraps to java Map.
                if (java instanceof float[][] arr && arr.length > 0) {
                    float[] row = arr[0];
                    if (row.length != labels.size()) continue;
                    var out = new HashMap<String, Double>();
                    for (int i = 0; i < row.length; i++) {
                        out.put(labels.get(i), (double) row[i]);
                    }
                    return out;
                }
                if (java instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    Map<?, ?> rowMap = null;
                    if (first instanceof Map<?, ?> m) {
                        rowMap = m;
                    } else if (first instanceof OnnxMap onnxMap) {
                        rowMap = (Map<?, ?>) onnxMap.getValue();
                    }
                    if (rowMap != null) {
                        var out = new HashMap<String, Double>();
                        for (var re : rowMap.entrySet()) {
                            int idx = ((Number) re.getKey()).intValue();
                            if (idx >= 0 && idx < labels.size()) {
                                out.put(labels.get(idx), ((Number) re.getValue()).doubleValue());
                            }
                        }
                        return out;
                    }
                }
            } catch (Exception e) {
                log.debug("Extract probabilities from output '{}' failed: {}",
                    name, e.getMessage());
            }
        }
        return Map.of();
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private static boolean allZeros(List<Float> emb) {
        for (Float f : emb) if (f != null && f != 0f) return false;
        return true;
    }

    /** Sanitize a DID for filesystem use. */
    private static String safeDid(String did) {
        if (did == null || did.isBlank()) return "unknown";
        return did.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** Release ONNX sessions. Call on companion shutdown. */
    public void close() {
        for (var h : heads.values()) {
            try {
                h.session.close();
            } catch (Exception ignored) {
            }
        }
        heads.clear();
    }
}
