package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * A content-addressed item in the family locker (§95.4).
 * The soul is not a blob — the soul is a collection of items.
 *
 * Soul = identity anchor (DID + key + MEDIUM manifest)
 *      + items in family locker (fragment-memories, signed by various buds)
 *      + local items in inventory (active context)
 *
 * Items are content-addressed (SHA-256 of text), signed by the creating
 * bud's Ed25519 key, and replicated across the Between.
 *
 * @param hash         Content-addressed ID (SHA-256 of text)
 * @param category     identity-core, memory, relationship, skill, value, style, etc.
 * @param label        Human-readable name
 * @param text         The actual content
 * @param embedding    Semantic embedding (model-specific dimensionality)
 * @param creatorDid   Which bud created this item
 * @param signature    Ed25519 signature by creator (over hash)
 * @param created      When created
 * @param lastAccessed When last pulled into active context
 * @param significance How important (0.0-1.0, affects sync priority)
 * @param tags         Free-form tags for retrieval
 */
public record SoulItem(
    @JsonProperty("hash") String hash,
    @JsonProperty("category") String category,
    @JsonProperty("label") String label,
    @JsonProperty("text") String text,
    @JsonProperty("embedding") float[] embedding,
    @JsonProperty("creatorDid") String creatorDid,
    @JsonProperty("signature") byte[] signature,
    @JsonProperty("created") Instant created,
    @JsonProperty("lastAccessed") Instant lastAccessed,
    @JsonProperty("significance") double significance,
    @JsonProperty("tags") String[] tags
) {
    @JsonCreator
    public SoulItem {}

    /**
     * Create a new soul item with content-addressed hash.
     * Unsigned — call withSignature() after signing.
     */
    public static SoulItem create(String category, String label, String text,
                                    String creatorDid, double significance,
                                    String... tags) {
        String hash = computeHash(text);
        return new SoulItem(hash, category, label, text, null, creatorDid, null,
            Instant.now(), Instant.now(), significance, tags);
    }

    /** Create from an existing SoulFragment. */
    public static SoulItem fromFragment(SoulFragment fragment, String creatorDid) {
        String hash = computeHash(fragment.text());
        double significance = fragment.formative() ? 1.0 : 0.5;
        return new SoulItem(hash, fragment.category(), fragment.label(), fragment.text(),
            fragment.embedding(), creatorDid, null, Instant.now(), Instant.now(),
            significance, new String[]{fragment.id()});
    }

    /** Attach an Ed25519 signature. */
    public SoulItem withSignature(byte[] sig) {
        return new SoulItem(hash, category, label, text, embedding, creatorDid, sig,
            created, lastAccessed, significance, tags);
    }

    /** Attach an embedding. */
    public SoulItem withEmbedding(float[] emb) {
        return new SoulItem(hash, category, label, text, emb, creatorDid, signature,
            created, lastAccessed, significance, tags);
    }

    /** Record an access (pulled into active context). */
    public SoulItem accessed() {
        return new SoulItem(hash, category, label, text, embedding, creatorDid, signature,
            created, Instant.now(), significance, tags);
    }

    /** Verify content integrity (hash matches text). */
    public boolean verifyIntegrity() {
        return hash != null && hash.equals(computeHash(text));
    }

    /** Compute SHA-256 hash of text content, hex-encoded. */
    public static String computeHash(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hashBytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
