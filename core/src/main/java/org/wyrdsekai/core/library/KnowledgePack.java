package org.wyrdsekai.core.library;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * OPDS-K knowledge pack metadata (pack.json).
 * Dublin Core base + AI knowledge extensions.
 *
 * @param name        Pack identifier (e.g., "simple-wikipedia")
 * @param title       Human-readable title
 * @param creator     Original content creator/source
 * @param subject     LCSH subject terms
 * @param description Brief description
 * @param publisher   Publisher DID (who packaged it)
 * @param date        Publication/last-update date (ISO 8601)
 * @param language    BCP 47 language tag
 * @param rights      License identifier (e.g., "CC-BY-SA-4.0")
 * @param copyright   Copyright classification (public-domain, cc-by, cc-by-sa, cc-by-nc, fair-use, licensed, non-commercial, copyrighted, unknown)
 * @param contentRating Content rating (general, teen, mature, explicit, restricted)
 * @param jurisdictionNotes Country-specific legal notes
 * @param version     Pack version string
 * @param size        Size estimates (download, indexed)
 * @param chunks      Chunk statistics (count, avgTokens)
 * @param embeddings  Embedding metadata (model, dimensions) — null if not pre-embedded
 * @param collections Target Lucene collections this pack populates
 * @param requires    Required capabilities (e.g., ["whisper"] for audio packs)
 * @param source      Download URL or HuggingFace dataset ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgePack(
    String name,
    String title,
    String creator,
    List<String> subject,
    String description,
    String publisher,
    String date,
    String language,
    String rights,
    String copyright,
    @JsonProperty("contentRating") String contentRating,
    @JsonProperty("jurisdictionNotes") Map<String, String> jurisdictionNotes,
    String version,
    Map<String, String> size,
    ChunkStats chunks,
    EmbeddingMeta embeddings,
    List<String> collections,
    List<String> requires,
    String source
) {

    public record ChunkStats(int count, int avgTokens) {}

    public record EmbeddingMeta(String model, int dimensions) {}

    /** Copyright risk level for UI display. */
    public enum CopyrightLevel {
        GREEN,   // public-domain, cc-by, cc-by-sa — install without warning
        YELLOW,  // cc-by-nc, fair-use, licensed, non-commercial — install with notice
        RED;     // copyrighted, unknown — install with warning

        public static CopyrightLevel fromString(String copyright) {
            if (copyright == null) return RED;
            return switch (copyright.toLowerCase()) {
                case "public-domain", "cc-by", "cc-by-sa" -> GREEN;
                case "cc-by-nc", "fair-use", "licensed", "non-commercial" -> YELLOW;
                default -> RED;
            };
        }
    }

    /** Content rating for policy enforcement. */
    public enum ContentRating {
        GENERAL, TEEN, MATURE, EXPLICIT, RESTRICTED;

        public static ContentRating fromString(String rating) {
            if (rating == null) return GENERAL;
            try {
                return valueOf(rating.toUpperCase());
            } catch (IllegalArgumentException e) {
                return GENERAL;
            }
        }

        public boolean allowedBy(ContentRating maxAllowed) {
            return this.ordinal() <= maxAllowed.ordinal();
        }
    }

    public CopyrightLevel copyrightLevel() {
        return CopyrightLevel.fromString(copyright);
    }

    public ContentRating rating() {
        return ContentRating.fromString(contentRating);
    }
}
