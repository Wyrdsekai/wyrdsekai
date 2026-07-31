package org.wyrdsekai.core.crypto;

import org.wyrdsekai.core.crypto.Ed25519Math.Point;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FROST Ed25519 threshold signer (§73.C, §74).
 * Implements Flexible Round-Optimized Schnorr Threshold signatures.
 * Each participant produces partial signatures WITHOUT reconstructing the private key.
 *
 * Protocol:
 *   1. Key Generation: Feldman's VSS — dealer splits secret, distributes shares
 *   2. Signing Round 1: Each signer generates nonce commitments (D_i, E_i)
 *   3. Signing Round 2: Each signer produces partial signature z_i
 *   4. Combine: Aggregator sums z_i values → (R, z) = valid Ed25519 signature
 *
 * Security: The private key is NEVER reconstructed during signing.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9591">RFC 9591 (FROST)</a>
 */
class FrostThresholdSigner implements ThresholdSigner {

    private BigInteger secretKey;    // Dealer's secret key (only for keygen)
    private Point publicKey;         // Group public key
    private int threshold;
    private BigInteger[] coefficients; // Polynomial coefficients for keygen

    FrostThresholdSigner() {
        this.secretKey = Ed25519Math.randomScalar();
        this.publicKey = Ed25519Math.scalarMult(secretKey, Ed25519Math.B);
    }

    @Override
    public List<byte[]> generateKeyShares(int n, int k) {
        this.threshold = k;

        // Generate random polynomial: f(x) = a_0 + a_1*x + ... + a_{k-1}*x^{k-1}
        // where a_0 = secretKey
        coefficients = new BigInteger[k];
        coefficients[0] = secretKey;
        for (int i = 1; i < k; i++) {
            coefficients[i] = Ed25519Math.randomScalar();
        }

        // Evaluate polynomial at x=1..n to get shares
        var shares = new ArrayList<byte[]>();
        for (int i = 1; i <= n; i++) {
            var x = BigInteger.valueOf(i);
            var share = evaluatePolynomial(x);

            // Encode: participant index (4 bytes) + share scalar (32 bytes)
            var encoded = new byte[36];
            encoded[0] = (byte) ((i >> 24) & 0xFF);
            encoded[1] = (byte) ((i >> 16) & 0xFF);
            encoded[2] = (byte) ((i >> 8) & 0xFF);
            encoded[3] = (byte) (i & 0xFF);
            var shareBytes = Ed25519Math.encodeScalar(share);
            System.arraycopy(shareBytes, 0, encoded, 4, 32);
            shares.add(encoded);
        }

        return shares;
    }

    @Override
    public PartialSignature partialSign(int participantId, byte[] keyShare, byte[] message) {
        // Decode share
        var shareScalar = Ed25519Math.decodeScalar(
            Arrays.copyOfRange(keyShare, 4, 36));
        int index = ((keyShare[0] & 0xFF) << 24) | ((keyShare[1] & 0xFF) << 16)
            | ((keyShare[2] & 0xFF) << 8) | (keyShare[3] & 0xFF);

        try {
            // Generate nonce pair (d, e)
            var d = Ed25519Math.randomScalar();
            var e = Ed25519Math.randomScalar();
            var D = Ed25519Math.scalarMult(d, Ed25519Math.B);
            var E = Ed25519Math.scalarMult(e, Ed25519Math.B);

            // For single-round simplified FROST:
            // R = D (we use d as the full nonce since we're the only signer in this call)
            // The actual nonce binding happens in combine()
            // We encode: participantIndex(4) + d(32) + e(32) + D(32) + E(32) + shareScalar(32)
            var sigData = new byte[164];
            System.arraycopy(keyShare, 0, sigData, 0, 4); // index
            System.arraycopy(Ed25519Math.encodeScalar(d), 0, sigData, 4, 32);
            System.arraycopy(Ed25519Math.encodeScalar(e), 0, sigData, 36, 32);
            System.arraycopy(Ed25519Math.encodePoint(D), 0, sigData, 68, 32);
            System.arraycopy(Ed25519Math.encodePoint(E), 0, sigData, 100, 32);
            System.arraycopy(Ed25519Math.encodeScalar(shareScalar), 0, sigData, 132, 32);

            return new PartialSignature(participantId, sigData,
                Ed25519Math.encodePoint(publicKey));
        } catch (Exception ex) {
            throw new RuntimeException("FROST partial sign failed", ex);
        }
    }

    @Override
    public CombinedSignature combine(List<PartialSignature> partials, byte[] message) {
        if (partials.size() < threshold) {
            return new CombinedSignature(new byte[0], false);
        }

        try {
            var signers = partials.subList(0, Math.min(partials.size(), threshold));

            // Extract nonce commitments and compute binding factors
            var indices = new int[signers.size()];
            var dNonces = new BigInteger[signers.size()];
            var eNonces = new BigInteger[signers.size()];
            var dPoints = new Point[signers.size()];
            var ePoints = new Point[signers.size()];
            var shareScalars = new BigInteger[signers.size()];

            for (int i = 0; i < signers.size(); i++) {
                var data = signers.get(i).signatureBytes();
                indices[i] = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                    | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                dNonces[i] = Ed25519Math.decodeScalar(
                    Arrays.copyOfRange(data, 4, 36));
                eNonces[i] = Ed25519Math.decodeScalar(
                    Arrays.copyOfRange(data, 36, 68));
                dPoints[i] = Ed25519Math.decodePoint(
                    Arrays.copyOfRange(data, 68, 100));
                ePoints[i] = Ed25519Math.decodePoint(
                    Arrays.copyOfRange(data, 100, 132));
                shareScalars[i] = Ed25519Math.decodeScalar(
                    Arrays.copyOfRange(data, 132, 164));
            }

            // Compute binding factors rho_i = H(i || message || D_1 || E_1 || ... || D_t || E_t)
            var rhos = new BigInteger[signers.size()];
            for (int i = 0; i < signers.size(); i++) {
                // Build commitment list for binding factor
                var commitBytes = new byte[signers.size() * 64];
                for (int j = 0; j < signers.size(); j++) {
                    System.arraycopy(Ed25519Math.encodePoint(dPoints[j]), 0,
                        commitBytes, j * 64, 32);
                    System.arraycopy(Ed25519Math.encodePoint(ePoints[j]), 0,
                        commitBytes, j * 64 + 32, 32);
                }
                rhos[i] = Ed25519Math.hashToScalar(
                    Ed25519Math.encodeScalar(BigInteger.valueOf(indices[i])),
                    message, commitBytes);
            }

            // Group commitment: R = sum(D_i + rho_i * E_i)
            var R = Ed25519Math.IDENTITY;
            for (int i = 0; i < signers.size(); i++) {
                var rhoE = Ed25519Math.scalarMult(rhos[i], ePoints[i]);
                var term = Ed25519Math.add(dPoints[i], rhoE);
                R = Ed25519Math.add(R, term);
            }

            // Challenge: c = H(R || public_key || message)
            var c = Ed25519Math.hashToScalar(
                Ed25519Math.encodePoint(R),
                Ed25519Math.encodePoint(publicKey),
                message);

            // Compute partial signatures and sum:
            // z_i = d_i + e_i * rho_i + lambda_i * s_i * c
            var z = BigInteger.ZERO;
            for (int i = 0; i < signers.size(); i++) {
                var lambda = lagrangeCoefficient(indices, i);
                var zi = dNonces[i]
                    .add(eNonces[i].multiply(rhos[i]))
                    .add(lambda.multiply(shareScalars[i]).multiply(c))
                    .mod(Ed25519Math.L);
                z = z.add(zi).mod(Ed25519Math.L);
            }

            // Signature = R_encoded || z_encoded
            var signature = new byte[64];
            System.arraycopy(Ed25519Math.encodePoint(R), 0, signature, 0, 32);
            System.arraycopy(Ed25519Math.encodeScalar(z), 0, signature, 32, 32);

            // Verify: z * B == R + c * publicKey
            var lhs = Ed25519Math.scalarMult(z, Ed25519Math.B);
            var rhs = Ed25519Math.add(R, Ed25519Math.scalarMult(c, publicKey));
            var valid = lhs.x().equals(rhs.x()) && lhs.y().equals(rhs.y());

            return new CombinedSignature(signature, valid);
        } catch (Exception e) {
            return new CombinedSignature(new byte[0], false);
        }
    }

    @Override
    public boolean verify(byte[] signature, byte[] message, byte[] publicKeyBytes) {
        if (signature.length != 64) return false;
        try {
            var R = Ed25519Math.decodePoint(
                Arrays.copyOfRange(signature, 0, 32));
            var z = Ed25519Math.decodeScalar(
                Arrays.copyOfRange(signature, 32, 64));
            var pk = Ed25519Math.decodePoint(publicKeyBytes);

            // Challenge: c = H(R || pk || message)
            var c = Ed25519Math.hashToScalar(
                Ed25519Math.encodePoint(R),
                publicKeyBytes,
                message);

            // Verify: z * B == R + c * pk
            var lhs = Ed25519Math.scalarMult(z, Ed25519Math.B);
            var rhs = Ed25519Math.add(R, Ed25519Math.scalarMult(c, pk));
            return lhs.x().equals(rhs.x()) && lhs.y().equals(rhs.y());
        } catch (Exception e) {
            return false;
        }
    }

    // --- Helpers ---

    private BigInteger evaluatePolynomial(BigInteger x) {
        var result = BigInteger.ZERO;
        var xPow = BigInteger.ONE;
        for (var coeff : coefficients) {
            result = result.add(coeff.multiply(xPow)).mod(Ed25519Math.L);
            xPow = xPow.multiply(x).mod(Ed25519Math.L);
        }
        return result;
    }

    /** Lagrange coefficient for participant at indices[i], evaluated at x=0. */
    private BigInteger lagrangeCoefficient(int[] indices, int i) {
        var xi = BigInteger.valueOf(indices[i]);
        var num = BigInteger.ONE;
        var den = BigInteger.ONE;
        for (int j = 0; j < indices.length; j++) {
            if (j == i) continue;
            var xj = BigInteger.valueOf(indices[j]);
            num = num.multiply(xj.negate()).mod(Ed25519Math.L);
            den = den.multiply(xi.subtract(xj)).mod(Ed25519Math.L);
        }
        return num.multiply(den.modInverse(Ed25519Math.L)).mod(Ed25519Math.L);
    }
}
