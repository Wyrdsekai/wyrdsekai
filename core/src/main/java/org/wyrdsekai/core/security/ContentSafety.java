package org.wyrdsekai.core.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content safety service (§15).
 * Provides hash-based matching for known harmful content (CSAM, exploit images).
 * <p>
 * Design: hash matching only — no ML classification in core.
 * External hash databases (PhotoDNA, NCMEC) feed the blocklist.
 * <p>
 * M2 scope: SHA-256 hash matching + blocklist management.
 * M3+: Perceptual hash matching (pHash/dHash), external service integration.
 */
public class ContentSafety {

    /** Result of a content safety check. */
    public record CheckResult(boolean blocked, String reason) {
        public static final CheckResult SAFE = new CheckResult(false, null);
    }

    /** A blocklist entry with metadata. */
    public record BlocklistEntry(String hash, String source, String category) {}

    private final Set<String> blockedHashes = ConcurrentHashMap.newKeySet();

    /** Add a hash to the blocklist. */
    public void addBlockedHash(String hash) {
        blockedHashes.add(hash.toLowerCase());
    }

    /** Add a hash with metadata (hash is stored lowercase). */
    public void addBlockedEntry(BlocklistEntry entry) {
        blockedHashes.add(entry.hash().toLowerCase());
    }

    /** Remove a hash from the blocklist. */
    public boolean removeBlockedHash(String hash) {
        return blockedHashes.remove(hash.toLowerCase());
    }

    /** Check content bytes against the blocklist. */
    public CheckResult checkContent(byte[] content) {
        var hash = sha256(content);
        if (blockedHashes.contains(hash)) {
            return new CheckResult(true, "Content matches known harmful hash");
        }
        return CheckResult.SAFE;
    }

    /** Check a pre-computed hash against the blocklist. */
    public CheckResult checkHash(String hash) {
        if (blockedHashes.contains(hash.toLowerCase())) {
            return new CheckResult(true, "Hash matches known harmful content");
        }
        return CheckResult.SAFE;
    }

    /** Number of hashes in the blocklist. */
    public int blocklistSize() {
        return blockedHashes.size();
    }

    /** Compute SHA-256 hash of content bytes. */
    public static String sha256(byte[] content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(content);
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
