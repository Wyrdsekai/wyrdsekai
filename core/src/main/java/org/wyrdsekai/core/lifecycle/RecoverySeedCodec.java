package org.wyrdsekai.core.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

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
 *   version      1 byte   0x01
 *   pbkdf2Iters  4 bytes  iteration count (currently 600000)
 *   saltLen      1 byte   16 (PBKDF2 salt length)
 *   salt         16 bytes random PBKDF2 salt
 *   ivLen        1 byte   12 (AES-GCM IV length)
 *   iv           12 bytes random AES-GCM IV
 *   ctLen        4 bytes  ciphertext length
 *   ciphertext   N bytes  AES-256-GCM(plaintextJson)
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>PBKDF2WithHmacSHA256 with 600000 iterations matches OWASP 2023
 *       guidance for password-derived encryption keys.</li>
 *   <li>AES-256-GCM provides AEAD — tampering with the ciphertext or
 *       header fails decryption.</li>
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
    public static final int CURRENT_VERSION = 1;
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

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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

        SecretKey key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        byte[] plaintext = MAPPER.writeValueAsBytes(seed);
        byte[] ciphertext = cipher.doFinal(plaintext);

        var buf = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buf)) {
            out.write(MAGIC);
            out.writeByte(CURRENT_VERSION);
            out.writeInt(PBKDF2_ITERATIONS);
            out.writeByte(SALT_LEN);
            out.write(salt);
            out.writeByte(IV_LEN);
            out.write(iv);
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
            int saltLen = in.readUnsignedByte();
            byte[] salt = new byte[saltLen];
            in.readFully(salt);
            int ivLen = in.readUnsignedByte();
            byte[] iv = new byte[ivLen];
            in.readFully(iv);
            int ctLen = in.readInt();
            byte[] ciphertext = new byte[ctLen];
            in.readFully(ciphertext);

            SecretKey key = deriveKey(passphrase, salt, iterations);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return MAPPER.readValue(plaintext, RecoverySeed.class);
        }
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
