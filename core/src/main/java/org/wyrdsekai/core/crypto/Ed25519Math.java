package org.wyrdsekai.core.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Ed25519 curve arithmetic for FROST threshold signing.
 * Uses BigInteger for clarity — adequate for household-scale (1-20 nodes).
 * Not constant-time; do not use for high-security signing key operations.
 *
 * Curve: -x^2 + y^2 = 1 + d*x^2*y^2 (twisted Edwards)
 * Field: GF(p) where p = 2^255 - 19
 * Group order: L = 2^252 + 27742317777372353535851937790883648493
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8032">RFC 8032 (EdDSA)</a>
 */
final class Ed25519Math {

    /** Field prime p = 2^255 - 19. */
    static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));

    /** Group order L. */
    static final BigInteger L = BigInteger.TWO.pow(252)
        .add(new BigInteger("27742317777372353535851937790883648493"));

    /** Curve parameter d = -121665/121666 mod p (known constant). */
    static final BigInteger D = new BigInteger(
        "37095705934669439343138083508754565189542113879843219016388785533085940283555");

    /** Base point B (RFC 8032 §5.1: y = 4/5 mod p, x positive/even). */
    static final Point B;
    static {
        var BY = new BigInteger(
            "46316835694926478169428394003475163141307993866256225615783033603165251855960");
        var BX = new BigInteger(
            "15112221349535400772501151409588531511454012693041857206046113283949847762202");
        B = new Point(BX, BY);
    }

    /** Identity point (0, 1). */
    static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A point on the Ed25519 curve in affine coordinates. */
    record Point(BigInteger x, BigInteger y) {
        boolean isIdentity() {
            return x.equals(BigInteger.ZERO) && y.equals(BigInteger.ONE);
        }
    }

    /** Generate a random scalar in [1, L-1]. */
    static BigInteger randomScalar() {
        BigInteger s;
        do {
            var bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            bytes[31] &= 0x7F; // ensure < 2^255
            s = new BigInteger(1, reverseBytes(bytes)).mod(L);
        } while (s.signum() == 0);
        return s;
    }

    /** Point addition on the twisted Edwards curve. */
    static Point add(Point p1, Point p2) {
        var x1 = p1.x; var y1 = p1.y;
        var x2 = p2.x; var y2 = p2.y;

        // x3 = (x1*y2 + y1*x2) / (1 + d*x1*x2*y1*y2)
        // y3 = (y1*y2 + x1*x2) / (1 - d*x1*x2*y1*y2)
        // (Note: Edwards curve formula uses -x^2 + y^2 = 1 + d*x^2*y^2,
        //  so we use: x3_num = x1*y2 + y1*x2, y3_num = y1*y2 + x1*x2)
        // Actually for twisted Edwards: -x^2 + y^2 = 1 + d*x^2*y^2
        // The addition formula is:
        //   x3 = (x1*y2 + y1*x2) / (1 + d*x1*x2*y1*y2)
        //   y3 = (y1*y2 - (-1)*x1*x2) / (1 - d*x1*x2*y1*y2)
        //      = (y1*y2 + x1*x2) / (1 - d*x1*x2*y1*y2)
        var dxy = D.multiply(x1).multiply(x2).multiply(y1).multiply(y2).mod(P);

        var x3Num = x1.multiply(y2).add(y1.multiply(x2)).mod(P);
        var x3Den = BigInteger.ONE.add(dxy).mod(P);

        var y3Num = y1.multiply(y2).add(x1.multiply(x2)).mod(P);
        var y3Den = BigInteger.ONE.subtract(dxy).mod(P);

        return new Point(
            x3Num.multiply(x3Den.modInverse(P)).mod(P),
            y3Num.multiply(y3Den.modInverse(P)).mod(P)
        );
    }

    /** Scalar multiplication: s * P. */
    static Point scalarMult(BigInteger s, Point p) {
        s = s.mod(L);
        var result = IDENTITY;
        var current = p;
        while (s.signum() > 0) {
            if (s.testBit(0)) {
                result = add(result, current);
            }
            current = add(current, current); // doubling
            s = s.shiftRight(1);
        }
        return result;
    }

    /** Negate a point. */
    static Point negate(Point p) {
        return new Point(p.x.negate().mod(P), p.y);
    }

    /** Encode a point to 32 bytes (RFC 8032 format: little-endian y, high bit = x odd). */
    static byte[] encodePoint(Point p) {
        var yBytes = p.y.toByteArray();
        var result = new byte[32];
        // BigInteger is big-endian, we need little-endian
        for (int i = 0; i < Math.min(yBytes.length, 32); i++) {
            result[i] = yBytes[yBytes.length - 1 - i];
        }
        // Set high bit if x is odd
        if (p.x.testBit(0)) {
            result[31] |= (byte) 0x80;
        }
        return result;
    }

    /** Decode a point from 32 bytes (RFC 8032 format). */
    static Point decodePoint(byte[] encoded) {
        if (encoded.length != 32) {
            throw new IllegalArgumentException("Point encoding must be 32 bytes");
        }
        // Extract x-odd bit
        boolean xOdd = (encoded[31] & 0x80) != 0;
        var yCopy = Arrays.copyOf(encoded, 32);
        yCopy[31] &= 0x7F; // clear high bit

        // Little-endian to BigInteger
        var y = new BigInteger(1, reverseBytes(yCopy));

        // Recover x from curve equation: x^2 = (y^2 - 1) / (d * y^2 + 1) (for a = -1)
        // Wait, for -x^2 + y^2 = 1 + d*x^2*y^2:
        // y^2 - 1 = x^2 + d*x^2*y^2 = x^2(1 + d*y^2)
        // x^2 = (y^2 - 1) / (1 + d*y^2)
        // But a = -1, so: -x^2 + y^2 = 1 + d*x^2*y^2
        // x^2 = (y^2 - 1) / (d*y^2 + 1)  ... wait, let me redo:
        // -x^2 + y^2 = 1 + d*x^2*y^2
        // y^2 - 1 = x^2 + d*x^2*y^2 = x^2(1 + d*y^2)
        // x^2 = (y^2 - 1) / (1 + d*y^2)
        var y2 = y.multiply(y).mod(P);
        var num = y2.subtract(BigInteger.ONE).mod(P);
        var den = D.multiply(y2).add(BigInteger.ONE).mod(P);
        var x2 = num.multiply(den.modInverse(P)).mod(P);

        // Square root: x = x2^((p+3)/8) mod p (for p ≡ 5 mod 8)
        var x = modSqrt(x2);
        if (x == null) {
            throw new IllegalArgumentException("Invalid point encoding");
        }

        // Adjust sign
        if (x.testBit(0) != xOdd) {
            x = P.subtract(x);
        }

        return new Point(x, y);
    }

    /** Encode a scalar as 32 little-endian bytes. */
    static byte[] encodeScalar(BigInteger s) {
        var bigEndian = s.toByteArray();
        var result = new byte[32];
        for (int i = 0; i < Math.min(bigEndian.length, 32); i++) {
            result[i] = bigEndian[bigEndian.length - 1 - i];
        }
        return result;
    }

    /** Decode a scalar from 32 little-endian bytes. */
    static BigInteger decodeScalar(byte[] encoded) {
        return new BigInteger(1, reverseBytes(Arrays.copyOf(encoded, encoded.length)));
    }

    /** SHA-512 hash, reduced to a scalar mod L. */
    static BigInteger hashToScalar(byte[]... inputs) throws Exception {
        var md = MessageDigest.getInstance("SHA-512");
        for (var input : inputs) {
            md.update(input);
        }
        var hash = md.digest();
        return new BigInteger(1, reverseBytes(hash)).mod(L);
    }

    /** Modular square root for p ≡ 5 (mod 8). Uses Atkin's algorithm. */
    private static BigInteger modSqrt(BigInteger a) {
        if (a.signum() == 0) return BigInteger.ZERO;

        // p ≡ 5 (mod 8), so we use: sqrt(a) = a^((p+3)/8) or i*a^((p+3)/8)
        // where i = 2^((p-1)/4)
        var exp = P.add(BigInteger.valueOf(3)).shiftRight(3); // (p+3)/8
        var x = a.modPow(exp, P);

        // Check: if x^2 == a, done
        if (x.multiply(x).mod(P).equals(a.mod(P))) {
            return x;
        }

        // Otherwise try i*x where i^2 = -1
        var sqrtM1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
        x = x.multiply(sqrtM1).mod(P);
        if (x.multiply(x).mod(P).equals(a.mod(P))) {
            return x;
        }

        return null; // No square root exists
    }

    private static byte[] reverseBytes(byte[] input) {
        var result = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            result[i] = input[input.length - 1 - i];
        }
        return result;
    }
}
