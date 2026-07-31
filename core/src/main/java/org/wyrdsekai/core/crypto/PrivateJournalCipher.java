package org.wyrdsekai.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

/**
 * 0.5a — at-rest encryption for {@code journal_private} Study entries.
 *
 * <p>AES-256-GCM with a PER-USER key derived from the zone master
 * ({@link ZoneSecrets} HKDF, purpose {@code study-journal:<userDid>}), the
 * owner DID bound in as GCM AAD so a ciphertext cannot be replayed onto
 * another user's row. Envelope format on the stored content field:
 * {@code enc:v1:<base64(iv || ciphertext)>}.</p>
 *
 * <p><b>Honest boundary:</b> this protects the entry AT REST — a copied
 * {@code world.db} / search index, a backup, an exfiltrated disk read as
 * plaintext no longer expose private entries. The running daemon holds the
 * zone master and CAN decrypt (it must, to serve the owner's own reads and
 * the authenticated phone mirror) — this is not end-to-end encryption
 * against a compromised server process, and nothing in the docs may claim
 * otherwise.</p>
 *
 * <p>Fail-closed on write: if the zone master is unavailable (zone-secret
 * bootstrap failed), encryption throws rather than silently storing
 * plaintext — the caller surfaces the gap. Fail-honest on read: content
 * that cannot be decrypted comes back as a marker string, never a crash and
 * never ciphertext soup in the UI.</p>
 */
public final class PrivateJournalCipher {

    private static final Logger log = LoggerFactory.getLogger(PrivateJournalCipher.class);
    private static final String PREFIX = "enc:v1:";
    private static final String PURPOSE_PREFIX = "study-journal:";
    private static final String UNREADABLE =
        "[private entry — encrypted; this node cannot decrypt it]";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PrivateJournalCipher() {}

    /** True if {@code content} is a v1 private-journal envelope. */
    public static boolean isEncrypted(String content) {
        return content != null && content.startsWith(PREFIX);
    }

    /**
     * Encrypt a private entry for {@code userDid}. Already-encrypted content
     * passes through unchanged (sync-in idempotency).
     *
     * @throws IllegalStateException when the zone master is unavailable —
     *         the private entry must NOT be stored as plaintext silently.
     */
    public static String encrypt(String userDid, String plaintext) {
        if (plaintext == null || isEncrypted(plaintext)) return plaintext;
        var key = keyFor(userDid);
        if (key == null) {
            throw new IllegalStateException("private-journal encryption unavailable — the zone "
                + "master secret is not loaded (zone-secret bootstrap failed?); refusing to "
                + "store the private entry as plaintext");
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var iv = new byte[12];
            RANDOM.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(userDid.getBytes(StandardCharsets.UTF_8));
            var ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("private-journal encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypt an envelope for {@code userDid}; non-envelope content (legacy
     * plaintext rows, shared entries) passes through unchanged. Undecryptable
     * content returns a marker string rather than throwing.
     */
    public static String decryptIfNeeded(String userDid, String content) {
        if (!isEncrypted(content)) return content;
        var key = keyFor(userDid);
        if (key == null) return UNREADABLE;
        try {
            var packed = Base64.getDecoder().decode(content.substring(PREFIX.length()));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, packed, 0, 12));
            cipher.updateAAD(userDid.getBytes(StandardCharsets.UTF_8));
            var plain = cipher.doFinal(packed, 12, packed.length - 12);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[PrivateJournal] entry for {} did not decrypt: {}", userDid, e.getMessage());
            return UNREADABLE;
        }
    }

    private static SecretKeySpec keyFor(String userDid) {
        try {
            var zoneId = WyrdConfig.get().zoneId();
            var service = ZoneSecrets.service();
            if (zoneId == null || zoneId.isBlank() || !service.has(zoneId)) return null;
            var keyBytes = service.derive(zoneId, PURPOSE_PREFIX + userDid, 32);
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            log.warn("[PrivateJournal] key derivation failed: {}", e.getMessage());
            return null;
        }
    }
}
