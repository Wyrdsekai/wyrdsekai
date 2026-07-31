package org.wyrdsekai.core.library;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single chunk of knowledge content, ready for Lucene indexing.
 * One line in a pack's chunks/ JSONL file.
 *
 * @param id          Unique chunk identifier (pack:index, e.g., "simple-wikipedia:4821")
 * @param packName    Source knowledge pack
 * @param title       Chunk title (article title, section heading, etc.)
 * @param content     The actual text content
 * @param source      Original source (URL, book title, etc.)
 * @param sourceUrl   Link to original (nullable)
 * @param license     License for this specific chunk (may differ from pack default)
 * @param subject     LCSH subject terms for this chunk
 * @param embedding   Pre-computed embedding vector (nullable — computed at index time if missing)
 * @param provenance Optional rich provenance. Null for
 *                    legacy bundled packs; treated as {@link Provenance.TrustTier#UNKNOWN}
 *                    by the auto-approve gate.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeChunk(
    String id,
    String packName,
    String title,
    String content,
    String source,
    String sourceUrl,
    String license,
    String[] subject,
    float[] embedding,
    Provenance provenance
) {

    /** Backward-compat canonical constructor (no provenance — defaults to null). */
    public KnowledgeChunk(String id, String packName, String title, String content,
                          String source, String sourceUrl, String license,
                          String[] subject, float[] embedding) {
        this(id, packName, title, content, source, sourceUrl, license, subject, embedding, null);
    }

    /** Create a chunk without pre-computed embedding (will be computed at index time). */
    public static KnowledgeChunk text(String id, String packName, String title,
                                       String content, String source) {
        return new KnowledgeChunk(id, packName, title, content, source,
            null, null, null, null, null);
    }

    /** Create a chunk with full metadata (no provenance). */
    public static KnowledgeChunk full(String id, String packName, String title,
                                       String content, String source, String sourceUrl,
                                       String license, String[] subject, float[] embedding) {
        return new KnowledgeChunk(id, packName, title, content, source, sourceUrl,
            license, subject, embedding, null);
    }

    /** Create a chunk with full metadata + provenance. */
    public static KnowledgeChunk full(String id, String packName, String title,
                                       String content, String source, String sourceUrl,
                                       String license, String[] subject, float[] embedding,
                                       Provenance provenance) {
        return new KnowledgeChunk(id, packName, title, content, source, sourceUrl,
            license, subject, embedding, provenance);
    }

    /** Effective trust tier — UNKNOWN when provenance is missing. */
    public Provenance.TrustTier trustTier() {
        return provenance != null && provenance.trustTier() != null
            ? provenance.trustTier()
            : Provenance.TrustTier.UNKNOWN;
    }
}
