package org.wyrdsekai.core.lifecycle;

import org.wyrdsekai.common.util.Json;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * encrypted-blob codec for {@link RecoverySeed}.
 *
 * <p>The seed is encrypted under a passphrase-derived key so a bondholder
 * with the file and the passphrase can recover companion continuity on a
 * fresh machine. The file is self-contained: salt + IV + ciphertext (no
 * separate keystore needed).
 *
 * <p><b>Wire format</b> (all values big-endian):
 * <pre>
 *   magic        4 bytes  "WSRS"  ("Wyrd Sekai Recovery Seed")
 *   version      1 byte   0x02
 *   pbkdf2Iters  4 bytes  iteration count (currently 600000)
 *   saltLen      1 byte   16 (PBKDF2 salt length)
 *   salt         16 bytes random PBKDF2 salt
 *   ivLen        1 byte   12 (AES-GCM IV length)
 *   iv           12 bytes random AES-GCM IV
 *   ctLen        4 bytes  ciphertext length
 *   ciphertext   N bytes  AES-256-GCM(gzip(plaintextJson)), header as AAD
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>PBKDF2WithHmacSHA256 with 600000 iterations matches OWASP 2023
 *       guidance for password-derived encryption keys.</li>
 *   <li>AES-256-GCM provides AEAD — tampering with the ciphertext or
 *       header fails decryption (the header up to ctLen is the AAD).</li>
 *   <li>Version 2 gzips the JSON first: a seed carries the whole soul
 *       manifest, and restore uploads it through a size-capped request.</li>
 *   <li>The iteration count and lengths are read from the file, so they are
 *       bounded before any work is done: a crafted header cannot make the
 *       node spin for hours or allocate gigabytes.</li>
 *   <li>The file does NOT include the agent DID in cleartext; everything
 *       except the magic/version/salt/iv is sealed under the passphrase.</li>
 * </ul>
 *
 * <p>v1 is simple: one seed, one passphrase. V2 (post-OSS) extends to a
 * Shamir-style split for multi-trustee recovery (Refuge institutional
 * layer) but the v1 codec is sufficient for the solo-household lifeline.
 */
public final class RecoverySeedCodec {

    /** File magic prefix — {@code "WSRS"}. */
    public static final byte[] MAGIC = "WSRS".getBytes(StandardCharsets.US_ASCII);
    /** Current wire-format version. */
    public static final int CURRENT_VERSION = 2;
    /** PBKDF2 iterations (OWASP 2023 guidance for HMAC-SHA256). */
    public static final int PBKDF2_ITERATIONS = 600_000;
    /** PBKDF2 salt length in bytes. */
    public static final int SALT_LEN = 16;
    /** AES-GCM IV length in bytes. */
    public static final int IV_LEN = 12;
    /** AES-GCM tag length in bits. */
    public static final int GCM_TAG_BITS = 128;
    /** Derived key length in bits (AES-256). */
    public static final int KEY_BITS = 256;
    /** Bounds on what a file may ask of the decoder. */
    static final int MIN_ITERATIONS = 100_000;
    static final int MAX_ITERATIONS = 5_000_000;
    static final int MAX_CIPHERTEXT = 64 * 1024 * 1024;

    private static final SecureRandom RNG = new SecureRandom();

    private RecoverySeedCodec() {}

    /**
     * Encrypt a {@link RecoverySeed} to the wire format under
     * {@code passphrase}. Caller is responsible for writing the returned
     * bytes to disk + remembering the passphrase.
     *
     * @throws GeneralSecurityException on cipher failure (shouldn't happen
     *         with JDK-bundled AES/PBKDF2 providers)
     */
    public static byte[] encrypt(RecoverySeed seed, char[] passphrase)
            throws GeneralSecurityException, IOException {
        if (seed == null) throw new IllegalArgumentException("seed must not be null");
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("passphrase must not be empty");
        }
        byte[] salt = new byte[SALT_LEN];
        RNG.nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);

        byte[] header = header(PBKDF2_ITERATIONS, salt, iv);
        SecretKey key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(header);

        var zipped = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(zipped)) {
            gz.write(Json.mapper().writeValueAsBytes(seed));
        }
        byte[] ciphertext = cipher.doFinal(zipped.toByteArray());

        var buf = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buf)) {
            out.write(header);
            out.writeInt(ciphertext.length);
            out.write(ciphertext);
        }
        return buf.toByteArray();
    }

    /**
     * Decrypt a Recovery Seed file produced by {@link #encrypt} using
     * {@code passphrase}. Returns the seed or throws on tamper/wrong
     * passphrase (AES-GCM AEAD failure surfaces as
     * {@code AEADBadTagException}, a subclass of {@link GeneralSecurityException}).
     */
    public static RecoverySeed decrypt(byte[] file, char[] passphrase)
            throws GeneralSecurityException, IOException {
        if (file == null || file.length < MAGIC.length + 1) {
            throw new IllegalArgumentException("file too short to be a recovery seed");
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("passphrase must not be empty");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(file))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IllegalArgumentException("not a Wyrdsekai recovery seed file (bad magic)");
            }
            int version = in.readUnsignedByte();
            if (version != CURRENT_VERSION) {
                throw new IllegalArgumentException(
                    "unsupported recovery seed format version: " + version);
            }
            int iterations = in.readInt();
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                throw new IllegalArgumentException("recovery seed header out of range");
            }
            int saltLen = in.readUnsignedByte();
            if (saltLen != SALT_LEN) throw new IllegalArgumentException("recovery seed header out of range");
            byte[] salt = new byte[saltLen];
            in.readFully(salt);
            int ivLen = in.readUnsignedByte();
            if (ivLen != IV_LEN) throw new IllegalArgumentException("recovery seed header out of range");
            byte[] iv = new byte[ivLen];
            in.readFully(iv);
            int ctLen = in.readInt();
            if (ctLen <= 0 || ctLen > MAX_CIPHERTEXT || ctLen > in.available()) {
                throw new IllegalArgumentException("recovery seed file is truncated or damaged");
            }
            byte[] ciphertext = new byte[ctLen];
            in.readFully(ciphertext);

            SecretKey key = deriveKey(passphrase, salt, iterations);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(header(iterations, salt, iv));
            byte[] zipped = cipher.doFinal(ciphertext);
            try (var gz = new GZIPInputStream(new ByteArrayInputStream(zipped))) {
                return Json.mapper().readValue(gz, RecoverySeed.class);
            }
        } catch (EOFException e) {
            throw new IllegalArgumentException("recovery seed file is truncated or damaged");
        }
    }

    private static byte[] header(int iterations, byte[] salt, byte[] iv) throws IOException {
        var buf = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buf)) {
            out.write(MAGIC);
            out.writeByte(CURRENT_VERSION);
            out.writeInt(iterations);
            out.writeByte(salt.length);
            out.write(salt);
            out.writeByte(iv.length);
            out.write(iv);
        }
        return buf.toByteArray();
    }

    /** PBKDF2WithHmacSHA256 key derivation. */
    private static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        var spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            byte[] raw = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(raw, "AES");
            } finally {
                // Best-effort zeroize.
                Arrays.fill(raw, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }
}
