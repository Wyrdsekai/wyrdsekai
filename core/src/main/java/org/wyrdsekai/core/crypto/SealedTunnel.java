package org.wyrdsekai.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;

/**
 * The sealed tunnel (v2): end-to-end encryption between a phone and its home through a relay that
 * only routes (, W3).
 *
 * <p>The phone knows the home's static X25519 key from pairing. Per session it sends an ephemeral
 * key; the home answers with its own ephemeral key. Both derive
 * {@code HKDF-SHA256(salt = session id, ikm = DH(e_p, s_z) || DH(e_p, e_z), info = "wyrd-tunnel-v2" || e_p || e_z)}
 * into two 32-byte keys, one per direction. Only the holder of the home's static key can compute
 * the first term (the relay cannot stand in for the home), and the second term makes each session's
 * keys independent of the static key being stolen later. Every frame is ChaCha20-Poly1305 with a
 * 96-bit nonce of four zero bytes and a 64-bit big-endian counter, strictly sequential per direction,
 * the NATS subject as associated data. Built only from JDK primitives.</p>
 */
public final class SealedTunnel {

    public static final int KEY_LEN = 32;
    static final int NONCE_LEN = 12;
    static final int TAG_LEN = 16;
    static final byte[] INFO = "wyrd-tunnel-v2".getBytes(StandardCharsets.UTF_8);

    private SealedTunnel() {}

    /** An X25519 key pair as raw 32-byte values (RFC 7748 encoding). */
    public record KeyPair(byte[] priv, byte[] pub) {}

    public static KeyPair generate() throws GeneralSecurityException {
        var kp = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        return new KeyPair(((XECPrivateKey) kp.getPrivate()).getScalar().orElseThrow(), rawPublic(kp.getPublic()));
    }

    /** X25519(priv, pub). Refuses an all-zero result (a low-order public key). */
    public static byte[] x25519(byte[] priv, byte[] pub) throws GeneralSecurityException {
        if (priv == null || priv.length != KEY_LEN || pub == null || pub.length != KEY_LEN) {
            throw new GeneralSecurityException("X25519 keys are 32 bytes");
        }
        var kf = KeyFactory.getInstance("XDH");
        PrivateKey sk = kf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, priv));
        PublicKey pk = kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, uFromLittleEndian(pub)));
        var ka = KeyAgreement.getInstance("XDH");
        ka.init(sk);
        ka.doPhase(pk, true);
        var shared = ka.generateSecret();
        boolean zero = true;
        for (byte b : shared) zero &= b == 0;
        if (zero) throw new GeneralSecurityException("X25519 result is all zero (low-order public key)");
        return shared;
    }

    /** One direction of a session: its key and its frame counter. Frames must arrive in order. */
    public static final class Channel {
        private final byte[] key;
        private long counter;

        Channel(byte[] key) { this.key = key.clone(); }

        /** {@code nonce(12) || ciphertext || tag(16)} for the next frame in this direction. */
        public synchronized byte[] seal(byte[] plaintext, byte[] aad) throws GeneralSecurityException {
            var nonce = nonce(counter);
            var c = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            if (aad != null) c.updateAAD(aad);
            var ct = c.doFinal(plaintext);
            counter++;
            return ByteBuffer.allocate(NONCE_LEN + ct.length).put(nonce).put(ct).array();
        }

        /** Opens the next frame; a frame out of order, forged or altered throws. */
        public synchronized byte[] open(byte[] frame, byte[] aad) throws GeneralSecurityException {
            if (frame == null || frame.length < NONCE_LEN + TAG_LEN) throw new GeneralSecurityException("sealed frame too short");
            var nonce = Arrays.copyOfRange(frame, 0, NONCE_LEN);
            if (!Arrays.equals(nonce, nonce(counter))) {
                throw new GeneralSecurityException("sealed frame out of order (expected frame " + counter + ")");
            }
            var c = cipher(Cipher.DECRYPT_MODE, key, nonce);
            if (aad != null) c.updateAAD(aad);
            var pt = c.doFinal(frame, NONCE_LEN, frame.length - NONCE_LEN);
            counter++;
            return pt;
        }

        long counter() { return counter; }
    }

    /** The home's side of a session: its ephemeral public key to send back, and both directions. */
    public record Accepted(byte[] zoneEphemeralPub, Channel up, Channel down) {}

    /** The home accepts a phone's opening key {@code phoneEphemeralPub} for {@code sessionId}. */
    public static Accepted accept(KeyPair zoneStatic, byte[] phoneEphemeralPub, String sessionId) throws GeneralSecurityException {
        return accept(zoneStatic, generate(), phoneEphemeralPub, sessionId);
    }

    static Accepted accept(KeyPair zoneStatic, KeyPair zoneEphemeral, byte[] phoneEphemeralPub, String sessionId)
            throws GeneralSecurityException {
        var keys = derive(x25519(zoneStatic.priv(), phoneEphemeralPub), x25519(zoneEphemeral.priv(), phoneEphemeralPub),
            sessionId, phoneEphemeralPub, zoneEphemeral.pub());
        return new Accepted(zoneEphemeral.pub(), new Channel(keys[0]), new Channel(keys[1]));
    }

    /** The phone's side, once the home's ephemeral key has arrived (the phone apps do the same). */
    public record Opened(Channel up, Channel down) {}

    public static Opened complete(KeyPair phoneEphemeral, byte[] zoneStaticPub, byte[] zoneEphemeralPub, String sessionId)
            throws GeneralSecurityException {
        var keys = derive(x25519(phoneEphemeral.priv(), zoneStaticPub), x25519(phoneEphemeral.priv(), zoneEphemeralPub),
            sessionId, phoneEphemeral.pub(), zoneEphemeralPub);
        return new Opened(new Channel(keys[0]), new Channel(keys[1]));
    }

    /** {k_up, k_down}. */
    static byte[][] derive(byte[] dhStatic, byte[] dhEphemeral, String sessionId, byte[] phoneEphPub, byte[] zoneEphPub)
            throws GeneralSecurityException {
        var ikm = ByteBuffer.allocate(dhStatic.length + dhEphemeral.length).put(dhStatic).put(dhEphemeral).array();
        var prk = hkdfExtract(sessionId.getBytes(StandardCharsets.UTF_8), ikm);
        var info = ByteBuffer.allocate(INFO.length + phoneEphPub.length + zoneEphPub.length)
            .put(INFO).put(phoneEphPub).put(zoneEphPub).array();
        var okm = hkdfExpand(prk, info, 2 * KEY_LEN);
        return new byte[][]{Arrays.copyOfRange(okm, 0, KEY_LEN), Arrays.copyOfRange(okm, KEY_LEN, 2 * KEY_LEN)};
    }

    // ── primitives ──────────────────────────────────────────────────────────

    static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        var out = new byte[length];
        var t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    static byte[] nonce(long counter) {
        return ByteBuffer.allocate(NONCE_LEN).putInt(0).putLong(counter).array();
    }

    static Cipher cipher(int mode, byte[] key, byte[] nonce) throws GeneralSecurityException {
        var c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        return c;
    }

    static BigInteger uFromLittleEndian(byte[] pub) {
        var be = new byte[KEY_LEN];
        for (int i = 0; i < KEY_LEN; i++) be[i] = pub[KEY_LEN - 1 - i];
        be[0] &= 0x7f;   // RFC 7748: the most significant bit of the u-coordinate is masked
        return new BigInteger(1, be);
    }

    static byte[] rawPublic(PublicKey pk) {
        var u = ((XECPublicKey) pk).getU().toByteArray();   // big-endian, maybe with a sign byte
        var le = new byte[KEY_LEN];
        for (int i = 0; i < KEY_LEN && i < u.length; i++) le[i] = u[u.length - 1 - i];
        return le;
    }
}
