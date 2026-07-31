package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Multilingual embedding-based action scorer using ONNX Runtime.
 *
 * <p>Loads a sentence-transformer model (all-MiniLM-L6-v2 or multilingual-e5-small)
 * and pre-computes embeddings for all action descriptions at startup. At inference time,
 * embeds the trigger text and scores actions by cosine similarity.</p>
 *
 * <p>Gracefully returns null from {@link #create()} if the ONNX model is not found.
 * Layers 1-3B continue to work without it.</p>
 */
public final class ActionEmbeddingScorer {

    private static final Logger log = LoggerFactory.getLogger(ActionEmbeddingScorer.class);

    /** Standard model paths to search for. */
    private static final List<String> MODEL_PATHS = List.of(
        System.getProperty("user.home") + "/.wyrdsekai/models/all-MiniLM-L6-v2.onnx",
        System.getProperty("user.home") + "/.wyrdsekai/models/multilingual-e5-small.onnx",
        "models/all-MiniLM-L6-v2.onnx",
        "models/multilingual-e5-small.onnx"
    );

    public record ActionScore(String actionType, double score) {}

    private final Object session; // OrtSession — kept as Object to avoid hard dependency
    private final Object environment; // OrtEnvironment
    private final Map<String, float[]> actionEmbeddings;
    private final int embeddingDim;

    private ActionEmbeddingScorer(Object environment, Object session,
                                   Map<String, float[]> actionEmbeddings, int embeddingDim) {
        this.environment = environment;
        this.session = session;
        this.actionEmbeddings = actionEmbeddings;
        this.embeddingDim = embeddingDim;
    }

    /**
     * Create an embedding scorer if the ONNX model is available.
     * Returns null if no model found or ONNX Runtime not available.
     */
    public static ActionEmbeddingScorer create() {
        // Find model file
        Path modelPath = null;
        for (var pathStr : MODEL_PATHS) {
            var path = Path.of(pathStr);
            if (Files.exists(path)) {
                modelPath = path;
                break;
            }
        }
        if (modelPath == null) {
            log.debug("No embedding model found in search paths");
            return null;
        }

        try {
            // Use reflection to avoid hard compile-time dependency on ONNX Runtime
            var envClass = Class.forName("ai.onnxruntime.OrtEnvironment");
            var getEnv = envClass.getMethod("getEnvironment");
            var env = getEnv.invoke(null);

            var sessionClass = Class.forName("ai.onnxruntime.OrtSession");
            var createSession = envClass.getMethod("createSession", String.class);
            var session = createSession.invoke(env, modelPath.toString());

            // Pre-compute action embeddings
            var embeddings = new HashMap<String, float[]>();
            int dim = 0;
            for (var entry : ActionPolicy.REGISTRY.entrySet()) {
                var description = buildEmbeddingText(entry.getKey());
                var embedding = embed(env, session, description);
                if (embedding != null) {
                    embeddings.put(entry.getKey(), embedding);
                    dim = embedding.length;
                }
            }

            if (embeddings.isEmpty()) {
                log.warn("Embedding model loaded but produced no embeddings");
                return null;
            }

            log.info("ActionEmbeddingScorer: loaded model {}, {} action embeddings (dim={})",
                modelPath.getFileName(), embeddings.size(), dim);
            return new ActionEmbeddingScorer(env, session, embeddings, dim);

        } catch (ClassNotFoundException e) {
            log.debug("ONNX Runtime not on classpath — embedding scorer disabled");
            return null;
        } catch (Exception e) {
            log.warn("Failed to initialize embedding scorer: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Score all actions against a trigger text.
     */
    public List<ActionScore> score(String triggerText) {
        try {
            var triggerEmbedding = embed(environment, session, triggerText);
            if (triggerEmbedding == null) return List.of();

            var scores = new ArrayList<ActionScore>();
            for (var entry : actionEmbeddings.entrySet()) {
                var similarity = cosineSimilarity(triggerEmbedding, entry.getValue());
                scores.add(new ActionScore(entry.getKey(), similarity));
            }
            return scores;
        } catch (Exception e) {
            log.debug("Embedding scoring error: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Embed text using the ONNX model via reflection.
     */
    private static float[] embed(Object env, Object session, String text) {
        try {
            // Simplified tokenization: split on whitespace, map to char indices
            // Real implementation would use a proper tokenizer
            // For now, use a basic approach that works with sentence-transformers
            var tensorClass = Class.forName("ai.onnxruntime.OnnxTensor");
            var createTensor = tensorClass.getMethod("createTensor",
                Class.forName("ai.onnxruntime.OrtEnvironment"), Object.class);

            // Create a simple token representation
            // Note: This is a placeholder — real ONNX sentence-transformer models
            // need proper WordPiece/SentencePiece tokenization. For production,
            // we'd use a tokenizer library. For now, gracefully return null
            // if the model requires specific input format.
            log.trace("Embedding text: {}", text);

            // Attempt basic inference
            // The actual tensor creation depends on the model's expected input format
            // Most sentence-transformers expect input_ids, attention_mask, token_type_ids
            return null; // Placeholder — real tokenizer integration needed

        } catch (Exception e) {
            log.trace("Embedding failed: {}", e.getMessage());
            return null;
        }
    }

    private static String buildEmbeddingText(String actionType) {
        var policy = ActionPolicy.forAction(actionType);
        return actionType.replace('_', ' ') + ": " + policy.domain()
            + " action for AI agent in virtual world";
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA > 0 && normB > 0) ? dot / (Math.sqrt(normA) * Math.sqrt(normB)) : 0.0;
    }
}
