package org.wyrdsekai.core.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Release manifest for the mesh update protocol.
 * Served by release channels and by peer nodes.
 * Signed with Ed25519 by the release signing key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseManifest(
    String version,
    int wireProtocol,
    String buildHash,
    Instant buildTimestamp,
    String minVersion,
    Map<String, PackageInfo> packages,
    String changelog,
    boolean breaking,
    String signature
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /**
     * Package info for a platform-specific or universal package.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackageInfo(
        String url,
        String sha256,
        long size
    ) {}

    /**
     * Verify this manifest's Ed25519 signature against a release public key.
     *
     * @param releasePublicKeyBase64 Base64-encoded Ed25519 public key
     * @return true if signature is valid
     */
    public boolean verify(String releasePublicKeyBase64) {
        if (signature == null || signature.isEmpty()) return false;
        try {
            var sigData = signingData();
            var sigBytes = Base64.getDecoder().decode(signature);
            var keyBytes = Base64.getDecoder().decode(releasePublicKeyBase64);

            var keyFactory = KeyFactory.getInstance("Ed25519");
            var pubKeySpec = new EdECPublicKeySpec(
                NamedParameterSpec.ED25519,
                decodeEdPoint(keyBytes));
            var publicKey = keyFactory.generatePublic(pubKeySpec);

            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(sigData);
            return verifier.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sign this manifest with an Ed25519 private key, returning a new manifest with signature.
     */
    public ReleaseManifest sign(PrivateKey privateKey) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(signingData());
            var sig = Base64.getEncoder().encodeToString(signer.sign());
            return new ReleaseManifest(version, wireProtocol, buildHash, buildTimestamp,
                minVersion, packages, changelog, breaking, sig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign manifest", e);
        }
    }

    /**
     * Check if this version can upgrade from the given current version.
     * Returns false if currentVersion is below minVersion.
     */
    public boolean canUpgradeFrom(String currentVersion) {
        if (minVersion == null || minVersion.isEmpty()) return true;
        return compareVersions(currentVersion, minVersion) >= 0;
    }

    /**
     * Check if this is a newer version than the given current version.
     */
    public boolean isNewerThan(String currentVersion) {
        return compareVersions(version, currentVersion) > 0;
    }

    /**
     * Serialize to JSON bytes.
     */
    public byte[] toBytes() {
        try {
            return MAPPER.writeValueAsBytes(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize manifest", e);
        }
    }

    /**
     * Deserialize from JSON bytes.
     */
    public static ReleaseManifest fromBytes(byte[] data) {
        try {
            return MAPPER.readValue(data, ReleaseManifest.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize manifest", e);
        }
    }

    /**
     * Deserialize from JSON string.
     */
    public static ReleaseManifest fromJson(String json) {
        try {
            return MAPPER.readValue(json, ReleaseManifest.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse manifest: " + e.getMessage(), e);
        }
    }

    // --- Internal ---

    private byte[] signingData() {
        // Sign everything except the signature field itself
        var data = version + ":" + wireProtocol + ":" + buildHash + ":"
            + (buildTimestamp != null ? buildTimestamp.toString() : "")
            + ":" + (minVersion != null ? minVersion : "")
            + ":" + (changelog != null ? changelog : "")
            + ":" + breaking;
        return data.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Simple semver comparison (major.minor.patch). Ignores pre-release suffixes.
     * Returns negative if a < b, 0 if equal, positive if a > b.
     */
    public static int compareVersions(String a, String b) {
        var aParts = stripSuffix(a).split("\\.");
        var bParts = stripSuffix(b).split("\\.");
        for (int i = 0; i < Math.max(aParts.length, bParts.length); i++) {
            int aVal = i < aParts.length ? parseIntSafe(aParts[i]) : 0;
            int bVal = i < bParts.length ? parseIntSafe(bParts[i]) : 0;
            if (aVal != bVal) return Integer.compare(aVal, bVal);
        }
        return 0;
    }

    private static String stripSuffix(String version) {
        if (version == null) return "0";
        int dash = version.indexOf('-');
        return dash >= 0 ? version.substring(0, dash) : version;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static EdECPoint decodeEdPoint(byte[] keyBytes) {
        // Ed25519 public keys are 32 bytes. The high bit of the last byte is the sign.
        boolean xOdd = (keyBytes[keyBytes.length - 1] & 0x80) != 0;
        keyBytes[keyBytes.length - 1] &= 0x7F; // clear sign bit
        // Reverse for big-endian
        var reversed = new byte[keyBytes.length];
        for (int i = 0; i < keyBytes.length; i++) {
            reversed[i] = keyBytes[keyBytes.length - 1 - i];
        }
        return new EdECPoint(xOdd, new BigInteger(1, reversed));
    }
}
