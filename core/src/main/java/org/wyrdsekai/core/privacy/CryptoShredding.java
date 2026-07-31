package org.wyrdsekai.core.privacy;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crypto-shredding — per-entity encryption keys for GDPR right to erasure (§9F).
 * Each entity's personal data is encrypted with a unique AES-256-GCM key.
 * Deleting the key effectively destroys all data (even in backups/event logs).
 */
public class CryptoShredding {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    /** Encrypted data with its IV. */
    public record EncryptedData(byte[] ciphertext, byte[] iv) {}

    /** Key info for an entity. */
    public record KeyInfo(
        String entityId,
        byte[] key,
        Instant createdAt,
        boolean active
    ) {}

    private final Map<String, KeyInfo> entityKeys = new ConcurrentHashMap<>();
    private final Set<String> shreddedEntities = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    /**
     * Generate and store a new encryption key for an entity.
     * @return the key info
     */
    public KeyInfo generateKey(String entityId) {
        try {
            var keygen = KeyGenerator.getInstance("AES");
            keygen.init(256, random);
            var secretKey = keygen.generateKey();
            var info = new KeyInfo(entityId, secretKey.getEncoded(),
                Instant.now(), true);
            entityKeys.put(entityId, info);
            return info;
        } catch (Exception e) {
            throw new RuntimeException("Key generation failed", e);
        }
    }

    /**
     * Encrypt data for an entity using their key.
     */
    public Optional<EncryptedData> encrypt(String entityId, byte[] plaintext) {
        var keyInfo = entityKeys.get(entityId);
        if (keyInfo == null || !keyInfo.active()) return Optional.empty();

        try {
            var iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);

            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyInfo.key(), "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            var ciphertext = cipher.doFinal(plaintext);

            return Optional.of(new EncryptedData(ciphertext, iv));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Decrypt data for an entity using their key.
     */
    public Optional<byte[]> decrypt(String entityId, EncryptedData data) {
        var keyInfo = entityKeys.get(entityId);
        if (keyInfo == null || !keyInfo.active()) return Optional.empty();

        try {
            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyInfo.key(), "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, data.iv()));
            return Optional.of(cipher.doFinal(data.ciphertext()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Shred (destroy) an entity's key — all their encrypted data becomes permanently inaccessible.
     * This is the GDPR right-to-erasure mechanism.
     * @return true if a key was destroyed
     */
    public boolean shred(String entityId) {
        var keyInfo = entityKeys.remove(entityId);
        if (keyInfo != null) {
            // Zero out the key material
            Arrays.fill(keyInfo.key(), (byte) 0);
            shreddedEntities.add(entityId);
            return true;
        }
        return false;
    }

    /** Check if an entity has an active key. */
    public boolean hasKey(String entityId) {
        var info = entityKeys.get(entityId);
        return info != null && info.active();
    }

    /** Check if an entity's key has been shredded. */
    public boolean isShredded(String entityId) {
        return shreddedEntities.contains(entityId);
    }

    /** Count of active keys. */
    public int activeKeyCount() {
        return (int) entityKeys.values().stream().filter(KeyInfo::active).count();
    }

    /** Count of shredded entities. */
    public int shreddedCount() {
        return shreddedEntities.size();
    }
}
