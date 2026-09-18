package org.wyrdsekai.core.vault;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * What seals the vault at rest. Every chunk and every manifest in the store is AES-256-GCM
 * under one key that lives outside the store, in {@code <data>/vault.key}, so a copy of the
 * store taken offsite by {@code wyrd vault sync} is unreadable without the key file. The key
 * is made once, on the first pass, and never copied into the store: it is the one file the
 * steward keeps somewhere else.
 *
 * <p>File layout: four magic bytes, a twelve-byte nonce, then the ciphertext with its tag. A
 * file without the magic is a plain chunk from before sealing; {@link #open} returns it as is,
 * and the vault reseals such files in place on its next pass.</p>
 */
public final class VaultCipher {

    static final byte[] MAGIC = {'W', 'V', 'S', '1'};
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String id;

    private VaultCipher(byte[] raw) {
        this.key = new SecretKeySpec(raw, "AES");
        this.id = fingerprint(raw);
    }

    /** The key in the file, made if the file does not exist. */
    public static VaultCipher load(Path keyFile) throws IOException {
        if (Files.isRegularFile(keyFile)) {
            var raw = Base64.getDecoder().decode(Files.readString(keyFile).strip());
            if (raw.length != 32) throw new IOException("the vault key at " + keyFile + " is not 32 bytes");
            return new VaultCipher(raw);
        }
        var raw = new byte[32];
        RANDOM.nextBytes(raw);
        Files.createDirectories(keyFile.toAbsolutePath().getParent());
        var tmp = keyFile.resolveSibling(keyFile.getFileName() + ".tmp");
        Files.writeString(tmp, Base64.getEncoder().encodeToString(raw) + "\n");
        try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { /* not a posix filesystem */ }
        Files.move(tmp, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new VaultCipher(raw);
    }

    /** Eight hex characters of the key's hash: enough to tell two keys apart, useless to recover one. */
    public String id() { return id; }

    static String fingerprint(byte[] raw) {
        try {
            var d = MessageDigest.getInstance("SHA-256").digest(raw);
            var sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean sealed(byte[] data) {
        return data.length >= MAGIC.length + NONCE + TAG_BITS / 8
            && Arrays.equals(data, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }

    public byte[] seal(byte[] plain, int off, int len) {
        try {
            var nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            var body = c.doFinal(plain, off, len);
            var out = new byte[MAGIC.length + NONCE + body.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(nonce, 0, out, MAGIC.length, NONCE);
            System.arraycopy(body, 0, out, MAGIC.length + NONCE, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] seal(byte[] plain) { return seal(plain, 0, plain.length); }

    /** The plaintext: decrypted when sealed, as is when it is a plain file from before sealing. */
    public byte[] open(byte[] data) throws IOException {
        if (!sealed(data)) return data;
        try {
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, MAGIC.length, NONCE));
            return c.doFinal(data, MAGIC.length + NONCE, data.length - MAGIC.length - NONCE);
        } catch (GeneralSecurityException e) {
            throw new IOException("sealed with another key (this key is " + id + ")");
        }
    }
}
