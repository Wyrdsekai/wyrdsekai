package org.wyrdsekai.core.agent.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Emergent agent argot codec (research §9E).
 * Encodes/decodes opaque agent messages using a zone-private vocabulary.
 * The encoding is injection-resistant by design — human-readable text is transformed
 * into tokens that only agents sharing the same codebook can interpret.
 */
public class ArgotCodec {

    /** A codebook mapping concepts to opaque tokens. */
    public record Codebook(
        String zoneId,
        Map<String, String> conceptToToken,
        Map<String, String> tokenToConcept,
        int version
    ) {
        public int size() { return conceptToToken.size(); }
    }

    /** Encoded message with metadata. */
    public record EncodedMessage(
        String originalText,
        String encodedText,
        String zoneId,
        int tokensUsed,
        int codebookVersion
    ) {}

    /** Decoded message with confidence. */
    public record DecodedMessage(
        String encodedText,
        String decodedText,
        double confidence,
        int tokensDecoded,
        int unknownTokens
    ) {}

    private final Map<String, Codebook> codebooks = new LinkedHashMap<>();

    /**
     * Generate a codebook for a zone from a seed (deterministic for reproducibility).
     */
    public Codebook generateCodebook(String zoneId, List<String> concepts, String seed) {
        var conceptToToken = new LinkedHashMap<String, String>();
        var tokenToConcept = new LinkedHashMap<String, String>();

        for (var concept : concepts) {
            var token = generateToken(zoneId, concept, seed);
            conceptToToken.put(concept.toLowerCase(), token);
            tokenToConcept.put(token, concept.toLowerCase());
        }

        var codebook = new Codebook(zoneId, Map.copyOf(conceptToToken),
            Map.copyOf(tokenToConcept), 1);
        codebooks.put(zoneId, codebook);
        return codebook;
    }

    /**
     * Extend an existing zone codebook with new concepts (the living lexicon, ).
     * Tokens stay deterministic (same concept+zone+seed → same token), so every same-zone agent
     * that promotes the same concept computes the identical token — comprehension stays free, no
     * sync needed. Version increments iff at least one new concept was added. Idempotent: promoting
     * a concept already in the codebook is a no-op (returns the existing codebook unchanged).
     */
    public Codebook extendCodebook(String zoneId, Collection<String> newConcepts, String seed) {
        var existing = codebooks.get(zoneId);
        if (existing == null) {
            return generateCodebook(zoneId, new ArrayList<>(newConcepts), seed);
        }
        var conceptToToken = new LinkedHashMap<>(existing.conceptToToken());
        var tokenToConcept = new LinkedHashMap<>(existing.tokenToConcept());
        int added = 0;
        for (var concept : newConcepts) {
            var c = concept.toLowerCase();
            if (conceptToToken.containsKey(c)) continue;
            var token = generateToken(zoneId, c, seed);
            conceptToToken.put(c, token);
            tokenToConcept.put(token, c);
            added++;
        }
        if (added == 0) return existing;
        var updated = new Codebook(zoneId, Map.copyOf(conceptToToken),
            Map.copyOf(tokenToConcept), existing.version() + 1);
        codebooks.put(zoneId, updated);
        return updated;
    }

    /**
     * Encode a message using the zone's codebook.
     * Words matching concepts are replaced with opaque tokens.
     */
    public EncodedMessage encode(String zoneId, String text) {
        var codebook = codebooks.get(zoneId);
        if (codebook == null) {
            return new EncodedMessage(text, text, zoneId, 0, 0);
        }

        var words = text.split("\\s+");
        var encoded = new StringBuilder();
        int tokensUsed = 0;

        for (var word : words) {
            var token = codebook.conceptToToken().get(word.toLowerCase());
            if (token != null) {
                encoded.append(token);
                tokensUsed++;
            } else {
                encoded.append(word);
            }
            encoded.append(" ");
        }

        return new EncodedMessage(text, encoded.toString().trim(), zoneId,
            tokensUsed, codebook.version());
    }

    /**
     * Decode an encoded message using the zone's codebook.
     */
    public DecodedMessage decode(String zoneId, String encodedText) {
        var codebook = codebooks.get(zoneId);
        if (codebook == null) {
            return new DecodedMessage(encodedText, encodedText, 0.0, 0, 0);
        }

        var words = encodedText.split("\\s+");
        var decoded = new StringBuilder();
        int tokensDecoded = 0;
        int unknownTokens = 0;

        for (var word : words) {
            var concept = codebook.tokenToConcept().get(word);
            if (concept != null) {
                decoded.append(concept);
                tokensDecoded++;
            } else {
                decoded.append(word);
                // Check if it looks like a token but wasn't decodable
                if (word.startsWith("§") && word.length() > 3) {
                    unknownTokens++;
                }
            }
            decoded.append(" ");
        }

        double confidence = words.length > 0
            ? (double) tokensDecoded / words.length
            : 0.0;

        return new DecodedMessage(encodedText, decoded.toString().trim(),
            confidence, tokensDecoded, unknownTokens);
    }

    /** Get a codebook for a zone. */
    public Optional<Codebook> getCodebook(String zoneId) {
        return Optional.ofNullable(codebooks.get(zoneId));
    }

    /** Number of registered codebooks. */
    public int codebookCount() {
        return codebooks.size();
    }

    /**
     * Generate a deterministic opaque token from zone + concept + seed.
     * Tokens are prefixed with § to distinguish them from natural language.
     */
    private static String generateToken(String zoneId, String concept, String seed) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update((zoneId + ":" + concept + ":" + seed).getBytes(StandardCharsets.UTF_8));
            var hash = md.digest();
            // Take first 4 bytes as hex, prefix with §
            return "§" + String.format("%02x%02x%02x%02x", hash[0], hash[1], hash[2], hash[3]);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
