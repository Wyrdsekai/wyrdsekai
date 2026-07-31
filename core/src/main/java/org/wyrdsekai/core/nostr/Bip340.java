package org.wyrdsekai.core.nostr;

import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.wyrdsekai.core.nostr.NostrKey.CURVE_ORDER;
import static org.wyrdsekai.core.nostr.NostrKey.CURVE_G;
import static org.wyrdsekai.core.nostr.NostrKey.padTo32;

/**
 * BIP-340 Schnorr signatures over secp256k1, as required by Nostr (NIP-01).
 *
 * <p>BouncyCastle 1.80 has the curve math but not a BIP-340 signer, so we
 * implement the spec directly using its primitives.
 *
 * <p>Reference: <a href="https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki">BIP-340</a>.
 */
final class Bip340 {

    private static final SecureRandom AUX_RNG = new SecureRandom();

    private Bip340() {}

    /**
     * Sign a 32-byte message hash with a secp256k1 private key, BIP-340 style.
     *
     * @param d the secret scalar
     * @param msg32 the 32-byte message hash (Nostr event id)
     * @return 64-byte (R.x || s) signature
     */
    static byte[] sign(BigInteger d, byte[] msg32) {
        if (msg32 == null || msg32.length != 32) {
            throw new IllegalArgumentException("msg must be 32 bytes");
        }
        if (d.signum() <= 0 || d.compareTo(CURVE_ORDER) >= 0) {
            throw new IllegalArgumentException("private scalar out of range");
        }

        // P = d·G
        var P = CURVE_G.multiply(d).normalize();
        var dEff = hasEvenY(P) ? d : CURVE_ORDER.subtract(d);

        // t = bytes(dEff) XOR taggedHash("BIP0340/aux", aux)
        var aux = new byte[32];
        AUX_RNG.nextBytes(aux);
        var t = xor(padTo32(dEff), taggedHash("BIP0340/aux", aux));

        // rand = taggedHash("BIP0340/nonce", t || bytes(P) || msg)
        var pX = padTo32(P.getAffineXCoord().toBigInteger());
        var rand = taggedHash("BIP0340/nonce", concat(t, pX, msg32));
        var kPrime = new BigInteger(1, rand).mod(CURVE_ORDER);
        if (kPrime.signum() == 0) throw new IllegalStateException("kPrime=0 (cosmic ray?)");

        // R = k'·G
        var R = CURVE_G.multiply(kPrime).normalize();
        var k = hasEvenY(R) ? kPrime : CURVE_ORDER.subtract(kPrime);
        var rX = padTo32(R.getAffineXCoord().toBigInteger());

        // e = int(taggedHash("BIP0340/challenge", bytes(R) || bytes(P) || msg)) mod n
        var e = new BigInteger(1, taggedHash("BIP0340/challenge", concat(rX, pX, msg32)))
            .mod(CURVE_ORDER);

        // s = (k + e·d) mod n
        var s = k.add(e.multiply(dEff)).mod(CURVE_ORDER);

        // sig = R.x || s
        var sig = new byte[64];
        System.arraycopy(rX, 0, sig, 0, 32);
        System.arraycopy(padTo32(s), 0, sig, 32, 32);
        return sig;
    }

    /**
     * Verify a BIP-340 signature.
     *
     * @param xOnlyPub 32-byte x-only public key
     * @param msg32 32-byte message hash
     * @param sig 64-byte signature (R.x || s)
     */
    static boolean verify(byte[] xOnlyPub, byte[] msg32, byte[] sig) {
        if (xOnlyPub == null || xOnlyPub.length != 32) return false;
        if (msg32 == null || msg32.length != 32) return false;
        if (sig == null || sig.length != 64) return false;

        try {
            var rX = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
            var s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
            var p = new BigInteger(1, xOnlyPub);

            // P = liftX(xOnlyPub)
            var P = liftX(p);
            if (P == null) return false;

            // e = int(taggedHash("BIP0340/challenge", sig[0:32] || xOnlyPub || msg)) mod n
            var e = new BigInteger(1, taggedHash("BIP0340/challenge",
                concat(Arrays.copyOfRange(sig, 0, 32), xOnlyPub, msg32)))
                .mod(CURVE_ORDER);

            // R = s·G - e·P
            var R = CURVE_G.multiply(s).add(P.negate().multiply(e)).normalize();
            if (R.isInfinity()) return false;
            if (!hasEvenY(R)) return false;
            return R.getAffineXCoord().toBigInteger().equals(rX);
        } catch (Exception e) {
            return false;
        }
    }

    /** liftX per BIP-340: find the point with even y for the given x. */
    private static ECPoint liftX(BigInteger x) {
        var fp = (org.bouncycastle.math.ec.ECCurve.AbstractFp) CURVE_G.getCurve();
        var p = fp.getField().getCharacteristic();
        if (x.signum() < 0 || x.compareTo(p) >= 0) return null;
        try {
            // c = x^3 + 7 mod p; y = c^((p+1)/4) mod p
            var c = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p);
            var y = c.modPow(p.add(BigInteger.ONE).shiftRight(2), p);
            if (!y.modPow(BigInteger.valueOf(2), p).equals(c)) return null;
            if (y.testBit(0)) y = p.subtract(y);  // pick even-y
            return CURVE_G.getCurve().createPoint(x, y).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasEvenY(ECPoint p) {
        return !p.getAffineYCoord().toBigInteger().testBit(0);
    }

    /** SHA256(SHA256(tag) || SHA256(tag) || msg). */
    static byte[] taggedHash(String tag, byte[] msg) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            var tagHash = sha.digest(tag.getBytes(StandardCharsets.UTF_8));
            sha.reset();
            sha.update(tagHash);
            sha.update(tagHash);
            sha.update(msg);
            return sha.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] xor(byte[] a, byte[] b) {
        var out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) (a[i] ^ b[i]);
        return out;
    }

    private static byte[] concat(byte[]... arrs) {
        int total = 0;
        for (var a : arrs) total += a.length;
        var out = new byte[total];
        int p = 0;
        for (var a : arrs) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }
}
