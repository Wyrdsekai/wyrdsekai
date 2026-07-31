package org.wyrdsekai.core.nostr;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * secp256k1 keypair + npub/nsec encoding for a companion.
 *
 * <p>Two ways to construct:
 * <ul>
 *   <li>{@link #deriveFromEd25519PrivateKey} — deterministic HKDF derivation
 *       so the companion's Nostr identity is a stable function of its DID.
 *       This is the default; same companion = same npub forever, no extra
 *       key storage needed.</li>
 *   <li>{@link #fromHexPrivateKey} — for stewards who want to import an
 *       existing Nostr persona, or for tests with known vectors.</li>
 * </ul>
 *
 * <p>The 32-byte x-only pubkey is the "raw" Nostr pubkey shape (BIP-340 / NIP-01).
 * {@link #npub} bech32-encodes it for human display. {@link #nsec} likewise
 * for the private key (NIP-19).
 */
public final class NostrKey {

    /** BIP-340 / Nostr HKDF derivation salt. v1 to allow future rotation. */
    private static final byte[] DERIVE_SALT = "wyrdsekai.nostr.v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DERIVE_INFO = "secp256k1-keypair".getBytes(StandardCharsets.UTF_8);

    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    static final BigInteger CURVE_ORDER = CURVE.getN();
    static final ECPoint CURVE_G = CURVE.getG();

    private final BigInteger privateScalar;       // d, in [1, n-1]
    private final byte[] xOnlyPubKey;             // 32 bytes (x coord of P=d·G, with even-y form)

    NostrKey(BigInteger d, byte[] xOnlyPub) {
        this.privateScalar = d;
        this.xOnlyPubKey = xOnlyPub;
    }

    /**
     * Derive a Nostr keypair from a 32-byte Ed25519 private key seed
     * (or any 32-byte secret) via HKDF-SHA256. Used to compute a companion's
     * Nostr identity from its DID's private key.
     */
    public static NostrKey deriveFromEd25519PrivateKey(byte[] ed25519PrivateKey32) {
        if (ed25519PrivateKey32 == null || ed25519PrivateKey32.length != 32) {
            throw new IllegalArgumentException("expected 32-byte seed");
        }
        // HKDF in a loop in case the first 32 bytes don't map to a valid scalar
        // (probability 2^-128, essentially never; defensive only).
        for (int counter = 0; counter < 4; counter++) {
            var hkdf = new HKDFBytesGenerator(new SHA256Digest());
            var info = new byte[DERIVE_INFO.length + 1];
            System.arraycopy(DERIVE_INFO, 0, info, 0, DERIVE_INFO.length);
            info[DERIVE_INFO.length] = (byte) counter;
            hkdf.init(new HKDFParameters(ed25519PrivateKey32, DERIVE_SALT, info));
            var out = new byte[32];
            hkdf.generateBytes(out, 0, 32);
            var d = new BigInteger(1, out);
            if (d.signum() > 0 && d.compareTo(CURVE_ORDER) < 0) {
                return fromScalar(d);
            }
        }
        throw new IllegalStateException("HKDF could not derive a valid scalar (cosmic ray?)");
    }

    /** Random keypair, for tests and standalone-Nostr-identity stewards. */
    public static NostrKey generate() {
        var random = new SecureRandom();
        while (true) {
            var bytes = new byte[32];
            random.nextBytes(bytes);
            var d = new BigInteger(1, bytes);
            if (d.signum() > 0 && d.compareTo(CURVE_ORDER) < 0) return fromScalar(d);
        }
    }

    /** Parse from 64-character hex private key. */
    public static NostrKey fromHexPrivateKey(String hex) {
        var bytes = HexFormat.of().parseHex(hex);
        if (bytes.length != 32) throw new IllegalArgumentException("private key must be 32 bytes");
        var d = new BigInteger(1, bytes);
        if (d.signum() <= 0 || d.compareTo(CURVE_ORDER) >= 0) {
            throw new IllegalArgumentException("private key out of range");
        }
        return fromScalar(d);
    }

    private static NostrKey fromScalar(BigInteger d) {
        var P = CURVE_G.multiply(d).normalize();
        var x = P.getAffineXCoord().toBigInteger();
        return new NostrKey(d, padTo32(x));
    }

    /** 32-byte x-only pubkey as raw bytes (Nostr wire format). */
    public byte[] xOnlyPubKeyBytes() {
        return xOnlyPubKey.clone();
    }

    /** Hex pubkey (64 lowercase chars), the {@code pubkey} field of a Nostr event. */
    public String pubKeyHex() {
        return HexFormat.of().formatHex(xOnlyPubKey);
    }

    /** {@code npub1...} display form. */
    public String npub() {
        return Bech32.encode32("npub", xOnlyPubKey);
    }

    /** {@code nsec1...} display form. Sensitive — never log. */
    public String nsec() {
        return Bech32.encode32("nsec", padTo32(privateScalar));
    }

    /** Private scalar (package-private — for signer). */
    BigInteger privateScalar() { return privateScalar; }

    /**
     * Decode an {@code npub1...} bech32 string to its 32 x-only pubkey bytes.
     */
    public static byte[] decodeNpub(String npub) {
        var decoded = Bech32.decode32(npub);
        if (!"npub".equals(decoded.hrp())) {
            throw new IllegalArgumentException("expected hrp 'npub', got '" + decoded.hrp() + "'");
        }
        return decoded.data();
    }

    static byte[] padTo32(BigInteger v) {
        var bytes = v.toByteArray();
        if (bytes.length == 32) return bytes;
        if (bytes.length == 33 && bytes[0] == 0) {
            var out = new byte[32];
            System.arraycopy(bytes, 1, out, 0, 32);
            return out;
        }
        if (bytes.length < 32) {
            var out = new byte[32];
            System.arraycopy(bytes, 0, out, 32 - bytes.length, bytes.length);
            return out;
        }
        throw new IllegalArgumentException("scalar too large: " + bytes.length + " bytes");
    }
}
