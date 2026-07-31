package org.wyrdsekai.core.crypto;

import java.security.*;
import java.util.List;
import java.util.Optional;

/**
 * Threshold signing interface (§73.C).
 * Pluggable: implementations can use Shamir-based key splitting
 * or FROST (when available for Java).
 */
public interface ThresholdSigner {

    /** A partial signature from one participant. */
    record PartialSignature(
        int participantId,
        byte[] signatureBytes,
        byte[] publicKeyBytes
    ) {}

    /** Result of combining partial signatures. */
    record CombinedSignature(
        byte[] signatureBytes,
        boolean valid
    ) {}

    /** Generate key shares for N participants with threshold K. */
    List<byte[]> generateKeyShares(int n, int k);

    /** Create a partial signature using a key share. */
    PartialSignature partialSign(int participantId, byte[] keyShare, byte[] message);

    /** Combine partial signatures into a full signature. */
    CombinedSignature combine(List<PartialSignature> partials, byte[] message);

    /** Verify a combined signature. */
    boolean verify(byte[] signature, byte[] message, byte[] publicKey);

    /**
     * Simple Shamir-based threshold signer using Ed25519.
     * Splits the private key using Shamir SS, reconstructs for signing.
     * (Not a true threshold signature — each partial requires key reconstruction.)
     */
    static ThresholdSigner shamirBased() {
        return new ShamirThresholdSigner();
    }

    /**
     * FROST Ed25519 threshold signer (RFC 9591).
     * True threshold signatures — the private key is NEVER reconstructed during signing.
     * Each participant produces a partial Schnorr signature; these combine into
     * a valid Ed25519-compatible signature.
     */
    static ThresholdSigner frostBased() {
        return new FrostThresholdSigner();
    }
}
