package org.wyrdsekai.core.i18n;

import java.util.*;

/**
 * Multilingual embedding configuration (§104.5).
 * Mandatory multilingual model for cross-language memory retrieval.
 * Accept 30-50 point cross-lingual retrieval degradation as tolerable —
 * worse retrieval is better than losing cultural-cognitive texture.
 *
 * Key principle: memories stay in origin language, embeddings bridge
 * the gap during retrieval. No translation pipeline.
 */
public record MultilingualEmbeddingConfig(
    String modelName,
    int dimensions,
    int maxTokens,
    Set<String> supportedLanguages,
    double crossLingualDegradation,
    boolean normalizeEmbeddings
) {

    /** Default: multilingual-e5-large — best quality/size tradeoff. */
    public static MultilingualEmbeddingConfig defaultConfig() {
        return new MultilingualEmbeddingConfig(
            "multilingual-e5-large",
            1024, 512,
            Set.of("en", "es", "ja", "fr", "de", "zh", "ko", "ar", "pt", "ru"),
            0.35, true
        );
    }

    /** Alternative: BGE-M3 — smaller, still multilingual. */
    public static MultilingualEmbeddingConfig bgeM3() {
        return new MultilingualEmbeddingConfig(
            "bge-m3",
            1024, 8192,
            Set.of("en", "es", "ja", "fr", "de", "zh", "ko", "ar", "pt", "ru"),
            0.40, true
        );
    }

    /** Phone-optimized: all-minilm multilingual variant. */
    public static MultilingualEmbeddingConfig phoneConfig() {
        return new MultilingualEmbeddingConfig(
            "paraphrase-multilingual-MiniLM-L12-v2",
            384, 256,
            Set.of("en", "es", "ja", "fr", "de", "zh", "ko"),
            0.45, true
        );
    }

    /**
     * Configuration matching the model actually bundled in core resources
     * (see {@code EmbeddingService}). Single source of truth for code paths
     * that need to reason about the in-process embedder's properties —
     * supported languages, dimensions, max tokens.
     *
     * <p>If you swap the bundled ONNX, update both this factory and
     * {@code EmbeddingService.MODEL_VERSION} together.
     */
    public static MultilingualEmbeddingConfig inUse() {
        // Currently identical to phoneConfig() — same dimensionality (384) and
        // the same paraphrase-multilingual-MiniLM-L12-v2 backbone — but the
        // factory is split so callers asking "what's loaded right now" don't
        // have to hard-code an alias to a phone-shaped config.
        return phoneConfig();
    }

    /** Whether a language is supported for cross-lingual retrieval. */
    public boolean supportsLanguage(String langCode) {
        return supportedLanguages.contains(langCode);
    }

    /** Estimated retrieval quality for cross-lingual query. */
    public double estimatedQuality(String queryLang, String documentLang) {
        if (queryLang.equals(documentLang)) return 1.0;
        if (!supportsLanguage(queryLang) || !supportsLanguage(documentLang)) return 0.3;
        return 1.0 - crossLingualDegradation;
    }

    public int supportedLanguageCount() { return supportedLanguages.size(); }
}
