package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Semantic search interface for the capability library.
 * Generates embeddings and performs ranked vector search.
 * <p>
 * This is a type-definition file for M0. Actual vector operations
 * (embedding generation, Milvus insert/search) are null at runtime
 * until the inference backend supports embeddings (M2+).
 * <p>
 * Adapted from CodeZaiku with Wyrdsekai-specific field types.
 */
public final class SemanticIndex {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndex.class);

    private final MilvusOperations milvus;
    private final EmbedFunction embedFunction;

    public SemanticIndex(MilvusOperations milvus, EmbedFunction embedFunction) {
        this.milvus = milvus;
        this.embedFunction = embedFunction;
    }

    /** Semantic search over the library. Returns ranked results. */
    public List<ScoredCapability> search(String query, int limit) {
        try {
            List<Float> queryEmbedding = embedFunction.embed(query);
            if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                log.warn("Empty query embedding, falling back to empty results");
                return List.of();
            }
            return milvus.hybridSearchLibrary(query, queryEmbedding, limit);
        } catch (Exception e) {
            log.error("Library semantic search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // --- Interfaces for dependency injection ---

    @FunctionalInterface
    public interface EmbedFunction {
        List<Float> embed(String text) throws Exception;
    }

    public interface MilvusOperations {
        void insertLibraryEntry(String id, String name, String cognitiveLayer,
                                String source, String protocol, String content,
                                String tags, float trustScore, String verificationStatus,
                                List<Float> embedding);

        void deleteById(String collection, String id);

        List<ScoredCapability> hybridSearchLibrary(String query, List<Float> queryEmbedding, int limit);
    }

    // --- Data records ---

    public record ScoredCapability(
        String id,
        String name,
        String cognitiveLayer,
        String source,
        String protocol,
        String content,
        String tags,
        float trustScore,
        String verificationStatus,
        float score
    ) {}
}
