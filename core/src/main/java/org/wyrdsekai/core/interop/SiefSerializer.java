package org.wyrdsekai.core.interop;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SoulItem Exchange Format (SIEF) serializer (§97.3).
 * Standard JSON format for exchanging soul items between platforms.
 * <p>
 * Properties:
 * - JSON, human-readable
 * - Signature optional (unsigned from non-Wyrdsekai sources accepted with low trust)
 * - Embedding optional (receiver can re-embed)
 * - Content is plain text (not prompt-formatted)
 * - Forward-compatible (unknown fields ignored)
 */
public class SiefSerializer {

    /** SIEF v1.0 item format. */
    public record SiefItem(
        @JsonProperty("sief_version") String siefVersion,
        @JsonProperty("type") String type,
        @JsonProperty("label") String label,
        @JsonProperty("content") String content,
        @JsonProperty("creator") SiefCreator creator,
        @JsonProperty("metadata") SiefMetadata metadata
    ) {
        @JsonCreator
        public SiefItem {}
    }

    public record SiefCreator(
        @JsonProperty("did") String did,
        @JsonProperty("platform") String platform,
        @JsonProperty("signature") String signature
    ) {
        @JsonCreator
        public SiefCreator {}
    }

    public record SiefMetadata(
        @JsonProperty("created") Instant created,
        @JsonProperty("significance") double significance,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("embedding_model") String embeddingModel,
        @JsonProperty("embedding") List<Double> embedding
    ) {
        @JsonCreator
        public SiefMetadata {}
    }

    /** Current SIEF version. */
    public static final String VERSION = "1.0";

    /**
     * Create a SIEF item from internal data.
     *
     * @param type         item type (memory, skill, relationship, etc.)
     * @param label        human-readable label
     * @param content      plain text content
     * @param creatorDid   creator's DID
     * @param significance significance score 0.0-1.0
     * @param tags         item tags
     * @return SIEF item
     */
    public SiefItem serialize(String type, String label, String content,
                               String creatorDid, double significance,
                               List<String> tags) {
        return new SiefItem(
            VERSION, type, label, content,
            new SiefCreator(creatorDid, "wyrdsekai", null),
            new SiefMetadata(Instant.now(), significance,
                tags != null ? List.copyOf(tags) : List.of(),
                null, null)
        );
    }

    /**
     * Create a signed SIEF item.
     */
    public SiefItem serializeSigned(String type, String label, String content,
                                     String creatorDid, double significance,
                                     List<String> tags, String signatureBase64) {
        return new SiefItem(
            VERSION, type, label, content,
            new SiefCreator(creatorDid, "wyrdsekai", signatureBase64),
            new SiefMetadata(Instant.now(), significance,
                tags != null ? List.copyOf(tags) : List.of(),
                null, null)
        );
    }

    /**
     * Validate a SIEF item.
     *
     * @return list of issues (empty = valid)
     */
    public static List<String> validate(SiefItem item) {
        var issues = new ArrayList<String>();
        if (item.siefVersion() == null || !item.siefVersion().startsWith("1.")) {
            issues.add("Unsupported SIEF version: " + item.siefVersion());
        }
        if (item.type() == null || item.type().isBlank()) {
            issues.add("Missing item type");
        }
        if (item.content() == null || item.content().isBlank()) {
            issues.add("Missing content");
        }
        if (item.creator() == null || item.creator().did() == null) {
            issues.add("Missing creator DID");
        }
        if (item.metadata() != null) {
            if (item.metadata().significance() < 0 || item.metadata().significance() > 1.0) {
                issues.add("Significance out of range [0, 1]: " + item.metadata().significance());
            }
        }
        return issues;
    }

    /**
     * Determine trust level based on SIEF item properties.
     */
    public static ImportTrust assessTrust(SiefItem item) {
        if (item.creator() == null) return ImportTrust.MINIMAL;
        if (item.creator().did() == null || item.creator().did().isBlank()) return ImportTrust.MINIMAL;

        boolean isSigned = item.creator().signature() != null
            && !item.creator().signature().isBlank();
        boolean isWyrdsekai = "wyrdsekai".equals(item.creator().platform());

        if (isWyrdsekai && isSigned) return ImportTrust.MEDIUM;
        if (isSigned) return ImportTrust.LOW;
        return ImportTrust.MINIMAL;
    }

    public enum ImportTrust {
        /** Signed, verified lineage — full trust. Handled by locker, not SIEF. */
        FULL(1.0),
        /** Signed, verified DID, same household. */
        HIGH(0.8),
        /** Signed, unknown DID, Wyrdsekai platform. */
        MEDIUM(0.5),
        /** Signed, external platform. */
        LOW(0.3),
        /** Unsigned or anonymous. */
        MINIMAL(0.1);

        private final double significanceCap;
        ImportTrust(double cap) { this.significanceCap = cap; }
        public double significanceCap() { return significanceCap; }
    }
}
