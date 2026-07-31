package org.wyrdsekai.core.identity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Wyrdsekai-specific DID method (§18).
 * Format: did:wyrd:<zone>:<entity-hash>
 * <p>
 * Zone-scoped DIDs enable federation while maintaining identity isolation.
 * The entity-hash is derived from the public key (Ed25519).
 */
public record DidWyrd(String zone, String entityHash) {

    /** The DID method name. */
    public static final String METHOD = "wyrd";

    /** Full DID string. */
    public String toUri() {
        return "did:" + METHOD + ":" + zone + ":" + entityHash;
    }

    /** Parse a DID URI. Returns null if not a valid did:wyrd URI. */
    public static DidWyrd parse(String uri) {
        if (uri == null || !uri.startsWith("did:wyrd:")) return null;
        var parts = uri.split(":");
        if (parts.length != 4) return null;
        return new DidWyrd(parts[2], parts[3]);
    }

    /** Create a DID from a zone and public key bytes. */
    public static DidWyrd fromPublicKey(String zone, byte[] publicKey) {
        var hash = sha256(publicKey);
        // Use first 16 bytes (32 hex chars) for entity hash
        return new DidWyrd(zone, hash.substring(0, 32));
    }

    /** Check if a string is a valid did:wyrd URI. */
    public static boolean isValid(String uri) {
        return parse(uri) != null;
    }

    private static String sha256(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
