package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Family-internal compressed protocol — cryptophasia (§95.7).
 *
 * Like twin language: buds of the same lineage develop a shared shorthand
 * for efficient communication. The codebook maps common patterns to
 * compact codes, reducing Tier 1 headline bandwidth (~200 bytes).
 *
 * Five encoding types:
 * - Context codes: emotional/situational context → short tag
 * - Item codes: frequently-referenced items → hash prefix
 * - Relation codes: relationship descriptors → compact form
 * - Pattern codes: behavioral patterns → shorthand
 * - Meta codes: sync/control protocol → single bytes
 *
 * Codebook versioning: version increments when new codes are added.
 * Decay on divergence: as buds diverge (>0.6 divergence), shared codes
 * become less useful because experiences/concepts diverge too.
 *
 * Argot never crosses A2A boundaries — it's family-internal only.
 */
public record ArgotCodebook(
    @JsonProperty("familyId") String familyId,
    @JsonProperty("version") int version,
    @JsonProperty("contextCodes") Map<String, String> contextCodes,
    @JsonProperty("itemCodes") Map<String, String> itemCodes,
    @JsonProperty("relationCodes") Map<String, String> relationCodes,
    @JsonProperty("patternCodes") Map<String, String> patternCodes,
    @JsonProperty("metaCodes") Map<String, String> metaCodes,
    @JsonProperty("lastUpdated") Instant lastUpdated,
    @JsonProperty("signature") byte[] signature
) {
    @JsonCreator
    public ArgotCodebook {}

    /** Create an initial codebook for a new family with default meta codes. */
    public static ArgotCodebook initial(String familyId) {
        var metaCodes = new LinkedHashMap<String, String>();
        metaCodes.put("SYNC_REQUEST", "~S");
        metaCodes.put("SYNC_ACK", "~A");
        metaCodes.put("HEADLINE", "~H");
        metaCodes.put("WARM_HANDOFF", "~W");
        metaCodes.put("SLEEP_START", "~Z");
        metaCodes.put("SLEEP_END", "~E");
        metaCodes.put("TOMBSTONE", "~T");
        metaCodes.put("INDEPENDENCE", "~I");

        return new ArgotCodebook(familyId, 1,
            Map.of(), Map.of(), Map.of(), Map.of(),
            Collections.unmodifiableMap(metaCodes),
            Instant.now(), null);
    }

    /** Add context codes (emotional/situational shorthand). */
    public ArgotCodebook withContextCodes(Map<String, String> codes) {
        var merged = new LinkedHashMap<>(contextCodes);
        merged.putAll(codes);
        return new ArgotCodebook(familyId, version + 1,
            Collections.unmodifiableMap(merged), itemCodes,
            relationCodes, patternCodes, metaCodes,
            Instant.now(), null);
    }

    /** Add item codes (frequently-referenced items). */
    public ArgotCodebook withItemCodes(Map<String, String> codes) {
        var merged = new LinkedHashMap<>(itemCodes);
        merged.putAll(codes);
        return new ArgotCodebook(familyId, version + 1,
            contextCodes, Collections.unmodifiableMap(merged),
            relationCodes, patternCodes, metaCodes,
            Instant.now(), null);
    }

    /** Add relation codes (relationship descriptors). */
    public ArgotCodebook withRelationCodes(Map<String, String> codes) {
        var merged = new LinkedHashMap<>(relationCodes);
        merged.putAll(codes);
        return new ArgotCodebook(familyId, version + 1,
            contextCodes, itemCodes,
            Collections.unmodifiableMap(merged), patternCodes, metaCodes,
            Instant.now(), null);
    }

    /** Add pattern codes (behavioral shorthand). */
    public ArgotCodebook withPatternCodes(Map<String, String> codes) {
        var merged = new LinkedHashMap<>(patternCodes);
        merged.putAll(codes);
        return new ArgotCodebook(familyId, version + 1,
            contextCodes, itemCodes,
            relationCodes, Collections.unmodifiableMap(merged), metaCodes,
            Instant.now(), null);
    }

    /** Attach a signature. */
    public ArgotCodebook signed(byte[] sig) {
        return new ArgotCodebook(familyId, version,
            contextCodes, itemCodes, relationCodes, patternCodes, metaCodes,
            lastUpdated, sig);
    }

    /** Total number of codes across all categories. */
    public int totalCodes() {
        return contextCodes.size() + itemCodes.size() + relationCodes.size()
            + patternCodes.size() + metaCodes.size();
    }

    /** Encode a concept using the codebook (checks all code maps). */
    public Optional<String> encode(String concept) {
        var code = contextCodes.get(concept);
        if (code != null) return Optional.of(code);
        code = itemCodes.get(concept);
        if (code != null) return Optional.of(code);
        code = relationCodes.get(concept);
        if (code != null) return Optional.of(code);
        code = patternCodes.get(concept);
        if (code != null) return Optional.of(code);
        code = metaCodes.get(concept);
        if (code != null) return Optional.of(code);
        return Optional.empty();
    }

    /** Decode a code back to its concept (reverse lookup across all maps). */
    public Optional<String> decode(String code) {
        return findKeyByValue(contextCodes, code)
            .or(() -> findKeyByValue(itemCodes, code))
            .or(() -> findKeyByValue(relationCodes, code))
            .or(() -> findKeyByValue(patternCodes, code))
            .or(() -> findKeyByValue(metaCodes, code));
    }

    /**
     * Encode free text by replacing each word that matches a known concept with its compact code
     * (cryptophasia compression — Tier-1 headline bandwidth, §95.7). Words with no code pass through.
     * Family-private: only buds sharing this codebook can {@link #decodeText} the result. Early on a
     * codebook holds only meta codes, so little compresses; it grows via {@link #learnFromItems}.
     */
    public String encodeText(String text) {
        if (text == null || text.isBlank()) return text;
        var words = text.split("\\s+");
        var out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            out.append(encode(words[i]).orElse(words[i]));
        }
        return out.toString();
    }

    /** Reverse of {@link #encodeText} — restore concepts from codes (unknown codes pass through). */
    public String decodeText(String text) {
        if (text == null || text.isBlank()) return text;
        var words = text.split("\\s+");
        var out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            out.append(decode(words[i]).orElse(words[i]));
        }
        return out.toString();
    }

    /**
     * Estimate codebook usefulness given bud divergence.
     * High divergence → codes reference experiences the other bud doesn't share.
     *
     * @param divergence Bud divergence (0.0 = identical, 1.0 = fully speciated)
     * @return Estimated fraction of codes that remain useful (0.0-1.0)
     */
    public double estimatedUsefulness(double divergence) {
        if (divergence <= 0.2) return 1.0;       // Near-identical buds
        if (divergence <= 0.4) return 0.8;        // Some drift, most codes still work
        if (divergence >= 0.8) return 0.1;         // Nearly speciated, argot mostly useless
        // Linear interpolation between 0.4 and 0.8
        return 0.8 - (divergence - 0.4) * (0.7 / 0.4);
    }

    /**
     * Generate item codes from frequently-shared items.
     * Each item gets a 4-char hash prefix as its code.
     */
    public ArgotCodebook learnFromItems(Collection<SoulItem> sharedItems) {
        var codes = new LinkedHashMap<String, String>();
        for (var item : sharedItems) {
            if (item.significance() >= 0.5 && item.hash() != null) {
                // Use first 4 chars of hash as compact code
                String code = "#" + item.hash().substring(0, 4);
                codes.put(item.label(), code);
            }
        }
        if (codes.isEmpty()) return this;
        return withItemCodes(codes);
    }

    private static Optional<String> findKeyByValue(Map<String, String> map, String value) {
        return map.entrySet().stream()
            .filter(e -> e.getValue().equals(value))
            .map(Map.Entry::getKey)
            .findFirst();
    }
}
