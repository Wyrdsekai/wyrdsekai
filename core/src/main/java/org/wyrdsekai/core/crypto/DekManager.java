package org.wyrdsekai.core.crypto;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data Encryption Key lifecycle management (§60).
 * Wraps/unwraps DEKs with a master key (AES-256 Key Wrap).
 * Tracks key metadata and supports deletion workflow (destroy DEK → data unreadable).
 */
public class DekManager {

    /** Data classification levels. */
    public enum DataClassification {
        PUBLIC,       // no encryption needed
        INTERNAL,     // zone-level DEK
        CONFIDENTIAL, // entity-level DEK
        RESTRICTED    // per-object DEK with audit
    }

    /** Key metadata. */
    public record KeyEntry(
        String keyId,
        byte[] wrappedKey,
        DataClassification classification,
        String owner,
        Instant createdAt,
        boolean active
    ) {}

    private final SecretKey masterKey;
    private final Map<String, KeyEntry> keys = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Create with an existing master key. */
    public DekManager(SecretKey masterKey) {
        this.masterKey = masterKey;
    }

    /** Create with a randomly generated master key (for testing/standalone). */
    public static DekManager withRandomMasterKey() {
        try {
            var keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            return new DekManager(keyGen.generateKey());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate master key", e);
        }
    }

    /** Generate a new DEK, wrap it with the master key, and store. */
    public KeyEntry generateDek(String keyId, DataClassification classification, String owner) {
        try {
            var keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            var dek = keyGen.generateKey();

            var wrapped = wrapKey(dek);
            var entry = new KeyEntry(keyId, wrapped, classification, owner, Instant.now(), true);
            keys.put(keyId, entry);
            return entry;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate DEK: " + e.getMessage(), e);
        }
    }

    /** Unwrap a DEK to get the actual encryption key. */
    public Optional<SecretKey> unwrapDek(String keyId) {
        var entry = keys.get(keyId);
        if (entry == null || !entry.active()) return Optional.empty();
        try {
            return Optional.of(unwrapKey(entry.wrappedKey()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Encrypt data using a DEK. */
    public byte[] encrypt(String keyId, byte[] plaintext) {
        var key = unwrapDek(keyId).orElseThrow(
            () -> new IllegalStateException("DEK not found or inactive: " + keyId));
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var iv = new byte[12];
            random.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            var ciphertext = cipher.doFinal(plaintext);

            // Prepend IV to ciphertext
            var result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /** Decrypt data using a DEK. */
    public byte[] decrypt(String keyId, byte[] encrypted) {
        var key = unwrapDek(keyId).orElseThrow(
            () -> new IllegalStateException("DEK not found or inactive: " + keyId));
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var iv = new byte[12];
            System.arraycopy(encrypted, 0, iv, 0, 12);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return cipher.doFinal(encrypted, 12, encrypted.length - 12);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /** Destroy a DEK — data encrypted with it becomes permanently unreadable. */
    public boolean destroyDek(String keyId) {
        var entry = keys.get(keyId);
        if (entry == null) return false;
        keys.put(keyId, new KeyEntry(keyId, new byte[0], entry.classification(),
            entry.owner(), entry.createdAt(), false));
        return true;
    }

    /** Get key metadata. */
    public Optional<KeyEntry> getKeyEntry(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    /** Total number of keys (active + destroyed). */
    public int keyCount() {
        return keys.size();
    }

    /** Number of active keys. */
    public int activeKeyCount() {
        return (int) keys.values().stream().filter(KeyEntry::active).count();
    }

    // --- AES Key Wrap ---

    private byte[] wrapKey(SecretKey dek) throws Exception {
        var cipher = Cipher.getInstance("AESWrap");
        cipher.init(Cipher.WRAP_MODE, masterKey);
        return cipher.wrap(dek);
    }

    private SecretKey unwrapKey(byte[] wrapped) throws Exception {
        var cipher = Cipher.getInstance("AESWrap");
        cipher.init(Cipher.UNWRAP_MODE, masterKey);
        return (SecretKey) cipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY);
    }
}
