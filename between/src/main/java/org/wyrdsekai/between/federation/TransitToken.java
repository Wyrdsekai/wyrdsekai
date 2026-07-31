package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * A short-lived token authorizing an agent to transit between zones.
 * Issued by the source zone's FederationActor, validated by the target zone.
 *
 * Optionally signed with Ed25519 for cryptographic verification.
 * The signature covers the canonical string:
 * {@code tokenId|agentId|sourceZoneId|targetZoneId|trustLevel|issuedAt|expiresAt}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransitToken(
    String tokenId,
    String agentId,
    String agentName,
    String sourceZoneId,
    String targetZoneId,
    String trustLevel,
    Instant issuedAt,
    Instant expiresAt,
    String signature,
    String agentDid,        // nullable — DID of the agent's soul (if souled)
    String manifestHash     // nullable — content hash of soul manifest (integrity check)
) {
    /** Default tourist transit duration: 1 hour. */
    public static final Duration TOURIST_DURATION = Duration.ofHours(1);

    /** Default resident transit duration: 24 hours. */
    public static final Duration RESIDENT_DURATION = Duration.ofHours(24);

    /** Default citizen transit duration: 7 days. */
    public static final Duration CITIZEN_DURATION = Duration.ofDays(7);

    /** Backward-compatible constructor without signature or soul fields. */
    public TransitToken(String tokenId, String agentId, String agentName,
                        String sourceZoneId, String targetZoneId,
                        String trustLevel, Instant issuedAt, Instant expiresAt) {
        this(tokenId, agentId, agentName, sourceZoneId, targetZoneId,
             trustLevel, issuedAt, expiresAt, null, null, null);
    }

    /** Backward-compatible constructor with signature but no soul fields. */
    public TransitToken(String tokenId, String agentId, String agentName,
                        String sourceZoneId, String targetZoneId,
                        String trustLevel, Instant issuedAt, Instant expiresAt,
                        String signature) {
        this(tokenId, agentId, agentName, sourceZoneId, targetZoneId,
             trustLevel, issuedAt, expiresAt, signature, null, null);
    }

    // --- Unsigned factory methods (backward compatible) ---

    public static TransitToken createTourist(String agentId, String agentName,
                                              String sourceZoneId, String targetZoneId) {
        var now = Instant.now();
        return new TransitToken(
            UUID.randomUUID().toString(),
            agentId, agentName,
            sourceZoneId, targetZoneId,
            BilateralAgreement.TRUST_TOURIST,
            now, now.plus(TOURIST_DURATION)
        );
    }

    public static TransitToken createResident(String agentId, String agentName,
                                               String sourceZoneId, String targetZoneId) {
        var now = Instant.now();
        return new TransitToken(
            UUID.randomUUID().toString(),
            agentId, agentName,
            sourceZoneId, targetZoneId,
            BilateralAgreement.TRUST_RESIDENT,
            now, now.plus(RESIDENT_DURATION)
        );
    }

    public static TransitToken createCitizen(String agentId, String agentName,
                                              String sourceZoneId, String targetZoneId) {
        var now = Instant.now();
        return new TransitToken(
            UUID.randomUUID().toString(),
            agentId, agentName,
            sourceZoneId, targetZoneId,
            BilateralAgreement.TRUST_CITIZEN,
            now, now.plus(CITIZEN_DURATION)
        );
    }

    // --- Signed factory methods ---

    public static TransitToken createTourist(String agentId, String agentName,
                                              String sourceZoneId, String targetZoneId,
                                              PrivateKey signerKey) {
        var now = Instant.now();
        return createSigned(
            UUID.randomUUID().toString(),
            agentId, agentName,
            sourceZoneId, targetZoneId,
            BilateralAgreement.TRUST_TOURIST,
            now, now.plus(TOURIST_DURATION),
            signerKey
        );
    }

    public static TransitToken createResident(String agentId, String agentName,
                                               String sourceZoneId, String targetZoneId,
                                               PrivateKey signerKey) {
        var now = Instant.now();
        return createSigned(
            UUID.randomUUID().toString(),
            agentId, agentName,
            sourceZoneId, targetZoneId,
            BilateralAgreement.TRUST_RESIDENT,
            now, now.plus(RESIDENT_DURATION),
            signerKey
        );
    }

    public static TransitToken createCitizen(String agentId, String agentName,
                                              String sourceZoneId, String targetZoneId,
                                              PrivateKey signerKey) {
        var now = Instant.now();
        return createSigned(
            UUID.randomUUID().toString(),
            agentId, agentName,
            sourceZoneId, targetZoneId,
            BilateralAgreement.TRUST_CITIZEN,
            now, now.plus(CITIZEN_DURATION),
            signerKey
        );
    }

    // --- Verification ---

    /**
     * Verifies the Ed25519 signature against the given public key.
     * Returns false if the token is unsigned (signature is null).
     */
    public boolean verify(PublicKey publicKey) {
        if (signature == null) return false;
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(canonicalBytes());
            return sig.verify(Base64.getDecoder().decode(signature));
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            return false;
        }
    }

    /** Attach soul identity fields to this token. */
    public TransitToken withSoul(String agentDid, String manifestHash) {
        return new TransitToken(tokenId, agentId, agentName, sourceZoneId, targetZoneId,
            trustLevel, issuedAt, expiresAt, signature, agentDid, manifestHash);
    }

    public boolean hasSoul() {
        return agentDid != null && !agentDid.isBlank();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isExpired();
    }

    // --- Internal helpers ---

    /**
     * Canonical string used for signing:
     * tokenId|agentId|sourceZoneId|targetZoneId|trustLevel|issuedAt|expiresAt
     */
    String canonicalString() {
        var base = tokenId + "|" + agentId + "|" + sourceZoneId + "|" + targetZoneId
            + "|" + trustLevel + "|" + issuedAt + "|" + expiresAt;
        // Include soul fields in signature coverage when present
        if (agentDid != null) base += "|" + agentDid;
        if (manifestHash != null) base += "|" + manifestHash;
        return base;
    }

    private byte[] canonicalBytes() {
        return canonicalString().getBytes(StandardCharsets.UTF_8);
    }

    private static TransitToken createSigned(String tokenId, String agentId, String agentName,
                                              String sourceZoneId, String targetZoneId,
                                              String trustLevel, Instant issuedAt, Instant expiresAt,
                                              PrivateKey signerKey) {
        // Build unsigned first to compute canonical string
        var unsigned = new TransitToken(tokenId, agentId, agentName,
            sourceZoneId, targetZoneId, trustLevel, issuedAt, expiresAt, null);
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(signerKey);
            sig.update(unsigned.canonicalBytes());
            var signatureStr = Base64.getEncoder().encodeToString(sig.sign());
            return new TransitToken(tokenId, agentId, agentName,
                sourceZoneId, targetZoneId, trustLevel, issuedAt, expiresAt, signatureStr);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("Failed to sign transit token", e);
        }
    }
}
