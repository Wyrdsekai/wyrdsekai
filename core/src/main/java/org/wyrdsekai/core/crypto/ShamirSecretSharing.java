package org.wyrdsekai.core.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Shamir's Secret Sharing (§74.11).
 * Pure Java implementation over GF(256) arithmetic.
 * Splits a secret into N shares, any K of which can reconstruct it.
 */
public class ShamirSecretSharing {

    /** A single share of a secret. */
    public record Share(int x, byte[] y) {
        @Override public String toString() {
            return "Share(x=" + x + ", len=" + y.length + ")";
        }
    }

    private static final SecureRandom random = new SecureRandom();

    /**
     * Split a secret into n shares, requiring k to reconstruct.
     * @param secret the secret bytes
     * @param n total number of shares (2-255)
     * @param k threshold for reconstruction (2-n)
     * @return list of n shares
     */
    public static List<Share> split(byte[] secret, int n, int k) {
        if (k < 2 || k > n || n > 255) {
            throw new IllegalArgumentException("Invalid parameters: k=" + k + ", n=" + n);
        }

        var shares = new ArrayList<Share>(n);
        for (int i = 0; i < n; i++) {
            shares.add(new Share(i + 1, new byte[secret.length]));
        }

        for (int byteIdx = 0; byteIdx < secret.length; byteIdx++) {
            // Generate random polynomial coefficients
            // a[0] = secret byte, a[1..k-1] = random
            var coefficients = new int[k];
            coefficients[0] = secret[byteIdx] & 0xFF;
            for (int j = 1; j < k; j++) {
                coefficients[j] = random.nextInt(256);
            }

            // Evaluate polynomial at each share's x value
            for (int i = 0; i < n; i++) {
                int x = i + 1;
                shares.get(i).y()[byteIdx] = (byte) evaluatePolynomial(coefficients, x);
            }
        }

        return shares;
    }

    /**
     * Reconstruct a secret from k or more shares using Lagrange interpolation.
     * @param shares at least k shares
     * @return the reconstructed secret
     */
    public static byte[] reconstruct(List<Share> shares) {
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("No shares provided");
        }

        int length = shares.getFirst().y().length;
        var secret = new byte[length];

        for (int byteIdx = 0; byteIdx < length; byteIdx++) {
            // Lagrange interpolation at x=0
            int value = 0;
            for (int i = 0; i < shares.size(); i++) {
                int xi = shares.get(i).x();
                int yi = shares.get(i).y()[byteIdx] & 0xFF;

                int lagrangeBasis = 1;
                for (int j = 0; j < shares.size(); j++) {
                    if (i == j) continue;
                    int xj = shares.get(j).x();
                    // lagrangeBasis *= xj / (xj - xi) in GF(256)
                    lagrangeBasis = gf256Mul(lagrangeBasis,
                        gf256Mul(xj, gf256Inv(xj ^ xi)));
                }

                value ^= gf256Mul(yi, lagrangeBasis);
            }
            secret[byteIdx] = (byte) value;
        }

        return secret;
    }

    // --- GF(256) arithmetic ---

    private static int evaluatePolynomial(int[] coefficients, int x) {
        int result = 0;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            result = gf256Mul(result, x) ^ coefficients[i];
        }
        return result;
    }

    /** Multiplication in GF(256) using Russian peasant multiplication. */
    static int gf256Mul(int a, int b) {
        a &= 0xFF;
        b &= 0xFF;
        int result = 0;
        while (b > 0) {
            if ((b & 1) != 0) {
                result ^= a;
            }
            a <<= 1;
            if ((a & 0x100) != 0) {
                a ^= 0x11B; // AES irreducible polynomial
            }
            b >>= 1;
        }
        return result & 0xFF;
    }

    /** Multiplicative inverse in GF(256) using extended Euclidean algorithm. */
    static int gf256Inv(int a) {
        if (a == 0) throw new ArithmeticException("No inverse for 0 in GF(256)");
        // a^254 = a^(-1) in GF(256) (Fermat's little theorem)
        int result = a;
        for (int i = 0; i < 6; i++) {
            result = gf256Mul(result, result);
            result = gf256Mul(result, a);
        }
        result = gf256Mul(result, result);
        return result;
    }
}
